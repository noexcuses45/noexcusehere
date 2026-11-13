import 'package:material_ui/material_ui.dart';

import '../app_state.dart';
import '../models.dart';
import '../platforms.dart';
import '../services/prefs.dart';
import '../theme.dart';

const _tiktokPrivacy = {
  'PUBLIC_TO_EVERYONE': 'Everyone',
  'FOLLOWER_OF_CREATOR': 'Followers',
  'MUTUAL_FOLLOW_FRIENDS': 'Friends',
  'SELF_ONLY': 'Only me',
};

const _youtubePrivacy = {'public': 'Public', 'unlisted': 'Unlisted', 'private': 'Private'};

/// Options that differ per platform, shown under a ticked account.
class TargetOptions extends StatelessWidget {
  const TargetOptions({super.key, required this.target, required this.account, required this.onChanged});

  final PostTarget target;
  final SocialAccount account;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    final info = platformInfo(target.platform);
    final theme = Theme.of(context);
    final children = <Widget>[];

    if (info.postTypes.length > 1) {
      final canDirect = target.platform != 'tiktok' ||
          ((account.meta['scopes'] as String?) ?? '').contains('video.publish');
      children.add(
        SegmentedButton<String>(
          showSelectedIcon: false,
          segments: [
            for (final t in info.postTypes)
              ButtonSegment(value: t.key, label: Text(t.label), enabled: t.key != 'direct' || canDirect),
          ],
          selected: {target.postType},
          onSelectionChanged: (s) {
            target.postType = s.first;
            onChanged();
          },
        ),
      );
      final hint = info.postTypes.firstWhere((t) => t.key == target.postType, orElse: () => info.postTypes.first).hint;
      children.add(Padding(
        padding: const EdgeInsets.only(top: 6),
        child: Text(hint, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
      ));
    }

    if (target.platform == 'tiktok' && target.postType == 'direct') {
      children.add(const SizedBox(height: 10));
      children.add(DropdownButtonFormField<String>(
        initialValue: _tiktokPrivacy.containsKey(target.options['privacy_level']) ? target.options['privacy_level'] as String : null,
        decoration: const InputDecoration(labelText: 'Who can see it'),
        items: [for (final e in _tiktokPrivacy.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
        onChanged: (v) {
          target.options['privacy_level'] = v;
          onChanged();
        },
      ));
    }

    if (target.platform == 'youtube') {
      children.add(TextFormField(
        initialValue: (target.options['title'] as String?) ?? '',
        maxLength: 100,
        decoration: const InputDecoration(
          labelText: 'Video title',
          helperText: 'Left empty, the first line of the caption is used.',
        ),
        onChanged: (v) {
          target.options['title'] = v;
          onChanged();
        },
      ));
      children.add(const SizedBox(height: 4));
      children.add(DropdownButtonFormField<String>(
        initialValue: _youtubePrivacy.containsKey(target.options['privacy_status'])
            ? target.options['privacy_status'] as String
            : 'public',
        decoration: const InputDecoration(labelText: 'Visibility'),
        items: [for (final e in _youtubePrivacy.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
        onChanged: (v) {
          target.options['privacy_status'] = v;
          onChanged();
        },
      ));
      children.add(SwitchListTile(
        contentPadding: EdgeInsets.zero,
        dense: true,
        title: const Text('Made for kids'),
        subtitle: const Text('YouTube requires you to say whether this video is aimed at children.'),
        value: target.options['made_for_kids'] == true,
        onChanged: (v) {
          target.options['made_for_kids'] = v;
          onChanged();
        },
      ));
    }

    if (children.isEmpty) return const SizedBox.shrink();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: children);
  }
}

/// Lets the owner word the caption differently for one platform.
Future<void> editCaptionOverride(BuildContext context, PostTarget target, String mainCaption) async {
  final info = platformInfo(target.platform);
  final controller = TextEditingController(text: target.captionOverride ?? mainCaption);
  final result = await showDialog<String?>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text('Caption for ${info.label}'),
      content: TextField(
        controller: controller,
        maxLines: 8,
        minLines: 4,
        maxLength: info.captionLimit,
        autofocus: true,
        decoration: const InputDecoration(hintText: 'Leave as-is to use the main caption'),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, ''), child: const Text('Use main caption')),
        FilledButton(onPressed: () => Navigator.pop(context, controller.text), child: const Text('Save')),
      ],
    ),
  );
  if (result == null) return;
  final trimmed = result.trim();
  target.captionOverride = (trimmed.isEmpty || trimmed == mainCaption.trim()) ? null : result;
}

