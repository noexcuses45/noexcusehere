import 'dart:async';

import 'package:flutter/material.dart';

import '../engine/quaddie_value.dart';
import '../engine/race_rater.dart';
import '../models/race.dart';
import '../services/picks_store.dart';
import '../services/prediction_log.dart';
import '../services/racing_api.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'race_screen.dart';

/// A meeting: the quaddie up top (picks, results, what it paid or will pay),
/// then every race with its result or its top-rated runners.
class MeetingScreen extends StatefulWidget {
  const MeetingScreen({super.key, required this.meeting});
  final Meeting meeting;

  @override
  State<MeetingScreen> createState() => _MeetingScreenState();
}

class _MeetingScreenState extends State<MeetingScreen> {
  final Map<String, Race> _races = {};
  final Map<String, List<RunnerRating>> _ratings = {};
  int _loaded = 0;
  int? _quaddieStart; // race number of leg 1, when the user picks other legs
  String? _error;
  Timer? _tick; // brings in results as races are run
  bool _refreshing = false;

  Meeting get m => widget.meeting;

  @override
  void initState() {
    super.initState();
    _load();
    _tick = Timer.periodic(const Duration(seconds: 45), (_) => _refresh());
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  void _store(RaceSummary rs, Race r) {
    final had = _ratings[rs.id];
    _races[rs.id] = r;
    // Once a race is run, keep the ratings made before it, so the picks shown
    // are the ones that were shown beforehand.
    if (had == null || had.isEmpty || !r.hasResults) _ratings[rs.id] = RaceRater.rate(r);
    PredictionLog.instance.recordRace(r, _ratings[rs.id]!);
  }

  Future<void> _load() async {
    setState(() {
      _loaded = 0;
      _error = null;
    });
    try {
      final futures = m.races.map((rs) async {
        try {
          _store(rs, await RacingApi.race(rs.id, finished: rs.isFinal));
        } catch (_) {}
        _loaded++;
        if (mounted) setState(() {});
      });
      await Future.wait(futures);
      PredictionLog.instance.save();
      if (_races.isEmpty) _error = 'Could not load fields for this meeting.';
    } catch (_) {
      _error = 'Could not load fields for this meeting.';
    }
    if (mounted) setState(() {});
  }

  /// Re-reads races that are due or awaiting a result. With [all], every race
  /// that is not final yet (pull to refresh).
  Future<void> _refresh({bool all = false}) async {
    if (_refreshing || _loaded < m.races.length) return;
    _refreshing = true;
    final soon = DateTime.now().add(const Duration(minutes: 5));
    var changed = false;
    try {
      await Future.wait(m.races.map((rs) async {
        final r = _races[rs.id];
        if (r != null && (r.isFinal || r.abandoned)) return;
        if (!all && r != null && rs.start.isAfter(soon)) return;
        try {
          _store(rs, await RacingApi.race(rs.id, fresh: true));
          changed = true;
        } catch (_) {}
      }));
    } finally {
      _refreshing = false;
    }
    if (changed && mounted) setState(() {});
  }

  /// The quaddie the tote is running on this meeting (race numbers), if the
  /// feed says; otherwise the last four races.
  List<int> get _officialLegs {
    for (final r in _races.values) {
      if (r.quaddieLegs.length == 4) return r.quaddieLegs;
    }
    return const [];
  }

  int get _defaultStart {
    final official = _officialLegs;
    final all = m.races;
    if (official.isNotEmpty) {
      final i = all.indexWhere((r) => r.number == official.first);
      if (i >= 0 && i + 3 < all.length) return official.first;
    }
    return all[all.length - 4].number;
  }

  List<RaceSummary> get _quaddieRaces {
    final all = m.races;
    if (all.length < 4) return all;
    final start = _quaddieStart ?? _defaultStart;
    final idx = all.indexWhere((r) => r.number == start);
    final from = idx < 0 ? all.length - 4 : idx;
    return all.sublist(from, (from + 4).clamp(0, all.length));
  }

  @override
  Widget build(BuildContext context) {
    final total = m.races.length;
    return Scaffold(
      appBar: AppBar(title: Text('${m.name} · ${m.categoryLabel}')),
      body: _error != null
          ? ErrorRetry(_error!, onRetry: _load)
          : RefreshIndicator(
              onRefresh: () => _refresh(all: true),
              child: ListView(padding: const EdgeInsets.fromLTRB(12, 0, 12, 32), children: [
                if (_loaded < total)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Column(children: [
                      LinearProgressIndicator(value: total == 0 ? null : _loaded / total, color: EdgeColors.lime, backgroundColor: Colors.white12),
                      const SizedBox(height: 4),
                      Muted('Rating fields ($_loaded/$total)...'),
                    ]),
                  ),
                if (m.races.length >= 2) _quaddieCard(),
                const SectionTitle('Races'),
                for (final rs in m.races) _raceCard(rs),
              ]),
            ),
    );
  }

