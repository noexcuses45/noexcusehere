import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../theme.dart';
import 'exercise_picker_screen.dart';

class DiaryScreen extends StatefulWidget {
  const DiaryScreen({super.key});

  @override
  State<DiaryScreen> createState() => _DiaryScreenState();
}

class _DiaryScreenState extends State<DiaryScreen> {
  List<Map<String, dynamic>> _days = [];
  List<Map<String, dynamic>> _entries = [];
  final Map<int, double> _pb = {};
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
      final days = await client
          .from('nx_diary_days')
          .select('id, day_date')
          .eq('user_id', userId)
          .order('day_date', ascending: true)
          .order('id', ascending: true);
      final entries = await client
          .from('nx_diary_entries')
          .select(
              'id, day_id, exercise_id, day_date, time_min, distance_km, sets, reps, weight_kg, nx_exercises(name)')
          .eq('user_id', userId)
          .order('created_at', ascending: true);

      _pb.clear();
      for (final e in entries) {
        final w = e['weight_kg'];
        if (w != null) {
          final wd = double.tryParse(w.toString()) ?? 0;
          final ex = e['exercise_id'] as int;
          if (wd > (_pb[ex] ?? 0)) _pb[ex] = wd;
        }
      }

      if (mounted) {
        setState(() {
          _days = List<Map<String, dynamic>>.from(days);
          _entries = List<Map<String, dynamic>>.from(entries);
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  bool _isPb(Map<String, dynamic> e) {
    final w = e['weight_kg'];
    if (w == null) return false;
    final wd = double.tryParse(w.toString()) ?? 0;
    return wd > 0 && wd == (_pb[e['exercise_id']] ?? -1);
  }

  String _fmtNum(dynamic v) {
    final d = double.tryParse(v.toString()) ?? 0;
    return d == d.roundToDouble() ? d.round().toString() : d.toString();
  }

  String _entryDetails(Map<String, dynamic> e) {
    final parts = <String>[];
    if (e['reps'] != null) {
      parts.add('${e['reps']} reps');
    }
    if (e['weight_kg'] != null) parts.add('${_fmtNum(e['weight_kg'])} kg');
    if (e['time_min'] != null) parts.add('${_fmtNum(e['time_min'])} min');
    if (e['distance_km'] != null) {
      parts.add('${_fmtNum(e['distance_km'])} km');
    }
    return parts.isEmpty ? 'Logged' : parts.join(' | ');
  }

  Future<void> _addDay() async {
    final client = Supabase.instance.client;
    await client.from('nx_diary_days').insert({
      'user_id': client.auth.currentUser!.id,
      'day_date': DateFormat('yyyy-MM-dd').format(DateTime.now()),
    });
    _load();
  }

  Future<void> _changeDate(Map<String, dynamic> day) async {
    final current = DateTime.parse(day['day_date'] as String);
    final picked = await showDatePicker(
      context: context,
      initialDate: current,
      firstDate: DateTime(2000),
      lastDate: DateTime.now().add(const Duration(days: 366)),
    );
    if (picked == null) return;
    final key = DateFormat('yyyy-MM-dd').format(picked);
    final client = Supabase.instance.client;
    await client
        .from('nx_diary_days')
        .update({'day_date': key}).eq('id', day['id'] as int);
    await client
        .from('nx_diary_entries')
        .update({'day_date': key}).eq('day_id', day['id'] as int);
    _load();
  }

  Future<void> _deleteDay(Map<String, dynamic> day, int dayNumber) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Delete Day $dayNumber?'),
        content: const Text(
            'This removes the day and every exercise logged on it.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Delete')),
        ],
      ),
    );
    if (confirm == true) {
      await Supabase.instance.client
          .from('nx_diary_days')
          .delete()
          .eq('id', day['id'] as int);
      _load();
    }
  }

  Future<void> _deleteEntry(int id) async {
    await Supabase.instance.client
        .from('nx_diary_entries')
        .delete()
        .eq('id', id);
    _load();
  }

  Future<Map<String, dynamic>?> _entryDetailsDialog(String exerciseName,
      {Map<String, dynamic>? prefill}) {
    String pre(String key) =>
        prefill?[key] == null ? '' : _fmtNum(prefill![key]);
    final reps = TextEditingController(text: pre('reps'));
    final weight = TextEditingController(text: pre('weight_kg'));
    final time = TextEditingController(text: pre('time_min'));
    final distance = TextEditingController(text: pre('distance_km'));

    Widget field(TextEditingController c, String label) => Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: TextField(
            controller: c,
            keyboardType:
                const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(
              labelText: label,
              isDense: true,
              border: const OutlineInputBorder(),
            ),
          ),
        );

    return showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(exerciseName,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Padding(
                padding: EdgeInsets.only(bottom: 12),
                child: Text('Fill in what applies - leave the rest blank.',
                    style: TextStyle(fontSize: 12, color: Colors.grey)),
              ),
              field(reps, 'Reps'),
              field(weight, 'Weight (kg)'),
              field(time, 'Time (minutes)'),
              field(distance, 'Distance (km)'),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () {
              final result = <String, dynamic>{};
              final r = int.tryParse(reps.text.trim());
              final w = double.tryParse(weight.text.trim());
              final t = double.tryParse(time.text.trim());
              final d = double.tryParse(distance.text.trim());
              if (r != null) result['reps'] = r;
              if (w != null) result['weight_kg'] = w;
              if (t != null) result['time_min'] = t;
              if (d != null) result['distance_km'] = d;
              Navigator.pop(ctx, result);
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  }

  Future<void> _addExercise(Map<String, dynamic> day) async {
    final exercise = await Navigator.push<Map<String, dynamic>>(
      context,
      MaterialPageRoute(builder: (_) => const ExercisePickerScreen()),
    );
    if (exercise == null || !mounted) return;
    final details = await _entryDetailsDialog(exercise['name'] as String);
    if (details == null) return;
    final client = Supabase.instance.client;
    await client.from('nx_diary_entries').insert({
      'day_id': day['id'],
      'user_id': client.auth.currentUser!.id,
      'exercise_id': exercise['id'],
      'day_date': day['day_date'],
      ...details,
    });
    _load();
  }

  Future<void> _addSet(Map<String, dynamic> entry) async {
    final name = (entry['nx_exercises'] as Map?)?['name'] as String? ?? 'Exercise';
    final details = await _entryDetailsDialog(name, prefill: entry);
    if (details == null) return;
    final client = Supabase.instance.client;
    await client.from('nx_diary_entries').insert({
      'day_id': entry['day_id'],
      'user_id': client.auth.currentUser!.id,
      'exercise_id': entry['exercise_id'],
      'day_date': entry['day_date'],
      ...details,
    });
    _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Exercise diary',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
        actions: [
          IconButton(
            icon: const Icon(Icons.add_circle, color: NxColors.teal, size: 28),
            tooltip: 'Add day',
            onPressed: _addDay,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _days.isEmpty
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(32),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.menu_book,
                            size: 48, color: NxColors.teal),
                        const SizedBox(height: 12),
                        const Text('Your diary is empty',
                            style: TextStyle(
                                fontSize: 18, fontWeight: FontWeight.w700)),
                        const SizedBox(height: 6),
                        Text('Start Day 1 and log your first exercise.',
                            style: TextStyle(color: Colors.grey.shade600)),
                        const SizedBox(height: 16),
                        FilledButton.icon(
                          onPressed: _addDay,
                          icon: const Icon(Icons.add),
                          label: const Text('Start Day 1'),
                        ),
                      ],
                    ),
                  ),
                )
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.builder(
                    padding: const EdgeInsets.all(16),
                    itemCount: _days.length,
                    itemBuilder: (context, i) {
                      final ascIndex = _days.length - 1 - i;
                      final day = _days[ascIndex];
                      final dayNumber = ascIndex + 1;
                      final dayEntries = _entries
                          .where((e) => e['day_id'] == day['id'])
                          .toList();
                      final date = DateTime.parse(day['day_date'] as String);
                      return Card(
                        margin: const EdgeInsets.only(bottom: 14),
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(14, 12, 8, 6),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  Text('Day $dayNumber',
                                      style: const TextStyle(
                                          fontSize: 16,
                                          fontWeight: FontWeight.w700)),
                                  const SizedBox(width: 10),
                                  InkWell(
                                    onTap: () => _changeDate(day),
                                    borderRadius: BorderRadius.circular(16),
                                    child: Container(
                                      padding: const EdgeInsets.symmetric(
                                          horizontal: 10, vertical: 4),
                                      decoration: BoxDecoration(
                                        color: NxColors.tealLight
                                            .withOpacity(0.4),
                                        borderRadius:
                                            BorderRadius.circular(16),
                                      ),
                                      child: Row(
                                        mainAxisSize: MainAxisSize.min,
                                        children: [
                                          const Icon(Icons.calendar_today,
                                              size: 12,
                                              color: NxColors.tealDark),
                                          const SizedBox(width: 4),
                                          Text(
                                              DateFormat('EEE d MMM yyyy')
                                                  .format(date),
                                              style: const TextStyle(
                                                  fontSize: 12,
                                                  color: NxColors.tealDark,
                                                  fontWeight:
                                                      FontWeight.w600)),
                                        ],
                                      ),
                                    ),
                                  ),
                                  const Spacer(),
                                  IconButton(
                                    icon: Icon(Icons.delete_outline,
                                        size: 20,
                                        color: Colors.grey.shade500),
                                    tooltip: 'Delete day',
                                    onPressed: () =>
                                        _deleteDay(day, dayNumber),
                                  ),
                                ],
                              ),
                              if (dayEntries.isEmpty)
                                Padding(
                                  padding:
                                      const EdgeInsets.symmetric(vertical: 8),
                                  child: Text('No exercises yet.',
                                      style: TextStyle(
                                          fontSize: 13,
                                          color: Colors.grey.shade500)),
                                ),
                              ...dayEntries.map((e) {
                                final pb = _isPb(e);
                                return Container(
                                  margin: const EdgeInsets.only(
                                      bottom: 8, right: 6),
                                  padding: const EdgeInsets.symmetric(
                                      horizontal: 12, vertical: 8),
                                  decoration: BoxDecoration(
                                    color: pb
                                        ? NxColors.tealLight.withOpacity(0.25)
                                        : NxColors.surface,
                                    borderRadius: BorderRadius.circular(10),
                                    border: pb
                                        ? Border.all(
                                            color: NxColors.teal, width: 1.5)
                                        : null,
                                  ),
                                  child: Row(
                                    children: [
                                      Expanded(
                                        child: Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.start,
                                          children: [
                                            Row(
                                              children: [
                                                Flexible(
                                                  child: Text(
                                                      (e['nx_exercises']
                                                                  as Map?)?[
                                                              'name'] ??
                                                          'Exercise',
                                                      style: const TextStyle(
                                                          fontWeight:
                                                              FontWeight
                                                                  .w600)),
                                                ),
                                                if (pb) ...[
                                                  const SizedBox(width: 6),
                                                  Container(
                                                    padding: const EdgeInsets
                                                        .symmetric(
                                                        horizontal: 6,
                                                        vertical: 2),
                                                    decoration: BoxDecoration(
                                                      color: NxColors.teal,
                                                      borderRadius:
                                                          BorderRadius
                                                              .circular(8),
                                                    ),
                                                    child: const Text('PB',
                                                        style: TextStyle(
                                                            fontSize: 10,
                                                            color:
                                                                Colors.white,
                                                            fontWeight:
                                                                FontWeight
                                                                    .w700)),
                                                  ),
                                                ],
                                              ],
                                            ),
                                            Text(_entryDetails(e),
                                                style: TextStyle(
                                                    fontSize: 12,
                                                    color: Colors
                                                        .grey.shade600)),
                                          ],
                                        ),
                                      ),
                                      TextButton.icon(
                                        onPressed: () => _addSet(e),
                                        icon: const Icon(Icons.add, size: 14),
                                        label: const Text('Add set',
                                            style: TextStyle(fontSize: 12)),
                                        style: TextButton.styleFrom(
                                            padding:
                                                const EdgeInsets.symmetric(
                                                    horizontal: 6),
                                            minimumSize: const Size(0, 30)),
                                      ),
                                      IconButton(
                                        icon: Icon(Icons.close,
                                            size: 16,
                                            color: Colors.grey.shade400),
                                        onPressed: () =>
                                            _deleteEntry(e['id'] as int),
                                      ),
                                    ],
                                  ),
                                );
                              }),
                              Align(
                                alignment: Alignment.centerLeft,
                                child: TextButton.icon(
                                  onPressed: () => _addExercise(day),
                                  icon: const Icon(Icons.add, size: 18),
                                  label: const Text('Add exercise'),
                                ),
                              ),
                            ],
                          ),
                        ),
                      );
                    },
                  ),
                ),
    );
  }
}
