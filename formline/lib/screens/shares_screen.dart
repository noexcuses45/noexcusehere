import 'dart:async';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../engine/day_plan.dart';
import '../engine/market_clock.dart';
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
  Timer? _tick; // keeps the Today card's countdowns moving

  @override
  void initState() {
    super.initState();
    ShareScan.instance.addListener(_changed);
    Settings.instance.addListener(_changed);
    PracticeAccount.instance.addListener(_changed);
    ShareScan.instance.load();
    _tick = Timer.periodic(const Duration(minutes: 1), (_) => _changed());
  }

  @override
  void dispose() {
    ShareScan.instance.removeListener(_changed);
    Settings.instance.removeListener(_changed);
    PracticeAccount.instance.removeListener(_changed);
    _searchCtrl.dispose();
    _tick?.cancel();
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
          if (PracticeAccount.instance.active) ...[_todayCard(), const SizedBox(height: 10)],
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

  // ------------------------------------------------------------ My plan

  bool get _short => Settings.instance.planStyle == 'short';
  String get _planKind => _short ? 'short' : 'plan';
  int get _slots => _short ? PlanAdvisor.shortSlotsFor(PracticeAccount.instance.start) : PlanAdvisor.slotsFor(PracticeAccount.instance.start);

  /// What the chosen way of trading did in the test at [amount] dollars:
  /// the sentence, and whether it made money.
  (String, bool) _testedLine(double amount, bool short) {
    if (short) {
      final h = PlanAdvisor.shortHistoryFor(amount);
      final fees = '${h[3].round()} trades a year, ${dollars(h[4])} a year in brokerage';
      if (h[1] <= -0.95) {
        return ('Tested on the last five years with CMC Invest brokerage: at this size the fees used up the whole account ($fees). '
            'Short-term trading starts to pay from about \$7,500. Until then, follow it without real money.', false);
      }
      if (h[1] <= 0.005) {
        return ('Tested on the last five years with CMC Invest brokerage: about ${signedPct(h[1])} a year ($fees), and at its worst it was down ${(-h[2] * 100).round()}%. '
            'Short-term trading starts to pay from about \$7,500. Until then, follow it without real money.', false);
      }
      return ('Tested on the last five years with CMC Invest brokerage: about ${signedPct(h[1])} a year ($fees), but at its worst it was down ${(-h[2] * 100).round()}%.', true);
    }
    final h = PlanAdvisor.historyFor(amount);
    if (h[1] > 0.005) {
      return ('Tested on the last five years with CMC Invest brokerage: about ${signedPct(h[1])} a year, but at its worst it was down ${(-h[2] * 100).round()}%.', true);
    }
    return ('Tested on the last five years with CMC Invest brokerage: fees beat the plan at this size, about ${signedPct(h[1])} a year, and at its worst it was down ${(-h[2] * 100).round()}%. '
        'It starts to pay from about \$2,000. Until then, follow it without real money.', false);
  }

  /// "My plan": the balance, how the money is split, and what the test says.
  Widget _planCard(ScanResult r) {
    final acct = PracticeAccount.instance;
    if (!acct.active) {
      return Card(
        child: ListTile(
          leading: const Icon(Icons.assignment_outlined, color: EdgeColors.lime),
          title: const Text('My plan: tell me how much you are starting with', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 14)),
          subtitle: const Muted('The app then gives you a routine for each day: what to buy, at what time, how much to put on it, and when to sell or hold.'),
          trailing: const Icon(Icons.chevron_right),
          onTap: _setupPlan,
        ),
      );
    }
    final slots = _slots;
    final others = acct.openTrades.where((t) => t.kind != _planKind).toList();
    final pnl = acct.totalPnl;
    final closed = acct.closedTrades;
    final (tested, good) = _testedLine(acct.start, _short);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.assignment_outlined, color: EdgeColors.lime, size: 20),
            const SizedBox(width: 8),
            Expanded(child: Text('My plan · ${_short ? 'short-term trades' : 'leaders to hold'}', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
            TextButton(onPressed: _setupPlan, child: const Text('Change')),
          ]),
          Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
            Text(dollars(acct.equity), style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w900)),
            const SizedBox(width: 10),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.only(bottom: 3),
                child: Text('${pnl >= 0 ? '+' : ''}${dollars(pnl)} since you started with ${dollars(acct.start)}',
                    style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5, color: pnl >= 0 ? EdgeColors.win : EdgeColors.coral)),
              ),
            ),
          ]),
          const SizedBox(height: 4),
          Text(
            _short
                ? (slots == 1 ? 'One trade at a time, held for a few days. Cash waiting: ${dollars(acct.cash)}.' : '$slots trades at a time, about ${dollars(acct.start / slots)} each, held for a few days. Cash waiting: ${dollars(acct.cash)}.')
                : 'Split across $slots shares, about ${dollars(acct.start / slots)} each, held for weeks. Cash waiting: ${dollars(acct.cash)}.',
            style: const TextStyle(fontSize: 13),
          ),
          const SizedBox(height: 4),
          Text(tested, style: TextStyle(fontSize: 12.5, color: good ? EdgeColors.muted : EdgeColors.coral)),
          for (final t in others)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(children: [
                Expanded(
                  child: Text('${t.code} · ${t.kind == 'dip' ? 'short-term practice' : t.kind == 'leader' ? 'leader practice' : 'earlier plan'} · ${DateFormat('d MMM').format(t.entryDate)} at ${money(t.entryPrice)}',
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
        ]),
      ),
    );
  }

  // -------------------------------------------------------------- Today

  DayPlan _dayPlan() {
    final acct = PracticeAccount.instance;
    final st = Settings.instance;
    return DayPlan.build(
      series: ShareScan.instance.series,
      style: st.planStyle,
      held: [for (final t in acct.openTrades.where((t) => t.kind == _planKind)) Holding(t.code, t.entryDate, t.entryPrice)],
      cash: acct.cash,
      slots: _slots,
      buyFee: st.buyFee,
    );
  }

  PracticeTrade? _tradeFor(String code) {
    for (final t in PracticeAccount.instance.openTrades) {
      if (t.kind == _planKind && t.code == code) return t;
    }
    return null;
  }

  /// The daily routine: what to do at 10:15am and what to do at 3:50pm.
  Widget _todayCard() {
    final acct = PracticeAccount.instance;
    if (!acct.active) return const SizedBox();
    final plan = _dayPlan();
    final short = plan.style == 'short';
    final s = plan.session;
    final closeLabel = MarketClock.timeLabel(DayPlan.closeHour, DayPlan.closeMinute);
    final buys = plan.close.where((x) => x.kind == StepKind.buyIfInRange).toList();
    final heldAtClose = plan.close.where((x) => x.kind != StepKind.buyIfInRange).toList();
    final morningBuys = plan.morning.where((x) => x.kind == StepKind.buyNow).length;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.today_outlined, color: EdgeColors.lime, size: 20),
            const SizedBox(width: 8),
            Expanded(child: Text('${s.isToday ? 'Today' : 'Next trading day'} · ${shortDate(s.date)}', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
          ]),
          const SizedBox(height: 2),
          Muted(plan.basedOn == null
              ? 'Waiting for prices.'
              : 'Worked out from closing prices on ${shortDate(plan.basedOn!)}.${plan.behind ? ' Newer prices are not in yet: pull down to refresh.' : ''}'),
          _timeHeader(DayPlan.morningHour, DayPlan.morningMinute, 'after the market opens', plan, untilHour: DayPlan.closeHour, untilMinute: DayPlan.closeMinute, hasSteps: plan.morning.isNotEmpty),
          if (plan.morning.isEmpty)
            Muted(short
                ? 'Nothing to buy or sell in the morning. This rule trades at the end of the day, because that is the price it was tested on.'
                : plan.freeSlots > 0
                    ? (plan.budget < 20 ? 'No cash left for another share.' : 'Nothing qualifies to buy right now. Keep the cash until a new share enters the top ten.')
                    : 'Nothing to do.'),
          for (final x in plan.morning) _stepRow(x, plan),
          if (!short && plan.morning.isNotEmpty && plan.freeSlots > morningBuys && plan.budget >= 20)
            Muted('Nothing else qualifies right now. Keep ${dollars(plan.budget * (plan.freeSlots - morningBuys))} in cash until a new share enters the top ten.'),
          _timeHeader(DayPlan.closeHour, DayPlan.closeMinute, 'ten minutes before the close', plan, untilHour: 16, untilMinute: 10, hasSteps: plan.close.isNotEmpty),
          if (!short) const Muted('Nothing to do. This plan only trades in the morning: look again tomorrow.'),
          if (short) ...[
            for (final x in heldAtClose) _stepRow(x, plan),
            if (plan.freeSlots == 0)
              const Muted('All your cash is in use, so no new buys today.')
            else if (plan.budget < 20)
              const Muted('No cash left for a new trade.')
            else if (buys.isEmpty)
              const Muted('No leading share is close to a sharp dip today, so there is nothing to buy. That is normal: the rule fires a few times a week.')
            else ...[
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  'You have cash for ${plan.freeSlots == 1 ? 'one trade' : '${plan.freeSlots} trades'} of about ${dollars(plan.budget)}. '
                  'At $closeLabel look at the price in your broker app and buy the first share on this list that is under its buy price. If none is, do nothing.',
                  style: const TextStyle(fontSize: 13, height: 1.35),
                ),
              ),
              for (final x in buys) _stepRow(x, plan),
            ],
          ],
          const SizedBox(height: 8),
          const Muted('Times are Sydney time. The ASX trades 10:00am to 4:00pm on weekdays, not on public holidays. '
              'The app only keeps track. It never places orders: you buy and sell in your broker app.'),
        ]),
      ),
    );
  }

  Widget _timeHeader(int hour, int minute, String what, DayPlan plan, {required int untilHour, required int untilMinute, required bool hasSteps}) {
    final String status;
    final Color color;
    if (!plan.session.isToday) {
      status = DateFormat('EEE').format(plan.session.date);
      color = EdgeColors.muted;
    } else {
      final mins = MarketClock.minutesUntil(hour, minute);
      if (mins > 0) {
        status = mins >= 60 ? 'in ${mins ~/ 60}h ${mins % 60}m' : 'in $mins min';
        color = EdgeColors.sky;
      } else if (MarketClock.minutesUntil(untilHour, untilMinute) > 0) {
        // "NOW" only when there is something to do.
        status = hasSteps ? 'NOW' : '';
        color = EdgeColors.lime;
      } else {
        status = 'passed';
        color = EdgeColors.muted;
      }
    }
    return Padding(
      padding: const EdgeInsets.only(top: 14, bottom: 8),
      child: Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
        Expanded(
          child: Text.rich(TextSpan(children: [
            TextSpan(text: MarketClock.timeLabel(hour, minute), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15, color: EdgeColors.lime)),
            TextSpan(text: '  $what', style: const TextStyle(fontSize: 12.5, color: EdgeColors.muted)),
          ])),
        ),
        if (status.isNotEmpty)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(color: color.withValues(alpha: 0.18), borderRadius: BorderRadius.circular(8)),
            child: Text(status, style: TextStyle(color: color, fontWeight: FontWeight.w800, fontSize: 11.5)),
          ),
      ]),
    );
  }

  Widget _tag(String text, Color color) => Container(
        width: 52,
        padding: const EdgeInsets.symmetric(vertical: 5),
        alignment: Alignment.center,
        decoration: BoxDecoration(color: color.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(8)),
        child: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w900, fontSize: 12)),
      );

  /// One instruction in the Today card.
  Widget _stepRow(DayStep x, DayPlan plan) {
    final t = _tradeFor(x.code);
    final live = x.livePrice;
    final open = MarketClock.asxOpen();
    final nowWord = open ? 'Now' : 'Closed at';
    final closeLabel = MarketClock.timeLabel(DayPlan.closeHour, DayPlan.closeMinute);
    final put = t == null ? '' : 'You put in ${dollars(t.dollars)} on ${DateFormat('d MMM').format(t.entryDate)} at ${money(t.entryPrice)}. ';
    final String tag, text;
    final Color color;
    String? sellLabel, buyLabel;
    switch (x.kind) {
      case StepKind.sellNow:
        tag = 'SELL';
        color = EdgeColors.coral;
        text = 'Sell all of it: ${x.reason}. ${put}Last close ${money(x.lastClose)}${live == null ? '' : ', ${nowWord.toLowerCase()} ${money(live)}'}.';
        sellLabel = 'I have sold it';
      case StepKind.buyNow:
        tag = 'BUY';
        color = EdgeColors.win;
        text = 'Put ${dollars(x.dollars)} on it: ${x.shares} share${x.shares == 1 ? '' : 's'} at about ${money(x.lastClose)}. '
            'It ranks ${x.rank} with ${signedPct(x.mom, 0)} over six months. '
            'Then hold it until the app says sell: when it closes below ${money(x.sellBelow)} or its rank slips past ${StockRules.leaderExit}.';
        buyLabel = 'I have bought it';
      case StepKind.hold:
        tag = 'HOLD';
        color = EdgeColors.sky;
        text = plan.style == 'short'
            ? '${put}Nothing more to do today. From the next trading day this page says whether to sell or hold.'
            : '${put}Keep it today. It becomes a sell if it closes below ${x.sellBelow.isNaN ? '-' : money(x.sellBelow)} or its rank slips past ${StockRules.leaderExit}${x.rank == 0 ? '' : ' (now ${x.rank})'}.';
        sellLabel = 'I sold it early';
      case StepKind.sellIfAbove:
        final met = x.metNow;
        tag = met == null ? 'CHECK' : (met ? 'SELL' : 'HOLD');
        color = met == null ? EdgeColors.sky : (met ? EdgeColors.coral : EdgeColors.sky);
        text = 'Day ${x.holdDay} of ${StockRules.dipMaxHold}. At $closeLabel: sell if the price is above ${money(x.high)}. If it is not, hold another day. '
            'Also sell if it is at or below ${money(x.stop)}. $put'
            '${live == null ? '' : '$nowWord ${money(live)}: ${met! ? 'that is a SELL${open ? ' if it stays there' : ''}' : 'that is a HOLD${open ? ' so far' : ''}'}.'}';
        sellLabel = 'I have sold it';
      case StepKind.sellAtClose:
        tag = 'SELL';
        color = EdgeColors.coral;
        text = 'Day ${x.holdDay} of ${StockRules.dipMaxHold}, the last day. Sell at $closeLabel whatever the price. $put';
        sellLabel = 'I have sold it';
      case StepKind.buyIfInRange:
        final met = x.metNow;
        tag = met == true ? 'BUY' : 'WATCH';
        color = met == true ? EdgeColors.win : EdgeColors.sky;
        final floor = x.low > x.lastClose * 0.85 ? ' (but not below ${money(x.low)})' : '';
        text = 'Buy at $closeLabel if the price is under ${money(x.high)}$floor. Put ${dollars(x.dollars)} on it: about ${x.shares} share${x.shares == 1 ? '' : 's'}. '
            '${x.fallNeeded > 0 ? 'It last closed at ${money(x.lastClose)}, so it has to fall ${(x.fallNeeded * 100).toStringAsFixed(1)}% today.' : 'It last closed at ${money(x.lastClose)}: already low enough if it stays there.'}'
            '${live == null ? '' : ' $nowWord ${money(live)}: ${met! ? 'that is a BUY${open ? ' if it stays there' : ''}' : 'no buy${open ? ' so far' : ''}'}.'}';
        buyLabel = 'I have bought it';
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _tag(tag, color),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text('${x.code} · ${AsxUniverse.nameOf(x.code)}', style: const TextStyle(fontWeight: FontWeight.w800), overflow: TextOverflow.ellipsis)),
              if (t != null)
                Text('${t.pnl >= 0 ? '+' : ''}${dollars(t.pnl)}', style: TextStyle(fontWeight: FontWeight.w800, color: t.pnl >= 0 ? EdgeColors.win : EdgeColors.coral))
              else
                Text(money(live ?? x.lastClose), style: const TextStyle(fontWeight: FontWeight.w800)),
            ]),
            const SizedBox(height: 2),
            Text(text.trim(), style: const TextStyle(fontSize: 13, height: 1.35)),
            Row(children: [
              if (buyLabel != null)
                TextButton(
                  style: TextButton.styleFrom(foregroundColor: EdgeColors.lime, padding: EdgeInsets.zero, visualDensity: VisualDensity.compact),
                  onPressed: () => _confirmBought(x),
                  child: Text(buyLabel),
                ),
              if (sellLabel != null && t != null)
                TextButton(
                  style: TextButton.styleFrom(foregroundColor: x.kind == StepKind.hold ? EdgeColors.muted : EdgeColors.coral, padding: EdgeInsets.zero, visualDensity: VisualDensity.compact),
                  onPressed: () => _confirmSold(t, x.kind == StepKind.sellNow ? x.reason : (x.kind == StepKind.sellAtClose ? 'time limit' : (x.kind == StepKind.hold ? 'sold early' : 'sold on the daily check'))),
                  child: Text(sellLabel),
                ),
              const SizedBox(width: 16),
              TextButton(
                style: TextButton.styleFrom(foregroundColor: EdgeColors.muted, padding: EdgeInsets.zero, visualDensity: VisualDensity.compact),
                onPressed: () => _open(x.code),
                child: const Text('See chart'),
              ),
            ]),
          ]),
        ),
      ]),
    );
  }

  String _priceText(double p) => p >= 2 ? p.toStringAsFixed(2) : p.toStringAsFixed(3);

  /// Records a buy the user made, at the price and number of shares they got.
  Future<void> _confirmBought(DayStep x) async {
    final acct = PracticeAccount.instance;
    final guess = x.livePrice ?? (x.kind == StepKind.buyIfInRange && x.high < x.lastClose ? x.high : x.lastClose);
    final priceCtrl = TextEditingController(text: _priceText(guess));
    final sharesCtrl = TextEditingController(text: '${x.shares}');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Bought ${x.code}?'),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Muted('Enter what you actually got, so the app can track it and tell you when to sell.'),
          const SizedBox(height: 10),
          TextField(controller: priceCtrl, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: const InputDecoration(labelText: 'Price paid', prefixText: '\$')),
          const SizedBox(height: 8),
          TextField(controller: sharesCtrl, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Number of shares')),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Bought')),
        ],
      ),
    );
    if (ok != true) return;
    final price = double.tryParse(priceCtrl.text.replaceAll(',', '')) ?? 0;
    final shares = int.tryParse(sharesCtrl.text.replaceAll(',', '')) ?? 0;
    if (price <= 0 || shares < 1) return;
    final amount = price * shares;
    final total = amount + Settings.instance.buyFee(amount);
    final done = total <= acct.cash + 0.005 &&
        await acct.buy(code: x.code, kind: _planKind, entryDate: MarketClock.lastSessionDate(), entryPrice: price, dollars: total);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(done ? 'Tracking $shares ${x.code} at ${money(price)}' : 'That is more than the ${dollars(acct.cash)} cash in your plan')));
    }
  }

  Future<void> _confirmSold(PracticeTrade t, String reason) async {
    final ctrl = TextEditingController(text: _priceText(t.lastPrice));
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
    final price = double.tryParse(ctrl.text.replaceAll(',', '')) ?? t.lastPrice;
    if (price <= 0) return;
    await PracticeAccount.instance.sell(t, MarketClock.lastSessionDate(), price, reason);
  }

  Future<void> _setupPlan() async {
    final acct = PracticeAccount.instance;
    final ctrl = TextEditingController(text: acct.active ? acct.start.round().toString() : '');
    String? style = acct.active ? Settings.instance.planStyle : null; // null = follow the test
    double amount() => double.tryParse(ctrl.text.replaceAll(',', '')) ?? 0;
    String better(double v) => PlanAdvisor.shortHistoryFor(v)[1] > PlanAdvisor.historyFor(v)[1] ? 'short' : 'long';
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setLocal) {
        final v = amount();
        final chosen = style ?? better(v);
        Widget option(String id, String title, String what) {
          final (tested, good) = _testedLine(v, id == 'short');
          final on = chosen == id;
          return InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () => setLocal(() => style = id),
            child: Container(
              margin: const EdgeInsets.only(top: 8),
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: on ? EdgeColors.lime : Colors.white24, width: on ? 1.6 : 1),
              ),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Icon(on ? Icons.radio_button_checked : Icons.radio_button_off, size: 20, color: on ? EdgeColors.lime : EdgeColors.muted),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(title, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 13.5)),
                    Text(what, style: const TextStyle(fontSize: 12.5)),
                    if (v >= 50) Text(tested, style: TextStyle(fontSize: 12, color: good ? EdgeColors.win : EdgeColors.coral)),
                  ]),
                ),
              ]),
            ),
          );
        }

        return AlertDialog(
          title: Text(acct.active ? 'Change your plan' : 'How much are you starting with?'),
          content: SingleChildScrollView(
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Muted(acct.active ? 'Starting again clears the trades the plan is tracking.' : 'Enter the dollars you have for shares, then pick how you want to trade.'),
              const SizedBox(height: 10),
              TextField(
                controller: ctrl,
                autofocus: !acct.active,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Starting amount', prefixText: '\$'),
                onChanged: (_) => setLocal(() {}),
              ),
              option('short', 'Short-term trades (days)', 'Buy a sharp dip in a leading share at 3:50pm, sell on the bounce a few days later.'),
              option('long', 'Leaders to hold (weeks)', 'Buy the strongest shares at 10:15am and hold each one until it fades.'),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: Text(acct.active ? 'Start again' : 'Make my plan')),
          ],
        );
      }),
    );
    if (ok != true) return;
    final v = amount();
    if (v < 50) return;
    final chosen = style ?? better(v);
    Settings.instance.planStyle = chosen;
    // Size the brokerage maths to the parcels this plan will use.
    Settings.instance.tradeSize = v / (chosen == 'short' ? PlanAdvisor.shortSlotsFor(v) : PlanAdvisor.slotsFor(v));
    await Settings.instance.save();
    await acct.reset(v);
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
          row('Buying a dip and selling the next day', '${pct(PlanAdvisor.oneDayWin)} win, ${signedPct(PlanAdvisor.oneDayGross, 2)} before fees'),
          const SizedBox(height: 8),
          const Muted('Those rejected ideas win often but make nothing after costs, because losses are bigger than wins. '
              'Caveats: the test used today\'s large companies, which flatters older results, and prices are daily closes, so this is not minute-by-minute day trading.'),
        ]),
      ),
    );
  }
}
