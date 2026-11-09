// Walk-forward backtest of the team rating model.
// For every game day it fits the model on earlier games only, predicts that
// day's games, and scores the predictions against what happened.
// Usage: dart run tool/backtest_sports.dart <cacheDir> <leagueId> [leagueId...]
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:formline/engine/predictor.dart';
import 'package:formline/engine/ratings.dart';
import 'package:formline/models/game.dart';
import 'package:formline/models/league.dart';

const base = 'https://site.api.espn.com/apis/site/v2/sports';

Future<Map<String, dynamic>?> get(String url, String cacheDir) async {
  final key = url.replaceAll(RegExp(r'[^a-zA-Z0-9]'), '_');
  final f = File('$cacheDir/${key.length > 180 ? key.substring(key.length - 180) : key}.json');
  if (await f.exists()) {
    try {
      return json.decode(await f.readAsString()) as Map<String, dynamic>;
    } catch (_) {}
  }
  for (var attempt = 0; attempt < 3; attempt++) {
    try {
      final c = HttpClient();
      final req = await c.getUrl(Uri.parse(url));
      final res = await req.close().timeout(const Duration(seconds: 30));
      final body = await res.transform(utf8.decoder).join();
      c.close();
      if (res.statusCode != 200) return null;
      await f.writeAsString(body);
      return json.decode(body) as Map<String, dynamic>;
    } catch (_) {
      await Future.delayed(const Duration(seconds: 2));
    }
  }
  return null;
}

double? score(dynamic s) {
  if (s is num) return s.toDouble();
  if (s is String) return double.tryParse(s);
  if (s is Map) return (s['value'] as num?)?.toDouble();
  return null;
}

Future<List<GameResult>> loadLeague(League league, String cacheDir) async {
  final byId = <String, GameResult>{};
  final now = DateTime.now();
  final months = [for (var i = 1; i <= 34; i++) DateTime(now.year, now.month - i, 1)];
  for (var b = 0; b < months.length; b += 6) {
    await Future.wait([
      for (final d in months.skip(b).take(6))
        () async {
          final ym = '${d.year}${d.month.toString().padLeft(2, '0')}';
          final j = await get('$base/${league.path}/scoreboard?dates=$ym&limit=1000', cacheDir);
          for (final e in (j?['events'] as List? ?? [])) {
            try {
              final slug = '${(e['season'] as Map?)?['slug'] ?? ''}';
              if (slug.contains('preseason') || slug.contains('all-star')) continue;
              final comp = (e['competitions'] as List).first;
              final type = (comp['status'] ?? e['status'])['type'];
              if (type['completed'] != true) continue;
              final cs = (comp['competitors'] as List);
              final h = cs.firstWhere((c) => c['homeAway'] == 'home');
              final a = cs.firstWhere((c) => c['homeAway'] == 'away');
              final hs = score(h['score']), as = score(a['score']);
              if (hs == null || as == null) continue;
              byId['${e['id']}'] = GameResult(
                eventId: '${e['id']}',
                date: DateTime.parse(e['date']),
                homeId: '${h['team']['id']}',
                awayId: '${a['team']['id']}',
                homeScore: hs,
                awayScore: as,
                neutral: comp['neutralSite'] == true,
              );
            } catch (_) {}
          }
        }()
    ]);
  }
  final out = byId.values.toList()..sort((a, b) => a.date.compareTo(b.date));
  return out;
}

class Metrics {
  final resTotal = <double>[], resMargin = <double>[], zTotal = <double>[], zMargin = <double>[];
  final baseTotal = <double>[], baseMargin = <double>[];
  var n = 0, decided = 0, winRight = 0;
  var winLogLoss = 0.0, baseLogLoss = 0.0, brier = 0.0;
  final bins = List.generate(5, (_) => [0.0, 0.0, 0.0]); // sumP, hits, n for fav prob 50-60..90-100
  var overLL = 0.0, overN = 0, cover50 = 0, cover80 = 0;

  double rmse(List<double> xs) => xs.isEmpty ? 0 : math.sqrt(xs.map((x) => x * x).reduce((a, b) => a + b) / xs.length);
  double mean(List<double> xs) => xs.isEmpty ? 0 : xs.reduce((a, b) => a + b) / xs.length;
  double sd(List<double> xs) {
    final m = mean(xs);
    return xs.length < 2 ? 0 : math.sqrt(xs.map((x) => (x - m) * (x - m)).reduce((a, b) => a + b) / (xs.length - 1));
  }

