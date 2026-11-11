import 'package:flutter/material.dart';

import '../engine/stock_scanner.dart';
import '../engine/stocks.dart';
import '../models/asx_universe.dart';
import '../services/asx_api.dart';
import '../services/settings_service.dart';
import '../services/share_scan.dart';
import '../theme.dart';
import '../widgets/common.dart';
import '../widgets/price_chart.dart';
import 'shares_screen.dart' show money, signedPct, shortDate, addTradingDays;

/// One ASX share: chart, where it ranks, and what the two rules say about it.
class StockScreen extends StatefulWidget {
  const StockScreen({super.key, required this.code, this.dip, this.leader});
  final String code;
  final DipTrade? dip;
  final LeaderPosition? leader;

  @override
  State<StockScreen> createState() => _StockScreenState();
}

class _StockScreenState extends State<StockScreen> {
  Series? _series;
  CompanyInfo? _info;
  bool _loading = true;
  String? _error;

  String get code => widget.code;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      _series = ShareScan.instance.series[code] ?? await AsxApi.history(code);
      if (_series == null) _error = 'No price history found for $code.';
    } catch (_) {
      _error = 'Could not load prices for $code.';
    }
    if (mounted) setState(() => _loading = false);
    final info = await AsxApi.company(code);
    if (mounted && info != null) setState(() => _info = info);
  }

  @override
  Widget build(BuildContext context) {
    final s = _series;
    final watch = Settings.instance.watchlist;
    final watched = watch.contains(code);
    return Scaffold(
      appBar: AppBar(
        title: Text(code),
        actions: [
          IconButton(
            icon: Icon(watched ? Icons.star : Icons.star_border, color: watched ? EdgeColors.lime : null),
            tooltip: watched ? 'Remove from watchlist' : 'Add to watchlist',
            onPressed: () {
              setState(() => watched ? watch.remove(code) : watch.add(code));
              Settings.instance.save();
            },
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null || s == null
              ? ErrorRetry(_error ?? 'No data', onRetry: _load)
              : ListView(padding: const EdgeInsets.fromLTRB(12, 0, 12, 28), children: _content(s)),
    );
  }

  List<Widget> _content(Series s) {
    final scan = ShareScan.instance;
    final i = s.n - 1;
    final price = s.close(i);
    final prev = i > 0 ? s.close(i - 1) : price;
    final ma200 = s.sma(200)[i];
    final rsi2 = s.rsi(2)[i];
    final mom = StockScanner.momentum(s, i);
    final ranks = scan.result?.ranks;
    // Rank among the scanned universe (works for shares outside it too).
    int? rank;
    int? of;
    if (ranks != null && !mom.isNaN) {
      of = ranks.count + (ranks.rank.containsKey(code) ? 0 : 1);
      rank = ranks.rank[code] ?? (ranks.mom.values.where((m) => m > mom).length + 1);
    }
    final dip = widget.dip ?? scan.result?.dips.where((t) => t.code == code).firstOrNull;
    final leader = widget.leader ?? scan.result?.leaders.where((l) => l.code == code).firstOrNull;
    final levels = <String, (double, Color)>{};
    if (dip != null && dip.action != TradeAction.sell) {
      levels['buy ${money(dip.entryPrice)}'] = (dip.entryPrice, EdgeColors.lime);
      levels['stop ${money(dip.stop)}'] = (dip.stop, EdgeColors.coral);
    } else if (leader != null && leader.open) {
      levels['bought ${money(leader.entryPrice)}'] = (leader.entryPrice, EdgeColors.lime);
    }
    final up = !ma200.isNaN && price > ma200;
    return [
      Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(_info?.name ?? AsxUniverse.nameOf(code), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            if ((_info?.sector ?? '').isNotEmpty) Muted(_info!.sector),
            const SizedBox(height: 8),
            Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Text(money(price), style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w900)),
              const SizedBox(width: 10),
              Padding(
                padding: const EdgeInsets.only(bottom: 5),
                child: Text(signedPct(price / prev - 1, 2),
                    style: TextStyle(fontWeight: FontWeight.w800, color: price >= prev ? EdgeColors.win : EdgeColors.coral)),
              ),
              const Spacer(),
              Muted('close ${shortDate(s.bars[i].date)}${scan.live ? ' (live)' : ''}'),
            ]),
            const SizedBox(height: 12),
            PriceChart(series: s, levels: levels),
            const SizedBox(height: 6),
            const Row(children: [
              Icon(Icons.remove, size: 16, color: EdgeColors.lime),
              Muted(' price   '),
              Icon(Icons.remove, size: 16, color: EdgeColors.sky),
              Muted(' 200-day average'),
            ]),
          ]),
        ),
      ),
      const SectionTitle('What the rules say'),
      if (dip != null) _planCard(_dipPlan(dip), dip.action, 'Short-term trade'),
      if (leader != null) _planCard(_leaderPlan(leader), leader.action, 'Leader to hold'),
      if (dip == null && leader == null)
        InfoBanner(
          rank == null
              ? 'Not enough history to rank this share.'
              : rank <= StockRules.leaderEnter && !up
                  ? 'Strong six-month gain, but it is below its 200-day average, so the rules stay out.'
                  : 'No signal. The model only trades the strongest shares, and this one ranks $rank of $of.',
        ),
      const SectionTitle('The numbers'),
      Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(children: [
            _fact('Six-month change', mom.isNaN ? '-' : signedPct(mom, 0), color: mom.isNaN ? null : (mom >= 0 ? EdgeColors.win : EdgeColors.coral)),
            _fact('Strength rank', rank == null ? '-' : '$rank of $of', hint: 'Top ${StockRules.leaderEnter} are bought, top ${StockRules.leaderExit} are kept'),
            _fact('Long-term trend', ma200.isNaN ? '-' : (up ? 'Up' : 'Down'),
                color: ma200.isNaN ? null : (up ? EdgeColors.win : EdgeColors.coral), hint: ma200.isNaN ? null : '200-day average ${money(ma200)}'),
            _fact('Short-term stretch', rsi2.isNaN ? '-' : (rsi2 < StockRules.dipRsi ? 'Sharp dip' : (rsi2 > 90 ? 'Run-up' : 'Normal')),
                hint: rsi2.isNaN ? null : '2-day RSI ${rsi2.round()} (dip under ${StockRules.dipRsi.round()})'),
            _fact('5-day average', money(s.sma(5)[i])),
            if (_info?.marketCap != null) _fact('Market value', _cap(_info!.marketCap!)),
          ]),
        ),
      ),
      const SizedBox(height: 10),
      const InfoBanner('General information from a rules-based model, not personal financial advice.', icon: Icons.gavel_outlined),
    ];
  }

  String _dipPlan(DipTrade t) {
    switch (t.action) {
      case TradeAction.buy:
        return 'Buy around ${money(t.entryPrice)}.\n'
            'Sell when it closes above ${money(t.sellAbove)} (its 5-day average), or by ${shortDate(addTradingDays(t.entryDate, StockRules.dipMaxHold))}.\n'
            'Get out if it closes below ${money(t.stop)}.';
      case TradeAction.hold:
        return 'Bought ${shortDate(t.entryDate)} at ${money(t.entryPrice)}; now ${signedPct(t.gain)}.\n'
            'Sell when it closes above ${money(t.sellAbove)}, or in ${t.daysLeft} more trading day${t.daysLeft == 1 ? '' : 's'}.\n'
            'Get out if it closes below ${money(t.stop)}.';
      case TradeAction.sell:
        return 'Sell now: ${t.exitReason}.\nBought ${shortDate(t.entryDate)} at ${money(t.entryPrice)}, out at ${money(t.exitPrice!)} (${signedPct(t.gain)}).';
    }
  }

  String _leaderPlan(LeaderPosition l) {
    switch (l.action) {
      case TradeAction.buy:
        return 'New leader. Buy around ${money(l.priceNow)}.\n'
            'Hold while it stays in the top ${StockRules.leaderExit} and above ${money(l.trendLevel)} (its 200-day average).';
      case TradeAction.hold:
        return 'Held since ${shortDate(l.entryDate)} at ${money(l.entryPrice)}; now ${signedPct(l.gain)}.\n'
            'Sell if it closes below ${money(l.trendLevel)} or its rank slips past ${StockRules.leaderExit} (now ${l.rankNow}).';
      case TradeAction.sell:
        return 'Sell: ${l.exitReason}.\nBought ${shortDate(l.entryDate)} at ${money(l.entryPrice)}, out at ${money(l.exitPrice!)} (${signedPct(l.gain)}).';
    }
  }

  Widget _planCard(String text, TradeAction a, String kind) {
    final (label, color) = switch (a) {
      TradeAction.buy => ('BUY', EdgeColors.win),
      TradeAction.hold => ('HOLD', EdgeColors.sky),
      TradeAction.sell => ('SELL', EdgeColors.coral),
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(color: color.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(8)),
                child: Text(label, style: TextStyle(color: color, fontWeight: FontWeight.w900, fontSize: 12)),
              ),
              const SizedBox(width: 10),
              Text(kind, style: const TextStyle(fontWeight: FontWeight.w800)),
            ]),
            const SizedBox(height: 8),
            Text(text, style: const TextStyle(height: 1.45)),
          ]),
        ),
      ),
    );
  }

  Widget _fact(String label, String value, {Color? color, String? hint}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label),
              if (hint != null) Muted(hint),
            ]),
          ),
          Text(value, style: TextStyle(fontWeight: FontWeight.w800, color: color)),
        ]),
      );

  static String _cap(double v) => v >= 1e9 ? '\$${(v / 1e9).toStringAsFixed(1)}b' : '\$${(v / 1e6).toStringAsFixed(0)}m';
}
