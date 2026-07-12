import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../quotes.dart';
import '../services/steps_service.dart';
import '../theme.dart';

const int dailyStepGoal = 10000;

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _steps = StepsService();
  String _name = '';
  int _todaySteps = 0;
  int _streak = 0;
  int _workoutsThisWeek = 0;
  int? _restingHr;
  double? _hrvMs;
  int? _sleepMinutes;
  bool _loading = true;
  bool _syncing = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final client = Supabase.instance.client;
    final userId = client.auth.currentUser!.id;
    try {
      final profile = await client
          .from('nx_profiles')
          .select('display_name')
          .eq('id', userId)
          .maybeSingle();

      final since = DateTime.now().subtract(const Duration(days: 60));
      final stepRows = await client
          .from('nx_daily_steps')
          .select('day, steps')
          .eq('user_id', userId)
          .gte('day', since.toIso8601String().substring(0, 10))
          .order('day', ascending: false);

      final today = DateTime.now();
      final todayKey = DateFormat('yyyy-MM-dd').format(today);
      int todaySteps = 0;
      final daysWithSteps = <String>{};
      for (final row in stepRows) {
        if ((row['steps'] as int) > 0) daysWithSteps.add(row['day'] as String);
        if (row['day'] == todayKey) todaySteps = row['steps'] as int;
      }
      int streak = 0;
      var cursor = today;
      if (!daysWithSteps.contains(todayKey)) {
        cursor = cursor.subtract(const Duration(days: 1));
      }
      while (daysWithSteps.contains(DateFormat('yyyy-MM-dd').format(cursor))) {
        streak++;
        cursor = cursor.subtract(const Duration(days: 1));
      }

      final health = await client
          .from('nx_daily_health')
          .select('resting_hr, hrv_ms, sleep_minutes')
          .eq('user_id', userId)
          .eq('day', todayKey)
          .maybeSingle();

      final monday = today.subtract(Duration(days: today.weekday - 1));
      final diaryEntries = await client
          .from('nx_diary_entries')
          .select('day_date')
          .eq('user_id', userId)
          .gte('day_date', DateFormat('yyyy-MM-dd').format(monday));
      final workoutDays = <String>{};
      for (final row in diaryEntries) {
        workoutDays.add(row['day_date'] as String);
      }

      if (mounted) {
        setState(() {
          _name = profile?['display_name'] ?? '';
          _todaySteps = todaySteps;
          _streak = streak;
          _workoutsThisWeek = workoutDays.length;
          _restingHr = health?['resting_hr'] as int?;
          _hrvMs = health?['hrv_ms'] == null
              ? null
              : double.tryParse(health!['hrv_ms'].toString());
          _sleepMinutes = health?['sleep_minutes'] as int?;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _syncFromWatch() async {
    setState(() => _syncing = true);
    final summary = await _steps.syncTodayFromWatch();
    if (mounted) {
      setState(() => _syncing = false);
      if (summary == null) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text(
                "Couldn't read your watch data. Install Health Connect and allow access, or add steps manually.")));
      } else {
        _load();
      }
    }
  }

  String get _sleepLabel {
    final m = _sleepMinutes;
    if (m == null) return '--';
    return '${m ~/ 60}h ${m % 60}m';
  }

  Future<void> _addStepsManually() async {
    final controller = TextEditingController();
    final result = await showDialog<int>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text("Today's steps"),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          autofocus: true,
          decoration: const InputDecoration(hintText: 'e.g. 8500'),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () =>
                Navigator.pop(ctx, int.tryParse(controller.text.trim())),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (result != null && result >= 0) {
      await _steps.saveSteps(result);
      _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final progress = (_todaySteps / dailyStepGoal).clamp(0.0, 1.0);
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(DateFormat('EEEE d MMMM').format(DateTime.now()),
                style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
            Text("G'day, $_name",
                style:
                    const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Sign out',
            onPressed: () => Supabase.instance.client.auth.signOut(),
          ),
        ],
      ),
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
                      color: NxColors.teal,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text("TODAY'S MOTIVATION",
                            style: TextStyle(
                                color: NxColors.tealLight,
                                fontSize: 11,
                                letterSpacing: 1)),
                        const SizedBox(height: 6),
                        Text('"${quoteOfTheDay()}"',
                            style: const TextStyle(
                                color: Colors.white,
                                fontSize: 16,
                                fontWeight: FontWeight.w600,
                                height: 1.4)),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Row(
                        children: [
                          SizedBox(
                            width: 90,
                            height: 90,
                            child: Stack(
                              fit: StackFit.expand,
                              children: [
                                CircularProgressIndicator(
                                  value: progress,
                                  strokeWidth: 8,
                                  backgroundColor: Colors.grey.shade200,
                                  color: NxColors.teal,
                                ),
                                Center(
                                  child: Column(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    children: [
                                      Text(
                                          NumberFormat.decimalPattern()
                                              .format(_todaySteps),
                                          style: const TextStyle(
                                              fontWeight: FontWeight.w700)),
                                      Text(
                                          '/ ${NumberFormat.decimalPattern().format(dailyStepGoal)}',
                                          style: TextStyle(
                                              fontSize: 10,
                                              color: Colors.grey.shade600)),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Text('Steps today',
                                    style:
                                        TextStyle(fontWeight: FontWeight.w600)),
                                Text('${(progress * 100).round()}% of daily goal',
                                    style: TextStyle(
                                        fontSize: 12,
                                        color: Colors.grey.shade600)),
                                const SizedBox(height: 8),
                                Row(
                                  children: [
                                    Expanded(
                                      child: OutlinedButton.icon(
                                        onPressed:
                                            _syncing ? null : _syncFromWatch,
                                        icon: _syncing
                                            ? const SizedBox(
                                                width: 14,
                                                height: 14,
                                                child:
                                                    CircularProgressIndicator(
                                                        strokeWidth: 2))
                                            : const Icon(Icons.watch, size: 16),
                                        label: const Text('Sync watch',
                                            style: TextStyle(fontSize: 12)),
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 4),
                                TextButton(
                                  onPressed: _addStepsManually,
                                  child: const Text('Add steps manually',
                                      style: TextStyle(fontSize: 12)),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(
                        child: _statCard(Icons.local_fire_department,
                            NxColors.coral, 'Streak', '$_streak days'),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: _statCard(Icons.fitness_center, NxColors.teal,
                            'Workouts', '$_workoutsThisWeek this week'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: _statCard(Icons.favorite, NxColors.coral,
                            'Resting HR',
                            _restingHr == null ? '--' : '$_restingHr bpm'),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: _statCard(Icons.bedtime, const Color(0xFF534AB7),
                            'Sleep', _sleepLabel),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: _statCard(Icons.monitor_heart, NxColors.teal,
                            'HRV',
                            _hrvMs == null ? '--' : '${_hrvMs!.round()} ms'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    "Heart rate, sleep and HRV come from your watch via Health Connect. Watches that don't track a metric show --.",
                    style:
                        TextStyle(fontSize: 11, color: Colors.grey.shade500),
                  ),
                ],
              ),
            ),
    );
  }

  Widget _statCard(IconData icon, Color color, String label, String value) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Icon(icon, size: 16, color: color),
              const SizedBox(width: 6),
              Text(label,
                  style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
            ]),
            const SizedBox(height: 4),
            Text(value,
                style:
                    const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
          ],
        ),
      ),
    );
  }
}