  double get objective => rmse(resTotal) / (rmse(baseTotal) + 1e-9) + rmse(resMargin) / (rmse(baseMargin) + 1e-9);
}

Metrics run(League league, List<GameResult> games, ModelParams params, DateTime evalFrom) {
  final m = Metrics();
  final days = <DateTime, List<GameResult>>{};
  for (final g in games) {
    if (g.date.isBefore(evalFrom)) continue;
    days.putIfAbsent(DateTime.utc(g.date.year, g.date.month, g.date.day), () => []).add(g);
  }
  final homeRate = games.where((g) => g.homeScore > g.awayScore).length / math.max(1, games.where((g) => g.homeScore != g.awayScore).length);
  var idx = 0;
  final sortedDays = days.keys.toList()..sort();
  for (final d in sortedDays) {
    while (idx < games.length && games[idx].date.isBefore(d)) {
      idx++;
    }
    final from = d.subtract(const Duration(days: 800));
    final hist = games.sublist(0, idx).where((g) => g.date.isAfter(from)).toList();
    if (hist.length < 30) continue;
    final model = LeagueModel(league, params: params)..fit(hist, now: d);
    // Baseline: recent league averages only.
    final recent = hist.length > 200 ? hist.sublist(hist.length - 200) : hist;
    final avgTotal = recent.map((g) => g.total).reduce((a, b) => a + b) / recent.length;
    final avgMargin = recent.map((g) => g.margin).reduce((a, b) => a + b) / recent.length;
    for (final g in days[d]!) {
      final game = Game(
        league: league,
        eventId: g.eventId,
        date: g.date,
        home: TeamRef(id: g.homeId, abbr: 'H', name: 'H'),
        away: TeamRef(id: g.awayId, abbr: 'A', name: 'A'),
        state: GameState.pre,
        neutral: g.neutral,
      );
      final p = Predictor(model).predict(game, sims: 100);
      final rt = g.total - p.muTotal, rm = g.margin - p.muMargin;
      m.resTotal.add(rt);
      m.resMargin.add(rm);
      m.baseTotal.add(g.total - avgTotal);
      m.baseMargin.add(g.margin - avgMargin);
      final sdT = p.poisson ? math.sqrt(p.muTotal) : p.sdTotal;
      final sdM = p.poisson ? math.sqrt(p.muTotal) : p.sdMargin;
      m.zTotal.add(rt / sdT);
      m.zMargin.add(rm / sdM);
      if ((rt / sdT).abs() < 0.674) m.cover50++;
      if ((rt / sdT).abs() < 1.282) m.cover80++;
      m.n++;
      if (g.homeScore != g.awayScore) {
        m.decided++;
        var ph = p.probHomeWin, pa = p.probAwayWin;
        final z = ph + pa;
        ph = (ph / z).clamp(0.01, 0.99);
        pa = 1 - ph;
        final homeWon = g.homeScore > g.awayScore;
        m.winLogLoss += -math.log(homeWon ? ph : pa);
        m.baseLogLoss += -math.log(homeWon ? homeRate : 1 - homeRate);
        m.brier += math.pow((homeWon ? 1 : 0) - ph, 2);
        final fav = math.max(ph, pa);
        final favWon = (ph >= pa) == homeWon;
        if (favWon) m.winRight++;
        final b = ((fav - 0.5) * 10).floor().clamp(0, 4);
        m.bins[b][0] += fav;
        m.bins[b][1] += favWon ? 1 : 0;
        m.bins[b][2] += 1;
      }
      // Over/under calibration at the league-average line (a line the model did not choose).
      final line = (avgTotal - 0.5).roundToDouble() + 0.5;
      final po = p.probOver(line).clamp(0.01, 0.99);
      m.overLL += -math.log(g.total > line ? po : 1 - po);
      m.overN++;
    }
  }
  return m;
}

