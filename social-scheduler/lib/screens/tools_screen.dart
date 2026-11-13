import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';
import 'package:url_launcher/url_launcher.dart';

import '../app_state.dart';
import '../models.dart';
import '../services/prefs.dart';
import '../services/update_service.dart';
import '../theme.dart';
import '../widgets/common.dart';

class ToolsScreen extends StatefulWidget {
  const ToolsScreen({super.key, required this.state, required this.onSignOut});

  final AppState state;
  final Future<void> Function() onSignOut;

  @override
  State<ToolsScreen> createState() => _ToolsScreenState();
}

class _ToolsScreenState extends State<ToolsScreen> {
  bool _shrink = true;
  String _version = '';

  AppState get state => widget.state;

  @override
  void initState() {
    super.initState();
    Prefs.shrinkVideos().then((v) {
      if (mounted) setState(() => _shrink = v);
    });
    UpdateService.currentVersion().then((v) {
      if (mounted) setState(() => _version = v);
    });
  }

  void _open(Widget screen) => Navigator.of(context).push(MaterialPageRoute(builder: (_) => screen));

  Future<void> _checkUpdate() async {
    final update = await UpdateService.check();
    if (!mounted) return;
    if (update == null) {
      showMessage(context, 'You have the latest version.');
      return;
    }
    await launchUrl(Uri.parse(update.url), mode: LaunchMode.externalApplication);
  }

  Future<void> _reminders() async {
    final ok = await state.notifications.requestPermission();
    if (!mounted) return;
    showMessage(
      context,
      ok
          ? 'Reminders are switched on.'
          : state.notifications.supported
              ? 'Notifications are blocked. Allow them for this app in your phone settings.'
              : 'Reminders work in the Android app.',
    );
  }

