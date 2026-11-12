import 'package:flutter_test/flutter_test.dart';
import 'package:formline/engine/plan_advisor.dart';
import 'package:formline/engine/stock_scanner.dart';
import 'package:formline/engine/stocks.dart';
import 'package:formline/services/practice_account.dart';
import 'package:formline/services/settings_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

Series make(String code, double Function(int) price, {int n = 320}) => Series(code, [
      for (var i = 0; i < n; i++) Bar(DateTime.utc(2025, 1, 1).add(Duration(days: i)), price(i), 1000),
    ]);

void main() {
  test('plan spreads the money: three shares, five from \$5,000', () {
    expect(PlanAdvisor.slotsFor(840), 3);
    expect(PlanAdvisor.slotsFor(4999), 3);
    expect(PlanAdvisor.slotsFor(5000), 5);
    expect(PlanAdvisor.slotsFor(50000), 5);
  });

  test('history lookup uses the nearest smaller tested amount', () {
    expect(PlanAdvisor.historyFor(840)[0], 840);
    expect(PlanAdvisor.historyFor(900)[0], 840);
    expect(PlanAdvisor.historyFor(2500)[0], 2000);
    expect(PlanAdvisor.historyFor(100)[0], 300); // below the table: the smallest row
    expect(PlanAdvisor.historyFor(1e6)[0], 50000);
    // At \$840 the tested plan lost to brokerage; from \$2,000 it paid.
    expect(PlanAdvisor.historyFor(840)[1], lessThan(0));
    expect(PlanAdvisor.historyFor(2000)[1], greaterThan(0));
  });

  test('picks the best-ranked rising shares and says when to sell', () {
    // Twelve steady risers of different speeds and one that has collapsed.
    final list = <Series>[
      for (var k = 0; k < 12; k++) make('S$k', (i) => 10 * (1 + 0.0005 * (k + 1) * i)),
      make('CRASH', (i) => i < 300 ? 10 * (1 + 0.01 * i) : 40 - 1.5 * (i - 300)),
    ];
    final series = {for (final s in list) s.code: s};
    final sc = StockScanner(list);
    final ranks = sc.ranksAt(sc.calendar.length - 1);
    final picks = PlanAdvisor.picks(ranks, series, count: 3, dollarsEach: 280);
    expect(picks.length, 3);
    // Fastest steady risers first; the crashed share is below its average so it is skipped.
    expect(picks.map((p) => p.code), isNot(contains('CRASH')));
    expect(picks.first.code, 'S11');
    // Whole shares only, so the amount is a little under the $280 parcel.
    expect(picks.first.shares, (280 / picks.first.price).floor());
    expect(picks.first.dollars, closeTo(picks.first.shares * picks.first.price, 1e-9));
    expect(picks.first.dollars, lessThanOrEqualTo(280));
    // A parcel too small for even one share skips that share.
    expect(PlanAdvisor.picks(ranks, series, count: 3, dollarsEach: 5), isEmpty);
    expect(picks.first.sellBelow, lessThan(picks.first.price));
    // Already-held shares are not suggested again.
    final next = PlanAdvisor.picks(ranks, series, count: 1, dollarsEach: 280, exclude: {'S11', 'S10', 'S9'});
    expect(next.single.code, 'S8');
    // Sell signals.
    expect(PlanAdvisor.sellReason('S11', ranks, series['S11']), isNull);
    expect(PlanAdvisor.sellReason('CRASH', ranks, series['CRASH']), contains('200-day'));
    expect(PlanAdvisor.sellReason('GONE', ranks, series['S0']), contains('top'));
  });

  test('plan ledger: buy three parcels, re-price, sell and reuse the cash', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    SharedPreferences.setMockInitialValues({});
    Settings.instance.brokerModel = 'cmc';
    final acct = PracticeAccount.instance;
    await acct.reset(840);
    final day = DateTime.utc(2026, 11, 12);
    for (final code in ['AAA', 'BBB', 'CCC']) {
      expect(await acct.buy(code: code, kind: 'plan', entryDate: day, entryPrice: 2.0, dollars: 280), isTrue);
    }
    expect(acct.cash, closeTo(0, 1e-9));
    expect(acct.openTrades.length, 3);
    expect(acct.feesPaid, 0); // each buy is under \$1,000, so free at CMC
    // AAA rises 10%: worth \$308, sold for \$308 less the \$11 fee.
    final a = acct.openTrades.firstWhere((t) => t.code == 'AAA');
    a.lastPrice = 2.2;
    expect(acct.equity, closeTo(840 + 28, 1e-9));
    await acct.sell(a, day.add(const Duration(days: 30)), 2.2, 'it dropped out of the top 25');
    expect(a.sellFee, 11);
    expect(acct.cash, closeTo(308 - 11, 1e-9));
    expect(a.pnl, closeTo(17, 1e-9));
    // The freed cash funds the next share.
    expect(await acct.buy(code: 'DDD', kind: 'plan', entryDate: day, entryPrice: 5, dollars: acct.cash), isTrue);
    expect(acct.openTrades.length, 3);
    Settings.instance.tradeSize = 10000;
  });
}