/// Bottom sheet to pick a saved hashtag group or caption.
Future<Snippet?> pickSnippet(BuildContext context, AppState state, String kind) {
  final items = state.snippets.where((s) => s.kind == kind).toList();
  final label = kind == 'hashtags' ? 'hashtag groups' : 'saved captions';
  return showModalBottomSheet<Snippet>(
    context: context,
    showDragHandle: true,
    builder: (context) => SafeArea(
      child: items.isEmpty
          ? Padding(
              padding: const EdgeInsets.fromLTRB(24, 8, 24, 32),
              child: Text(
                'No $label yet. Add some under Tools and they will appear here.',
                textAlign: TextAlign.center,
              ),
            )
          : ListView(
              shrinkWrap: true,
              children: [
                for (final s in items)
                  ListTile(
                    title: Text(s.title),
                    subtitle: Text(s.body, maxLines: 2, overflow: TextOverflow.ellipsis),
                    onTap: () => Navigator.pop(context, s),
                  ),
              ],
            ),
    ),
  );
}

class AiResult {
  AiResult({this.caption, this.hashtags});
  final String? caption;
  final String? hashtags;
}

/// AI caption ideas: describe the post, get three options and hashtags.
class AiSheet extends StatefulWidget {
  const AiSheet({super.key, required this.state, required this.draft, required this.platforms});

  final AppState state;
  final String draft;
  final List<String> platforms;

  @override
  State<AiSheet> createState() => _AiSheetState();
}

class _AiSheetState extends State<AiSheet> {
  static const _tones = ['Friendly', 'Professional', 'Playful', 'Bold', 'Calm'];

  final _brief = TextEditingController();
  String _tone = 'Friendly';
  String _business = '';
  bool _hashtags = true;
  bool _emoji = true;
  bool _busy = false;
  String? _error;
  CaptionIdeas? _ideas;

  @override
  void initState() {
    super.initState();
    Prefs.tone().then((t) {
      if (mounted && _tones.contains(t)) setState(() => _tone = t);
    });
    Prefs.business().then((b) {
      if (mounted) _business = b;
    });
  }

  @override
  void dispose() {
    _brief.dispose();
    super.dispose();
  }

  Future<void> _generate() async {
    if (_brief.text.trim().isEmpty && widget.draft.trim().isEmpty) {
      setState(() => _error = 'Say what the post is about first.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    Prefs.setTone(_tone);
    try {
      final ideas = await widget.state.repo.captionIdeas(
        brief: _brief.text,
        draft: widget.draft,
        tone: _tone,
        business: _business,
        platforms: widget.platforms,
        hashtags: _hashtags,
        emoji: _emoji,
      );
      if (mounted) setState(() => _ideas = ideas);
    } on AppException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final ideas = _ideas;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: ListView(
          shrinkWrap: true,
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          children: [
            Text('Caption ideas', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 12),
            TextField(
              controller: _brief,
              minLines: 2,
              maxLines: 4,
              decoration: InputDecoration(
                labelText: 'What is this post about?',
                hintText: widget.draft.trim().isEmpty
                    ? 'e.g. New winter menu starts Monday'
                    : 'Optional - your current caption is used as a starting point',
              ),
            ),
            const SizedBox(height: 12),
            Wrap(spacing: 8, runSpacing: 4, children: [
              for (final t in _tones)
                ChoiceChip(label: Text(t), selected: _tone == t, onSelected: (_) => setState(() => _tone = t)),
            ]),
            Row(children: [
              Expanded(
                child: CheckboxListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  controlAffinity: ListTileControlAffinity.leading,
                  title: const Text('Hashtags'),
                  value: _hashtags,
                  onChanged: (v) => setState(() => _hashtags = v ?? true),
                ),
              ),
              Expanded(
                child: CheckboxListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  controlAffinity: ListTileControlAffinity.leading,
                  title: const Text('Emoji'),
                  value: _emoji,
                  onChanged: (v) => setState(() => _emoji = v ?? true),
                ),
              ),
            ]),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
              ),
            FilledButton.icon(
              onPressed: _busy ? null : _generate,
              icon: _busy
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.auto_awesome),
              label: Text(_busy ? 'Writing...' : (ideas == null ? 'Write ideas' : 'Try again')),
            ),
            if (ideas != null) ...[
              const SizedBox(height: 16),
              for (final c in ideas.captions)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Card(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(14, 12, 8, 6),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(c),
                          Align(
                            alignment: Alignment.centerRight,
                            child: TextButton(
                              onPressed: () => Navigator.pop(context, AiResult(caption: c)),
                              child: const Text('Use this'),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              if (ideas.hashtags.isNotEmpty)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(14, 12, 8, 6),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(ideas.hashtags.join(' '), style: TextStyle(color: StatusColors(theme.colorScheme).info)),
                        Align(
                          alignment: Alignment.centerRight,
                          child: TextButton(
                            onPressed: () => Navigator.pop(context, AiResult(hashtags: ideas.hashtags.join(' '))),
                            child: const Text('Add hashtags'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }
}