  @override
  Widget build(BuildContext context) {
    final hashtags = state.snippets.where((s) => s.kind == 'hashtags').length;
    final captions = state.snippets.where((s) => s.kind == 'caption').length;

    return Scaffold(
      appBar: AppBar(title: const Text('Tools')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(12, 4, 12, 32),
        children: [
          Card(
            child: Column(children: [
              ListTile(
                leading: const Icon(Icons.tag),
                title: const Text('Hashtag groups'),
                subtitle: Text(hashtags == 0 ? 'Save sets of hashtags to drop into captions' : '$hashtags saved'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _open(SnippetsScreen(state: state, kind: 'hashtags')),
              ),
              const Divider(indent: 56),
              ListTile(
                leading: const Icon(Icons.bookmark_outline),
                title: const Text('Saved captions'),
                subtitle: Text(captions == 0 ? 'Reusable sign-offs, opening hours, calls to action' : '$captions saved'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _open(SnippetsScreen(state: state, kind: 'caption')),
              ),
              const Divider(indent: 56),
              ListTile(
                leading: const Icon(Icons.auto_mode),
                title: const Text('Posting times'),
                subtitle: Text(state.slots.isEmpty
                    ? 'Choose your regular times; new posts fill the next free one'
                    : '${state.slots.length} times a week'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _open(SlotsScreen(state: state)),
              ),
              const Divider(indent: 56),
              ListTile(
                leading: const Icon(Icons.auto_awesome),
                title: const Text('AI captions'),
                subtitle: Text(state.hasAi ? 'Switched on' : 'Add an API key to get caption ideas'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _open(AiSettingsScreen(state: state)),
              ),
            ]),
          ),
          const SizedBox(height: 12),
          Card(
            child: Column(children: [
              SwitchListTile(
                secondary: const Icon(Icons.compress),
                title: const Text('Shrink large videos'),
                subtitle: const Text('Re-encode videos over 48 MB before upload so they send faster'),
                value: _shrink,
                onChanged: (v) {
                  setState(() => _shrink = v);
                  Prefs.setShrinkVideos(v);
                },
              ),
              const Divider(indent: 56),
              ListTile(
                leading: const Icon(Icons.notifications_active_outlined),
                title: const Text('Posting reminders'),
                subtitle: const Text('Allow notifications for posts you publish yourself'),
                onTap: _reminders,
              ),
            ]),
          ),
          const SizedBox(height: 12),
          Card(
            child: Column(children: [
              ListTile(
                leading: const Icon(Icons.system_update_outlined),
                title: const Text('Check for updates'),
                subtitle: Text(_version.isEmpty ? '' : 'Version $_version'),
                onTap: _checkUpdate,
              ),
              const Divider(indent: 56),
              ListTile(
                leading: const Icon(Icons.logout),
                title: Text(state.repo.isDemo ? 'Leave the demo' : 'Sign out'),
                subtitle: Text(state.repo.userEmail ?? ''),
                onTap: () => widget.onSignOut(),
              ),
            ]),
          ),
        ],
      ),
    );
  }
}

/// Saved hashtag groups or captions.
class SnippetsScreen extends StatelessWidget {
  const SnippetsScreen({super.key, required this.state, required this.kind});

  final AppState state;
  final String kind;

  bool get _isTags => kind == 'hashtags';

  Future<void> _edit(BuildContext context, [Snippet? existing]) async {
    final title = TextEditingController(text: existing?.title ?? '');
    final body = TextEditingController(text: existing?.body ?? '');
    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(existing == null ? (_isTags ? 'New hashtag group' : 'New saved caption') : 'Edit'),
        content: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(
              controller: title,
              autofocus: true,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(labelText: 'Name'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: body,
              minLines: 3,
              maxLines: 8,
              decoration: InputDecoration(
                labelText: _isTags ? 'Hashtags' : 'Text',
                hintText: _isTags ? '#coffee #brunch #localeats' : null,
              ),
            ),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Save')),
        ],
      ),
    );
    if (saved != true) return;
    if (title.text.trim().isEmpty || body.text.trim().isEmpty) {
      if (context.mounted) showMessage(context, 'Give it a name and some text.');
      return;
    }
    try {
      await state.repo.saveSnippet(
        Snippet(id: existing?.id, kind: kind, title: title.text.trim(), body: body.text.trim()),
      );
      await state.refreshTools();
    } on AppException catch (e) {
      if (context.mounted) showMessage(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        final items = state.snippets.where((s) => s.kind == kind).toList();
        return Scaffold(
          appBar: AppBar(title: Text(_isTags ? 'Hashtag groups' : 'Saved captions')),
          floatingActionButton: FloatingActionButton.extended(
            onPressed: () => _edit(context),
            icon: const Icon(Icons.add),
            label: const Text('Add'),
          ),
          body: items.isEmpty
              ? EmptyState(
                  icon: _isTags ? Icons.tag : Icons.bookmark_outline,
                  title: _isTags ? 'No hashtag groups yet' : 'No saved captions yet',
                  message: 'Anything you save here can be dropped into a caption with one tap.',
                )
              : ListView.separated(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 96),
                  itemCount: items.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, i) {
                    final s = items[i];
                    return Card(
                      child: ListTile(
                        title: Text(s.title, style: const TextStyle(fontWeight: FontWeight.w600)),
                        subtitle: Text(s.body, maxLines: 3, overflow: TextOverflow.ellipsis),
                        onTap: () => _edit(context, s),
                        trailing: IconButton(
                          tooltip: 'Delete',
                          icon: const Icon(Icons.delete_outline),
                          onPressed: () async {
                            try {
                              await state.repo.deleteSnippet(s.id!);
                              await state.refreshTools();
                            } on AppException catch (e) {
                              if (context.mounted) showMessage(context, e.message);
                            }
                          },
                        ),
                      ),
                    );
                  },
                ),
        );
      },
    );
  }
}

/// Regular weekly posting times.
class SlotsScreen extends StatelessWidget {
  const SlotsScreen({super.key, required this.state});

  final AppState state;

  static const _days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

