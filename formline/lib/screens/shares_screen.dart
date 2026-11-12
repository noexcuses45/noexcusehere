import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../engine/plan_advisor.dart';
import '../engine/stock_calibration.dart';
import '../engine/stock_scanner.dart';
import '../models/asx_universe.dart';
import '../services/asx_api.dart';
import '../services/practice_account.dart';
import '../services/settings_service.dart';
import '../services/share_scan.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'stock_screen.dart';

String money(double v) => '\$${v >= 100 ? v.toStringAsFixed(2) : (v >= 2 ? v.toStringAsFixed(2) : v.toStringAsFixed(3))}';
String signedPct(double v, [int d = 1]) => '${v >= 0 ? '+' : ''}${(v * 100).toStringAsFixed(d)}%';
String shortDate(DateTime d) => DateFormat('EEE d MMM').format(d);

/// Adds [n] weekdays to [from].
DateTime addTradingDays(DateTime from, int n) {
  var d = from;
  var left = n;
  while (left > 0) {
    d = d.add(const Duration(days: 1));
    if (d.weekday <= 5) left--;
  }
  return d;
}

/// Shares page: rule-based ASX trade ideas with exact buy and sell rules.
class SharesScreen extends StatefulWidget {
  const SharesScreen({super.key});

  @override
  State<SharesScreen> createState() => _SharesScreenState();
}

class _SharesScreenState extends State<SharesScreen> {
  final _searchCtrl = TextEditingController();
  List<CompanyInfo> _hits = [];
  bool _searching = false;

  @override
  void initState() {
    super.initState();
    ShareScan.instance.addListener(_changed);
    Settings.instance.addListener(_changed);
    PracticeAccount.instance.addListener(_changed);
    ShareScan.instance.load();
  }

