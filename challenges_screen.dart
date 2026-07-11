import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../theme.dart';

const int weeklyStepChallenge = 70000;

class ChallengesScreen extends StatefulWidget {
  const ChallengesScreen({super.key});

  @override
  State<ChallengesScreen> createState() => _ChallengesScreenState();
}

class _ChallengesScreenState extends State<ChallengesScreen> {
  int _weekSteps = 0;
  int _totalSets = 0;
  int _totalSteps = 0;
  int _streak = 0;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final client = Supabase.instance.client;
    final userId = client.auth.currentUser!.id;
    try {
      final today = DateTime.now();
      final monday = DateTime(today.year, today.month, today.day)
          .subtract(Duration(days: today.weekday - 1));

      final stepRows = await client
          .from('nx_daily_steps')
          .select('day, steps')
          .eq('user_id', userId)
          .order('day', ascending: false);

      int weekSteps = 0;
      int totalSteps = 0;
      final daysWithSteps = <String>{};
      for (final row in stepRows) {
        final steps = row['steps'] as int;
        totalSteps += steps;
        if (steps > 0) daysWithSteps.add(row['day'] as String);
        final day = DateTime.parse(row['day'] as String);
        if (!day.isBefore(monday)) weekSteps += steps;
      }

      int streak = 0;
      var cursor = today;
      final todayKey = DateFormat('yyyy-MM-dd').format(today);
      if (!daysWithSteps.contains(todayKey)) {
        cursor = cursor.subtract(const Duration(days: 1));
      }
      while (daysWithSteps.contains(DateFormat('yyyy-MM-dd').format(cursor))) {
        streak++;
        cursor = cursor.subtract(const Duration(days: 1));
      }

      final sets = await client
          .from('nx_workout_sets')
          .select('id')
          .eq('user_id', userId);

      if (mounted) {
        setState(() {
          _weekSteps = weekSteps;
          _totalSteps = totalSteps;
          _totalSets = (sets as List).length;
          _streak = streak;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final fmt = NumberFormat.decimalPattern();
    final progress = (_weekSteps / weeklyStepChallenge).clamp(0.0, 1.0);
    final badges = [
      _Badge(Icons.local_fire_department, NxColors.coral, '10-day streak',
          _streak >= 10),
      _Badge(Icons.fitness_center, NxColors.teal, '50 sets logged',
          _totalSets >= 50),
      _Badge(Icons.directions_walk, const Color(0xFF534AB7), '1M steps',
          _totalSteps >= 1000000),
      _Badge(Icons.bolt, const Color(0xFFBA7517), 'First workout',
          _totalSets >= 1),
      _Badge(Icons.calendar_month, const Color(0xFF993556), '30-day streak',
          _streak >= 30),
      _Badge(Icons.emoji_events, const Color(0xFF185FA5), 'Weekly 70k',
          _weekSteps >= weeklyStepChallenge),
    ];

    return Scaffold(
      appBar: AppBar(
          title: const Text('Challenges',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600))),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _load,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: NxColors.coral,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('ENDS SUNDAY',
                            style: TextStyle(
                                color: NxColors.coralLight,
                                fontSize: 11,
                                letterSpacing: 1)),
                        const SizedBox(height: 4),
                        Text('${fmt.format(weeklyStepChallenge)} steps in 7 days',
                            style: const TextStyle(
                                color: Colors.white,
                                fontSize: 16,
                                fontWeight: FontWeight.w600)),
                        const SizedBox(height: 12),
                        ClipRRect(
                          borderRadius: BorderRadius.circular(6),
                          child: LinearProgressIndicator(
                            value: progress,
                            minHeight: 8,
                            backgroundColor: NxColors.coralDark,
                            color: Colors.white,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                            '${fmt.format(_weekSteps)} / ${fmt.format(weeklyStepChallenge)} - ${(progress * 100).round()}%',
                            style: const TextStyle(
                                color: Colors.white, fontSize: 12)),
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),
                  Text('Badges',
                      style:
                          TextStyle(fontSize: 13, color: Colors.grey.shade600)),
                  const SizedBox(height: 8),
                  GridView.count(
                    crossAxisCount: 3,
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    mainAxisSpacing: 8,
                    crossAxisSpacing: 8,
                    childAspectRatio: 0.95,
                    children: badges
                        .map((b) => Card(
                              child: Padding(
                                padding: const EdgeInsets.all(8),
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    Icon(b.icon,
                                        size: 26,
                                        color: b.earned
                                            ? b.color
                                            : Colors.grey.shade300),
                                    const SizedBox(height: 6),
                                    Text(b.label,
                                        textAlign: TextAlign.center,
                                        style: TextStyle(
                                            fontSize: 11,
                                            color: b.earned
                                                ? NxColors.ink
                                                : Colors.grey.shade400)),
                                  ],
                                ),
                              ),
                            ))
                        .toList(),
                  ),
                ],
              ),
            ),
    );
  }
}

class _Badge {
  final IconData icon;
  final Color color;
  final String label;
  final bool earned;
  _Badge(this.icon, this.color, this.label, this.earned);
}
