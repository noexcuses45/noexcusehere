// Backtest of the short-term daily routine for a real account: at the close,
// sell held dips that bounced (or hit the stop or the time limit), then buy
// new dips in leaders with the cash that is free. Whole shares only, CMC
// Invest brokerage.
// Usage: dart run tool/short_backtest.dart <cacheDir> [table]
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:formline/engine/plan_advisor.dart';
import 'package:formline/engine/stock_scanner.dart';
import 'package:formline/engine/stocks.dart';
import 'package:formline/models/asx_universe.dart';

double buyFee(double size) => size < 1000 ? 0 : math.max(11, size * 0.001);
double sellFee(double size) => math.max(11, size * 0.001);

class Held {
  Held(this.code, this.shares, this.cost, this.entry, this.stop);
  final String code;
  final int shares;
  final double cost; // cash paid including the buy fee
  final double entry;
  final double stop;
  int days = 0;
}

({double end, double maxDd, int trades, int wins, double fees}) simulate(
    StockScanner sc, Map<String, Series> byCode, double start, int slots, int from, int to) {
  var cash = start;
  final held = <Held>[];
  var peak = start, maxDd = 0.0, trades = 0, wins = 0, fees = 0.0;
  for (var d = from; d <= to; d++) {
    final ranks = sc.ranksAt(d);
    final today = sc.dateOf(d);
    for (final h in held.toList()) {
      final s = byCode[h.code]!;
      final i = sc.barAt(s, d);
      if (i < 0 || s.bars[i].date.isBefore(today)) continue;
      h.days++;
      final c = s.close(i);
      if (c <= h.stop || c > s.sma(5)[i] || h.days >= StockRules.dipMaxHold) {
        final gross = h.shares * c;
        final fee = sellFee(gross);
        fees += fee;
        cash += gross - fee;
        held.remove(h);
        trades++;
        if (gross - fee > h.cost) wins++;
      }
    }
    if (held.length < slots) {
      final signals = <String>[];
      for (final s in sc.series) {
        if (held.any((h) => h.code == s.code)) continue;
        if (ranks.pct(s.code) < StockRules.dipLeaderPct) continue;
        final i = sc.barAt(s, d);
        if (i < 0 || s.bars[i].date.isBefore(today)) continue;
        final ma = s.sma(StockRules.trendLen)[i], rsi = s.rsi(2)[i];
        if (ma.isNaN || rsi.isNaN) continue;
        if (s.close(i) > ma && rsi < StockRules.dipRsi) signals.add(s.code);
      }
      signals.sort((a, b) => ranks.rank[a]!.compareTo(ranks.rank[b]!));
      for (final c in signals) {
        if (held.length >= slots) break;
        final s = byCode[c]!;
        final price = s.close(sc.barAt(s, d));
        final budget = cash / (slots - held.length);
        // Leave room for the buy fee, then whole shares only.
        var shares = (budget / price).floor();
        while (shares > 0 && shares * price + buyFee(shares * price) > budget) {
          shares--;
        }
        if (shares < 1) continue;
        final fee = buyFee(shares * price);
        fees += fee;
        cash -= shares * price + fee;
        held.add(Held(c, shares, shares * price + fee, price, price * (1 - StockRules.dipStop)));
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
    if (d == to) return (end: equity, maxDd: maxDd, trades: trades, wins: wins, fees: fees);
  }
  return (end: cash, maxDd: maxDd, trades: trades, wins: wins, fees: fees);
}

/// The same dip signal held for a fixed number of days: [trades, winners, average gain].
(int, double, double) fixedHold(StockScanner sc, int hold) {
  var n = 0, wins = 0;
  var sum = 0.0;
  for (final s in sc.series) {
    var next = 0; // first day a new trade may start
    for (var d = 210; d < sc.calendar.length; d++) {
      if (d < next) continue;
      final i = sc.barAt(s, d);
      if (i < 0 || s.bars[i].date.isBefore(sc.dateOf(d)) || i + hold >= s.n) continue;
      if (sc.ranksAt(d).pct(s.code) < StockRules.dipLeaderPct) continue;
      final ma = s.sma(StockRules.trendLen)[i], rsi = s.rsi(2)[i];
      if (ma.isNaN || rsi.isNaN || s.close(i) <= ma || rsi >= StockRules.dipRsi) continue;
      final g = s.close(i + hold) / s.close(i) - 1;
      n++;
      sum += g;
      if (g > 0) wins++;
      next = d + hold + 1;
    }
  }
  return (n, wins / n, sum / n);
}

Future<void> main(List<String> args) async {
  final cacheDir = args[0];
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
  final half = last - 1265; // about five years of trading days
  final years5 = (last - half) / 252.0;
  for (final hold in [1, 2, 3]) {
    final (n, win, gross) = fixedHold(sc, hold);
    stdout.writeln('sell after $hold day${hold == 1 ? '' : 's'}: $n trades, ${(win * 100).toStringAsFixed(1)}% up, ${(gross * 100).toStringAsFixed(2)}% a trade before brokerage');
  }
  final rows = <String>[];
  for (final start in [300.0, 500.0, 840.0, 1000.0, 1500.0, 2000.0, 3000.0, 5000.0, 7500.0, 10000.0, 20000.0, 50000.0]) {
    final slots = PlanAdvisor.shortSlotsFor(start);
    final b = simulate(sc, byCode, start, slots, half, last);
    // An account that brokerage has emptied counts as losing everything.
    final cb = b.end <= 1 ? -1.0 : math.pow(b.end / start, 1 / years5) - 1;
    final perYear = b.trades / years5;
    stdout.writeln('short rule: \$${start.round()} in $slots at a time -> \$${b.end.round()} over the last 5 yrs '
        '(${(cb * 100).toStringAsFixed(1)}%/yr, worst fall ${(b.maxDd * 100).round()}%, ${perYear.round()} trades/yr, ${(b.wins / math.max(1, b.trades) * 100).round()}% won, fees \$${b.fees.round()})');
    rows.add('    [${start.round()}, ${cb.toStringAsFixed(4)}, ${math.max(-1.0, b.maxDd).toStringAsFixed(3)}, ${perYear.round()}, ${(b.fees / years5).round()}],');
  }
  stdout.writeln('  static const List<List<double>> shortHistory = [\n${rows.join('\n')}\n  ];');
  if (args.contains('table')) return;
  for (final start in [840.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0]) {
    stdout.writeln('Start \$${start.round()}');
    for (final slots in [1, 2, 3, 5]) {
      final b = simulate(sc, byCode, start, slots, half, last);
      final a = simulate(sc, byCode, start, slots, 210, half - 1);
      final ya = (half - 1 - 210) / 252.0;
      final cb = math.pow(b.end / start, 1 / years5) - 1, ca = math.pow(a.end / start, 1 / ya) - 1;
      stdout.writeln('   $slots at a time: last 5 yrs ${(cb * 100).toStringAsFixed(1)}%/yr, worst fall ${(b.maxDd * 100).round()}%, ${b.trades} trades ${(b.wins / math.max(1, b.trades) * 100).round()}% won'
          ' | earlier ${(ca * 100).toStringAsFixed(1)}%/yr, worst fall ${(a.maxDd * 100).round()}%');
    }
  }
}
