import 'stocks.dart';

/// Rules of the two share strategies. The backtest (tool/backtest_stocks.dart)
/// and the app both run exactly this code.
class StockRules {
  static const momLookback = 126; // about six months of trading days
  static const momSkip = 5; // ignore the latest week (short-term noise)
  static const leaderEnter = 10; // buy when ranked in the top 10
  static const leaderExit = 25; // sell when it drops out of the top 25
  static const dipLeaderPct = 0.90; // dips are only traded in the top 10% by momentum
  static const dipRsi = 10.0; // 2-day RSI below this is a "dip"
  static const dipMaxHold = 10; // trading days
  static const dipStop = 0.12; // sell if it closes 12% below the buy price
  static const trendLen = 200;
}

/// Momentum ranking of every share on one day.
class DayRanks {
  DayRanks(this.date, this.rank, this.mom);
  final DateTime date;
  final Map<String, int> rank; // 1 = strongest
  final Map<String, double> mom; // six-month return
  int get count => rank.length;
  double pct(String code) => count <= 1 || !rank.containsKey(code) ? 0 : 1 - (rank[code]! - 1) / (count - 1);
}

enum TradeAction { buy, hold, sell }

/// A leader position under the hold-the-strongest rule.
class LeaderPosition {
  LeaderPosition({required this.code, required this.entryDate, required this.entryPrice});
  final String code;
  final DateTime entryDate;
  final double entryPrice;
  DateTime? exitDate;
  double? exitPrice;
  String exitReason = '';
  TradeAction action = TradeAction.hold;
  int rankNow = 0;
  double momNow = 0;
  double priceNow = 0;
  double trendLevel = 0; // 200-day average: closing below it is a sell
  int days = 0;

  bool get open => exitDate == null;
  double get gain => (exitPrice ?? priceNow) / entryPrice - 1;
}

/// A short-term dip trade in a momentum leader.
class DipTrade {
  DipTrade({required this.code, required this.signalDate, required this.entryDate, required this.entryPrice});
  final String code;
  final DateTime signalDate;
  final DateTime entryDate;
  final double entryPrice;
  DateTime? exitDate;
  double? exitPrice;
  String exitReason = '';
  TradeAction action = TradeAction.hold;
  double priceNow = 0;
  double sellAbove = 0; // 5-day average: closing above it is the sell
  double stop = 0;
  int days = 0;
  int rankAtSignal = 0;

  bool get open => exitDate == null;
  double get gain => (exitPrice ?? priceNow) / entryPrice - 1;
  int get daysLeft => StockRules.dipMaxHold - days;
}

class ScanResult {
  ScanResult({required this.asOf, required this.ranks, required this.leaders, required this.dips, required this.closedLeaders, required this.closedDips});
  final DateTime asOf;
  final DayRanks ranks;
  final List<LeaderPosition> leaders; // open positions plus any sold on the last day
  final List<DipTrade> dips; // open trades plus any sold on the last day
  final List<LeaderPosition> closedLeaders; // all closed in the window (for stats)
  final List<DipTrade> closedDips;
}

/// Runs both strategies across a set of price histories.
class StockScanner {
  StockScanner(this.series) {
    final days = <int>{};
    for (final s in series) {
      for (final b in s.bars) {
        days.add(b.date.millisecondsSinceEpoch ~/ 86400000);
      }
    }
    calendar = days.toList()..sort();
    for (final s in series) {
      // Index of each series' last bar on or before every calendar day.
      final idx = List<int>.filled(calendar.length, -1);
      var j = -1;
      for (var d = 0; d < calendar.length; d++) {
        while (j + 1 < s.n && s.bars[j + 1].date.millisecondsSinceEpoch ~/ 86400000 <= calendar[d]) {
          j++;
        }
        idx[d] = j;
      }
      _idx[s.code] = idx;
    }
  }

  final List<Series> series;
  late final List<int> calendar; // day numbers since epoch
  final Map<String, List<int>> _idx = {};

  DateTime dateOf(int d) => DateTime.fromMillisecondsSinceEpoch(calendar[d] * 86400000, isUtc: true);

  /// Bar index of [s] on calendar day [d], or -1 when it has no fresh bar.
  int barAt(Series s, int d) {
    final i = _idx[s.code]![d];
    if (i < 0) return -1;
    // Treat a share as untradeable when its last price is over 5 days stale.
    final age = calendar[d] - s.bars[i].date.millisecondsSinceEpoch ~/ 86400000;
    return age > 7 ? -1 : i;
  }

  static double momentum(Series s, int i) {
    if (i < StockRules.momLookback) return double.nan;
    return s.close(i - StockRules.momSkip) / s.close(i - StockRules.momLookback) - 1;
  }

  /// Momentum ranks on calendar day [d]. [eligible] limits which codes are ranked.
  DayRanks ranksAt(int d, {bool Function(Series s)? eligible}) {
    final rows = <(String, double)>[];
    for (final s in series) {
      if (eligible != null && !eligible(s)) continue;
      final i = barAt(s, d);
      if (i < 0) continue;
      final m = momentum(s, i);
      if (m.isNaN) continue;
      rows.add((s.code, m));
    }
    rows.sort((a, b) => b.$2.compareTo(a.$2));
    return DayRanks(dateOf(d), {for (var k = 0; k < rows.length; k++) rows[k].$1: k + 1}, {for (final r in rows) r.$1: r.$2});
  }

