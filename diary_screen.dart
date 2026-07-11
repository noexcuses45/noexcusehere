import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../theme.dart';

class DiaryScreen extends StatefulWidget {
  const DiaryScreen({super.key});

  @override
  State<DiaryScreen> createState() => _DiaryScreenState();
}

class _DiaryScreenState extends State<DiaryScreen> {
  List<Map<String, dynamic>> _exercises = [];
  List<Map<String, dynamic>> _todaySets = [];
  String _group = 'All';
  int? _selectedId;
  int _reps = 10;
  double _kg = 40;
  bool _loading = true;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final client = Supabase.instance.client;
    final userId = client.auth.currentUser!.id;
    try {
      final exercises = await client
          .from('nx_exercises')
          .select('id, name, muscle_group')
          .order('muscle_group')
          .order('name');
      final today = DateTime.now();
      final midnight = DateTime(today.year, today.month, today.day);
      final sets = await client
          .from('nx_workout_sets')
          .select('id, reps, weight_kg, performed_at, nx_exercises(name)')
          .eq('user_id', userId)
          .gte('performed_at', midnight.toIso8601String())
          .order('performed_at', ascending: false);
      if (mounted) {
        setState(() {
          _exercises = List<Map<String, dynamic>>.from(exercises);
          _todaySets = List<Map<String, dynamic>>.from(sets);
          _selectedId ??=
              _exercises.isNotEmpty ? _exercises.first['id'] as int : null;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _logSet() async {
    if (_selectedId == null || _saving) return;
    setState(() => _saving = true);
    final client = Supabase.instance.client;
    try {
      await client.from('nx_workout_sets').insert({
        'user_id': client.auth.currentUser!.id,
        'exercise_id': _selectedId,
        'reps': _reps,
        'weight_kg': _kg,
      });
      await _load();
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Set logged')));
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text("Couldn't save. Check connection.")));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _deleteSet(int id) async {
    await Supabase.instance.client.from('nx_workout_sets').delete().eq('id', id);
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final groups = ['All', ..._exercises.map((e) => e['muscle_group'] as String).toSet()];
    final visible = _group == 'All'
        ? _exercises
        : _exercises.where((e) => e['muscle_group'] == _group).toList();

    return Scaffold(
      appBar: AppBar(
          title: const Text('Exercise diary',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600))),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                SizedBox(
                  height: 36,
                  child: ListView(
                    scrollDirection: Axis.horizontal,
                    children: groups
                        .map((g) => Padding(
                              padding: const EdgeInsets.only(right: 8),
                              child: ChoiceChip(
                                label: Text(g),
                                selected: _group == g,
                                selectedColor: NxColors.tealLight,
                                onSelected: (_) => setState(() => _group = g),
                              ),
                            ))
                        .toList(),
                  ),
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: visible.map((e) {
                    final selected = e['id'] == _selectedId;
                    return ChoiceChip(
                      label: Text(e['name'] as String),
                      selected: selected,
                      selectedColor: NxColors.teal,
                      labelStyle: TextStyle(
                          color: selected ? Colors.white : NxColors.ink),
                      onSelected: (_) =>
                          setState(() => _selectedId = e['id'] as int),
                    );
                  }).toList(),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                        child: _stepper('Reps', '$_reps',
                            () => setState(() => _reps = (_reps - 1).clamp(1, 999).toInt()),
                            () => setState(() => _reps = (_reps + 1).clamp(1, 999).toInt()))),
                    const SizedBox(width: 12),
                    Expanded(
                        child: _stepper(
                            'Weight (kg)',
                            _kg == _kg.roundToDouble()
                                ? '${_kg.round()}'
                                : _kg.toStringAsFixed(1),
                            () => setState(() => _kg = (_kg - 2.5).clamp(0, 999).toDouble()),
                            () => setState(() => _kg = (_kg + 2.5).clamp(0, 999).toDouble()))),
                  ],
                ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: _saving ? null : _logSet,
                  child: Text(_saving ? 'Saving...' : 'Log set'),
                ),
                const SizedBox(height: 20),
                Text("Today's sets",
                    style: TextStyle(fontSize: 13, color: Colors.grey.shade600)),
                const SizedBox(height: 8),
                if (_todaySets.isEmpty)
                  Text('Nothing logged yet. No excuses.',
                      style: TextStyle(color: Colors.grey.shade500)),
                ..._todaySets.map((s) => Card(
                      margin: const EdgeInsets.only(bottom: 8),
                      child: ListTile(
                        dense: true,
                        title: Text(
                            (s['nx_exercises'] as Map?)?['name'] ?? 'Exercise'),
                        subtitle: Text('${s['reps']} reps x ${s['weight_kg']} kg'),
                        trailing: IconButton(
                          icon: const Icon(Icons.delete_outline, size: 20),
                          onPressed: () => _deleteSet(s['id'] as int),
                        ),
                      ),
                    )),
              ],
            ),
    );
  }

  Widget _stepper(
      String label, String value, VoidCallback onMinus, VoidCallback onPlus) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Column(
          children: [
            Text(label,
                style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                    onPressed: onMinus, icon: const Icon(Icons.remove_circle_outline)),
                Text(value,
                    style: const TextStyle(
                        fontSize: 20, fontWeight: FontWeight.w700)),
                IconButton(
                    onPressed: onPlus, icon: const Icon(Icons.add_circle_outline)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
