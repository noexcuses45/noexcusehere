import 'package:flutter/material.dart';

import '../services/picks_store.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// Saved picks and the model's honest track record.
class PicksScreen extends StatefulWidget {
  const PicksScreen({super.key});

  @override
  State<PicksScreen> createState() => _PicksScreenState();
}

class _PicksScreenState extends State<PicksScreen> {
  bool _grading = false;

  @override
  void initState() {
    super.initState();
    PicksStore.instance.addListener(_changed);
    _grade();
  }

  @override
  void dispose() {
    PicksStore.instance.removeListener(_changed);
    super.dispose();
  }

  void _changed() => setState(() {});

  Future<void> _grade() async {
    setState(() => _grading = true);
    await PicksStore.instance.gradePending();
    if (mounted) setState(() => _grading = false);
  }

  @override
  Widget build(BuildContext context) {
    final store = PicksStore.instance;
    final picks = store.picks;
    final graded = store.wins + store.losses;
    final avgProb = graded == 0 ? 0.0 : picks.where((p) => p.result == 'win' || p.result == 'loss').map((p) => p.prob).reduce((a, b) => a + b) / graded;
    return RefreshIndicator(
      onRefresh: _grade,
      child: ListView(padding: const EdgeInsets.fromLTRB(12, 8, 12, 24), children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(children: [
              _stat('Record', '${store.wins}-${store.losses}'),
              _stat('Hit rate', graded == 0 ? '-' : pct(store.wins / graded)),
              _stat('Expected', graded == 0 ? '-' : pct(avgProb)),
              _stat('Return', graded == 0 ? '-' : '${store.roi >= 0 ? '+' : ''}${(store.roi * 100).round()}%'),
              IconButton(
                onPressed: _grading ? null : _grade,
                icon: _grading ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.refresh),
                tooltip: 'Grade finished picks',
              ),
            ]),
          ),
        ),
        const SizedBox(height: 6),
        const Muted('Hit rate vs expected shows whether the model is calibrated. Return uses your entered odds, or fair odds if none.'),
        if (picks.isEmpty)
          const Padding(
            padding: EdgeInsets.all(32),
            child: Center(child: Muted('Nothing saved yet. Save a multi or a horse and it will be graded here once the event finishes.', size: 14)),
          ),
        for (final p in picks) _pickCard(p),
      ]),
    );
  }

  Widget _stat(String label, String value) => Expanded(
        child: Column(children: [
          Text(value, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
          Muted(label),
        ]),
      );

  Widget _pickCard(Pick p) {
    final color = p.result == 'win' ? EdgeColors.win : (p.result == 'loss' ? EdgeColors.loss : (p.result == 'void' ? EdgeColors.muted : EdgeColors.sky));
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Dismissible(
        key: ValueKey(p.id),
        direction: DismissDirection.endToStart,
        background: Container(
          alignment: Alignment.centerRight,
          padding: const EdgeInsets.only(right: 20),
          decoration: BoxDecoration(color: EdgeColors.loss.withValues(alpha: 0.3), borderRadius: BorderRadius.circular(16)),
          child: const Icon(Icons.delete_outline),
        ),
        onDismissed: (_) => PicksStore.instance.remove(p.id),
        child: Card(
          child: ListTile(
            onTap: () => _editOdds(p),
            leading: Container(
              width: 6,
              height: 40,
              decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(3)),
            ),
            title: Text(p.title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
            subtitle: Text('${p.detail}\n${pct(p.prob)} · fair \$${odds(p.fairOdds)}${p.bookOdds != null ? ' · your odds \$${odds(p.bookOdds!)}' : ''}',
                style: const TextStyle(color: EdgeColors.muted, fontSize: 12)),
            isThreeLine: true,
            trailing: Text((p.result ?? 'pending').toUpperCase(), style: TextStyle(color: color, fontWeight: FontWeight.w900, fontSize: 12)),
          ),
        ),
      ),
    );
  }

  Future<void> _editOdds(Pick p) async {
    final ctrl = TextEditingController(text: p.bookOdds?.toString() ?? '');
    final v = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Odds you were offered'),
        content: TextField(
          controller: ctrl,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(hintText: 'e.g. ${odds(p.fairOdds)}', labelText: 'Decimal odds'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, ctrl.text), child: const Text('Save')),
        ],
      ),
    );
    if (v == null) return;
    await PicksStore.instance.setBookOdds(p.id, double.tryParse(v));
  }
}
