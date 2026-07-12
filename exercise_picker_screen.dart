import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../theme.dart';

/// Full-screen searchable exercise list. Favourites pinned to the top.
/// Tap an exercise to pick it; tap the star to favourite it; tap the
/// ? button to learn how to do it.
class ExercisePickerScreen extends StatefulWidget {
  const ExercisePickerScreen({super.key});

  @override
  State<ExercisePickerScreen> createState() => _ExercisePickerScreenState();
}

class _ExercisePickerScreenState extends State<ExercisePickerScreen> {
  List<Map<String, dynamic>> _all = [];
  final Set<int> _favs = {};
  String _query = '';
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final client = Supabase.instance.client;
    try {
      final rows = await client
          .from('nx_exercises')
          .select('id, name, muscle_group, how_to')
          .order('name');
      final favRows = await client
          .from('nx_favorite_exercises')
          .select('exercise_id')
          .eq('user_id', client.auth.currentUser!.id);
      if (mounted) {
        setState(() {
          _all = List<Map<String, dynamic>>.from(rows);
          _favs
            ..clear()
            ..addAll(favRows.map<int>((r) => r['exercise_id'] as int));
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _toggleFav(int exerciseId) async {
    final client = Supabase.instance.client;
    final userId = client.auth.currentUser!.id;
    if (_favs.contains(exerciseId)) {
      setState(() => _favs.remove(exerciseId));
      await client
          .from('nx_favorite_exercises')
          .delete()
          .eq('user_id', userId)
          .eq('exercise_id', exerciseId);
    } else {
      setState(() => _favs.add(exerciseId));
      await client.from('nx_favorite_exercises').upsert(
          {'user_id': userId, 'exercise_id': exerciseId});
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

  Widget _sectionLabel(String text) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 4),
        child: Text(text,
            style: TextStyle(
                fontSize: 12,
                letterSpacing: 1,
                fontWeight: FontWeight.w700,
                color: Colors.grey.shade600)),
      );

  Widget _tile(Map<String, dynamic> e) {
    final id = e['id'] as int;
    final isFav = _favs.contains(id);
    return ListTile(
      title: Text(e['name'] as String),
      subtitle: Text(e['muscle_group'] as String,
          style: TextStyle(fontSize: 12, color: Colors.grey.shade600)),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            icon: Icon(isFav ? Icons.star : Icons.star_border,
                color: isFav ? const Color(0xFFEF9F27) : Colors.grey.shade400),
            tooltip: isFav ? 'Remove favourite' : 'Add to favourites',
            onPressed: () => _toggleFav(id),
          ),
          IconButton(
            icon: const Icon(Icons.help_outline, color: NxColors.teal),
            tooltip: 'How to do this exercise',
            onPressed: () => _showHowTo(e),
          ),
        ],
      ),
      onTap: () => Navigator.pop(context, e),
    );
  }

  @override
  Widget build(BuildContext context) {
    final q = _query.trim().toLowerCase();
    bool matches(Map<String, dynamic> e) =>
        q.isEmpty ||
        (e['name'] as String).toLowerCase().contains(q) ||
        (e['muscle_group'] as String).toLowerCase().contains(q);

    final favs = _all
        .where((e) => _favs.contains(e['id'] as int) && matches(e))
        .toList();
    final rest = _all
        .where((e) => !_favs.contains(e['id'] as int) && matches(e))
        .toList();

    final children = <Widget>[];
    if (favs.isNotEmpty) {
      children.add(_sectionLabel('FAVOURITES'));
      children.addAll(favs.map(_tile));
      children.add(_sectionLabel('ALL EXERCISES'));
    }
    children.addAll(rest.map(_tile));

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
                : ListView(children: children),
          ),
        ],
      ),
    );
  }
}
