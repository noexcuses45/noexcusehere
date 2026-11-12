import 'market_clock.dart';
import 'plan_advisor.dart';
import 'stock_scanner.dart';
import 'stocks.dart';

/// Day number of a calendar date (bars carry the Sydney date at midnight UTC).
int dayNo(DateTime d) => DateTime.utc(d.year, d.month, d.day).millisecondsSinceEpoch ~/ 86400000;

/// Price lines for today's close, worked out from the closes before today.
/// The rules are judged on closing prices, so these say in the morning what
/// today's closing price has to do for each rule to fire.
class DayLevels {
  static double _avgLast(Series s, int k) {
    if (s.n < k) return double.nan;
    var sum = 0.0;
    for (var i = s.n - k; i < s.n; i++) {
      sum += s.close(i);
    }
    return sum / k;
  }

  /// A close above this today is back above the 5-day average: the bounce
  /// the short-term rule sells on.
  static double bounceLine(Series s) => _avgLast(s, 4);

  /// A close below this today is under the 200-day average.
  static double trendLine(Series s) => _avgLast(s, StockRules.trendLen - 1);

  /// A close below this today takes the 2-day RSI under the dip level.
  static double dipLine(Series s) {
    if (s.n < 3) return double.nan;
    // Wilder's averages up to the last finished day (as in Series.rsi(2)).
    var gain = 0.0, loss = 0.0;
    for (var i = 1; i < s.n; i++) {
      final ch = s.close(i) - s.close(i - 1);
      final g = ch > 0 ? ch : 0.0, l = ch < 0 ? -ch : 0.0;
      if (i <= 2) {
        gain += g / 2;
        loss += l / 2;
      } else {
        gain = (gain + g) / 2;
        loss = (loss + l) / 2;
      }
    }
    // RSI < r  <=>  gain' * k < loss', with k = (100 - r) / r.
    const k = (100 - StockRules.dipRsi) / StockRules.dipRsi;
    final p = s.close(s.n - 1);
    return loss > k * gain ? p + loss / k - gain : p + loss - k * gain;
  }

  /// The closing range today that makes a short-term buy: above the 200-day
  /// average and low enough to count as a sharp dip. Null when no price does both.
  static ({double low, double high})? buyZone(Series s) {
    final low = trendLine(s), high = dipLine(s);
    if (low.isNaN || high.isNaN || high <= low) return null;
    return (low: low, high: high);
  }
}

/// The trading day a plan is for.
class Session {
  const Session(this.date, this.isToday);
  final DateTime date; // Sydney calendar date
  final bool isToday; // false once today is done (the plan is then for the next trading day)

  static Session at([DateTime? now]) {
    final s = MarketClock.sydney(now);
    var d = DateTime.utc(s.year, s.month, s.day);
    // The day's plan stays up for an hour after the close, so trades can be marked.
    final today = d.weekday <= 5 && s.hour < 17;
    if (!today) {
      do {
        d = d.add(const Duration(days: 1));
      } while (d.weekday > 5);
    }
    return Session(d, today);
  }
}

/// A share the user holds under the plan.
class Holding {
  const Holding(this.code, this.entryDate, this.entryPrice);
  final String code;
  final DateTime entryDate;
  final double entryPrice;
}

enum StepKind {
  sellNow, // the sell rule already fired at the last close
  buyNow, // leaders plan: buy this morning
  hold, // leaders plan: nothing to do today
  sellIfAbove, // short-term: sell at the close if the price is above [DayStep.high], else hold
  sellAtClose, // short-term: last day, sell at the close whatever the price
  buyIfInRange, // short-term: buy at the close if the price is between low and high
}

/// One instruction for the day.
class DayStep {
  DayStep(this.kind, this.code);
  final StepKind kind;
  final String code;
  double lastClose = double.nan;
  double? livePrice; // today's price so far, when the market is open
  double low = double.nan, high = double.nan; // buy range, or the sell line in [high]
  double stop = double.nan;
  double sellBelow = double.nan; // leaders: the 200-day line
  double dollars = 0;
  int shares = 0;
  int rank = 0;
  double mom = 0;
  int holdDay = 0; // which day of the hold today is (short-term)
  String reason = '';

