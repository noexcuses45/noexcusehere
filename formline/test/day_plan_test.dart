import 'package:flutter_test/flutter_test.dart';
import 'package:formline/engine/day_plan.dart';
import 'package:formline/engine/market_clock.dart';
import 'package:formline/engine/plan_advisor.dart';
import 'package:formline/engine/stock_scanner.dart';
import 'package:formline/engine/stocks.dart';

// Price histories that end on Wednesday 11 November 2026.
final lastDay = DateTime.utc(2026, 11, 11);
Series make(String code, double Function(int) price, {int n = 320}) => Series(code, [
      for (var i = 0; i < n; i++) Bar(lastDay.subtract(Duration(days: n - 1 - i)), price(i), 1000),
    ]);

Series plus(Series s, double close) => Series(s.code, [...s.bars, Bar(s.bars.last.date.add(const Duration(days: 1)), close, 1000)]);

// Thursday 12 November 2026 in Sydney (daylight saving, UTC+11).
DateTime sydney(int hour, int minute, {int day = 12}) => DateTime.utc(2026, 11, day, hour, minute).subtract(const Duration(hours: 11));

// A wobbly riser, so the averages and the RSI are not trivial.
double wobble(int i, double speed) => 10 * (1 + speed * i) * (1 + 0.003 * ((i * 7) % 5 - 2));

