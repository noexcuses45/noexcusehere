import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

import '../app_state.dart';
import '../models.dart';
import '../platforms.dart';
import '../services/media_service.dart';
import '../services/prefs.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'composer_parts.dart';

class _Slot {
  _Slot({this.media, this.stage});
  PostMedia? media;
  String? stage; // what is happening while it is being prepared
  String? error;
  bool get busy => media == null && error == null;
}

/// Create or edit a post: media, caption, where it goes, and when.
class ComposerScreen extends StatefulWidget {
  const ComposerScreen({super.key, required this.state, this.existing, this.initialDay, this.duplicate = false});

  final AppState state;
  final Post? existing;
  final DateTime? initialDay;

  /// Start from [existing] but save as a new post.
  final bool duplicate;

  @override
  State<ComposerScreen> createState() => _ComposerScreenState();
}

class _ComposerScreenState extends State<ComposerScreen> {
  static const _reminderPlatforms = ['instagram', 'facebook', 'tiktok', 'youtube', 'linkedin', 'other'];

  final _caption = TextEditingController();
  final _link = TextEditingController();
  final _slots = <_Slot>[];
  final _targets = <String, PostTarget>{}; // by account id
  final _reminders = <String>{};

  late final MediaService _mediaService = MediaService(widget.state.repo);
  bool _showLink = false;
  bool _postNow = false;
  DateTime? _when;
  bool _saving = false;
  bool _dirty = false;

  AppState get state => widget.state;
  String? get _editingId => widget.duplicate ? null : widget.existing?.id;

  @override
  void initState() {
    super.initState();
    final post = widget.existing;
    if (post != null) {
      _caption.text = post.caption;
      _link.text = post.linkUrl ?? '';
      _showLink = _link.text.isNotEmpty;
      _slots.addAll(post.media.map((m) => _Slot(media: m)));
      for (final t in post.targets) {
        if (t.isReminder) {
          _reminders.add(t.platform);
        } else if (t.accountId != null && state.account(t.accountId) != null) {
          _targets[t.accountId!] = t.copy();
        }
      }
      if (!widget.duplicate && post.scheduledAt != null && post.scheduledAt!.isAfter(DateTime.now())) {
        _when = post.scheduledAt;
      }
    }
    if (_when == null) {
      final now = DateTime.now();
      final day = widget.initialDay;
      _when = day != null && !DateTime(day.year, day.month, day.day, 9).isBefore(now)
          ? DateTime(day.year, day.month, day.day, 9)
          : nextFreeSlot(state.slots, state.posts, now);
    }
    _caption.addListener(_touch);
    _link.addListener(_touch);
  }

  @override
  void dispose() {
    _caption.dispose();
    _link.dispose();
    super.dispose();
  }

  void _touch() {
    _dirty = true;
    setState(() {});
  }

  List<PostMedia> get _media => [for (final s in _slots) if (s.media != null) s.media!];

  List<PostTarget> get _allTargets => [
        ..._targets.values,
        for (final p in _reminders) PostTarget(platform: p, mode: 'reminder', postType: 'feed'),
      ];

  int? get _tightestLimit {
    final limits = _targets.values.map((t) => platformInfo(t.platform).captionLimit).toList();
    if (limits.isEmpty) return null;
    return limits.reduce((a, b) => a < b ? a : b);
  }

  // --- media ---------------------------------------------------------------

