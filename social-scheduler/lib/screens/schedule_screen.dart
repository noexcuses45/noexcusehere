import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

import '../app_state.dart';
import '../models.dart';
import '../theme.dart';
import '../widgets/common.dart';

DateTime _dayOf(DateTime d) => DateTime(d.year, d.month, d.day);

/// Month calendar with the selected day's posts underneath.
class ScheduleScreen extends StatefulWidget {
  const ScheduleScreen({super.key, required this.state, required this.onOpenPost, required this.onCompose});

  final AppState state;
  final void Function(String id) onOpenPost;
  final void Function({DateTime? day}) onCompose;

  @override
  State<ScheduleScreen> createState() => _ScheduleScreenState();
}

class _ScheduleScreenState extends State<ScheduleScreen> {
  late DateTime _month;
  late DateTime _selected;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _selected = _dayOf(now);
    _month = DateTime(now.year, now.month);
  }

  void _shiftMonth(int by) => setState(() => _month = DateTime(_month.year, _month.month + by));

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final theme = Theme.of(context);
    final now = DateTime.now();

    final byDay = <DateTime, List<Post>>{};
    for (final p in state.posts) {
      if (p.isDraft || p.when == null) continue;
      byDay.putIfAbsent(_dayOf(p.when!), () => []).add(p);
    }
    final todays = [...(byDay[_selected] ?? const <Post>[])]..sort((a, b) => a.when!.compareTo(b.when!));

    return Scaffold(
      appBar: AppBar(
        title: Text(DateFormat.yMMMM().format(_month)),
        actions: [
          IconButton(tooltip: 'Previous month', icon: const Icon(Icons.chevron_left), onPressed: () => _shiftMonth(-1)),
          IconButton(tooltip: 'Next month', icon: const Icon(Icons.chevron_right), onPressed: () => _shiftMonth(1)),
          TextButton(
            onPressed: () => setState(() {
              _selected = _dayOf(now);
              _month = DateTime(now.year, now.month);
            }),
            child: const Text('Today'),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: state.refreshPosts,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 96),
          children: [
            if (state.repo.isDemo) const _DemoNote(),
            if (state.loadError != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(state.loadError!, style: TextStyle(color: theme.colorScheme.error)),
              ),
            _MonthGrid(
              month: _month,
              selected: _selected,
              today: _dayOf(now),
              byDay: byDay,
              onSelect: (d) => setState(() {
                _selected = d;
                if (d.month != _month.month || d.year != _month.year) _month = DateTime(d.year, d.month);
              }),
            ),
            const SizedBox(height: 16),
            Row(children: [
              Expanded(
                child: Text(
                  _selected == _dayOf(now) ? 'Today' : DateFormat('EEEE d MMMM').format(_selected),
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                ),
              ),
              if (!_selected.isBefore(_dayOf(now)))
                TextButton.icon(
                  onPressed: () => widget.onCompose(day: _selected),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('Add'),
                ),
            ]),
            const SizedBox(height: 4),
            if (state.loading)
              const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()))
            else if (todays.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 24),
                child: Text(
                  _selected.isBefore(_dayOf(now)) ? 'Nothing was posted this day.' : 'Nothing planned for this day yet.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                ),
              )
            else
              for (final p in todays)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: PostCard(post: p, state: state, onTap: () => widget.onOpenPost(p.id)),
                ),
          ],
        ),
      ),
    );
  }
}

class _DemoNote extends StatelessWidget {
  const _DemoNote();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(color: scheme.tertiaryContainer, borderRadius: BorderRadius.circular(12)),
      child: Row(children: [
        Icon(Icons.science_outlined, size: 18, color: scheme.onTertiaryContainer),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            'Sample data. Nothing here is posted anywhere.',
            style: TextStyle(color: scheme.onTertiaryContainer),
          ),
        ),
      ]),
    );
  }
}

class _MonthGrid extends StatelessWidget {
  const _MonthGrid({
    required this.month,
    required this.selected,
    required this.today,
    required this.byDay,
    required this.onSelect,
  });

  final DateTime month;
  final DateTime selected;
  final DateTime today;
  final Map<DateTime, List<Post>> byDay;
  final void Function(DateTime day) onSelect;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final colors = StatusColors(scheme);
    final first = DateTime(month.year, month.month, 1);
    // Weeks start on Monday.
    final start = first.subtract(Duration(days: first.weekday - 1));
    final weeks = (first.weekday - 1 + DateUtils.getDaysInMonth(month.year, month.month) + 6) ~/ 7;
    const names = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
    final now = DateTime.now();

    Color dotFor(Post p) {
      if (p.status == 'failed' || p.status == 'partial') return colors.danger;
      if (p.reminderDue(now)) return colors.warning;
      if (p.status == 'published') return colors.success;
      return colors.info;
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(6, 10, 6, 6),
        child: Column(
          children: [
            Row(children: [
              for (final n in names)
                Expanded(
                  child: Center(
                    child: Text(n, style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
                  ),
                ),
            ]),
            const SizedBox(height: 4),
            for (var w = 0; w < weeks; w++)
              Row(children: [
                for (var d = 0; d < 7; d++)
                  Expanded(
                    child: Builder(builder: (context) {
                      final day = DateTime(start.year, start.month, start.day + w * 7 + d);
                      final inMonth = day.month == month.month;
                      final isSelected = day == selected;
                      final isToday = day == today;
                      final posts = byDay[day] ?? const <Post>[];
                      return Semantics(
                        button: true,
                        selected: isSelected,
                        label: '${DateFormat('EEEE d MMMM').format(day)}, ${posts.length} posts',
                        child: InkWell(
                          borderRadius: BorderRadius.circular(12),
                          onTap: () => onSelect(day),
                          child: Container(
                            height: 46,
                            margin: const EdgeInsets.all(1.5),
                            decoration: BoxDecoration(
                              color: isSelected ? scheme.primary : null,
                              borderRadius: BorderRadius.circular(12),
                              border: isToday && !isSelected ? Border.all(color: scheme.primary, width: 1.5) : null,
                            ),
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                ExcludeSemantics(
                                  child: Text(
                                    '${day.day}',
                                    style: theme.textTheme.bodyMedium?.copyWith(
                                      fontWeight: isToday || isSelected ? FontWeight.w700 : FontWeight.w400,
                                      color: isSelected
                                          ? scheme.onPrimary
                                          : inMonth
                                              ? scheme.onSurface
                                              : scheme.onSurfaceVariant.withValues(alpha: 0.5),
                                    ),
                                  ),
                                ),
                                const SizedBox(height: 3),
                                SizedBox(
                                  height: 6,
                                  child: Row(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    children: [
                                      for (final p in posts.take(3))
                                        Container(
                                          width: 5,
                                          height: 5,
                                          margin: const EdgeInsets.symmetric(horizontal: 1),
                                          decoration: BoxDecoration(
                                            color: isSelected ? scheme.onPrimary : dotFor(p),
                                            shape: BoxShape.circle,
                                          ),
                                        ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      );
                    }),
                  ),
              ]),
          ],
        ),
      ),
    );
  }
}
