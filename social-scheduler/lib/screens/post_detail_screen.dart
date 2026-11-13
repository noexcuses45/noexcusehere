import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:material_ui/material_ui.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../app_state.dart';
import '../models.dart';
import '../platforms.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'composer_screen.dart';

/// One post: what it is, where it went, and what (if anything) went wrong.
class PostDetailScreen extends StatefulWidget {
  const PostDetailScreen({super.key, required this.state, required this.postId});

  final AppState state;
  final String postId;

  @override
  State<PostDetailScreen> createState() => _PostDetailScreenState();
}

class _PostDetailScreenState extends State<PostDetailScreen> {
  bool _busy = false;

  AppState get state => widget.state;

  Future<void> _run(Future<void> Function() action, {String? done}) async {
    setState(() => _busy = true);
    try {
      await action();
      await state.refreshPosts();
      if (mounted && done != null) showMessage(context, done);
    } on AppException catch (e) {
      if (mounted) showMessage(context, e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete(Post post) async {
    final live = post.targets.any((t) => t.status == 'published' && !t.isReminder);
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete this post?'),
        content: Text(live
            ? 'It is removed from this app only. What has already been published stays on the social networks.'
            : 'It will not be published.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Delete')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final navigator = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      await state.repo.deletePost(post.id);
      await state.refreshPosts();
      navigator.pop();
      messenger.showSnackBar(const SnackBar(content: Text('Post deleted.')));
    } on AppException catch (e) {
      if (mounted) {
        setState(() => _busy = false);
        showMessage(context, e.message);
      }
    }
  }

  void _edit(Post post, {bool duplicate = false}) {
    Navigator.of(context).push(MaterialPageRoute(
      fullscreenDialog: true,
      builder: (_) => ComposerScreen(state: state, existing: post, duplicate: duplicate),
    ));
  }

  Future<void> _copyCaption(Post post, PostTarget target) async {
    await Clipboard.setData(ClipboardData(text: target.captionOverride ?? post.caption));
    if (mounted) showMessage(context, 'Caption copied. Paste it in the app you post from.');
  }

  /// Hands the media to Android's share sheet so it can be opened in
  /// Instagram, TikTok or any other app, with the caption on the clipboard.
  Future<void> _share(Post post, PostTarget target) async {
    final caption = target.captionOverride ?? post.caption;
    await Clipboard.setData(ClipboardData(text: caption));
    setState(() => _busy = true);
    try {
      final files = <XFile>[];
      if (!kIsWeb) {
        for (var i = 0; i < post.media.length; i++) {
          final m = post.media[i];
          if (m.storagePath == null) continue;
          final url = await state.repo.mediaUrl(m.storagePath!);
          if (url == null) continue;
          final res = await http.get(Uri.parse(url));
          if (res.statusCode != 200) continue;
          final ext = m.storagePath!.split('.').last;
          files.add(XFile.fromData(res.bodyBytes, mimeType: m.mimeType, name: 'post-${i + 1}.$ext'));
        }
      }
      if (files.isEmpty) {
        await SharePlus.instance.share(ShareParams(text: caption));
      } else {
        await SharePlus.instance.share(ShareParams(files: files, text: caption));
      }
      if (mounted) showMessage(context, 'Caption copied to the clipboard too.');
    } catch (_) {
      if (mounted) showMessage(context, 'Could not open the share sheet. The caption is on your clipboard.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        final post = state.post(widget.postId);
        if (post == null) {
          return Scaffold(
            appBar: AppBar(),
            body: const EmptyState(icon: Icons.search_off, title: 'This post no longer exists'),
          );
        }
        final theme = Theme.of(context);
        final scheme = theme.colorScheme;
        final now = DateTime.now();
        final style = postStatusStyle(context, post, now);
        final due = post.scheduledAt != null && !post.scheduledAt!.isAfter(now);

        return Scaffold(
          appBar: AppBar(
            title: const Text('Post'),
            actions: [
              if (post.canEdit) IconButton(tooltip: 'Edit', icon: const Icon(Icons.edit_outlined), onPressed: () => _edit(post)),
              PopupMenuButton<String>(
                onSelected: (v) {
                  if (v == 'duplicate') _edit(post, duplicate: true);
                  if (v == 'delete') _delete(post);
                },
                itemBuilder: (context) => const [
                  PopupMenuItem(value: 'duplicate', child: Text('Duplicate')),
                  PopupMenuItem(value: 'delete', child: Text('Delete')),
                ],
              ),
            ],
          ),
          body: RefreshIndicator(
            onRefresh: state.refreshPosts,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
              children: [
                if (_busy) const LinearProgressIndicator(),
                Row(children: [
                  StatusChip(style),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      post.isDraft ? 'Not scheduled' : formatWhen(post.when, now),
                      style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                    ),
                  ),
                ]),
                const SizedBox(height: 14),
                if (post.media.isNotEmpty) ...[
                  SizedBox(
                    height: 120,
                    child: ListView.separated(
                      scrollDirection: Axis.horizontal,
                      itemCount: post.media.length,
                      separatorBuilder: (_, _) => const SizedBox(width: 8),
                      itemBuilder: (context, i) => MediaThumb(post.media[i], state: state, size: 120, radius: 14),
                    ),
                  ),
                  const SizedBox(height: 14),
                ],
                SelectableText(
                  post.caption.trim().isEmpty ? 'No caption' : post.caption,
                  style: theme.textTheme.bodyLarge?.copyWith(
                    color: post.caption.trim().isEmpty ? scheme.onSurfaceVariant : null,
                  ),
                ),
                if ((post.linkUrl ?? '').isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Row(children: [
                    Icon(Icons.link, size: 16, color: scheme.primary),
                    const SizedBox(width: 6),
                    Expanded(child: Text(post.linkUrl!, style: TextStyle(color: scheme.primary))),
                  ]),
                ],
                const SizedBox(height: 20),
                Text('Where it goes', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                if (post.targets.isEmpty)
                  Text('No destinations chosen yet.', style: TextStyle(color: scheme.onSurfaceVariant))
                else
                  for (final target in post.targets)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: _TargetCard(
                        post: post,
                        target: target,
                        account: state.account(target.accountId),
                        due: due,
                        busy: _busy,
                        onCopy: () => _copyCaption(post, target),
                        onShare: () => _share(post, target),
                        onDone: () => _run(() => state.repo.markReminderDone(target.id!), done: 'Marked as posted.'),
                      ),
                    ),
                if (post.hasFailure) ...[
                  const SizedBox(height: 8),
                  FilledButton.icon(
                    onPressed: _busy
                        ? null
                        : () => _run(() => state.repo.retryPost(post.id), done: 'Trying again now.'),
                    icon: const Icon(Icons.refresh),
                    label: const Text('Retry failed'),
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }
}

class _TargetCard extends StatelessWidget {
  const _TargetCard({
    required this.post,
    required this.target,
    required this.account,
    required this.due,
    required this.busy,
    required this.onCopy,
    required this.onShare,
    required this.onDone,
  });

  final Post post;
  final PostTarget target;
  final SocialAccount? account;
  final bool due;
  final bool busy;
  final VoidCallback onCopy;
  final VoidCallback onShare;
  final VoidCallback onDone;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final info = platformInfo(target.platform);
    final style = targetStatusStyle(context, target, due: due && !post.isDraft);
    final typeLabel = info.postTypes.length > 1
        ? info.postTypes.firstWhere((t) => t.key == target.postType, orElse: () => info.postTypes.first).label
        : null;
    final who = target.isReminder
        ? 'You post it yourself'
        : (account?.name ?? 'Account disconnected');

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              PlatformBadge(target.platform, size: 30),
              const SizedBox(width: 10),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(
                    target.isReminder || typeLabel == null ? info.label : '${info.label} - $typeLabel',
                    style: theme.textTheme.titleSmall,
                  ),
                  Text(who, style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
                ]),
              ),
              if (!post.isDraft) StatusChip(style),
            ]),
            if (target.captionOverride != null) ...[
              const SizedBox(height: 8),
              Text(
                'Custom caption: ${target.captionOverride}',
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall,
              ),
            ],
            if (target.error != null) ...[
              const SizedBox(height: 8),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(color: scheme.errorContainer, borderRadius: BorderRadius.circular(10)),
                child: SelectableText(target.error!, style: TextStyle(color: scheme.onErrorContainer)),
              ),
            ],
            if (target.note != null) ...[
              const SizedBox(height: 8),
              Text(target.note!, style: theme.textTheme.bodySmall?.copyWith(color: StatusColors(scheme).warning)),
            ],
            if (target.permalink != null)
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: () => launchUrl(Uri.parse(target.permalink!), mode: LaunchMode.externalApplication),
                  icon: const Icon(Icons.open_in_new, size: 16),
                  label: const Text('View post'),
                ),
              ),
            if (target.isReminder && target.status == 'pending' && !post.isDraft)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Wrap(spacing: 8, runSpacing: 4, children: [
                  OutlinedButton.icon(
                    onPressed: busy ? null : onShare,
                    icon: const Icon(Icons.ios_share, size: 18),
                    label: const Text('Share to app'),
                  ),
                  OutlinedButton.icon(
                    onPressed: onCopy,
                    icon: const Icon(Icons.copy, size: 18),
                    label: const Text('Copy caption'),
                  ),
                  FilledButton.icon(
                    onPressed: busy ? null : onDone,
                    icon: const Icon(Icons.check, size: 18),
                    label: const Text('I posted it'),
                  ),
                ]),
              ),
          ],
        ),
      ),
    );
  }
}
