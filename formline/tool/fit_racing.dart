// Fits a conditional-logit (within-race multinomial) model for win probability
// from form features only, and reports cross-validated log loss.
// Usage: dart run tool/fit_racing.dart <days> <cacheDir>
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:formline/engine/race_features.dart';
import 'package:formline/models/race.dart';

Future<Map<String, dynamic>> get(String url, String cacheDir, String key) async {
  final f = File('$cacheDir/$key.json');
  if (await f.exists()) return json.decode(await f.readAsString()) as Map<String, dynamic>;
  final c = HttpClient();
  final req = await c.getUrl(Uri.parse(url));
  req.headers.set('Accept', 'application/json');
  final res = await req.close();
  final body = await res.transform(utf8.decoder).join();
  c.close();
  final j = json.decode(body) as Map<String, dynamic>;
  await f.writeAsString(body);
  return j;
}

Future<void> main(List<String> args) async {
  final days = int.parse(args[0]);
  final cacheDir = args[1];
  await Directory(cacheDir).create(recursive: true);
  final races = <Race>[];
  for (var d = 1; d <= days; d++) {
    final day = DateTime.now().subtract(Duration(days: d));
    final iso = '${day.year}-${day.month.toString().padLeft(2, '0')}-${day.day.toString().padLeft(2, '0')}';
    final j = await get('https://api.tab.co.nz/affiliates/v1/racing/meetings?date_from=$iso&date_to=$iso', cacheDir, 'meetings_$iso');
    final futures = <Future<void>>[];
    for (final m in (j['data']['meetings'] as List)) {
      if (m['category'] != 'T') continue;
      if (!['AUS', 'NZ', 'UK', 'IRE', 'FR', 'USA', 'SAF'].contains(m['country'])) continue;
      for (final r in (m['races'] as List? ?? [])) {
        if (r['status'] != 'Final') continue;
        futures.add(() async {
          try {
            final e = await get('https://api.tab.co.nz/affiliates/v1/racing/events/${r['id']}', cacheDir, 'event_${r['id']}');
            final race = parseRace(e, '${r['id']}');
            if (race.results.isNotEmpty && race.runners.where((x) => !x.scratched).length >= 5) races.add(race);
          } catch (_) {}
        }());
        if (futures.length >= 8) {
          await Future.wait(futures);
          futures.clear();
        }
      }
    }
    await Future.wait(futures);
    stderr.writeln('day -$d: ${races.length} races');
  }
  stdout.writeln('Races: ${races.length}');

  // Build feature matrices.
  final X = <List<List<double>>>[]; // race -> runner -> features
  final Y = <int>[]; // winner index
  final M = <List<double>>[]; // market implied probs per runner (for baseline)
  for (final race in races) {
    final rs = race.runners.where((r) => !r.scratched).toList();
    final winner = race.results.first.number;
    final wi = rs.indexWhere((r) => r.number == winner);
    if (wi < 0) continue;
    X.add(RaceFeatures.matrix(race, rs));
    Y.add(wi);
    final inv = rs.map((r) => r.fixedWin != null && r.fixedWin! > 1 ? 1 / r.fixedWin! : 0.0).toList();
    final z = inv.reduce((a, b) => a + b);
    M.add(z > 0 ? inv.map((v) => v / z).toList() : List.filled(rs.length, 1 / rs.length));
  }
  final k = RaceFeatures.names.length;
  stdout.writeln('Usable races: ${X.length}, features: $k');
  // Standardise each feature (scale only; a shift cancels within a race).
  final sd = List<double>.filled(k, 1);
  for (var f = 0; f < k; f++) {
    var s = 0.0, s2 = 0.0;
    var n = 0;
    for (final race in X) {
      for (final row in race) {
        s += row[f];
        s2 += row[f] * row[f];
        n++;
      }
    }
    final v = s2 / n - (s / n) * (s / n);
    sd[f] = v > 1e-9 ? math.sqrt(v) : 1;
  }
  for (final race in X) {
    for (final row in race) {
      for (var f = 0; f < k; f++) {
        row[f] /= sd[f];
      }
    }
  }

  double marketLl = 0;
  var marketTop = 0;
  for (var i = 0; i < X.length; i++) {
    marketLl += -math.log(math.max(1e-6, M[i][Y[i]]));
    var best = 0;
    for (var j = 0; j < M[i].length; j++) {
      if (M[i][j] > M[i][best]) best = j;
    }
    if (best == Y[i]) marketTop++;
  }
  stdout.writeln('Market baseline: logloss=${(marketLl / X.length).toStringAsFixed(4)} top=${(marketTop / X.length * 100).toStringAsFixed(1)}%');

  List<double> fit(List<int> idx, {double l2 = 0.002, int iters = 1500, double lr = 0.8}) {
    final w = List<double>.filled(k, 0);
    for (var it = 0; it < iters; it++) {
      final g = List<double>.filled(k, 0);
      for (final i in idx) {
        final p = softmax(X[i], w);
        for (var j = 0; j < X[i].length; j++) {
          final coef = (j == Y[i] ? 1 : 0) - p[j];
          for (var f = 0; f < k; f++) {
            g[f] += coef * X[i][j][f];
          }
        }
      }
      for (var f = 0; f < k; f++) {
        w[f] += lr * (g[f] / idx.length - l2 * w[f]);
      }
    }
    return w;
  }

  (double, double, double) evaluate(List<int> idx, List<double> w) {
    double ll = 0, topP = 0;
    var top = 0;
    for (final i in idx) {
      final p = softmax(X[i], w);
      ll += -math.log(math.max(1e-6, p[Y[i]]));
      var best = 0;
      for (var j = 0; j < p.length; j++) {
        if (p[j] > p[best]) best = j;
      }
      if (best == Y[i]) top++;
      topP += p[best];
    }
    return (ll / idx.length, top / idx.length, topP / idx.length);
  }

  // 5-fold cross-validation.
  final all = List<int>.generate(X.length, (i) => i)..shuffle(math.Random(1));
  double cvLl = 0, cvTop = 0, cvTopP = 0;
  for (var fold = 0; fold < 5; fold++) {
    final test = <int>[], train = <int>[];
    for (var i = 0; i < all.length; i++) {
      (i % 5 == fold ? test : train).add(all[i]);
    }
    final w = fit(train);
    final (ll, top, topP) = evaluate(test, w);
    cvLl += ll / 5;
    cvTop += top / 5;
    cvTopP += topP / 5;
  }
  stdout.writeln('CV: logloss=${cvLl.toStringAsFixed(4)} top-pick hit=${(cvTop * 100).toStringAsFixed(1)}% avg top prob=${(cvTopP * 100).toStringAsFixed(1)}%');
  final w = fit(all);
  final (ll, top, topP) = evaluate(all, w);
  stdout.writeln('Train: logloss=${ll.toStringAsFixed(4)} top=${(top * 100).toStringAsFixed(1)}% avgTopProb=${(topP * 100).toStringAsFixed(1)}%');
  stdout.writeln('Weights:');
  for (var f = 0; f < k; f++) {
    stdout.writeln('    ${(w[f] / sd[f]).toStringAsFixed(4)}, // ${RaceFeatures.names[f]} (std ${w[f].toStringAsFixed(3)})');
  }
}

