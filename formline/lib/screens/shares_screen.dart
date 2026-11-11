import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../engine/stock_calibration.dart';
import '../engine/stock_scanner.dart';
import '../models/asx_universe.dart';
import '../services/asx_api.dart';
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
    ShareScan.instance.load();
  }

  @override
  void dispose() {
    ShareScan.instance.removeListener(_changed);
    Settings.instance.removeListener(_changed);
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
          _costsCard(),
          SectionTitle('Short-term trades', trailing: const Muted('held for days')),
          Muted('Sharp dips in the market\'s strongest shares. Tested ${StockCalibration.period}: '
              '${pct(StockCalibration.dips.winRate)} winners, about ${StockCalibration.dips.days.round()} days each.'),
          const SizedBox(height: 8),
          ..._dipCards(r),
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

  Widget _costsCard() {
    final s = Settings.instance;
    final net = StockCalibration.dips.net(s.costPerSide);
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
                Text('Your trades: \$${s.tradeSize.round()} each, \$${num1(s.brokerage)} brokerage', style: const TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 2),
                Text(
                  net > 0
                      ? 'Short-term trades expect about ${signedPct(net, 2)} each after your costs.'
                      : 'At this size brokerage eats the short-term edge (${signedPct(net, 2)} a trade). Trade bigger parcels or stick to Leaders.',
                  style: TextStyle(fontSize: 12.5, color: net > 0 ? EdgeColors.win : EdgeColors.coral),
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
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Your trade size and brokerage'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(controller: size, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Dollars per trade', prefixText: '\$')),
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
    );
    if (ok != true) return;
    s.tradeSize = (double.tryParse(size.text) ?? s.tradeSize).clamp(100, 10000000).toDouble();
    s.brokerage = (double.tryParse(fee.text) ?? s.brokerage).clamp(0, 1000).toDouble();
    await s.save();
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
        plan = 'Buy around ${money(t.entryPrice)}${live ? ' if it closes near here' : ''}. '
            'Sell when it closes above ${money(t.sellAbove)} (its 5-day average) or by ${shortDate(addTradingDays(t.entryDate, StockRules.dipMaxHold))}. '
            'Get out if it closes below ${money(t.stop)}.';
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
                ]),
              ),
            ]),
          ),
        ),
      ),
    );
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