  /// Simulates both strategies from calendar day [from] to the end.
  ScanResult run({int? from, bool Function(Series s)? eligible}) {
    final last = calendar.length - 1;
    final start = (from ?? 0).clamp(0, last);
    final open = <String, LeaderPosition>{};
    final closedLeaders = <LeaderPosition>[];
    final soldToday = <LeaderPosition>[];
    final openDips = <String, DipTrade>{};
    final closedDips = <DipTrade>[];
    final dipSoldToday = <DipTrade>[];
    final byCode = {for (final s in series) s.code: s};
    late DayRanks ranks;
    for (var d = start; d <= last; d++) {
      ranks = ranksAt(d, eligible: eligible);
      final today = dateOf(d);
      // ----- leaders: sell first, then buy
      for (final code in open.keys.toList()) {
        final s = byCode[code]!;
        final i = barAt(s, d);
        if (i < 0) continue;
        final p = open[code]!;
        final r = ranks.rank[code] ?? 9999;
        final ma = s.sma(StockRules.trendLen)[i];
        p.days++;
        final belowTrend = !ma.isNaN && s.close(i) < ma;
        if (r > StockRules.leaderExit || belowTrend) {
          p.exitDate = today;
          p.exitPrice = s.close(i);
          p.exitReason = belowTrend ? 'closed below its 200-day average' : 'dropped out of the top ${StockRules.leaderExit}';
          open.remove(code);
          closedLeaders.add(p);
          if (d == last) soldToday.add(p..action = TradeAction.sell);
        }
      }
      ranks.rank.forEach((code, r) {
        if (r > StockRules.leaderEnter || open.containsKey(code)) return;
        final s = byCode[code]!;
        final i = barAt(s, d);
        if (i < 0) return;
        final ma = s.sma(StockRules.trendLen)[i];
        if (ma.isNaN || s.close(i) < ma) return;
        open[code] = LeaderPosition(code: code, entryDate: today, entryPrice: s.close(i))..action = d == last ? TradeAction.buy : TradeAction.hold;
      });
      // ----- dips in leaders
      for (final code in openDips.keys.toList()) {
        final s = byCode[code]!;
        final i = barAt(s, d);
        if (i < 0) continue;
        final t = openDips[code]!;
        // Only act on a new bar for this share.
        if (s.bars[i].date.isBefore(today) && d != last) continue;
        t.days++;
        final c = s.close(i);
        String? reason;
        if (c <= t.stop) {
          reason = 'stop';
        } else if (c > s.sma(5)[i]) {
          reason = 'closed back above its 5-day average';
        } else if (t.days >= StockRules.dipMaxHold) {
          reason = 'time limit';
        }
        if (reason != null) {
          t.exitDate = today;
          t.exitPrice = c;
          t.exitReason = reason;
          openDips.remove(code);
          closedDips.add(t);
          if (d == last) dipSoldToday.add(t..action = TradeAction.sell);
        }
      }
      for (final s in series) {
        if (openDips.containsKey(s.code)) continue;
        if (ranks.pct(s.code) < StockRules.dipLeaderPct) continue;
        final i = barAt(s, d);
        if (i < 0 || s.bars[i].date.isBefore(today)) continue;
        final ma = s.sma(StockRules.trendLen)[i], rsi = s.rsi(2)[i];
        if (ma.isNaN || rsi.isNaN) continue;
        if (s.close(i) > ma && rsi < StockRules.dipRsi) {
          openDips[s.code] = DipTrade(code: s.code, signalDate: today, entryDate: today, entryPrice: s.close(i))
            ..stop = s.close(i) * (1 - StockRules.dipStop)
            ..rankAtSignal = ranks.rank[s.code] ?? 0
            ..action = d == last ? TradeAction.buy : TradeAction.hold;
        }
      }
    }
    // Fill in the current state of everything still open.
    for (final p in open.values) {
      final s = byCode[p.code]!;
      final i = barAt(s, last);
      if (i < 0) continue;
      p
        ..priceNow = s.close(i)
        ..rankNow = ranks.rank[p.code] ?? 0
        ..momNow = ranks.mom[p.code] ?? 0
        ..trendLevel = s.sma(StockRules.trendLen)[i];
    }
    for (final p in soldToday) {
      p
        ..priceNow = p.exitPrice!
        ..rankNow = ranks.rank[p.code] ?? 0
        ..momNow = ranks.mom[p.code] ?? 0;
    }
    for (final t in openDips.values) {
      final s = byCode[t.code]!;
      final i = barAt(s, last);
      if (i < 0) continue;
      t
        ..priceNow = s.close(i)
        ..sellAbove = s.sma(5)[i];
    }
    for (final t in dipSoldToday) {
      t.priceNow = t.exitPrice!;
    }
    final leaders = [...open.values, ...soldToday]..sort((a, b) {
        final o = a.action.index.compareTo(b.action.index);
        return o != 0 ? o : a.rankNow.compareTo(b.rankNow);
      });
    final dips = [...openDips.values, ...dipSoldToday]..sort((a, b) => a.action.index.compareTo(b.action.index));
    return ScanResult(asOf: dateOf(last), ranks: ranks, leaders: leaders, dips: dips, closedLeaders: closedLeaders, closedDips: closedDips);
  }
}
