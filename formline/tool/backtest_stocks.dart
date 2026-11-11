// Backtest of the share-trading setups on ASX daily closes.
// Usage: dart run tool/backtest_stocks.dart <cacheDir>
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:formline/engine/stock_scanner.dart';
import 'package:formline/engine/stocks.dart';
import 'package:formline/models/asx_universe.dart';

Future<Series?> load(String code, String cacheDir) async {
  final f = File('$cacheDir/$code.json');
  String body;
  if (await f.exists()) {
    body = await f.readAsString();
  } else {
    final c = HttpClient();
    try {
      final req = await c.getUrl(Uri.parse('https://www.asx.com.au/asx/1/chart/highcharts?asx_code=$code&years=10'));
      final res = await req.close().timeout(const Duration(seconds: 40));
      body = await res.transform(utf8.decoder).join();
      if (res.statusCode != 200) return null;
      await f.writeAsString(body);
    } catch (_) {
      return null;
    } finally {
      c.close();
    }
  }
  try {
    final rows = json.decode(body) as List;
    final bars = <Bar>[
      for (final r in rows)
        if (r[1] != null && (r[1] as num) > 0)
          Bar(DateTime.fromMillisecondsSinceEpoch((r[0] as num).toInt(), isUtc: true), (r[1] as num).toDouble(), ((r[2] ?? 0) as num).toDouble())
    ];
    return bars.length < 300 ? null : Series(code, bars);
  } catch (_) {
    return null;
  }
}

String pc(double x, [int d = 2]) => '${(x * 100).toStringAsFixed(d)}%';