  Future<void> _add(BuildContext context, int weekday) async {
    final time = await showTimePicker(context: context, initialTime: const TimeOfDay(hour: 9, minute: 0));
    if (time == null) return;
    try {
      await state.repo.addSlot(weekday, time.hour * 60 + time.minute);
      await state.refreshTools();
    } on AppException catch (e) {
      if (context.mounted) showMessage(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ListenableBuilder(
      listenable: state,
      builder: (context, _) => Scaffold(
        appBar: AppBar(title: const Text('Posting times')),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 32),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 0, 4, 12),
              child: Text(
                'Pick the times you like to post each week. When you write a new post, '
                '"Next free posting time" fills the first one that is not taken.',
                style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            ),
            Card(
              child: Column(children: [
                for (var d = 1; d <= 7; d++) ...[
                  if (d > 1) const Divider(indent: 16, endIndent: 16),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
                    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      SizedBox(
                        width: 96,
                        child: Padding(
                          padding: const EdgeInsets.only(top: 12),
                          child: Text(_days[d - 1], style: theme.textTheme.titleSmall),
                        ),
                      ),
                      Expanded(
                        child: Wrap(spacing: 6, runSpacing: 2, children: [
                          for (final s in state.slots.where((s) => s.weekday == d))
                            InputChip(
                              label: Text(DateFormat.jm().format(DateTime(2024, 1, 1, s.minuteOfDay ~/ 60, s.minuteOfDay % 60))),
                              onDeleted: () async {
                                try {
                                  await state.repo.deleteSlot(s.id!);
                                  await state.refreshTools();
                                } on AppException catch (e) {
                                  if (context.mounted) showMessage(context, e.message);
                                }
                              },
                            ),
                          ActionChip(
                            avatar: const Icon(Icons.add, size: 16),
                            label: const Text('Add'),
                            onPressed: () => _add(context, d),
                          ),
                        ]),
                      ),
                    ]),
                  ),
                ],
              ]),
            ),
          ],
        ),
      ),
    );
  }
}

/// Anthropic API key and the business description used for caption ideas.
class AiSettingsScreen extends StatefulWidget {
  const AiSettingsScreen({super.key, required this.state});

  final AppState state;

  @override
  State<AiSettingsScreen> createState() => _AiSettingsScreenState();
}

class _AiSettingsScreenState extends State<AiSettingsScreen> {
  final _key = TextEditingController();
  final _business = TextEditingController();
  bool _saving = false;

  AppState get state => widget.state;

  @override
  void initState() {
    super.initState();
    Prefs.business().then((b) {
      if (mounted) _business.text = b;
    });
  }

  @override
  void dispose() {
    _key.dispose();
    _business.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    try {
      await Prefs.setBusiness(_business.text.trim());
      if (_key.text.trim().isNotEmpty) {
        await state.repo.saveCredentials('anthropic', '', _key.text.trim(), const {});
        await state.refreshAccounts();
        _key.clear();
      }
      if (mounted) showMessage(context, 'Saved.');
    } on AppException catch (e) {
      if (mounted) showMessage(context, e.message);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _remove() async {
    try {
      await state.repo.clearCredentials('anthropic');
      await state.refreshAccounts();
      if (mounted) showMessage(context, 'AI captions switched off.');
    } on AppException catch (e) {
      if (mounted) showMessage(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ListenableBuilder(
      listenable: state,
      builder: (context, _) => Scaffold(
        appBar: AppBar(title: const Text('AI captions')),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 32),
          children: [
            Text(
              'The Ideas button in the composer writes three caption options and suggests hashtags, using Claude. '
              'It needs your own Anthropic API key; Anthropic bills you for what you use, typically a cent or two per request.',
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: () =>
                  launchUrl(Uri.parse('https://platform.claude.com/settings/keys'), mode: LaunchMode.externalApplication),
              icon: const Icon(Icons.open_in_new, size: 18),
              label: const Text('Get an API key'),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _key,
              obscureText: true,
              autocorrect: false,
              enableSuggestions: false,
              decoration: InputDecoration(
                labelText: 'Anthropic API key',
                helperText: state.hasAi ? 'A key is saved. Enter a new one to replace it.' : 'Stored on the server only.',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _business,
              minLines: 2,
              maxLines: 5,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(
                labelText: 'About your business (optional)',
                hintText: 'e.g. Family-run cafe in Fremantle, open 7 days, known for our sourdough',
                helperText: 'Included with every request so captions sound like you.',
              ),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Save'),
            ),
            if (state.hasAi)
              TextButton(onPressed: _remove, child: const Text('Remove the saved key')),
          ],
        ),
      ),
    );
  }
}