void main() {
  test('price lines match the indicators the rules use', () {
    final s = make('AAA', (i) => wobble(i, 0.002));
    // Dip line: a close just under it takes the 2-day RSI below 10, just over does not.
    final dip = DayLevels.dipLine(s);
    expect(plus(s, dip * 0.9995).rsi(2).last, lessThan(StockRules.dipRsi));
    expect(plus(s, dip * 1.0005).rsi(2).last, greaterThanOrEqualTo(StockRules.dipRsi));
    // Bounce line: a close just over it is above the new 5-day average.
    final bounce = DayLevels.bounceLine(s);
    final up = plus(s, bounce * 1.0005), down = plus(s, bounce * 0.9995);
    expect(up.close(up.n - 1), greaterThan(up.sma(5).last));
    expect(down.close(down.n - 1), lessThan(down.sma(5).last));
    // Trend line: a close just under it is below the new 200-day average.
    final trend = DayLevels.trendLine(s);
    final under = plus(s, trend * 0.9995), over = plus(s, trend * 1.0005);
    expect(under.close(under.n - 1), lessThan(under.sma(200).last));
    expect(over.close(over.n - 1), greaterThan(over.sma(200).last));
    // A share far below its 200-day average has no buy range.
    final crash = make('CRASH', (i) => i < 300 ? 10 * (1 + 0.01 * i) : 40 - 1.5 * (i - 300));
    expect(DayLevels.buyZone(crash), isNull);
  });

  test('the plan is for today until 5pm Sydney, then for the next trading day', () {
    expect(Session.at(sydney(9, 0)).date, DateTime.utc(2026, 11, 12));
    expect(Session.at(sydney(9, 0)).isToday, isTrue);
    expect(Session.at(sydney(16, 30)).isToday, isTrue);
    expect(Session.at(sydney(17, 30)).date, DateTime.utc(2026, 11, 13));
    expect(Session.at(sydney(17, 30)).isToday, isFalse);
    // Friday evening and the weekend roll to Monday.
    expect(Session.at(sydney(18, 0, day: 13)).date, DateTime.utc(2026, 11, 16));
    expect(Session.at(sydney(11, 0, day: 14)).date, DateTime.utc(2026, 11, 16));
  });

  test('clock helpers', () {
    expect(MarketClock.minutesUntil(15, 50, sydney(13, 40)), 130);
    expect(MarketClock.minutesUntil(10, 15, sydney(13, 40)), lessThan(0));
    // A phone on UTC is 11 hours behind Sydney in November.
    expect(MarketClock.timeLabel(15, 50, sydney(9, 0)), '3:50pm Sydney (4:50am your time)');
    // Before the open the latest session is yesterday's; weekends go back to Friday.
    expect(MarketClock.lastSessionDate(sydney(9, 0)), DateTime.utc(2026, 11, 11));
    expect(MarketClock.lastSessionDate(sydney(15, 55)), DateTime.utc(2026, 11, 12));
    expect(MarketClock.lastSessionDate(sydney(12, 0, day: 15)), DateTime.utc(2026, 11, 13));
  });

  test('short-term routine: buy prices at 3:50pm agree with the tested rule', () {
    // Twenty risers of different speeds; the fastest are the leaders.
    final list = [for (var k = 0; k < 20; k++) make('S${k.toString().padLeft(2, '0')}', (i) => wobble(i + k, 0.0005 * (k + 1)))];
    final series = {for (final s in list) s.code: s};
    final plan = DayPlan.build(series: series, style: 'short', held: const [], cash: 840, slots: 1, now: sydney(9, 0));
    expect(plan.session.date, DateTime.utc(2026, 11, 12));
    expect(plan.basedOn, lastDay);
    expect(plan.behind, isFalse);
    expect(plan.morning, isEmpty); // this rule never trades in the morning
    final buys = plan.close.where((x) => x.kind == StepKind.buyIfInRange).toList();
    expect(buys, isNotEmpty);
    for (final b in buys) {
      expect(b.fallNeeded, lessThanOrEqualTo(DayPlan.maxFallShown));
      // Whole shares that fit inside the cash.
      expect(b.shares, greaterThanOrEqualTo(1));
      expect(b.dollars, lessThanOrEqualTo(840 + 1e-9));
      // Replay today with this share closing just inside its range, and just
      // above it: the tested scanner must buy in the first case only.
      for (final (close, expected) in [(b.high * 0.999, true), (b.high * 1.001, false)]) {
        final next = [for (final s in list) plus(s, s.code == b.code ? close : s.close(s.n - 1))];
        final r = StockScanner(next).run(from: next.first.n - 1);
        final bought = r.dips.any((d) => d.code == b.code && d.action == TradeAction.buy);
        expect(bought, expected, reason: '${b.code} at $close');
      }
    }
    // Ranked best first.
    for (var i = 1; i < buys.length; i++) {
      expect(buys[i].rank, greaterThan(buys[i - 1].rank));
    }
    // No free cash, no buy list.
    final full = DayPlan.build(series: series, style: 'short', held: [Holding('S19', lastDay, 30)], cash: 0, slots: 1, now: sydney(9, 0));
    expect(full.close.where((x) => x.kind == StepKind.buyIfInRange), isEmpty);
  });

  test('short-term routine: sell or hold each day of a trade', () {
    final s = make('AAA', (i) => wobble(i, 0.002));
    final series = {'AAA': s};
    final price = s.close(s.n - 1);
    DayPlan run(DateTime entry, double entryPrice, {DateTime? now}) =>
        DayPlan.build(series: series, style: 'short', held: [Holding('AAA', entry, entryPrice)], cash: 0, slots: 1, now: now ?? sydney(9, 0));

    // Bought at yesterday's close: today is day 1, sell at the close if it bounces.
    var plan = run(lastDay, price);
    expect(plan.morning, isEmpty);
    var step = plan.close.single;
    expect(step.kind, StepKind.sellIfAbove);
    expect(step.holdDay, 1);
    expect(step.high, closeTo(DayLevels.bounceLine(s), 1e-9));
    expect(step.stop, closeTo(price * 0.88, 1e-9));

    // Bought today: nothing more to do until tomorrow.
    plan = run(DateTime.utc(2026, 11, 12), price, now: sydney(16, 5));
    expect(plan.close.single.kind, StepKind.hold);

    // Held nine finished days without a bounce: the tenth day is the last.
    final flat = make('AAA', (i) => i < 305 ? 10 + 0.02 * i : 16.1 - 0.05 * (i - 305));
    plan = DayPlan.build(series: {'AAA': flat}, style: 'short', held: [Holding('AAA', lastDay.subtract(const Duration(days: 9)), 16.0)], cash: 0, slots: 1, now: sydney(9, 0));
    step = plan.close.single;
    expect(step.kind, StepKind.sellAtClose);
    expect(step.holdDay, 10);

    // The stop was hit at yesterday's close: sell in the morning.
    plan = run(lastDay.subtract(const Duration(days: 2)), price * 1.2);
    expect(plan.morning.single.kind, StepKind.sellNow);
    expect(plan.morning.single.reason, contains('stop'));
    expect(plan.close, isEmpty);

    // During the day the live price decides the tag; the unfinished bar is
    // never treated as a close.
    final bounce = DayLevels.bounceLine(s);
    final live = {'AAA': plus(s, bounce * 1.01)};
    plan = DayPlan.build(series: live, style: 'short', held: [Holding('AAA', lastDay, price)], cash: 0, slots: 1, now: sydney(14, 0));
    step = plan.close.single;
    expect(plan.basedOn, lastDay);
    expect(step.kind, StepKind.sellIfAbove);
    expect(step.high, closeTo(bounce, 1e-9));
    expect(step.livePrice, closeTo(bounce * 1.01, 1e-9));
    expect(step.metNow, isTrue);
  });

  test('leaders routine: sells and buys in the morning, nothing at the close', () {
    final list = <Series>[
      for (var k = 0; k < 12; k++) make('S$k', (i) => 10 * (1 + 0.0005 * (k + 1) * i)),
      make('CRASH', (i) => i < 300 ? 10 * (1 + 0.01 * i) : 40 - 1.5 * (i - 300)),
    ];
    final series = {for (final s in list) s.code: s};
    final plan = DayPlan.build(
      series: series,
      style: 'long',
      held: [Holding('CRASH', lastDay.subtract(const Duration(days: 40)), 30), Holding('S11', lastDay.subtract(const Duration(days: 40)), 12)],
      cash: 280,
      slots: 3,
      now: sydney(9, 0),
    );
    expect(plan.close, isEmpty);
    expect(plan.morning.map((x) => (x.code, x.kind)), [('CRASH', StepKind.sellNow), ('S11', StepKind.hold), ('S10', StepKind.buyNow)]);
    final buy = plan.morning.last;
    expect(buy.shares, (280 / buy.lastClose).floor());
    expect(buy.dollars, lessThanOrEqualTo(280));
    expect(plan.morning.first.reason, contains('200-day'));
    // Today's unfinished prices do not change the morning's instructions.
    final live = {for (final s in list) s.code: plus(s, s.close(s.n - 1) * 0.5)};
    final again = DayPlan.build(
      series: live,
      style: 'long',
      held: [Holding('CRASH', lastDay.subtract(const Duration(days: 40)), 30), Holding('S11', lastDay.subtract(const Duration(days: 40)), 12)],
      cash: 280,
      slots: 3,
      now: sydney(11, 0),
    );
    expect(again.morning.map((x) => (x.code, x.kind)), plan.morning.map((x) => (x.code, x.kind)));
    expect(again.morning.last.livePrice, isNotNull);
  });

  test('short-term trading needs a bigger account than the leaders plan', () {
    expect(PlanAdvisor.shortSlotsFor(840), 1);
    expect(PlanAdvisor.shortSlotsFor(10000), 2);
    // At \$840 brokerage emptied the account in the test; from \$7,500 it paid.
    expect(PlanAdvisor.shortHistoryFor(840)[1], -1);
    expect(PlanAdvisor.shortHistoryFor(900)[0], 840);
    expect(PlanAdvisor.shortHistoryFor(7500)[1], greaterThan(0));
    // A one-day trade makes too little to cover any brokerage.
    expect(PlanAdvisor.oneDayGross, lessThan(0.002));
  });
}