List<double> softmax(List<List<double>> x, List<double> w) {
  final s = x.map((row) {
    var v = 0.0;
    for (var f = 0; f < w.length; f++) {
      v += row[f] * w[f];
    }
    return v;
  }).toList();
  final mx = s.reduce(math.max);
  final e = s.map((v) => math.exp(v - mx)).toList();
  final z = e.reduce((a, b) => a + b);
  return e.map((v) => v / z).toList();
}

Race parseRace(Map<String, dynamic> j, String raceId) {
  final d = j['data'] as Map<String, dynamic>;
  final r = d['race'] as Map<String, dynamic>;
  final runners = <Runner>[];
  for (final x in (d['runners'] as List? ?? [])) {
    final odds = x['odds'] as Map? ?? {};
    final jp = x['jockey_past_performances'] as Map? ?? {};
    final j50 = jp['last_50_starts'] as Map? ?? {};
    final tr = jp['trainer'] as Map? ?? {};
    final w = x['weight'] as Map? ?? {};
    runners.add(Runner(
      id: '${x['entrant_id']}',
      number: (x['runner_number'] as num?)?.toInt() ?? 0,
      name: x['name'] as String? ?? '',
      scratched: x['is_scratched'] == true,
      barrier: (x['barrier'] as num?)?.toInt(),
      weight: double.tryParse('${w['total'] ?? w['allocated'] ?? ''}'),
      allowance: double.tryParse('${x['allowance_weight'] ?? ''}'),
      apprentice: (x['apprentice_indicator'] as String? ?? '').isNotEmpty,
      jockey: x['jockey'] as String? ?? '',
      trainer: x['trainer_name'] as String? ?? '',
      form: x['last_twenty_starts'] as String? ?? '',
      prizeMoney: double.tryParse('${x['prize_money'] ?? ''}') ?? 0,
      age: (x['age'] as num?)?.toInt(),
      gear: x['gear'] as String? ?? '',
      speedMap: (x['speedmap'] as Map?)?['label'] as String? ?? '',
      fixedWin: (odds['fixed_win'] as num?)?.toDouble(),
      fixedPlace: (odds['fixed_place'] as num?)?.toDouble(),
      poolWin: (odds['pool_win'] as num?)?.toDouble(),
      jockeyStarts: (j50['number_of_starts'] as num?)?.toInt() ?? 0,
      jockeyWins: (j50['number_of_wins'] as num?)?.toInt() ?? 0,
      trainerStarts: (tr['number_of_starts'] as num?)?.toInt() ?? 0,
      trainerWins: (tr['number_of_wins'] as num?)?.toInt() ?? 0,
    ));
  }
  final results = <RaceResult>[];
  for (final x in (d['results'] as List? ?? [])) {
    results.add(RaceResult(position: (x['position'] as num).toInt(), number: (x['runner_number'] as num?)?.toInt() ?? 0, name: x['name'] as String? ?? ''));
  }
  results.sort((a, b) => a.position.compareTo(b.position));
  return Race(
    id: raceId,
    meetingId: '',
    meetingName: r['meeting_name'] as String? ?? '',
    number: (r['race_number'] as num?)?.toInt() ?? 0,
    name: r['description'] as String? ?? '',
    start: DateTime.now(),
    status: r['status'] as String? ?? '',
    type: r['type'] as String? ?? 'T',
    distance: (r['distance'] as num?)?.toInt() ?? 0,
    positionsPaid: (r['positions_paid'] as num?)?.toInt() ?? 3,
    runners: runners,
    results: results,
  );
}
