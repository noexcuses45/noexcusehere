import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../theme.dart';

/// Full-screen searchable exercise list. Tap an exercise to pick it;
/// tap the ? button to learn how to do it.
class ExercisePickerScreen extends StatefulWidget {
  const ExercisePickerScreen({super.key});

  @override
  State<ExercisePickerScreen> createState() => _ExercisePickerScreenState();
}

class _ExercisePickerScreenState extends State<ExercisePickerScreen> {
  List<Map<String, dynamic>> _all = [];
  String _query = '';
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final rows = await Supabase.instance.client
          .from('nx_exercises')
          .select('id, name, muscle_group, how_to')
          .order('name');
      if (mounted) {
        setState(() {
          _all = List<Map<String, dynamic>>.from(rows);
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _showHowTo(Map<String, dynamic> exercise) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
      builder: (ctx) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.school_outlined, color: NxColors.teal),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(exercise['name'] as String,
                      style: const TextStyle(
                          fontSize: 18, fontWeight: FontWeight.w700)),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(exercise['muscle_group'] as String,
                style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
            const SizedBox(height: 12),
            Text(
              (exercise['how_to'] as String?) ??
                  'Instructions coming soon for this one.',
              style: const TextStyle(fontSize: 15, height: 1.5),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final q = _query.trim().toLowerCase();
    final visible = q.isEmpty
        ? _all
        : _all
            .where((e) =>
                (e['name'] as String).toLowerCase().contains(q) ||
                (e['muscle_group'] as String).toLowerCase().contains(q))
            .toList();

    return Scaffold(
      appBar: AppBar(
          title: const Text('Choose exercise',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600))),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: TextField(
              autofocus: false,
              onChanged: (v) => setState(() => _query = v),
              decoration: InputDecoration(
                hintText: 'Search exercises...',
                prefixIcon: const Icon(Icons.search),
                filled: true,
                fillColor: Colors.white,
                border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: BorderSide.none),
                contentPadding: const EdgeInsets.symmetric(vertical: 12),
              ),
            ),
          ),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : ListView.separated(
                    itemCount: visible.length,
                    separatorBuilder: (_, __) =>
                        Divider(height: 1, color: Colors.grey.shade200),
                    itemBuilder: (context, i) {
                      final e = visible[i];
                      return ListTile(
                        title: Text(e['name'] as String),
                        subtitle: Text(e['muscle_group'] as String,
                            style: TextStyle(
                                fontSize: 12, color: Colors.grey.shade600)),
                        trailing: IconButton(
                          icon: const Icon(Icons.help_outline,
                              color: NxColors.teal),
                          tooltip: 'How to do this exercise',
                          onPressed: () => _showHowTo(e),
                        ),
                        onTap: () => Navigator.pop(context, e),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