Future<void> main(List<String> args) async {
  final cacheDir = args[0];
  await Directory(cacheDir).create(recursive: true);
  final codes = AsxUniverse.all.keys.toList();
  final series = <Series>[];
  for (var b = 0; b < codes.length; b += 6) {
    final got = await Future.wait([for (final c in codes.skip(b).take(6)) load(c, cacheDir)]);
    series.addAll(got.whereType<Series>());
  }
  stdout.writeln('Loaded ${series.length} of ${codes.length} codes; '
      '${series.map((s) => s.n).reduce((a, b) => a + b)} daily bars; '
      '${series.first.bars.first.date.toIso8601String().substring(0, 10)} to ${series.first.bars.last.date.toIso8601String().substring(0, 10)}');
  const cost = 0.001; // 0.1% each way

  // Baseline: buying any share on any day and holding h days (after costs).
  double baseline(int h) {
    var sum = 0.0, n = 0;
    for (final s in series) {
      for (var i = 205; i + h < s.n; i += 3) {
        sum += s.close(i + h) * (1 - cost) / (s.close(i) * (1 + cost)) - 1;
        n++;
      }
    }
    return sum / n;
  }

  final out = <String, dynamic>{};
  for (final setup in allSetups) {
    stdout.writeln('\n== ${setup.name} (${setup.id})');
    for (final delay in [0, 1]) {
      final all = <Trade>[];
      for (final s in series) {
        all.addAll(Simulator.run(s, setup, entryDelay: delay).where((t) => !t.open));
      }
      final st = TradeStats(all, cost: cost);
      final half = <TradeStats>[];
      final mid = DateTime.utc(2021, 11, 11);
      for (final first in [true, false]) {
        half.add(TradeStats(all.where((t) {
          final s = series.firstWhere((x) => x.code == t.code);
          return s.bars[t.entryIdx].date.isBefore(mid) == first;
        }), cost: cost));
      }
      final base = baseline(math.max(1, st.avgDays.round()));
      final reasons = <String, int>{};
      for (final t in all) {
        reasons[t.exitReason] = (reasons[t.exitReason] ?? 0) + 1;
      }
      final rets = all.map((t) => t.netReturn(cost)).toList()..sort();
      final worst = rets.isEmpty ? 0.0 : rets[(rets.length * 0.05).floor()];
      stdout.writeln('  entry ${delay == 0 ? 'at signal close' : 'next day close '}: ${st.n} trades  win ${pc(st.winRate, 1)}  avg ${pc(st.avg)}  '
          'hold ${st.avgDays.toStringAsFixed(1)}d  PF ${st.profitFactor.toStringAsFixed(2)}  '
          'random-hold ${pc(base)}  edge ${pc(st.avg - base)}  worst-5% ${pc(worst, 1)}');
      stdout.writeln('     2016-21: ${half[0].n} trades win ${pc(half[0].winRate, 1)} avg ${pc(half[0].avg)}   '
          '2021-26: ${half[1].n} trades win ${pc(half[1].winRate, 1)} avg ${pc(half[1].avg)}   exits $reasons');
      out['${setup.id}_$delay'] = {
        'n': st.n,
        'win': st.winRate,
        'avg': st.avg,
        'days': st.avgDays,
        'pf': st.profitFactor,
        'base': base,
        'win1': half[0].winRate,
        'avg1': half[0].avg,
        'win2': half[1].winRate,
        'avg2': half[1].avg,
        'worst5': worst,
      };
    }
  }
  // ---------------- The two strategies the app actually runs ----------------
  final shares = series.where((s) => AsxUniverse.shares.containsKey(s.code)).toList();
  final scanner = StockScanner(shares);
  final res = scanner.run(from: 210);
  final byCode = {for (final s in shares) s.code: s};
  final mid = DateTime.utc(2021, 11, 11);

  // Average share's return between two dates (the "just hold anything" yardstick).
  double marketReturn(DateTime a, DateTime b) {
    var sum = 0.0, n = 0;
    for (final s in shares) {
      int at(DateTime d) {
        var lo = 0, hi = s.n - 1;
        while (lo < hi) {
          final m = (lo + hi + 1) >> 1;
          if (s.bars[m].date.isAfter(d)) {
            hi = m - 1;
          } else {
            lo = m;
          }
        }
        return lo;
      }

      final i = at(a), j = at(b);
      if (s.bars[i].date.isAfter(a) || j <= i) continue;
      sum += s.close(j) / s.close(i) - 1;
      n++;
    }
    return n == 0 ? 0 : sum / n;
  }

  Map<String, dynamic> summarise(String label, List<({DateTime entry, DateTime exit, double gross, int days})> trades) {
    double mean(Iterable<double> xs) => xs.isEmpty ? 0 : xs.reduce((a, b) => a + b) / xs.length;
    final gross = trades.map((t) => t.gross).toList();
    final wins = gross.where((g) => g - 2 * cost > 0).toList(), losses = gross.where((g) => g - 2 * cost <= 0).toList();
    final excess = mean(trades.map((t) => t.gross - marketReturn(t.entry, t.exit)));
    final h1 = trades.where((t) => t.entry.isBefore(mid)).toList(), h2 = trades.where((t) => !t.entry.isBefore(mid)).toList();
    final sorted = [...gross]..sort();
    final r = {
      'n': trades.length,
      'win': wins.length / trades.length,
      'gross': mean(gross),
      'avgWin': mean(wins),
      'avgLoss': mean(losses),
      'days': mean(trades.map((t) => t.days.toDouble())),
      'excess': excess,
      'worst5': sorted[(sorted.length * 0.05).floor()],
      'best5': sorted[(sorted.length * 0.95).floor()],
      'gross1': mean(h1.map((t) => t.gross)),
      'gross2': mean(h2.map((t) => t.gross)),
      'excess1': mean(h1.map((t) => t.gross - marketReturn(t.entry, t.exit))),
      'excess2': mean(h2.map((t) => t.gross - marketReturn(t.entry, t.exit))),
      'n1': h1.length,
      'n2': h2.length,
    };
    stdout.writeln('\n== $label: ${r['n']} trades  win ${pc(r['win'] as double, 1)}  avg gross ${pc(r['gross'] as double)} '
        '(net of 0.1%/side ${pc((r['gross'] as double) - 2 * cost)})  avg win ${pc(r['avgWin'] as double, 1)}  avg loss ${pc(r['avgLoss'] as double, 1)}  '
        'hold ${(r['days'] as double).toStringAsFixed(1)}d');
    stdout.writeln('   vs average share over the same days: ${pc(excess)} better   worst 5% ${pc(r['worst5'] as double, 1)}   best 5% ${pc(r['best5'] as double, 1)}');
    stdout.writeln('   2016-21: ${h1.length} trades avg ${pc(r['gross1'] as double)} (excess ${pc(r['excess1'] as double)})   '
        '2021-26: ${h2.length} trades avg ${pc(r['gross2'] as double)} (excess ${pc(r['excess2'] as double)})');
    return r;
  }

  out['dips'] = summarise('Dips in leaders (app rule)', [
    for (final t in res.closedDips) (entry: t.entryDate, exit: t.exitDate!, gross: t.gain, days: t.days),
  ]);
  out['leaders'] = summarise('Leaders to hold (app rule)', [
    for (final p in res.closedLeaders) (entry: p.entryDate, exit: p.exitDate!, gross: p.gain, days: p.days),
  ]);
  stdout.writeln('   shares ranked: ${shares.length}; leaders held at the end: ${res.leaders.where((l) => l.open).length}; '
      'sample holding: ${res.leaders.take(3).map((l) => '${l.code} #${l.rankNow}').join(', ')}; ${byCode.length} codes');
  await File('$cacheDir/_results.json').writeAsString(json.encode(out));
}