  @override
  void dispose() {
    ShareScan.instance.removeListener(_changed);
    Settings.instance.removeListener(_changed);
    PracticeAccount.instance.removeListener(_changed);
    _searchCtrl.dispose();
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  void _open(String code, {DipTrade? dip, LeaderPosition? leader}) {
    Navigator.push(context, MaterialPageRoute(builder: (_) => StockScreen(code: code, dip: dip, leader: leader)));
  }

  Future<void> _search(String text) async {
    setState(() => _searching = true);
    final hits = await AsxApi.search(text);
    if (!mounted) return;
    setState(() {
      _hits = hits.take(6).toList();
      _searching = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final scan = ShareScan.instance;
    final r = scan.result;
    return RefreshIndicator(
      onRefresh: () => scan.load(force: true),
      child: ListView(padding: const EdgeInsets.fromLTRB(12, 4, 12, 28), children: [
        _statusRow(scan),
        if (scan.loading)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Column(children: [
              LinearProgressIndicator(value: scan.total == 0 ? null : scan.done / scan.total, color: EdgeColors.lime, backgroundColor: Colors.white12),
              const SizedBox(height: 4),
              Muted('Reading ASX prices (${scan.done}/${scan.total})...'),
            ]),
          ),
        if (scan.error != null && r == null) ErrorRetry(scan.error!, onRetry: () => scan.load(force: true)),
        if (r != null) ...[
          _planCard(r),
          const SizedBox(height: 10),
          _costsCard(),
          SectionTitle('Short-term trades', trailing: const Muted('held for days')),
          Muted('Sharp dips in the market\'s strongest shares. Tested ${StockCalibration.period}: '
              '${pct(StockCalibration.dips.winRate)} winners, about ${StockCalibration.dips.days.round()} days each.'),
          const SizedBox(height: 8),
          ..._dipCards(r),
          _expectCard(r),
          SectionTitle('Leaders to hold', trailing: const Muted('weeks to months')),
          Muted('The ten strongest shares of the past six months, held until they fade. '
              'Fewer than half win, but winners average ${signedPct(StockCalibration.leaders.avgWin, 0)} against losers at ${signedPct(StockCalibration.leaders.avgLoss, 0)}.'),
          const SizedBox(height: 8),
          ..._leaderCards(r),
          const SectionTitle('Look up any ASX share'),
          _searchCard(),
          const SectionTitle('How this was tested'),
          _testedCard(),
          const SizedBox(height: 10),
          const InfoBanner(
            'General information from a rules-based model, not personal financial advice. Past results do not guarantee future results. '
            'Only trade money you can afford to lose.',
            icon: Icons.gavel_outlined,
          ),
        ],
      ]),
    );
  }

  Widget _statusRow(ShareScan scan) {
    final r = scan.result;
    return Row(children: [
      const Icon(Icons.show_chart, color: EdgeColors.lime, size: 20),
      const SizedBox(width: 8),
      Expanded(
        child: Text(
          r == null ? 'ASX shares' : 'ASX · prices to ${shortDate(r.asOf)} · ${scan.live ? 'market open, not final' : 'final'}',
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      IconButton(onPressed: scan.loading ? null : () => scan.load(force: true), icon: const Icon(Icons.refresh), tooltip: 'Refresh prices'),
    ]);
  }

  String dollars(double v) {
    final a = v.abs();
    final body = a >= 10 ? NumberFormat('#,##0').format(a) : a.toStringAsFixed(2);
    return '${v < 0 ? '-' : ''}\$$body';
  }

  Widget _costsCard() {
    final s = Settings.instance;
    final net = StockCalibration.dips.net(s.costPerSide);
    // Leaders are judged on the last five years, the more cautious figure.
    final leaderNet = StockCalibration.leaders.recentGross - s.roundTripCost;
    final fees = s.buyFee(s.tradeSize) + s.sellFee(s.tradeSize);
    final broker = s.brokerModel == 'cmc' ? 'CMC Invest' : 'flat fee';
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: _editCosts,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(children: [
            const Icon(Icons.payments_outlined, color: EdgeColors.sky),
            const SizedBox(width: 12),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('Your trades: ${dollars(s.tradeSize)} each · $broker', style: const TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Muted('Brokerage ${dollars(s.buyFee(s.tradeSize))} to buy + ${dollars(s.sellFee(s.tradeSize))} to sell = ${dollars(fees)} a trade (${(s.roundTripCost * 100).toStringAsFixed(2)}%)'),
                const SizedBox(height: 2),
                Text(
                  net > 0
                      ? 'Short-term trades: about ${dollars(net * s.tradeSize)} each after brokerage (${signedPct(net, 2)}).'
                      : 'Short-term trades: brokerage beats the edge at this size (${dollars(net * s.tradeSize)} a trade).',
                  style: TextStyle(fontSize: 12.5, color: net > 0 ? EdgeColors.win : EdgeColors.coral),
                ),
                Text(
                  leaderNet > 0
                      ? 'Leaders: about ${dollars(leaderNet * s.tradeSize)} a trade after brokerage, on the last five years.'
                      : 'Leaders: brokerage beats the edge at this size too (${dollars(leaderNet * s.tradeSize)} a trade).',
                  style: TextStyle(fontSize: 12.5, color: leaderNet > 0 ? EdgeColors.win : EdgeColors.coral),
                ),
                if (net <= 0)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Muted(leaderNet > 0
                        ? 'With parcels this small, trade rarely: Leaders only. Frequent trading loses to fees.'
                        : 'With parcels this small, fees beat every tested rule. Follow the signals on paper until your parcels are bigger or your fees are lower.'),
                  ),
              ]),
            ),
            const Icon(Icons.edit_outlined, size: 18, color: EdgeColors.muted),
          ]),
        ),
      ),
    );
  }

  Future<void> _editCosts() async {
    final s = Settings.instance;
    final size = TextEditingController(text: s.tradeSize.round().toString());
    final fee = TextEditingController(text: num1(s.brokerage));
    var model = s.brokerModel;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: const Text('Your trade size and broker'),
          content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            TextField(controller: size, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Dollars per trade', prefixText: '\$')),
            const SizedBox(height: 12),
            DropdownButton<String>(
              value: model,
              isExpanded: true,
              items: const [
                DropdownMenuItem(value: 'cmc', child: Text('CMC Invest (Australian shares)')),
                DropdownMenuItem(value: 'flat', child: Text('Other broker: flat fee')),
              ],
              onChanged: (v) => setLocal(() => model = v ?? model),
            ),
            if (model == 'cmc')
              const Muted('First buy under \$1,000 per stock per day is free. Every other buy and all sells cost the greater of \$11 or 0.10%.')
            else
              TextField(
                  controller: fee,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(labelText: 'Brokerage per trade (each way)', prefixText: '\$')),
          ]),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Save')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    s.tradeSize = (double.tryParse(size.text) ?? s.tradeSize).clamp(100, 10000000).toDouble();
    s.brokerage = (double.tryParse(fee.text) ?? s.brokerage).clamp(0, 1000).toDouble();
    s.brokerModel = model;
    await s.save();
  }

  /// What following every short-term signal has looked like, in dollars.
  Widget _expectCard(ScanResult r) {
    final s = Settings.instance;
    final rt = s.roundTripCost;
    final size = s.tradeSize;
    final months = [for (final m in StockCalibration.dipMonths) (m[0] - rt * m[1]) * size]..sort();
    final positive = months.where((m) => m > 0).length;
    final median = months[months.length ~/ 2];
    final worst = months.first, best = months.last;
    final perMonth = StockCalibration.dipMonths.fold<num>(0, (a, m) => a + m[1]) / StockCalibration.dipMonths.length;
    // The last three months, replayed from real prices.
    final cutoff = r.asOf.subtract(const Duration(days: 92));
    final recent = r.closedDips.where((t) => t.exitDate!.isAfter(cutoff)).toList();
    final recentNet = recent.fold<double>(0, (a, t) => a + (t.gain - rt) * size);
    final recentWins = recent.where((t) => t.gain - rt > 0).length;
    Widget row(String a, String b, {Color? color}) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(children: [
            Expanded(child: Text(a, style: const TextStyle(fontSize: 13))),
            Text(b, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w800, color: color)),
          ]),
        );
    Color tone(double v) => v >= 0 ? EdgeColors.win : EdgeColors.coral;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('What to expect with ${dollars(size)} parcels', style: const TextStyle(fontWeight: FontWeight.w800)),
          const SizedBox(height: 2),
          const Muted('If you took every short-term signal, after your brokerage.'),
          const SizedBox(height: 8),
          row('Trades in a typical month', perMonth.toStringAsFixed(0)),
          row('Months that made money', '$positive of ${months.length}'),
          row('Typical month', dollars(median), color: tone(median)),
          row('Best month / worst month', '${dollars(best)} / ${dollars(worst)}'),
          const Divider(height: 18),
          row('Last 3 months: trades / winners', recent.isEmpty ? 'none' : '${recent.length} / $recentWins'),
          row('Last 3 months: result', recent.isEmpty ? '-' : dollars(recentNet), color: tone(recentNet)),
          const SizedBox(height: 8),
          Muted('Usually ${StockCalibration.dipOpenMedian} trades are open at once and sometimes ${StockCalibration.dipOpenP90}, '
              'so taking every signal needs about ${dollars(size * StockCalibration.dipOpenP90)} available. '
              'Results come in lumps, not as a steady daily amount: one month in three loses. '
              'Backtests flatter reality (this one used today\'s large companies and assumed buying at the closing price), so treat these figures as a ceiling, not a forecast.'),
        ]),
      ),
    );
  }

  List<Widget> _dipCards(ScanResult r) {
    if (r.dips.isEmpty) {
      return [const InfoBanner('No short-term trades right now. That is normal: the rule only fires when a leading share has a sharp dip, a few times a week.')];
    }
    return [for (final t in r.dips) _dipCard(t)];
  }

  Widget _badge(TradeAction a) {
    final (text, color) = switch (a) {
      TradeAction.buy => ('BUY', EdgeColors.win),
      TradeAction.hold => ('HOLD', EdgeColors.sky),
      TradeAction.sell => ('SELL', EdgeColors.coral),
    };
    return Container(
      width: 52,
      padding: const EdgeInsets.symmetric(vertical: 5),
      alignment: Alignment.center,
      decoration: BoxDecoration(color: color.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(8)),
      child: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w900, fontSize: 12)),
    );
  }

  Widget _dipCard(DipTrade t) {
    final live = ShareScan.instance.live;
    final String plan;
    switch (t.action) {
      case TradeAction.buy:
        final st = Settings.instance;
        final shares = (st.tradeSize / t.entryPrice).floor();
        final net = StockCalibration.dips.net(st.costPerSide) * st.tradeSize;
        final atStop = (StockRules.dipStop + st.roundTripCost) * st.tradeSize;
        plan = 'Buy around ${money(t.entryPrice)}${live ? ' if it closes near here' : ''}: about $shares shares for ${dollars(st.tradeSize)}. '
            'Sell when it closes above ${money(t.sellAbove)} (its 5-day average) or by ${shortDate(addTradingDays(t.entryDate, StockRules.dipMaxHold))}. '
            'Get out if it closes below ${money(t.stop)}. '
            'On average these make ${dollars(net)}; a stop-out costs about ${dollars(atStop)}.';
      case TradeAction.hold:
        plan = 'Bought ${shortDate(t.entryDate)} at ${money(t.entryPrice)}, now ${money(t.priceNow)} (${signedPct(t.gain)}). '
            'Sell when it closes above ${money(t.sellAbove)}, or in ${t.daysLeft} more trading day${t.daysLeft == 1 ? '' : 's'}. Stop ${money(t.stop)}.';
      case TradeAction.sell:
        plan = 'Sell now: ${t.exitReason}. Bought ${shortDate(t.entryDate)} at ${money(t.entryPrice)}, out at ${money(t.exitPrice!)} (${signedPct(t.gain)}).';
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: () => _open(t.code, dip: t),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              _badge(t.action),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Expanded(child: Text('${t.code} · ${AsxUniverse.nameOf(t.code)}', style: const TextStyle(fontWeight: FontWeight.w800), overflow: TextOverflow.ellipsis)),
                    Text(money(t.priceNow), style: const TextStyle(fontWeight: FontWeight.w800)),
                  ]),
                  const SizedBox(height: 4),
                  Text(plan, style: const TextStyle(fontSize: 13, height: 1.35)),
                  if (t.action == TradeAction.buy) _practiceBuy(t.code, 'dip', t.entryDate, t.entryPrice),
                ]),
              ),
            ]),
          ),
        ),
      ),
    );
  }

  List<Widget> _leaderCards(ScanResult r) {
    final buys = r.leaders.where((l) => l.action == TradeAction.buy).toList();
    final sells = r.leaders.where((l) => l.action == TradeAction.sell).toList();
    final holds = r.leaders.where((l) => l.action == TradeAction.hold).toList()..sort((a, b) => a.rankNow.compareTo(b.rankNow));
    return [
      for (final l in [...buys, ...sells]) _leaderActionCard(l),
      if (buys.isEmpty && sells.isEmpty) const Padding(padding: EdgeInsets.only(bottom: 8), child: Muted('No new buys or sells today. Current holdings:')),
      Card(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Column(children: [
            for (final l in holds)
              ListTile(
                dense: true,
                onTap: () => _open(l.code, leader: l),
                leading: _badge(TradeAction.hold),
                title: Text('${l.code} · ${AsxUniverse.nameOf(l.code)}', style: const TextStyle(fontWeight: FontWeight.w700), overflow: TextOverflow.ellipsis),
                subtitle: Text(
                  'Rank ${l.rankNow} · since ${DateFormat('d MMM').format(l.entryDate)} at ${money(l.entryPrice)} · sell under ${money(l.trendLevel)} or rank ${StockRules.leaderExit}+',
                  style: const TextStyle(color: EdgeColors.muted, fontSize: 12),
                ),
                trailing: Text(signedPct(l.gain), style: TextStyle(fontWeight: FontWeight.w800, color: l.gain >= 0 ? EdgeColors.win : EdgeColors.coral)),
              ),
            if (holds.isEmpty) const Padding(padding: EdgeInsets.all(14), child: Muted('Nothing held: no share currently qualifies.')),
          ]),
        ),
      ),
    ];
  }

  Widget _leaderActionCard(LeaderPosition l) {
    final buy = l.action == TradeAction.buy;
    final plan = buy
        ? 'New leader, ranked ${l.rankNow} with ${signedPct(l.momNow, 0)} over six months. Buy around ${money(l.priceNow)}. '
            'Hold until it closes below ${money(l.trendLevel)} (its 200-day average) or drops out of the top ${StockRules.leaderExit}.'
        : 'Sell: ${l.exitReason}. Bought ${shortDate(l.entryDate)} at ${money(l.entryPrice)}, out at ${money(l.exitPrice!)} (${signedPct(l.gain)}).';
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: () => _open(l.code, leader: l),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              _badge(l.action),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Expanded(child: Text('${l.code} · ${AsxUniverse.nameOf(l.code)}', style: const TextStyle(fontWeight: FontWeight.w800), overflow: TextOverflow.ellipsis)),
                    Text(money(l.priceNow), style: const TextStyle(fontWeight: FontWeight.w800)),
                  ]),
                  const SizedBox(height: 4),
                  Text(plan, style: const TextStyle(fontSize: 13, height: 1.35)),
                  if (buy) _practiceBuy(l.code, 'leader', l.entryDate, l.entryPrice),
                ]),
              ),
            ]),
          ),
        ),
      ),
    );
  }

  /// "Practice buy" button shown under a BUY signal.
  Widget _practiceBuy(String code, String kind, DateTime entryDate, double entryPrice) {
    final acct = PracticeAccount.instance;
    if (!acct.active) return const SizedBox();
    final taken = acct.has(code, kind, entryDate);
    final amount = acct.cash < Settings.instance.tradeSize ? acct.cash : Settings.instance.tradeSize;
    return Align(
      alignment: Alignment.centerLeft,
      child: taken
          ? const Padding(padding: EdgeInsets.only(top: 6), child: Muted('In your practice account'))
          : TextButton.icon(
              style: TextButton.styleFrom(foregroundColor: EdgeColors.lime, padding: EdgeInsets.zero, visualDensity: VisualDensity.compact),
              onPressed: amount < 20
                  ? null
                  : () async {
                      final ok = await acct.buy(code: code, kind: kind, entryDate: entryDate, entryPrice: entryPrice, dollars: amount);
                      if (mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(ok ? 'Practice buy: ${dollars(amount)} of $code' : 'Could not place the practice buy')));
                      }
                    },
              icon: const Icon(Icons.school_outlined, size: 18),
              label: Text(amount < 20 ? 'Practice cash is used up' : 'Practice buy ${dollars(amount)}'),
            ),
    );
  }

  /// "My plan": the user says how much they have, and the app says how much
  /// to put on which shares, when to sell, and what to buy next.
  Widget _planCard(ScanResult r) {
    final acct = PracticeAccount.instance;
    if (!acct.active) {
      return Card(
        child: ListTile(
          leading: const Icon(Icons.assignment_outlined, color: EdgeColors.lime),
          title: const Text('My plan: tell me how much you are starting with', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 14)),
          subtitle: const Muted('The app then says how much to put on which shares, when to sell each one, and what to buy next.'),
          trailing: const Icon(Icons.chevron_right),
          onTap: _setupPlan,
        ),
      );
    }
    final series = ShareScan.instance.series;
    final st = Settings.instance;
    final equity = acct.equity;
    final slots = PlanAdvisor.slotsFor(acct.start);
    final held = acct.openTrades.where((t) => t.kind == 'plan').toList();
    final others = acct.openTrades.where((t) => t.kind != 'plan').toList();
    final free = (slots - held.length).clamp(0, slots);
    final each = free == 0 ? 0.0 : acct.cash / free;
    final picks = free == 0 || each < 20
        ? <PlanPick>[]
        : PlanAdvisor.picks(r.ranks, series, count: free, dollarsEach: each, exclude: held.map((t) => t.code).toSet());
    final hist = PlanAdvisor.historyFor(acct.start);
    final pnl = acct.totalPnl;
    final closed = acct.closedTrades;
    final parcel = acct.start / slots;
    final fee = st.buyFee(parcel) + st.sellFee(parcel);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.assignment_outlined, color: EdgeColors.lime, size: 20),
            const SizedBox(width: 8),
            const Expanded(child: Text('My plan', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
            TextButton(onPressed: _setupPlan, child: const Text('Change')),
          ]),
          Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
            Text(dollars(equity), style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w900)),
            const SizedBox(width: 10),
            Padding(
              padding: const EdgeInsets.only(bottom: 3),
              child: Text('${pnl >= 0 ? '+' : ''}${dollars(pnl)} since you started with ${dollars(acct.start)}',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5, color: pnl >= 0 ? EdgeColors.win : EdgeColors.coral)),
            ),
          ]),
          const SizedBox(height: 4),
          Text('Split it across $slots shares, about ${dollars(parcel)} each. Cash waiting: ${dollars(acct.cash)}.', style: const TextStyle(fontSize: 13)),
          const SizedBox(height: 4),
          Text(
            hist[1] > 0.005
                ? 'Tested on the last five years: about ${signedPct(hist[1])} a year after brokerage at this size, but at its worst it was down ${(-hist[2] * 100).round()}%.'
                : 'Tested on the last five years: at this size brokerage (${dollars(fee)} a trade) beat the plan, about ${signedPct(hist[1])} a year, and at its worst it was down ${(-hist[2] * 100).round()}%. It starts to pay from about \$2,000. Until then, follow it without real money.',
            style: TextStyle(fontSize: 12.5, color: hist[1] > 0.005 ? EdgeColors.muted : EdgeColors.coral),
          ),
          const Divider(height: 20),
          for (final t in held) _planHeld(r, t),
          for (final p in picks) _planBuy(r, p),
          if (free > 0 && picks.length < free)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Muted(each < 20
                  ? 'No cash left for another share.'
                  : 'Nothing else qualifies right now. Keep ${dollars(each * (free - picks.length))} in cash until a new share enters the top ten.'),
            ),
          if (held.isEmpty && picks.isEmpty && free == 0) const Muted('Fully invested.'),
          for (final t in others)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(children: [
                Expanded(
                  child: Text('${t.code} · ${t.kind == 'dip' ? 'short-term practice' : 'leader practice'} · ${DateFormat('d MMM').format(t.entryDate)} at ${money(t.entryPrice)}',
                      style: const TextStyle(fontSize: 13)),
                ),
                Text('${t.pnl >= 0 ? '+' : ''}${dollars(t.pnl)}', style: TextStyle(fontWeight: FontWeight.w800, color: t.pnl >= 0 ? EdgeColors.win : EdgeColors.coral)),
              ]),
            ),
          if (closed.isNotEmpty) ...[
            const SizedBox(height: 10),
            Muted('Finished: ${closed.length} trade${closed.length == 1 ? '' : 's'}, ${closed.where((t) => t.pnl > 0).length} made money · brokerage paid ${dollars(acct.feesPaid)}'),
            for (final t in closed.take(3))
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Row(children: [
                  Expanded(
                    child: Text('${t.code} · sold ${DateFormat('d MMM').format(t.exitDate!)} at ${money(t.exitPrice!)}', style: const TextStyle(fontSize: 12.5, color: EdgeColors.muted)),
                  ),
                  Text('${t.pnl >= 0 ? '+' : ''}${dollars(t.pnl)}', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5, color: t.pnl >= 0 ? EdgeColors.win : EdgeColors.coral)),
                ]),
              ),
          ],
          const SizedBox(height: 8),
          const Muted('The app only keeps track. It never places orders: you buy and sell in your broker app.'),
        ]),
      ),
    );
  }

  /// A share the plan holds: HOLD with its sell trigger, or SELL now.
  Widget _planHeld(ScanResult r, PracticeTrade t) {
    final s = ShareScan.instance.series[t.code];
    final reason = PlanAdvisor.sellReason(t.code, r.ranks, s);
    final sellBelow = s == null ? double.nan : s.sma(StockRules.trendLen)[s.n - 1];
    final rank = r.ranks.rank[t.code];
    final sell = reason != null;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _badge(sell ? TradeAction.sell : TradeAction.hold),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('${t.code} · ${AsxUniverse.nameOf(t.code)}', style: const TextStyle(fontWeight: FontWeight.w800), overflow: TextOverflow.ellipsis)),
              Text('${t.pnl >= 0 ? '+' : ''}${dollars(t.pnl)}', style: TextStyle(fontWeight: FontWeight.w800, color: t.pnl >= 0 ? EdgeColors.win : EdgeColors.coral)),
            ]),
            const SizedBox(height: 2),
            Text(
              sell
                  ? 'Sell all of it now: $reason. You put in ${dollars(t.dollars)} on ${DateFormat('d MMM').format(t.entryDate)} at ${money(t.entryPrice)}; it is ${money(t.lastPrice)} now.'
                  : 'You put in ${dollars(t.dollars)} on ${DateFormat('d MMM').format(t.entryDate)} at ${money(t.entryPrice)}; it is ${money(t.lastPrice)} now. '
                      'Keep it until it closes below ${sellBelow.isNaN ? '-' : money(sellBelow)} or its rank slips past ${StockRules.leaderExit}${rank == null ? '' : ' (now $rank)'}.',
              style: const TextStyle(fontSize: 13, height: 1.35),
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                style: TextButton.styleFrom(foregroundColor: sell ? EdgeColors.coral : EdgeColors.muted, padding: EdgeInsets.zero, visualDensity: VisualDensity.compact),
                onPressed: () => _confirmSold(r, t, reason),
                child: Text(sell ? 'I have sold it' : 'I sold it early'),
              ),
            ),
          ]),
        ),
      ]),
    );
  }

  /// A share the plan says to buy now, with the dollar amount.
  Widget _planBuy(ScanResult r, PlanPick p) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _badge(TradeAction.buy),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('${p.code} · ${AsxUniverse.nameOf(p.code)}', style: const TextStyle(fontWeight: FontWeight.w800), overflow: TextOverflow.ellipsis)),
              Text(money(p.price), style: const TextStyle(fontWeight: FontWeight.w800)),
            ]),
            const SizedBox(height: 2),
            Text(
              'Put ${dollars(p.dollars)} on it: about ${p.shares} shares at ${money(p.price)}. '
              'It ranks ${p.rank} with ${signedPct(p.mom, 0)} over six months. '
              'Sell when it closes below ${money(p.sellBelow)} or its rank slips past ${StockRules.leaderExit}.',
              style: const TextStyle(fontSize: 13, height: 1.35),
            ),
            Row(children: [
              TextButton(
                style: TextButton.styleFrom(foregroundColor: EdgeColors.lime, padding: EdgeInsets.zero, visualDensity: VisualDensity.compact),
                onPressed: () async {
                  final ok = await PracticeAccount.instance.buy(code: p.code, kind: 'plan', entryDate: r.asOf, entryPrice: p.price, dollars: p.dollars);
                  if (mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(ok ? 'Tracking ${dollars(p.dollars)} of ${p.code}' : 'Could not add ${p.code}')));
                  }
                },
                child: const Text('I have bought it'),
              ),
              const SizedBox(width: 16),
              TextButton(
                style: TextButton.styleFrom(foregroundColor: EdgeColors.muted, padding: EdgeInsets.zero, visualDensity: VisualDensity.compact),
                onPressed: () => _open(p.code),
                child: const Text('See chart'),
              ),
            ]),
          ]),
        ),
      ]),
    );
  }

  Future<void> _confirmSold(ScanResult r, PracticeTrade t, String? reason) async {
    final ctrl = TextEditingController(text: t.lastPrice >= 2 ? t.lastPrice.toStringAsFixed(2) : t.lastPrice.toStringAsFixed(3));
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Sold ${t.code}?'),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Muted('Enter the price you sold at. The money goes back into your plan, less brokerage.'),
          const SizedBox(height: 10),
          TextField(controller: ctrl, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Sale price', prefixText: '\$')),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Sold')),
        ],
      ),
    );
    if (ok != true) return;
    final price = double.tryParse(ctrl.text) ?? t.lastPrice;
    if (price <= 0) return;
    await PracticeAccount.instance.sell(t, r.asOf, price, reason ?? 'sold early');
  }

  Future<void> _setupPlan() async {
    final acct = PracticeAccount.instance;
    final ctrl = TextEditingController(text: acct.active ? acct.start.round().toString() : '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(acct.active ? 'Change your plan' : 'How much are you starting with?'),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Muted(acct.active
              ? 'Starting again clears the trades the plan is tracking.'
              : 'Enter the dollars you have for shares. The app works out how much to put on each one.'),
          const SizedBox(height: 10),
          TextField(controller: ctrl, autofocus: true, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Starting amount', prefixText: '\$')),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: Text(acct.active ? 'Start again' : 'Make my plan')),
        ],
      ),
    );
    if (ok != true) return;
    final v = double.tryParse(ctrl.text.replaceAll(',', '')) ?? 0;
    if (v < 50) return;
    await acct.reset(v);
    // Size the brokerage maths to the parcels this plan will use.
    Settings.instance.tradeSize = v / PlanAdvisor.slotsFor(v);
    await Settings.instance.save();
  }

  Widget _searchCard() {
    final watch = Settings.instance.watchlist;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          TextField(
            controller: _searchCtrl,
            textCapitalization: TextCapitalization.characters,
            decoration: InputDecoration(
              hintText: 'Code or company name, e.g. CBA',
              isDense: true,
              border: const OutlineInputBorder(),
              prefixIcon: const Icon(Icons.search),
              suffixIcon: _searching ? const Padding(padding: EdgeInsets.all(12), child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))) : null,
            ),
            onSubmitted: _search,
          ),
          for (final h in _hits)
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: Text('${h.code} · ${h.name}', overflow: TextOverflow.ellipsis),
              subtitle: h.sector.isEmpty ? null : Muted(h.sector),
              trailing: const Icon(Icons.chevron_right, color: EdgeColors.muted),
              onTap: () => _open(h.code),
            ),
          if (watch.isNotEmpty) ...[
            const SizedBox(height: 10),
            const Muted('Your watchlist'),
            const SizedBox(height: 4),
            Wrap(spacing: 6, runSpacing: 4, children: [
              for (final c in watch) ActionChip(label: Text(c), visualDensity: VisualDensity.compact, onPressed: () => _open(c)),
            ]),
          ],
        ]),
      ),
    );
  }

  Widget _testedCard() {
    const d = StockCalibration.dips, l = StockCalibration.leaders;
    Widget row(String a, String b) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(child: Text(a, style: const TextStyle(fontSize: 13))),
            const SizedBox(width: 8),
            Text(b, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w800)),
          ]),
        );
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Every rule was replayed on ASX prices, ${StockCalibration.period}', style: const TextStyle(fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          const Text('Short-term trades', style: TextStyle(color: EdgeColors.lime, fontWeight: FontWeight.w700)),
          row('Trades tested', '${d.trades}'),
          row('Winners', pct(d.winRate)),
          row('Average per trade before costs', signedPct(d.gross, 2)),
          row('Typical win / typical loss', '${signedPct(d.avgWin)} / ${signedPct(d.avgLoss)}'),
          row('Last five years only', signedPct(d.recentGross, 2)),
          const SizedBox(height: 8),
          const Text('Leaders to hold', style: TextStyle(color: EdgeColors.lime, fontWeight: FontWeight.w700)),
          row('Trades tested', '${l.trades}'),
          row('Winners', pct(l.winRate)),
          row('Average per trade', signedPct(l.gross)),
          row('Better than the average share over the same days', signedPct(l.excess)),
          row('Last five years only (vs average share)', signedPct(l.recentExcess)),
          row('One trade in twenty loses more than', signedPct(l.worst5, 0)),
          const SizedBox(height: 8),
          const Text('Tested and left out', style: TextStyle(color: EdgeColors.coral, fontWeight: FontWeight.w700)),
          for (final x in StockCalibration.rejected)
            row(x.name, '${pct(x.winRate)} win, ${signedPct(x.net - x.baseline, 2)} vs holding'),
          const SizedBox(height: 8),
          const Muted('Those rejected ideas win often but make nothing after costs, because losses are bigger than wins. '
              'Caveats: the test used today\'s large companies, which flatters older results, and prices are daily closes, so this is not minute-by-minute day trading.'),
        ]),
      ),
    );
  }
}