  Future<void> _addMedia() async {
    final choice = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(
            leading: const Icon(Icons.photo_library_outlined),
            title: const Text('Choose from gallery'),
            subtitle: const Text('Photos and videos'),
            onTap: () => Navigator.pop(context, 'gallery'),
          ),
          ListTile(
            leading: const Icon(Icons.photo_camera_outlined),
            title: const Text('Take a photo'),
            onTap: () => Navigator.pop(context, 'photo'),
          ),
          ListTile(
            leading: const Icon(Icons.videocam_outlined),
            title: const Text('Record a video'),
            onTap: () => Navigator.pop(context, 'video'),
          ),
        ]),
      ),
    );
    if (choice == null) return;

    List<XFile> files = const [];
    try {
      if (choice == 'gallery') {
        files = await _mediaService.pickFromGallery();
      } else {
        final one = choice == 'photo' ? await _mediaService.takePhoto() : await _mediaService.recordVideo();
        files = [?one];
      }
    } catch (_) {
      if (mounted) showMessage(context, 'Could not open that. Check the app has permission to use it.');
      return;
    }
    if (files.isEmpty || !mounted) return;

    final shrink = await Prefs.shrinkVideos();
    _dirty = true;
    for (final file in files) {
      final slot = _Slot(stage: 'Preparing');
      setState(() => _slots.add(slot));
      // Each file is prepared and uploaded straight away, so scheduling is instant later.
      _mediaService.prepare(file, shrinkVideos: shrink, onStage: (s) {
        if (mounted) setState(() => slot.stage = s);
      }).then((media) {
        if (!mounted) return;
        setState(() => slot.media = media);
      }).catchError((Object e) {
        if (!mounted) return;
        setState(() => slot.error = e is AppException ? e.message : 'This file could not be uploaded.');
      });
    }
  }

  // --- caption tools -------------------------------------------------------

  void _append(String text) {
    final current = _caption.text.trimRight();
    _caption.text = current.isEmpty ? text : '$current\n\n$text';
    _caption.selection = TextSelection.collapsed(offset: _caption.text.length);
  }

  Future<void> _insertSnippet(String kind) async {
    final s = await pickSnippet(context, state, kind);
    if (s != null) _append(s.body);
  }

  Future<void> _aiIdeas() async {
    if (!state.hasAi && !state.repo.isDemo) {
      showMessage(context, 'Add your Anthropic API key under Tools > AI captions to use this.');
      return;
    }
    final result = await showModalBottomSheet<AiResult>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => AiSheet(
        state: state,
        draft: _caption.text,
        platforms: _allTargets.map((t) => t.platform).toSet().toList(),
      ),
    );
    if (result == null) return;
    if (result.caption != null) {
      _caption.text = result.caption!;
      _caption.selection = TextSelection.collapsed(offset: _caption.text.length);
    }
    if (result.hashtags != null) _append(result.hashtags!);
  }

  // --- when ----------------------------------------------------------------

  Future<void> _pickWhen() async {
    final now = DateTime.now();
    final current = _when ?? now.add(const Duration(hours: 1));
    final date = await showDatePicker(
      context: context,
      initialDate: current.isBefore(now) ? now : current,
      firstDate: DateTime(now.year, now.month, now.day),
      lastDate: now.add(const Duration(days: 365)),
    );
    if (date == null || !mounted) return;
    final time = await showTimePicker(context: context, initialTime: TimeOfDay.fromDateTime(current));
    if (time == null) return;
    setState(() {
      _dirty = true;
      _postNow = false;
      _when = DateTime(date.year, date.month, date.day, time.hour, time.minute);
    });
  }

  // --- save ----------------------------------------------------------------

  Map<String, List<Issue>> _issues() {
    final caption = _caption.text;
    final media = _media;
    return {
      for (final entry in _targets.entries)
        entry.key: validateTarget(target: entry.value, caption: caption, linkUrl: _link.text, media: media),
    };
  }

  Future<void> _save({required bool schedule}) async {
    FocusScope.of(context).unfocus();
    if (_slots.any((s) => s.busy)) {
      showMessage(context, 'Wait for the uploads to finish first.');
      return;
    }
    if (_slots.any((s) => s.error != null)) {
      showMessage(context, 'Remove the file that failed to upload first.');
      return;
    }
    final targets = _allTargets;
    DateTime? when;
    if (schedule) {
      if (targets.isEmpty) {
        showMessage(context, 'Choose at least one place to post to.');
        return;
      }
      final blocking = _issues().values.expand((l) => l).where((i) => i.blocking).toList();
      if (blocking.isNotEmpty) {
        showMessage(context, blocking.first.message);
        return;
      }
      final now = DateTime.now();
      when = _postNow ? now : _when;
      if (when == null) {
        showMessage(context, 'Choose a date and time.');
        return;
      }
      if (!_postNow && when.isBefore(now.add(const Duration(seconds: 30)))) {
        showMessage(context, 'That time has already passed. Pick a later time or choose Post now.');
        return;
      }
    } else if (_caption.text.trim().isEmpty && _slots.isEmpty) {
      showMessage(context, 'Add a caption or some media before saving.');
      return;
    }

    setState(() => _saving = true);
    try {
      await state.repo.savePost(PostDraft(
        id: _editingId,
        caption: _caption.text.trim(),
        linkUrl: _showLink ? _link.text.trim() : null,
        schedule: schedule,
        scheduledAt: schedule ? when : _when,
        media: _media,
        targets: targets,
      ));
      if (schedule && _postNow) await state.repo.publishNow();
      if (schedule && _reminders.isNotEmpty) await state.notifications.requestPermission();
      await state.refreshPosts();
      if (!mounted) return;
      _dirty = false;
      final message = !schedule
          ? 'Draft saved.'
          : _postNow
              ? 'Posting now. You can follow it under Posts.'
              : 'Scheduled for ${formatWhen(when, DateTime.now())}.';
      final messenger = ScaffoldMessenger.of(context);
      Navigator.of(context).pop();
      messenger.showSnackBar(SnackBar(content: Text(message)));
    } on AppException catch (e) {
      if (mounted) showMessage(context, e.message);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<bool> _confirmDiscard() async {
    if (!_dirty) return true;
    final leave = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Discard this post?'),
        content: const Text('Your changes have not been saved.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Keep editing')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Discard')),
        ],
      ),
    );
    return leave ?? false;
  }

  // --- build ---------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final issues = _issues();
    final limit = _tightestLimit;
    final length = _caption.text.length;
    final byPlatform = [
      for (final p in kPlatformOrder) ...state.accounts.where((a) => a.platform == p),
    ];

    return PopScope(
      canPop: !_dirty,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        final navigator = Navigator.of(context);
        if (await _confirmDiscard()) navigator.pop();
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(_editingId != null ? 'Edit post' : 'New post'),
          leading: IconButton(
            tooltip: 'Close',
            icon: const Icon(Icons.close),
            onPressed: () => Navigator.of(context).maybePop(),
          ),
        ),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
          children: [
            // Media
            SizedBox(
              height: 96,
              child: ListView(
                scrollDirection: Axis.horizontal,
                children: [
                  for (final slot in _slots)
                    Padding(
                      padding: const EdgeInsets.only(right: 8, top: 6),
                      child: _MediaTile(
                        slot: slot,
                        state: state,
                        onRemove: () => setState(() {
                          _dirty = true;
                          _slots.remove(slot);
                        }),
                      ),
                    ),
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: InkWell(
                      borderRadius: BorderRadius.circular(12),
                      onTap: _addMedia,
                      child: Container(
                        width: _slots.isEmpty ? 220 : 88,
                        height: 88,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: scheme.outlineVariant),
                        ),
                        alignment: Alignment.center,
                        child: Column(mainAxisSize: MainAxisSize.min, children: [
                          Icon(Icons.add_photo_alternate_outlined, color: scheme.primary),
                          const SizedBox(height: 4),
                          Text(
                            _slots.isEmpty ? 'Add photos or video' : 'Add',
                            style: theme.textTheme.labelMedium?.copyWith(color: scheme.primary),
                          ),
                        ]),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),

            // Caption
            TextField(
              controller: _caption,
              minLines: 4,
              maxLines: 12,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(labelText: 'Caption', alignLabelWithHint: true),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(children: [
                Expanded(
                  child: Wrap(spacing: 4, children: [
                    TextButton.icon(
                      onPressed: () => _insertSnippet('hashtags'),
                      icon: const Icon(Icons.tag, size: 18),
                      label: const Text('Hashtags'),
                    ),
                    TextButton.icon(
                      onPressed: () => _insertSnippet('caption'),
                      icon: const Icon(Icons.bookmark_outline, size: 18),
                      label: const Text('Saved'),
                    ),
                    TextButton.icon(
                      onPressed: _aiIdeas,
                      icon: const Icon(Icons.auto_awesome, size: 18),
                      label: const Text('Ideas'),
                    ),
                    if (!_showLink)
                      TextButton.icon(
                        onPressed: () => setState(() => _showLink = true),
                        icon: const Icon(Icons.link, size: 18),
                        label: const Text('Link'),
                      ),
                  ]),
                ),
                Text(
                  limit == null ? '$length' : '$length / $limit',
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: limit != null && length > limit ? scheme.error : scheme.onSurfaceVariant,
                  ),
                ),
              ]),
            ),
            if (_showLink) ...[
              const SizedBox(height: 8),
              TextField(
                controller: _link,
                keyboardType: TextInputType.url,
                decoration: InputDecoration(
                  labelText: 'Link (Facebook and LinkedIn posts without media)',
                  suffixIcon: IconButton(
                    tooltip: 'Remove link',
                    icon: const Icon(Icons.close),
                    onPressed: () => setState(() {
                      _link.clear();
                      _showLink = false;
                    }),
                  ),
                ),
              ),
            ],
            const SizedBox(height: 20),

            // Where
            Text('Post automatically to', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            if (byPlatform.isEmpty)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Text(
                    'No accounts are connected yet. Connect them on the Accounts tab, or use a reminder below '
                    'and post it yourself when the time comes.',
                    style: theme.textTheme.bodyMedium,
                  ),
                ),
              )
            else
              Card(
                child: Column(children: [
                  for (final account in byPlatform) ...[
                    if (account != byPlatform.first) const Divider(indent: 16, endIndent: 16),
                    _AccountRow(
                      account: account,
                      target: _targets[account.id],
                      issues: issues[account.id] ?? const [],
                      onToggle: (on) => setState(() {
                        _dirty = true;
                        if (on) {
                          _targets[account.id] = defaultTargetFor(account, _media);
                        } else {
                          _targets.remove(account.id);
                        }
                      }),
                      onChanged: () => setState(() => _dirty = true),
                      onEditCaption: () async {
                        await editCaptionOverride(context, _targets[account.id]!, _caption.text);
                        if (mounted) setState(() => _dirty = true);
                      },
                    ),
                  ],
                ]),
              ),
            const SizedBox(height: 20),

            Text('Or remind me to post it myself',
                style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 2),
            Text(
              'You get a notification at the scheduled time with the caption and media ready to share. '
              'Handy for trending sounds, stickers, or anything a platform will not let apps post.',
              style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: 8),
            Wrap(spacing: 8, runSpacing: 4, children: [
              for (final p in _reminderPlatforms)
                FilterChip(
                  avatar: PlatformBadge(p, size: 18),
                  label: Text(platformInfo(p).label),
                  selected: _reminders.contains(p),
                  onSelected: (on) => setState(() {
                    _dirty = true;
                    on ? _reminders.add(p) : _reminders.remove(p);
                  }),
                ),
            ]),
            const SizedBox(height: 20),

            // When
            Text('When', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            SegmentedButton<bool>(
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: false, icon: Icon(Icons.schedule, size: 18), label: Text('Schedule')),
                ButtonSegment(value: true, icon: Icon(Icons.bolt, size: 18), label: Text('Post now')),
              ],
              selected: {_postNow},
              onSelectionChanged: (s) => setState(() {
                _dirty = true;
                _postNow = s.first;
              }),
            ),
            if (!_postNow) ...[
              const SizedBox(height: 8),
              Card(
                child: ListTile(
                  leading: const Icon(Icons.event),
                  title: Text(_when == null ? 'Choose date and time' : DateFormat('EEE d MMM y').format(_when!)),
                  subtitle: _when == null ? null : Text(DateFormat.jm().format(_when!)),
                  trailing: const Icon(Icons.edit_outlined),
                  onTap: _pickWhen,
                ),
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: () => setState(() {
                    _dirty = true;
                    _when = nextFreeSlot(state.slots, state.posts, DateTime.now());
                  }),
                  icon: const Icon(Icons.auto_mode, size: 18),
                  label: Text(state.slots.isEmpty ? 'Tomorrow 9:00 am' : 'Next free posting time'),
                ),
              ),
            ],
          ],
        ),
        bottomNavigationBar: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
            child: Row(children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: _saving ? null : () => _save(schedule: false),
                  child: const Text('Save draft'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                flex: 2,
                child: FilledButton(
                  onPressed: _saving ? null : () => _save(schedule: true),
                  child: _saving
                      ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(_postNow ? 'Post now' : 'Schedule'),
                ),
              ),
            ]),
          ),
        ),
      ),
    );
  }
}