  // ------------------------------------------------------------- quaddie

  Widget _quaddieCard() {
    final races = _quaddieRaces;
    final loaded = races.map((rs) => _races[rs.id]).toList();
    final plan = QuaddiePlan.build(loaded.whereType<Race>().toList(), _ratings);
    final tracker = QuaddieTracker(loaded);
    final official = _officialLegs;
    // Payouts only make sense for the quaddie the tote is actually running.
    final isOfficial = races.length == 4 && (official.isEmpty || (races.first.number == official.first && races.last.number == official.last));
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.auto_awesome, color: EdgeColors.lime, size: 20),
            const SizedBox(width: 8),
            const Expanded(child: Text('Quaddie', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
            if (m.races.length > 4)
              DropdownButton<int>(
                value: _quaddieStart ?? _defaultStart,
                underline: const SizedBox(),
                dropdownColor: EdgeColors.navyCard,
                style: const TextStyle(color: EdgeColors.text, fontSize: 13),
                items: [
                  for (var i = 0; i + 3 < m.races.length; i++)
                    DropdownMenuItem(value: m.races[i].number, child: Text('R${m.races[i].number}-R${m.races[i + 3].number}')),
                ],
                onChanged: (v) => setState(() => _quaddieStart = v),
              ),
          ]),
          const SizedBox(height: 8),
          if (plan.legs.isEmpty) const Muted('Rating the legs...'),
          for (var i = 0; i < plan.legs.length; i++) _legRow(i + 1, plan.legs[i]),
          if (plan.legs.length == races.length && plan.legs.isNotEmpty) ...[
            const Divider(height: 18),
            Row(children: [
              Expanded(
                child: Text('Our picks: ${plan.combos} combos · chance ≈ ${pct(plan.prob)}', style: const TextStyle(fontWeight: FontWeight.w700)),
              ),
              Muted('\$${odds(plan.prob > 0 ? 1 / plan.prob : 999)} fair'),
            ]),
            const SizedBox(height: 4),
            const Muted('Up to 3 picks per leg, covering about half the model\'s win chance; standouts are taken alone.'),
            if (isOfficial && tracker.complete) ..._quaddieStatus(plan, tracker),
            if (!isOfficial) const Padding(padding: EdgeInsets.only(top: 8), child: Muted('Payouts show for the quaddie the TAB is running. Pick those legs above to see them.')),
          ],
        ]),
      ),
    );
  }

  /// Results so far, what the quaddie paid, or what it would pay.
  List<Widget> _quaddieStatus(QuaddiePlan plan, QuaddieTracker t) {
    if (t.abandoned) {
      return [const Padding(padding: EdgeInsets.only(top: 10), child: Muted('A leg was abandoned, so the usual quaddie rules do not apply. Check your TAB for how it is settled.'))];
    }
    final run = t.runCount;
    if (run == 0) {
      return [const Padding(padding: EdgeInsets.only(top: 10), child: Muted('As each leg is run, the result shows here, then what the quaddie will pay, then what it paid.'))];
    }
    // How our picks are going.
    final missed = <int>[];
    for (var i = 0; i < run && i < plan.legs.length; i++) {
      final w = QuaddieTracker.winnerOf(plan.legs[i].race);
      if (w != null && !plan.legs[i].picks.any((p) => p.runner.number == w.number)) missed.add(i + 1);
    }
    final out = <Widget>[const Divider(height: 22)];
    if (t.finished) {
      final paid = t.paid;
      if (paid.isEmpty) {
        out.add(const Text('All four legs are run. Waiting for the quaddie dividend.', style: TextStyle(fontWeight: FontWeight.w700)));
      }
      for (final d in paid) {
        out.add(Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
          const Padding(padding: EdgeInsets.only(bottom: 3), child: Text('Quaddie paid  ', style: TextStyle(fontWeight: FontWeight.w700))),
          Text(payout(d.amount), style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w900, color: EdgeColors.win)),
        ]));
        out.add(Muted('for a \$1 bet · winners ${d.numbers.join(', ')}${d.isFinal ? '' : ' · interim, not final yet'}'
            '${d.pool > 0 ? ' · pool ${roughly(d.pool)}' : ''}'));
      }
      out.add(const SizedBox(height: 6));
      if (missed.isEmpty) {
        out.add(Text(
          paid.isEmpty
              ? 'Our picks had all four winners.'
              : 'Our picks had all four winners: ${plan.combos} combo${plan.combos == 1 ? '' : 's'} at \$1 each cost \$${plan.combos} and returned ${payout(paid.first.amount)}.',
          style: const TextStyle(color: EdgeColors.win, fontWeight: FontWeight.w700, fontSize: 13),
        ));
      } else {
        out.add(Text('Our picks got ${4 - missed.length} of 4 legs (missed leg ${missed.join(' and ')}).', style: const TextStyle(color: EdgeColors.muted, fontSize: 13)));
      }
      return out;
    }
    // Still running.
    final next = t.nextLeg!;
    out.add(Text('$run of 4 legs run. Leg ${run + 1} is R${next.number}${next.hasResults ? '' : ' at ${clockLocal(next.start)}'}.${t.pool > 0 ? ' Pool ${roughly(t.pool)}.' : ''}',
        style: const TextStyle(fontWeight: FontWeight.w700)));
    out.add(Text(
      missed.isEmpty ? 'Our picks are still alive.' : 'Our picks are out (missed leg ${missed.join(' and ')}).',
      style: TextStyle(color: missed.isEmpty ? EdgeColors.win : EdgeColors.muted, fontWeight: FontWeight.w700, fontSize: 13),
    ));
    final pays = t.willPays();
    if (pays.isEmpty) {
      out.add(const Padding(padding: EdgeInsets.only(top: 6), child: Muted('Waiting for prices to work out what it will pay.')));
      return out;
    }
    final ours = run < plan.legs.length ? plan.legs[run].picks.map((p) => p.runner.number).toSet() : <int>{};
    final plus = t.assumesFavourites ? '+' : '';
    out.add(const SizedBox(height: 10));
    out.add(Text('If it wins R${next.number}, a \$1 quaddie pays about', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 13.5)));
    out.add(const SizedBox(height: 4));
    for (final w in pays) {
      final mine = ours.contains(w.runner.number);
      out.add(Padding(
        padding: const EdgeInsets.symmetric(vertical: 2.5),
        child: Row(children: [
          SizedBox(width: 26, child: Text('${w.runner.number}', style: const TextStyle(fontWeight: FontWeight.w800))),
          Expanded(
            child: Text('${w.runner.name}${mine ? '  ★' : ''}', overflow: TextOverflow.ellipsis, style: TextStyle(color: mine ? EdgeColors.lime : EdgeColors.text)),
          ),
          SizedBox(width: 62, child: Muted('\$${odds(w.price)}', size: 12.5)),
          SizedBox(
            width: 84,
            child: Text('${roughly(w.value)}$plus', textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w800)),
          ),
        ]),
      ));
    }
    out.add(const SizedBox(height: 6));
    final later = [
      for (var i = run + 1; i < t.legs.length; i++)
        if (QuaddieTracker.favourite(t.legs[i]!) case final f?) 'R${t.legs[i]!.number} ${f.number} ${f.name} at \$${odds(QuaddieTracker.priceOf(f)!)}'
    ];
    out.add(Muted(
      '${ours.isEmpty ? '' : '★ our picks. '}'
      '${t.assumesFavourites ? 'The + means at least: it assumes the favourite wins after that (${later.join(', ')}). A longer-priced winner pays more. ' : ''}'
      'These are estimates from the win prices, not official will-pays: in ${QuaddieTracker.samples} past quaddies they were within double or half the real payout ${pct(t.within2)} of the time.',
    ));
    return out;
  }

  Widget _legRow(int leg, QuaddieLeg l) {
    final race = _races[l.race.id] ?? l.race;
    final result = QuaddieTracker.winnerOf(race);
    final hit = result != null && l.picks.any((p) => p.runner.number == result.number);
    final paid = result == null ? null : race.winDividend(result.number);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 60, child: Text('Leg $leg\nR${race.number}', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5))),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Wrap(spacing: 6, runSpacing: 4, children: [
              for (final p in l.picks)
                Chip(
                  visualDensity: VisualDensity.compact,
                  backgroundColor: result != null && result.number == p.runner.number ? EdgeColors.win.withValues(alpha: 0.25) : null,
                  label: Text('${p.runner.number} ${p.runner.name} ${pct(p.winProb)}', style: const TextStyle(fontSize: 12)),
                ),
            ]),
            if (result != null)
              Padding(
                padding: const EdgeInsets.only(top: 3),
                child: Row(children: [
                  Icon(hit ? Icons.check_circle : Icons.cancel, size: 15, color: hit ? EdgeColors.win : EdgeColors.loss),
                  const SizedBox(width: 5),
                  Expanded(
                    child: Text('Won by ${result.number} ${result.name}${paid == null ? '' : ' · paid ${payout(paid)}'}',
                        style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w700, color: hit ? EdgeColors.win : EdgeColors.loss)),
                  ),
                ]),
              ),
          ]),
        ),
        if (result == null) ProbPill(l.prob),
      ]),
    );
  }

  // --------------------------------------------------------------- races

  Widget _raceCard(RaceSummary rs) {
    final race = _races[rs.id];
    final rated = _ratings[rs.id] ?? [];
    final done = race?.hasResults ?? false;
    final winner = done ? race!.results.first : null;
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Card(
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: race == null ? null : () => Navigator.push(context, MaterialPageRoute(builder: (_) => RaceScreen(race: race, ratings: rated))),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Text('R${rs.number}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16, color: EdgeColors.lime)),
                const SizedBox(width: 10),
                Expanded(child: Text(rs.name, style: const TextStyle(fontWeight: FontWeight.w700), overflow: TextOverflow.ellipsis)),
                Muted(done ? (race!.isFinal ? 'Result' : 'Result (interim)') : (race?.abandoned ?? false) ? 'Abandoned' : clockLocal(rs.start)),
              ]),
              Muted('${rs.distance > 0 ? '${rs.distance}m · ' : ''}${race?.runners.where((r) => !r.scratched).length ?? '?'} runners'),
              const SizedBox(height: 6),
              if (race == null) const Muted('Loading field...'),
              if (done) ...[
                ResultTable(race: race!, places: 3),
                const SizedBox(height: 6),
                if (rated.isNotEmpty) Muted(_modelVerdict(race, rated)),
              ] else
                for (final r in rated.take(3))
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 2),
                    child: Row(children: [
                      SizedBox(width: 26, child: Text('${r.runner.number}', style: const TextStyle(fontWeight: FontWeight.w800))),
                      Expanded(child: Text(r.runner.name, overflow: TextOverflow.ellipsis)),
                      if (winner != null && winner.number == r.runner.number)
                        const Padding(padding: EdgeInsets.only(right: 6), child: Icon(Icons.emoji_events, size: 16, color: EdgeColors.win)),
                      if (r.runner.fixedWin != null) Muted('\$${odds(r.runner.fixedWin!)} '),
                      _valueTag(r),
                      const SizedBox(width: 6),
                      ProbPill(r.winProb),
                    ]),
                  ),
            ]),
          ),
        ),
      ),
    );
  }

  /// How the model's top pick went in a finished race.
  static String _modelVerdict(Race race, List<RunnerRating> rated) {
    final top = rated.first.runner;
    var pos = 0;
    for (final r in race.results) {
      if (r.number == top.number) pos = r.position;
    }
    final how = pos == 1 ? 'won' : (pos == 0 ? 'was unplaced' : 'ran ${ordinal(pos)}');
    return 'Model\'s top pick ${top.number} ${top.name} $how (it gave it ${pct(rated.first.winProb)}).';
  }

  static Widget _valueTag(RunnerRating r) {
    final e = r.winEdge;
    if (e == null || e < 0.1) return const SizedBox();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(color: EdgeColors.win.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(6)),
      child: const Text('VALUE', style: TextStyle(color: EdgeColors.win, fontSize: 10, fontWeight: FontWeight.w900)),
    );
  }
}

