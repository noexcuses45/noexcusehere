import 'package:flutter/material.dart';

import '../models/race.dart';
import '../services/racing_api.dart';
import '../services/settings_service.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'meeting_screen.dart';

/// Racing tab: meetings around the world for a day, filtered by country and code.
class RacingScreen extends StatefulWidget {
  const RacingScreen({super.key});

  @override
  State<RacingScreen> createState() => _RacingScreenState();
}

class _RacingScreenState extends State<RacingScreen> {
  int _dayOffset = 0;
  String _country = Settings.instance.racingCountry;
  String _category = 'T';
  List<Meeting> _meetings = [];
  bool _loading = true;
  String? _error;

  static const countries = ['AUS', 'NZ', 'UK', 'IRE', 'FR', 'USA', 'SAF', 'All'];

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
      final day = DateTime.now().add(Duration(days: _dayOffset));
      // The feed works on NZ dates; fetch the neighbouring day too so nothing is missed.
      final a = await RacingApi.meetings(day);
      List<Meeting> b = [];
      try {
        b = await RacingApi.meetings(day.add(const Duration(days: 1)));
      } catch (_) {}
      final seen = <String>{};
      final all = [...a, ...b].where((m) => seen.add(m.id)).toList();
      final start = DateTime(day.year, day.month, day.day);
      final end = start.add(const Duration(days: 1));
      _meetings = all.where((m) {
        if (m.races.isEmpty) return false;
        final first = m.races.first.start.toLocal();
        final last = m.races.last.start.toLocal();
        return !(last.isBefore(start) || first.isAfter(end));
      }).toList()
        ..sort((x, y) => x.races.first.start.compareTo(y.races.first.start));
    } catch (e) {
      _error = 'Could not load race meetings.';
    }
    if (mounted) setState(() => _loading = false);
  }

  @override
  Widget build(BuildContext context) {
    final shown = _meetings.where((m) {
      if (_category != 'All' && m.category != _category) return false;
      if (_country == 'All') return true;
      return m.country == _country;
    }).toList();
    return Column(children: [
      SizedBox(
        height: 48,
        child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12), children: [
          for (final c in countries)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(c),
                selected: c == _country,
                onSelected: (_) {
                  setState(() => _country = c);
                  Settings.instance.racingCountry = c;
                  Settings.instance.save();
                },
              ),
            ),
        ]),
      ),
      SizedBox(
        height: 40,
        child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12), children: [
          for (final e in const [('T', 'Horses'), ('H', 'Harness'), ('G', 'Greyhounds'), ('All', 'All codes')])
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(e.$2),
                selected: e.$1 == _category,
                visualDensity: VisualDensity.compact,
                onSelected: (_) => setState(() => _category = e.$1),
              ),
            ),
          const SizedBox(width: 12),
          for (final off in [0, 1])
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(dayLabel(DateTime.now().add(Duration(days: off)))),
                selected: off == _dayOffset,
                visualDensity: VisualDensity.compact,
                onSelected: (_) {
                  setState(() => _dayOffset = off);
                  _load();
                },
              ),
            ),
        ]),
      ),
      Expanded(
        child: _error != null
            ? ErrorRetry(_error!, onRetry: _load)
            : _loading
                ? const Center(child: CircularProgressIndicator())
                : shown.isEmpty
                    ? const Center(child: Muted('No meetings match these filters.', size: 14))
                    : RefreshIndicator(
                        onRefresh: _load,
                        child: ListView(padding: const EdgeInsets.fromLTRB(12, 4, 12, 24), children: [
                          for (final m in shown) _meetingCard(m),
                          const SizedBox(height: 8),
                          const InfoBanner('Ratings use each runner\'s form, class, jockey and trainer strike rates, barrier, weight and speed map. Prices are shown for comparison so you can spot value.'),
                        ]),
                      ),
      ),
    ]);
  }

  Widget _meetingCard(Meeting m) {
    final now = DateTime.now();
    final next = m.races.where((r) => r.start.isAfter(now) && !r.isFinal).toList();
    final done = m.races.where((r) => r.isFinal).length;
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Card(
        child: ListTile(
          onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => MeetingScreen(meeting: m))),
          leading: CircleAvatar(
            backgroundColor: EdgeColors.lime.withValues(alpha: 0.15),
            child: Text(m.category, style: const TextStyle(color: EdgeColors.lime, fontWeight: FontWeight.w900)),
          ),
          title: Text('${m.name} · ${m.country}', style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text(
            '${m.categoryLabel}${m.trackCondition.isNotEmpty ? ' · ${m.trackCondition}' : ''} · ${m.races.length} races'
            '${next.isNotEmpty ? ' · next R${next.first.number} ${clockLocal(next.first.start)}' : (done == m.races.length ? ' · finished' : '')}',
            style: const TextStyle(color: EdgeColors.muted, fontSize: 12.5),
          ),
          trailing: const Icon(Icons.chevron_right, color: EdgeColors.muted),
        ),
      ),
    );
  }
}