class _MediaTile extends StatelessWidget {
  const _MediaTile({required this.slot, required this.state, required this.onRemove});

  final _Slot slot;
  final AppState state;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SizedBox(
      width: 88,
      height: 88,
      child: Stack(clipBehavior: Clip.none, children: [
        if (slot.media != null)
          MediaThumb(slot.media, state: state, size: 88, radius: 12)
        else
          Container(
            width: 88,
            height: 88,
            padding: const EdgeInsets.all(6),
            decoration: BoxDecoration(
              color: slot.error != null ? scheme.errorContainer : scheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(12),
            ),
            alignment: Alignment.center,
            child: slot.error != null
                ? Tooltip(
                    message: slot.error!,
                    triggerMode: TooltipTriggerMode.tap,
                    showDuration: const Duration(seconds: 6),
                    child: Column(mainAxisSize: MainAxisSize.min, children: [
                      Icon(Icons.error_outline, color: scheme.onErrorContainer),
                      Text('Failed', style: TextStyle(color: scheme.onErrorContainer, fontSize: 11)),
                      Text('Tap for why', style: TextStyle(color: scheme.onErrorContainer, fontSize: 9)),
                    ]),
                  )
                : Column(mainAxisSize: MainAxisSize.min, children: [
                    const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2)),
                    const SizedBox(height: 6),
                    Text(
                      slot.stage ?? 'Preparing',
                      textAlign: TextAlign.center,
                      maxLines: 2,
                      style: TextStyle(fontSize: 10, color: scheme.onSurfaceVariant),
                    ),
                  ]),
          ),
        Positioned(
          right: -6,
          top: -6,
          child: Material(
            color: scheme.inverseSurface,
            shape: const CircleBorder(),
            child: InkWell(
              customBorder: const CircleBorder(),
              onTap: onRemove,
              child: Padding(
                padding: const EdgeInsets.all(3),
                child: Icon(Icons.close, size: 14, color: scheme.onInverseSurface, semanticLabel: 'Remove'),
              ),
            ),
          ),
        ),
      ]),
    );
  }
}