Future<void> main(List<String> args) async {
  final cacheDir = args[0];
  await Directory(cacheDir).create(recursive: true);
  final out = <String, dynamic>{};
  for (final id in args.skip(1)) {
    final league = Leagues.byId(id);
    if (league == null) continue;
    final games = await loadLeague(league, cacheDir);
    if (games.length < 150) {
      stdout.writeln('$id: only ${games.length} games, skipped');
      continue;
    }
    // Evaluate on everything after the first 35% of the history.
    final evalFrom = games[(games.length * 0.35).floor()].date;
    ModelParams? best;
    Metrics? bestM;
    for (final hl in [1.0, 2.0, 4.0]) {
      for (final k0 in [2.0, 4.0, 8.0, 16.0]) {
        for (final mix in [0.0, 0.3]) {
          final params = ModelParams(halfLifeMult: hl, k0: k0, longMix: mix);
          final m = run(league, games, params, evalFrom);
          if (bestM == null || m.objective < bestM.objective) {
            best = params;
            bestM = m;
          }
        }
      }
    }
    for (final kAvg in [2.0, 6.0, 60.0]) {
      final params = best!.copyWith(kAvg: kAvg);
      final m = run(league, games, params, evalFrom);
      if (m.objective < bestM!.objective) {
        best = params;
        bestM = m;
      }
    }
    final def = run(league, games, const ModelParams(), evalFrom);
    final m = bestM!;
    final tScale = league.isPoisson ? 1.0 : m.sd(m.zTotal), mScale = league.isPoisson ? 1.0 : m.sd(m.zMargin);
    final cal = run(league, games, best!.copyWith(totalScale: tScale, marginScale: mScale), evalFrom);
    String f(double x, [int d = 3]) => x.toStringAsFixed(d);
    stdout.writeln('== ${league.name} ($id): ${games.length} games, ${m.n} evaluated');
    stdout.writeln('   best: halfLifeMult=${best.halfLifeMult} k0=${best.k0} longMix=${best.longMix} kAvg=${best.kAvg}');
    stdout.writeln('   total RMSE  base ${f(m.rmse(m.baseTotal), 2)}  default ${f(def.rmse(def.resTotal), 2)}  tuned ${f(m.rmse(m.resTotal), 2)}  bias ${f(m.mean(m.resTotal), 2)}');
    stdout.writeln('   margin RMSE base ${f(m.rmse(m.baseMargin), 2)}  default ${f(def.rmse(def.resMargin), 2)}  tuned ${f(m.rmse(m.resMargin), 2)}');
    stdout.writeln('   spread vs model: total x${f(m.sd(m.zTotal), 2)}  margin x${f(m.sd(m.zMargin), 2)}  (1.00 = well calibrated)');
    stdout.writeln('   winner: accuracy default ${f(def.winRight / def.decided * 100, 1)}%  tuned ${f(m.winRight / m.decided * 100, 1)}%  calibrated ${f(cal.winRight / cal.decided * 100, 1)}%');
    stdout.writeln('   win logloss: coin/home-rate ${f(m.baseLogLoss / m.decided)}  default ${f(def.winLogLoss / def.decided)}  tuned ${f(m.winLogLoss / m.decided)}  calibrated ${f(cal.winLogLoss / cal.decided)}');
    stdout.writeln('   over/under logloss at league-average line: default ${f(def.overLL / def.overN)}  calibrated ${f(cal.overLL / cal.overN)}  (0.693 = coin flip)');
    stdout.writeln('   total interval coverage (calibrated): 50% -> ${f(cal.cover50 / cal.n * 100, 1)}%   80% -> ${f(cal.cover80 / cal.n * 100, 1)}%');
    final binText = [for (final b in cal.bins) if (b[2] > 0) 'said ${f(b[0] / b[2] * 100, 0)}% won ${f(b[1] / b[2] * 100, 0)}% (n=${b[2].toInt()})'].join(' | ');
    stdout.writeln('   favourite calibration (calibrated): $binText');
    stdout.writeln("   '$id': ModelParams(halfLifeMult: ${best.halfLifeMult}, k0: ${best.k0}, longMix: ${best.longMix}, kAvg: ${best.kAvg}, totalScale: ${f(tScale, 2)}, marginScale: ${f(mScale, 2)}),");
    out[id] = {
      'games': m.n,
      'halfLifeMult': best.halfLifeMult,
      'k0': best.k0,
      'longMix': best.longMix,
      'kAvg': best.kAvg,
      'bias': bestM.mean(bestM.resTotal),
      'said': [for (final b in cal.bins) b[2] > 0 ? b[0] / b[2] : 0],
      'won': [for (final b in cal.bins) b[2] > 0 ? b[1] / b[2] : 0],
      'binN': [for (final b in cal.bins) b[2]],
      'totalScale': tScale,
      'marginScale': mScale,
      'accuracy': cal.winRight / cal.decided,
      'winLogLoss': cal.winLogLoss / cal.decided,
      'baseLogLoss': m.baseLogLoss / m.decided,
      'totalRmse': m.rmse(m.resTotal),
      'baseTotalRmse': m.rmse(m.baseTotal),
      'overLL': cal.overLL / cal.overN,
    };
  }
  await File('$cacheDir/_results_${args.skip(1).join('_')}.json').writeAsString(json.encode(out));
}