  /// How far the price has to fall today to reach the buy range (0 if already there).
  double get fallNeeded => high >= lastClose ? 0 : 1 - high / lastClose;

  /// Whether the live price currently meets the step's condition (null when unknown).
  bool? get metNow {
    final p = livePrice;
    if (p == null) return null;
    return switch (kind) {
      StepKind.sellIfAbove => p > high || p <= stop,
      StepKind.buyIfInRange => p > low && p < high,
      _ => null,
    };
  }
}

/// The daily routine: what to do in the morning and what to do at the close.
class DayPlan {
  DayPlan({required this.session, required this.basedOn, required this.style, required this.morning, required this.close, required this.freeSlots, required this.budget});
  final Session session;
  final DateTime? basedOn; // the last finished day the plan was worked out from
  final String style;
  final List<DayStep> morning; // 10:15am Sydney
  final List<DayStep> close; // 3:50pm Sydney
  final int freeSlots;
  final double budget; // dollars for each new trade

  static const morningHour = 10, morningMinute = 15;
  static const closeHour = 15, closeMinute = 50;

  /// A buy range is listed only when the price is within this fall of it.
  static const maxFallShown = 0.04;

  /// True when the closing prices are older than the trading day before the session.
  bool get behind {
    if (basedOn == null) return true;
    var prev = session.date;
    do {
      prev = prev.subtract(const Duration(days: 1));
    } while (prev.weekday > 5);
    return dayNo(basedOn!) < dayNo(prev);
  }

  static DayRanks _ranks(Map<String, Series> finished, DateTime date, int latest, {required int offset}) {
    final rows = <(String, double)>[];
    finished.forEach((code, s) {
      // Leave out shares that have stopped trading.
      if (latest - dayNo(s.bars.last.date) > 7) return;
      final i = s.n - 1 + offset; // offset 1 = today's bar, which does not exist yet
      if (i < StockRules.momLookback) return;
      rows.add((code, s.close(i - StockRules.momSkip) / s.close(i - StockRules.momLookback) - 1));
    });
    rows.sort((a, b) => b.$2.compareTo(a.$2));
    return DayRanks(date, {for (var k = 0; k < rows.length; k++) rows[k].$1: k + 1}, {for (final r in rows) r.$1: r.$2});
  }