/// The placings of a finished race with win and place dividends, then the
/// exotic dividends. Shared by the meeting and race screens.
class ResultTable extends StatelessWidget {
  const ResultTable({super.key, required this.race, this.places = 4, this.multiRace = false});
  final Race race;
  final int places;
  final bool multiRace; // also list doubles, trebles and the quaddie

  @override
  Widget build(BuildContext context) {
    final exotics = <String>[
      for (final (product, label) in [('Quinella', 'Quinella'), ('Exacta', 'Exacta'), ('Trifecta', 'Trifecta'), ('First4', 'First 4')])
        if (race.exotic(product) case final d?) '$label ${payout(d.amount)}',
      if (multiRace)
        for (final (product, label) in [('Double', 'Double'), ('Treble', 'Treble'), ('Early Quadrella', 'Early quaddie'), ('Quadrella', 'Quaddie')])
          if (race.exotic(product) case final d?) '$label ${payout(d.amount)}',
    ];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      for (final r in race.results.where((r) => r.position <= places))
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Row(children: [
            SizedBox(
              width: 34,
              child: Text(ordinal(r.position), style: TextStyle(fontWeight: FontWeight.w800, color: r.position == 1 ? EdgeColors.win : EdgeColors.muted)),
            ),
            SizedBox(width: 24, child: Text('${r.number}', style: const TextStyle(fontWeight: FontWeight.w800))),
            Expanded(child: Text(r.name, overflow: TextOverflow.ellipsis, style: TextStyle(fontWeight: r.position == 1 ? FontWeight.w800 : FontWeight.w500))),
            if (race.winDividend(r.number) case final w?)
              Padding(padding: const EdgeInsets.only(left: 6), child: Text('Win ${payout(w)}', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 12.5, color: EdgeColors.win))),
            if (race.placeDividend(r.number) case final p?)
              Padding(padding: const EdgeInsets.only(left: 8), child: Text('Place ${payout(p)}', style: const TextStyle(fontSize: 12.5, color: EdgeColors.muted))),
          ]),
        ),
      if (exotics.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 4), child: Muted(exotics.join(' · '))),
    ]);
  }
}

/// Saves a racing pick (used by the race screen).
Future<void> saveRacePick(BuildContext context, Race race, RunnerRating r, {required bool place}) async {
  await PicksStore.instance.add(Pick(
    id: '${DateTime.now().microsecondsSinceEpoch}',
    kind: 'race',
    createdAt: DateTime.now(),
    title: '${r.runner.number} ${r.runner.name} ${place ? 'place' : 'win'}',
    detail: '${race.meetingName} R${race.number} · ${clockLocal(race.start)}',
    prob: place ? r.placeProb : r.winProb,
    raceId: race.id,
    runnerNumber: r.runner.number,
    runnerName: r.runner.name,
    place: place,
    bookOdds: place ? r.runner.fixedPlace : r.runner.fixedWin,
  ));
  if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Saved to My picks')));
}
