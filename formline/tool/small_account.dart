// What the share rules would have done for a small account that can only hold
// a few positions, paying CMC Invest brokerage.
// Usage: dart run tool/small_account.dart <cacheDir> <startDollars> [flatFeeEachWay]
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:formline/engine/stock_scanner.dart';
import 'package:formline/engine/stocks.dart';
import 'package:formline/models/asx_universe.dart';

double? flatFee; // when set, a flat dollar fee each way instead of CMC Invest rates
double buyFee(double size) => flatFee ?? (size < 1000 ? 0 : math.max(11, size * 0.001));
double sellFee(double size) => flatFee ?? math.max(11, size * 0.001);

typedef T = ({DateTime entry, DateTime exit, double gain});

/// Follows trades in date order with [slots] equal positions, taking a new
/// trade only when a slot is free. Returns the equity curve by exit date.
({double end, double maxDd, List<(DateTime, double)> curve, int taken}) simulate(List<T> trades, double start, int slots) {
  final sorted = [...trades]..sort((a, b) => a.entry.compareTo(b.entry));
  var cash = start;
  final open = <({DateTime exit, double value})>[];
  var peak = start, maxDd = 0.0, taken = 0;
  final curve = <(DateTime, double)>[];
  void settle(DateTime now) {
    open.sort((a, b) => a.exit.compareTo(b.exit));
    while (open.isNotEmpty && !open.first.exit.isAfter(now)) {
      final p = open.removeAt(0);
      cash += p.value - sellFee(p.value);
      final equity = cash + open.fold(0.0, (s, o) => s + o.value);
      peak = math.max(peak, equity);
      maxDd = math.min(maxDd, equity / peak - 1);
      curve.add((p.exit, equity));
    }
  }

  for (final t in sorted) {
    settle(t.entry);
    if (open.length >= slots) continue;
    final size = cash / (slots - open.length);
    if (size < 50) continue;
    final fee = buyFee(size);
    cash -= size;
    open.add((exit: t.exit, value: (size - fee) * (1 + t.gain)));
    taken++;
  }
  settle(DateTime.utc(2100));
  return (end: cash, maxDd: maxDd, curve: curve, taken: taken);
}

Future<void> main(List<String> args) async {
  final cacheDir = args[0];
  final start = double.parse(args[1]);
  if (args.length > 2) flatFee = double.parse(args[2]);
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
  final res = StockScanner(series).run(from: 210);
  final leaders = <T>[for (final p in res.closedLeaders) (entry: p.entryDate, exit: p.exitDate!, gain: p.gain)];
  final dips = <T>[for (final t in res.closedDips) (entry: t.entryDate, exit: t.exitDate!, gain: t.gain)];
  final first = leaders.first.entry;
  final years = DateTime.utc(2026, 11, 11).difference(first).inDays / 365.25;
  stdout.writeln('Start \$${start.round()}, ${years.toStringAsFixed(1)} years, CMC Invest brokerage');
  for (final (name, trades) in [('Leaders to hold', leaders), ('Short-term trades', dips)]) {
    for (final slots in [1, 2, 3]) {
      final r = simulate(trades, start, slots);
      final cagr = math.pow(r.end / start, 1 / years) - 1;
      // By calendar year.
      final byYear = <int, double>{};
      var prev = start;
      for (final (d, e) in r.curve) {
        byYear[d.year] = (byYear[d.year] ?? 0) + (e - prev);
        prev = e;
      }
      final up = byYear.values.where((v) => v > 0).length;
      // Last five years only.
      final cut = DateTime.utc(2021, 11, 11);
      final r5 = simulate(trades.where((t) => !t.entry.isBefore(cut)).toList(), start, slots);
      final cagr5 = math.pow(r5.end / start, 1 / 5.0) - 1;
      stdout.writeln('  $name, $slots at a time: \$${start.round()} -> \$${r.end.round()} (${(cagr * 100).toStringAsFixed(1)}%/yr), '
          '${r.taken} trades (${(r.taken / years).toStringAsFixed(1)}/yr), worst fall ${(r.maxDd * 100).round()}%, '
          'up years $up of ${byYear.length} | last 5 yrs: -> \$${r5.end.round()} (${(cagr5 * 100).toStringAsFixed(1)}%/yr, worst fall ${(r5.maxDd * 100).round()}%)');
    }
  }
}