  /// Builds the routine. [series] may include today's unfinished bar; it is
  /// used only as the live price. [buyFee] is the brokerage on a buy of a
  /// given size, so the dollars shown fit inside the cash that is free.
  static DayPlan build({
    required Map<String, Series> series,
    required String style,
    required List<Holding> held,
    required double cash,
    required int slots,
    double Function(double size)? buyFee,
    DateTime? now,
  }) {
    final session = Session.at(now);
    final sd = dayNo(session.date);
    final finished = <String, Series>{};
    final live = <String, double>{};
    series.forEach((code, s) {
      var n = s.n;
      while (n > 0 && dayNo(s.bars[n - 1].date) >= sd) {
        n--;
      }
      if (n < s.n && dayNo(s.bars[s.n - 1].date) == sd) live[code] = s.close(s.n - 1);
      if (n == 0) return;
      finished[code] = n == s.n ? s : Series(code, s.bars.sublist(0, n));
    });
    var latest = 0;
    DateTime? basedOn;
    for (final s in finished.values) {
      final d = dayNo(s.bars.last.date);
      if (d > latest) {
        latest = d;
        basedOn = s.bars.last.date;
      }
    }
    final fee = buyFee ?? (double _) => 0.0;
    final heldCodes = held.map((h) => h.code).toSet();
    final free = (slots - held.length).clamp(0, slots);
    final budget = free == 0 ? 0.0 : cash / free;
    final morning = <DayStep>[], close = <DayStep>[];

    int wholeShares(double price) {
      var k = (budget / price).floor();
      while (k > 0 && k * price + fee(k * price) > budget + 1e-9) {
        k--;
      }
      return k;
    }

    if (style == 'short') {
      for (final h in held) {
        final s = finished[h.code];
        if (s == null) continue;
        final entryDay = dayNo(h.entryDate);
        var days = 0; // finished days since the buy
        for (var i = s.n - 1; i >= 0 && dayNo(s.bars[i].date) > entryDay; i--) {
          days++;
        }
        final stop = h.entryPrice * (1 - StockRules.dipStop);
        final c = s.close(s.n - 1);
        String? fired;
        if (days >= 1) {
          if (c <= stop) {
            fired = 'it closed at or below its stop';
          } else if (c > s.sma(5)[s.n - 1]) {
            fired = 'it closed back above its 5-day average';
          } else if (days >= StockRules.dipMaxHold) {
            fired = 'the ${StockRules.dipMaxHold}-day limit is up';
          }
        }
        final DayStep step;
        if (entryDay >= sd) {
          // Bought today: the rule looks at it again from the next trading day.
          step = DayStep(StepKind.hold, h.code);
          close.add(step);
        } else if (fired != null) {
          step = DayStep(StepKind.sellNow, h.code)..reason = fired;
          morning.add(step);
        } else if (days + 1 >= StockRules.dipMaxHold) {
          step = DayStep(StepKind.sellAtClose, h.code);
          close.add(step);
        } else {
          step = DayStep(StepKind.sellIfAbove, h.code)..high = DayLevels.bounceLine(s);
          close.add(step);
        }
        step
          ..lastClose = c
          ..livePrice = live[h.code]
          ..stop = stop
          ..holdDay = days + 1;
      }
      if (free > 0 && budget >= 20) {
        final ranks = _ranks(finished, session.date, latest, offset: 1);
        final buys = <DayStep>[];
        finished.forEach((code, s) {
          if (heldCodes.contains(code) || !ranks.rank.containsKey(code)) return;
          if (ranks.pct(code) < StockRules.dipLeaderPct) return;
          final zone = DayLevels.buyZone(s);
          if (zone == null) return;
          final c = s.close(s.n - 1);
          final step = DayStep(StepKind.buyIfInRange, code)
            ..lastClose = c
            ..livePrice = live[code]
            ..low = zone.low
            ..high = zone.high
            ..rank = ranks.rank[code]!
            ..mom = ranks.mom[code] ?? 0;
          if (step.fallNeeded > maxFallShown) return;
          // Sized at the top of the range, so the cash always covers it.
          final at = zone.high < c ? zone.high : c;
          step.shares = wholeShares(at);
          if (step.shares < 1) return;
          step.dollars = step.shares * at;
          buys.add(step);
        });
        buys.sort((a, b) => a.rank.compareTo(b.rank));
        close.addAll(buys);
      }
    } else {
      // Leaders: judged on the last finished close, acted on in the morning.
      final ranks = _ranks(finished, basedOn ?? session.date, latest, offset: 0);
      for (final h in held) {
        final s = finished[h.code];
        if (s == null) continue;
        final reason = PlanAdvisor.sellReason(h.code, ranks, s);
        morning.add(DayStep(reason == null ? StepKind.hold : StepKind.sellNow, h.code)
          ..reason = reason ?? ''
          ..lastClose = s.close(s.n - 1)
          ..livePrice = live[h.code]
          ..sellBelow = DayLevels.trendLine(s)
          ..rank = ranks.rank[h.code] ?? 0);
      }
      // Sells first, then holds.
      morning.sort((a, b) => a.kind.index.compareTo(b.kind.index));
      if (free > 0 && budget >= 20) {
        final fresh = <String, Series>{
          for (final e in finished.entries)
            if (ranks.rank.containsKey(e.key)) e.key: e.value
        };
        var each = budget;
        // Leave room for brokerage on the buy.
        each -= fee(each);
        for (final p in PlanAdvisor.picks(ranks, fresh, count: free, dollarsEach: each, exclude: heldCodes)) {
          morning.add(DayStep(StepKind.buyNow, p.code)
            ..lastClose = p.price
            ..livePrice = live[p.code]
            ..dollars = p.dollars
            ..shares = p.shares
            ..rank = p.rank
            ..mom = p.mom
            ..sellBelow = p.sellBelow);
        }
      }
    }
    return DayPlan(session: session, basedOn: basedOn, style: style, morning: morning, close: close, freeSlots: free, budget: budget);
  }
}