class _AccountRow extends StatelessWidget {
  const _AccountRow({
    required this.account,
    required this.target,
    required this.issues,
    required this.onToggle,
    required this.onChanged,
    required this.onEditCaption,
  });

  final SocialAccount account;
  final PostTarget? target;
  final List<Issue> issues;
  final void Function(bool on) onToggle;
  final VoidCallback onChanged;
  final VoidCallback onEditCaption;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final colors = StatusColors(scheme);
    final selected = target != null;
    final info = platformInfo(account.platform);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        CheckboxListTile(
          value: selected,
          onChanged: account.needsReconnect ? null : (v) => onToggle(v ?? false),
          secondary: AccountAvatar(account, size: 36),
          title: Text(account.name, maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: Text(
            account.needsReconnect ? '${info.label} - reconnect on the Accounts tab' : info.label,
            style: account.needsReconnect ? TextStyle(color: scheme.error) : null,
          ),
        ),
        if (selected)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TargetOptions(target: target!, account: account, onChanged: onChanged),
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton.icon(
                    onPressed: onEditCaption,
                    icon: Icon(target!.captionOverride == null ? Icons.edit_outlined : Icons.check, size: 16),
                    label: Text(target!.captionOverride == null
                        ? 'Use a different caption here'
                        : 'Custom caption for ${info.label}'),
                  ),
                ),
                for (final issue in issues)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Icon(
                        issue.blocking ? Icons.error_outline : Icons.info_outline,
                        size: 16,
                        color: issue.blocking ? scheme.error : colors.warning,
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          issue.message,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: issue.blocking ? scheme.error : colors.warning,
                          ),
                        ),
                      ),
                    ]),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}
