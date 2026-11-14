import 'package:flutter/material.dart';

import '../engine/race_rater.dart';
import '../models/race.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'meeting_screen.dart';

/// Full rated field for one race.
class RaceScreen extends StatelessWidget {
  const RaceScreen({super.key, required this.race, required this.ratings});
  final Race race;
  final List<RunnerRating> ratings;

  @override
  Widget build(BuildContext context) {
    final scratched = race.runners.where((r) => r.scratched).toList();
    return Scaffold(
      appBar: AppBar(title: Text('${race.meetingName} R${race.number}')),
      body: ListView(padding: const EdgeInsets.fromLTRB(12, 0, 12, 32), children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(race.name, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15)),
              const SizedBox(height: 4),
              Muted('${race.distance}m · ${race.trackCondition} · ${timeLocal(race.start)} · ${race.status}'),
              if (race.results.isNotEmpty) ...[
                const Divider(height: 20),
                Text(race.isFinal ? 'Result' : 'Result (interim)', style: const TextStyle(color: EdgeColors.win, fontWeight: FontWeight.w800)),
                const SizedBox(height: 4),
                ResultTable(race: race, places: 4, multiRace: true),
                const SizedBox(height: 4),
                const Muted('Tote dividends for a \$1 bet. Your bookmaker\'s tote can differ a little.'),
              ],
            ]),
          ),
        ),
        const SectionTitle('Model ratings'),
        for (var i = 0; i < ratings.length; i++) _runnerCard(context, i + 1, ratings[i]),
        if (scratched.isNotEmpty) ...[
          const SizedBox(height: 8),
          Muted('Scratched: ${scratched.map((r) => '${r.number} ${r.name}').join(', ')}'),
        ],
        if (race.comment != null && race.comment!.isNotEmpty) ...[
          const SectionTitle('Form comment'),
          Muted(race.comment!, size: 13),
        ],
      ]),
    );
  }

  Widget _runnerCard(BuildContext context, int rank, RunnerRating r) {
    final run = r.runner;
    final edge = r.winEdge;
    final won = race.results.isNotEmpty && race.results.first.number == run.number;
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              CircleAvatar(
                radius: 15,
                backgroundColor: rank <= 3 ? EdgeColors.lime.withValues(alpha: 0.2) : Colors.white10,
                child: Text('${run.number}', style: TextStyle(fontWeight: FontWeight.w900, color: rank <= 3 ? EdgeColors.lime : EdgeColors.text)),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    Flexible(child: Text(run.name, style: const TextStyle(fontWeight: FontWeight.w800), overflow: TextOverflow.ellipsis)),
                    if (won) const Padding(padding: EdgeInsets.only(left: 6), child: Icon(Icons.emoji_events, size: 16, color: EdgeColors.win)),
                  ]),
                  Muted('${run.barrier != null ? 'Bar ${run.barrier} · ' : ''}${run.weight != null ? '${num1(run.weight!)}kg · ' : ''}${run.jockey.isNotEmpty ? run.jockey : run.trainer}'),
                ]),
              ),
              Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                ProbPill(r.winProb, big: true),
                Muted('place ${pct(r.placeProb)}'),
              ]),
            ]),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(child: Muted('Form ${run.form.isEmpty ? '-' : run.form}  ·  Fair \$${odds(r.fairWin)}')),
              if (run.fixedWin != null)
                Text('TAB \$${odds(run.fixedWin!)}',
                    style: TextStyle(fontWeight: FontWeight.w800, color: edge != null && edge >= 0.1 ? EdgeColors.win : EdgeColors.text)),
              if (edge != null && edge >= 0.1)
                Padding(padding: const EdgeInsets.only(left: 6), child: Text('+${(edge * 100).round()}% value', style: const TextStyle(color: EdgeColors.win, fontSize: 12, fontWeight: FontWeight.w700))),
            ]),
            if (r.reasons.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Wrap(spacing: 6, runSpacing: 4, children: [
                  for (final s in r.reasons)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.06), borderRadius: BorderRadius.circular(8)),
                      child: Text(s, style: const TextStyle(fontSize: 11.5, color: EdgeColors.muted)),
                    ),
                ]),
              ),
            if (race.results.isEmpty)
              Row(mainAxisAlignment: MainAxisAlignment.end, children: [
                TextButton(onPressed: () => saveRacePick(context, race, r, place: false), child: const Text('Save win')),
                TextButton(onPressed: () => saveRacePick(context, race, r, place: true), child: const Text('Save place')),
              ]),
          ]),
        ),
      ),
    );
  }
}
