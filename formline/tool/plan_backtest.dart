// Backtest of the "My plan" allocation rule: hold N of the best-ranked
// leaders at once; when a slot is free buy the best-ranked qualifying share;
// sell when it closes below its 200-day average or leaves the top 25.
// Usage: dart run tool/plan_backtest.dart <cacheDir> [flatFeeEachWay] [table]
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:formline/engine/plan_advisor.dart';
import 'package:formline/engine/stock_scanner.dart';
import 'package:formline/engine/stocks.dart';
import 'package:formline/models/asx_universe.dart';

double? flatFee;
double buyFee(double size) => flatFee ?? (size < 1000 ? 0 : math.max(11, size * 0.001));
double sellFee(double size) => flatFee ?? math.max(11, size * 0.001);
double costAt(double parcel) => parcel <= 0 ? 1 : (buyFee(parcel) + sellFee(parcel)) / parcel;

class Held {
  Held(this.code, this.shares, this.cost);
  final String code;
  final double shares;
  final double cost;
}

({double end, double maxDd, int trades, int wins}) simulate(StockScanner sc, Map<String, Series> byCode, double start, int slots, int from, int to) {
  var cash = start;
  final held = <Held>[];
  var peak = start, maxDd = 0.0, trades = 0, wins = 0;
  for (var d = from; d <= to; d++) {
    final ranks = sc.ranksAt(d);
    // Sell first.
    for (final h in held.toList()) {
      final s = byCode[h.code]!;
      final i = sc.barAt(s, d);
      if (i < 0) continue;
      final ma = s.sma(StockRules.trendLen)[i];
      final r = ranks.rank[h.code] ?? 9999;
      if ((!ma.isNaN && s.close(i) < ma) || r > StockRules.leaderExit) {
        final gross = h.shares * s.close(i);
        final net = gross - sellFee(gross);
        cash += net;
        held.remove(h);
        trades++;
        if (net > h.cost) wins++;
      }
    }
    // Buy the best-ranked qualifying shares into free slots.
    if (held.length < slots) {
      final codes = ranks.rank.keys.where((c) => ranks.rank[c]! <= StockRules.leaderEnter && !held.any((h) => h.code == c)).toList()
        ..sort((a, b) => ranks.rank[a]!.compareTo(ranks.rank[b]!));
      for (final c in codes) {
        if (held.length >= slots) break;
        final s = byCode[c]!;
        final i = sc.barAt(s, d);
        if (i < 0) continue;
        final ma = s.sma(StockRules.trendLen)[i];
        if (ma.isNaN || s.close(i) < ma) continue;
        final size = cash / (slots - held.length);
        if (size < 50) break;
        final fee = buyFee(size);
        cash -= size;
        held.add(Held(c, (size - fee) / s.close(i), size));
      }
    }
    var equity = cash;
    for (final h in held) {
      final s = byCode[h.code]!;
      final i = sc.barAt(s, d);
      equity += h.shares * s.close(i < 0 ? s.n - 1 : i);
    }
    peak = math.max(peak, equity);
    maxDd = math.min(maxDd, equity / peak - 1);
    if (d == to) return (end: equity, maxDd: maxDd, trades: trades, wins: wins);
  }
  return (end: cash, maxDd: maxDd, trades: trades, wins: wins);
}

Future<void> main(List<String> args) async {
  final cacheDir = args[0];
  if (args.length > 1) flatFee = double.tryParse(args[1]);
  final series = <Series>[];
  for (final code in AsxUniverse.shares.keys) {
    final f = File('$cacheDir/$code.json');
    if (!f.existsSync()) continue;
    try {
      final rows = json.decode(f.readAsStringSync()) as List;
      final bars = <Bar>[
        for (final r in rows)
          if (r[1] != null && (r[1] as num) > 0) Bar(DateTime.fromMillisecondsSinceEpoch((r[0] as num).toInt(), isUtc: true), (r[1] as num).toDouble(), 0)
      ];
      if (bars.length >= 300) series.add(Series(code, bars));
    } catch (_) {}
  }
  final sc = StockScanner(series);
  final byCode = {for (final s in series) s.code: s};
  final last = sc.calendar.length - 1;
  const from = 210;
  final half = last - 1265; // about five years of trading days
  final yearsAll = (last - from) / 252.0, years5 = (last - half) / 252.0;
  stdout.writeln('${flatFee == null ? 'CMC Invest brokerage' : 'flat \$$flatFee each way'}; ${yearsAll.toStringAsFixed(1)} years, and the last ${years5.toStringAsFixed(1)} years separately');
  // The table the app shows: the plan's own rule at each starting amount.
  final rows = <String>[];
  for (final start in [300.0, 500.0, 840.0, 1000.0, 1500.0, 2000.0, 3000.0, 5000.0, 10000.0, 20000.0, 50000.0]) {
    final slots = PlanAdvisor.slotsFor(start);
    final b = simulate(sc, byCode, start, slots, half, last);
    final cb = math.pow(b.end / start, 1 / years5) - 1;
    stdout.writeln('plan rule: \$${start.round()} in $slots shares -> \$${b.end.round()} over the last 5 yrs (${(cb * 100).toStringAsFixed(1)}%/yr, worst fall ${(b.maxDd * 100).round()}%)');
    rows.add('    [${start.round()}, ${cb.toStringAsFixed(4)}, ${b.maxDd.toStringAsFixed(3)}],');
  }
  stdout.writeln('  static const List<List<double>> history = [\n${rows.join('\n')}\n  ];');
  if (args.contains('table')) return;
  for (final start in [840.0, 3000.0, 5000.0, 10000.0, 20000.0, 50000.0]) {
    stdout.writeln('Start \$${start.round()}  (rule picks ${PlanAdvisor.slotsFor(start)} at a time)');
    for (final slots in [1, 2, 3, 5]) {
      final a = simulate(sc, byCode, start, slots, from, last);
      final b = simulate(sc, byCode, start, slots, half, last);
      final ca = math.pow(a.end / start, 1 / yearsAll) - 1, cb = math.pow(b.end / start, 1 / years5) - 1;
      stdout.writeln('   $slots at a time: ${(ca * 100).toStringAsFixed(1)}%/yr, worst fall ${(a.maxDd * 100).round()}%, ${a.trades} trades ${a.trades == 0 ? 0 : (a.wins / a.trades * 100).round()}% won'
          ' | last 5 yrs: \$${start.round()} -> \$${b.end.round()} (${(cb * 100).toStringAsFixed(1)}%/yr, worst fall ${(b.maxDd * 100).round()}%)');
    }
  }
}
