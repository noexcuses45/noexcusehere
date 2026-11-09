import 'package:flutter/material.dart';

import '../engine/calibration.dart';
import '../models/league.dart';
import '../services/model_store.dart';
import '../services/prediction_log.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// The model's honest record: every prediction it made on this phone, graded,
/// plus how it performed when replayed over past seasons.
class RecordView extends StatefulWidget {
  const RecordView({super.key});

  @override
  State<RecordView> createState() => _RecordViewState();
}

class _RecordViewState extends State<RecordView> {
  @override
  void initState() {
    super.initState();
    PredictionLog.instance.addListener(_changed);
  }

  @override
  void dispose() {
    PredictionLog.instance.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  Future<void> _grade() async {
    await PredictionLog.instance.gradePending();
    ModelStore.instance.refreshBias();
  }

  @override
  Widget build(BuildContext context) {
    final log = PredictionLog.instance;
    final leagues = log.leagueRecords;
    final races = log.raceRecords;
    final ids = leagues.keys.toList()..sort((a, b) => leagues[b]!.n.compareTo(leagues[a]!.n));
    return RefreshIndicator(
      onRefresh: _grade,
      child: ListView(padding: const EdgeInsets.fromLTRB(12, 8, 12, 24), children: [
        Row(children: [
          Expanded(
            child: Muted(
              'Every prediction is logged and graded automatically.'
              '${log.pendingCount > 0 ? ' ${log.pendingCount} awaiting results.' : ''}',
            ),
          ),
          IconButton(
            onPressed: log.grading ? null : _grade,
            icon: log.grading ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.refresh),
            tooltip: 'Grade finished events',
          ),
        ]),
        const SectionTitle('Live record on this phone'),
        if (ids.isEmpty && races.isEmpty)
          const InfoBanner('Nothing graded yet. Open a league or a race meeting and the app starts logging its predictions; results appear here once the games are played.'),
        for (final id in ids) _leagueCard(Leagues.byId(id), leagues[id]!),
        for (final e in races.entries) _raceCard(e.key, e.value),
        const SectionTitle('Backtest over past seasons'),
        const Muted('Each game was predicted using only the games played before it.'),
        const SizedBox(height: 8),
        for (final e in Calibration.backtests.entries) _backtestCard(Leagues.byId(e.key), e.value),
        const SizedBox(height: 8),
        const InfoBanner('"Said" vs "won" shows calibration: when the model gives a favourite 70%, favourites like that should win about 70% of the time.'),
      ]),
    );
  }

  Widget _stat(String label, String value, {Color? color}) => Expanded(
        child: Column(children: [
          Text(value, style: TextStyle(fontWeight: FontWeight.w900, fontSize: 16, color: color)),
          Muted(label, size: 11),
        ]),
      );

  Widget _leagueCard(League? league, LeagueRecord r) {
    if (league == null) return const SizedBox();
    final bias = PredictionLog.instance.totalBias(league);
    final ou = r.ouWins + r.ouLosses, ats = r.atsWins + r.atsLosses;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(league.name, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
              Muted('${r.n} games'),
            ]),
            const SizedBox(height: 10),
            Row(children: [
              _stat('winners right', pct(r.accuracy), color: probColor(r.accuracy)),
              _stat('expected', pct(r.expected)),
              _stat('avg total miss', num1(r.totalMae)),
            ]),
            if (ou > 0 || ats > 0) ...[
              const SizedBox(height: 10),
              Row(children: [
                _stat('totals vs bookie', ou == 0 ? '-' : '${r.ouWins}-${r.ouLosses}', color: ou >= 10 ? (r.ouWins / ou >= 0.524 ? EdgeColors.win : EdgeColors.muted) : null),
                _stat('line vs bookie', ats == 0 ? '-' : '${r.atsWins}-${r.atsLosses}', color: ats >= 10 ? (r.atsWins / ats >= 0.524 ? EdgeColors.win : EdgeColors.muted) : null),
                _stat('needed to profit', '52.4%'),
              ]),
            ],
            const SizedBox(height: 8),
            Muted(bias.abs() >= 0.1
                ? 'Totals have run ${r.bias >= 0 ? 'over' : 'under'} projection by ${num1(r.bias.abs())}. Projections now adjusted by ${bias >= 0 ? '+' : ''}${bias.toStringAsFixed(1)}.'
                : 'Totals bias ${r.bias >= 0 ? '+' : ''}${r.bias.toStringAsFixed(1)} per game${r.n < 15 ? ' (self-correction starts after 15 games)' : ''}.'),
          ]),
        ),
      ),
    );
  }

  Widget _raceCard(String type, RaceRecord r) {
    final name = type == 'T' ? 'Horse racing top picks' : (type == 'H' ? 'Harness top picks' : 'Greyhound top picks');
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(name, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
              Muted('${r.n} races'),
            ]),
            const SizedBox(height: 10),
            Row(children: [
              _stat('winners', pct(r.strike), color: probColor(r.strike + 0.3)),
              _stat('expected', pct(r.expectedStrike)),
              _stat('placed', pct(r.n == 0 ? 0 : r.places / r.n)),
              _stat('return at TAB', r.priced == 0 ? '-' : '${r.roi >= 0 ? '+' : ''}${(r.roi * 100).round()}%', color: r.roi >= 0 ? EdgeColors.win : EdgeColors.loss),
            ]),
          ]),
        ),
      ),
    );
  }

  static String _versus(double gain) {
    final p = (gain * 100).round();
    if (p >= 1) return '$p% better';
    if (p <= -1) return '${-p}% worse';
    return 'about the same';
  }

  Widget _backtestCard(League? league, BacktestResult b) {
    if (league == null) return const SizedBox();
    final bins = <String>[];
    for (var i = 0; i < b.said.length; i++) {
      if (b.binN[i] >= 20) bins.add('said ${(b.said[i] * 100).round()}% → won ${(b.won[i] * 100).round()}%');
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(child: Text(league.name, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
              Muted('${b.games} games'),
            ]),
            const SizedBox(height: 10),
            Row(children: [
              _stat('winners right', pct(b.accuracy), color: probColor(b.accuracy)),
              _stat('typical total miss', num1(b.totalRmse)),
              _stat('totals vs league-average guess', _versus(1 - b.totalRmse / b.baseTotalRmse)),
            ]),
            if (bins.isNotEmpty) ...[
              const SizedBox(height: 8),
              Wrap(spacing: 6, runSpacing: 4, children: [
                for (final t in bins)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.06), borderRadius: BorderRadius.circular(8)),
                    child: Text(t, style: const TextStyle(fontSize: 11.5, color: EdgeColors.muted)),
                  ),
              ]),
            ],
          ]),
        ),
      ),
    );
  }
}
