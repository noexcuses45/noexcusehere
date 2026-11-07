import 'package:flutter/material.dart';

import '../engine/race_rater.dart';
import '../models/race.dart';
import '../services/picks_store.dart';
import '../services/racing_api.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'race_screen.dart';

/// A meeting: quaddie picks up top, then every race with its top-rated runners.
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
  int? _quaddieStart; // race number of leg 1
  String? _error;

  Meeting get m => widget.meeting;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loaded = 0;
      _error = null;
    });
    try {
      final futures = m.races.map((rs) async {
        try {
          final r = await RacingApi.race(rs.id, finished: rs.isFinal);
          _races[rs.id] = r;
          _ratings[rs.id] = RaceRater.rate(r);
        } catch (_) {}
        _loaded++;
        if (mounted) setState(() {});
      });
      await Future.wait(futures);
      if (_races.isEmpty) _error = 'Could not load fields for this meeting.';
    } catch (_) {
      _error = 'Could not load fields for this meeting.';
    }
    if (mounted) setState(() {});
  }

  List<RaceSummary> get _quaddieRaces {
    final all = m.races;
    if (all.length < 4) return all;
    final start = _quaddieStart ?? (all.length - 3);
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
          : ListView(padding: const EdgeInsets.fromLTRB(12, 0, 12, 32), children: [
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
    );
  }

  Widget _quaddieCard() {
    final races = _quaddieRaces;
    final plan = QuaddiePlan.build(races.map((rs) => _races[rs.id]).whereType<Race>().toList(), _ratings);
    final allFinal = races.every((r) => r.isFinal);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.auto_awesome, color: EdgeColors.lime, size: 20),
            const SizedBox(width: 8),
            const Expanded(child: Text('Quaddie picks', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
            if (m.races.length > 4)
              DropdownButton<int>(
                value: _quaddieStart ?? (m.races.length - 3),
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
                child: Text('${plan.combos} combos · chance ≈ ${pct(plan.prob)}${allFinal ? ' (finished)' : ''}',
                    style: const TextStyle(fontWeight: FontWeight.w700)),
              ),
              Muted('\$${odds(plan.prob > 0 ? 1 / plan.prob : 999)} fair'),
            ]),
            const SizedBox(height: 4),
            const Muted('Up to 3 picks per leg, covering about half the model\'s win chance; standouts are taken alone.'),
          ],
        ]),
      ),
    );
  }

  Widget _legRow(int leg, QuaddieLeg l) {
    final result = l.race.results.isNotEmpty ? l.race.results.first : null;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 60, child: Text('Leg $leg\nR${l.race.number}', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5))),
        Expanded(
          child: Wrap(spacing: 6, runSpacing: 4, children: [
            for (final p in l.picks)
              Chip(
                visualDensity: VisualDensity.compact,
                backgroundColor: result != null && result.number == p.runner.number ? EdgeColors.win.withValues(alpha: 0.25) : null,
                label: Text('${p.runner.number} ${p.runner.name} ${pct(p.winProb)}', style: const TextStyle(fontSize: 12)),
              ),
            if (result != null && !l.picks.any((p) => p.runner.number == result.number))
              Chip(
                visualDensity: VisualDensity.compact,
                backgroundColor: EdgeColors.loss.withValues(alpha: 0.2),
                label: Text('Won by ${result.number} ${result.name}', style: const TextStyle(fontSize: 12)),
              ),
          ]),
        ),
        ProbPill(l.prob),
      ]),
    );
  }

  Widget _raceCard(RaceSummary rs) {
    final race = _races[rs.id];
    final rated = _ratings[rs.id] ?? [];
    final winner = race != null && race.results.isNotEmpty ? race.results.first : null;
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
                Muted(rs.isFinal ? 'Result' : clockLocal(rs.start)),
              ]),
              Muted('${rs.distance > 0 ? '${rs.distance}m · ' : ''}${race?.runners.where((r) => !r.scratched).length ?? '?'} runners'),
              const SizedBox(height: 6),
              if (race == null) const Muted('Loading field...'),
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
              if (winner != null && !rated.take(3).any((r) => r.runner.number == winner.number))
                Muted('Winner: ${winner.number} ${winner.name}'),
            ]),
          ),
        ),
      ),
    );
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
