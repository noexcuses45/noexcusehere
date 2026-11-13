import 'dart:typed_data';

import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

import '../app_state.dart';
import '../models.dart';
import '../platforms.dart';
import '../theme.dart';

/// Round brand-coloured mark for a platform.
class PlatformBadge extends StatelessWidget {
  const PlatformBadge(this.platform, {super.key, this.size = 28, this.dimmed = false});

  final String platform;
  final double size;
  final bool dimmed;

  @override
  Widget build(BuildContext context) {
    final info = platformInfo(platform);
    final gradient = platform == 'instagram'
        ? const LinearGradient(
            begin: Alignment.bottomLeft,
            end: Alignment.topRight,
            colors: [Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5)],
          )
        : null;
    return Opacity(
      opacity: dimmed ? 0.4 : 1,
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          color: gradient == null ? info.color : null,
          gradient: gradient,
          borderRadius: BorderRadius.circular(size * 0.28),
        ),
        alignment: Alignment.center,
        child: info.icon != null
            ? Icon(info.icon, size: size * 0.64, color: Colors.white)
            : Text(
                info.glyph ?? '?',
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w800,
                  fontSize: size * 0.5,
                  height: 1,
                ),
              ),
      ),
    );
  }
}

class StatusStyle {
  StatusStyle(this.label, this.color, this.icon);
  final String label;
  final Color color;
  final IconData icon;
}

StatusStyle postStatusStyle(BuildContext context, Post post, DateTime now) {
  final c = StatusColors(Theme.of(context).colorScheme);
  if (post.reminderDue(now)) return StatusStyle('Your turn', c.warning, Icons.notifications_active_outlined);
  switch (post.status) {
    case 'draft':
      return StatusStyle('Draft', c.muted, Icons.edit_note);
    case 'scheduled':
      return StatusStyle('Scheduled', c.info, Icons.schedule);
    case 'publishing':
      return StatusStyle('Publishing', c.info, Icons.sync);
    case 'published':
      return StatusStyle('Published', c.success, Icons.check_circle_outline);
    case 'partial':
      return StatusStyle('Partly failed', c.danger, Icons.error_outline);
    default:
      return StatusStyle('Failed', c.danger, Icons.error_outline);
  }
}

StatusStyle targetStatusStyle(BuildContext context, PostTarget target, {required bool due}) {
  final c = StatusColors(Theme.of(context).colorScheme);
  if (target.isReminder && target.status == 'pending') {
    return due
        ? StatusStyle('Your turn to post', c.warning, Icons.notifications_active_outlined)
        : StatusStyle('Reminder set', c.info, Icons.notifications_none);
  }
  switch (target.status) {
    case 'pending':
      return StatusStyle('Waiting', c.info, Icons.schedule);
    case 'processing':
      return StatusStyle('Publishing', c.info, Icons.sync);
    case 'published':
      return StatusStyle(target.isReminder ? 'Posted by you' : 'Published', c.success, Icons.check_circle_outline);
    default:
      return StatusStyle('Failed', c.danger, Icons.error_outline);
  }
}

class StatusChip extends StatelessWidget {
  const StatusChip(this.style, {super.key});
  final StatusStyle style;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: style.color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(style.icon, size: 14, color: style.color),
          const SizedBox(width: 4),
          Text(
            style.label,
            style: Theme.of(context).textTheme.labelMedium?.copyWith(color: style.color, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}

/// Square preview of one media item: in-memory preview, stored thumbnail, or
/// a neutral tile when neither exists.
class MediaThumb extends StatelessWidget {
  const MediaThumb(this.media, {super.key, required this.state, this.size = 64, this.radius = 10, this.count = 1});

  final PostMedia? media;
  final AppState state;
  final double size;
  final double radius;
  final int count;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final m = media;

    Widget placeholder(IconData icon) => Container(
          color: scheme.surfaceContainerHighest,
          alignment: Alignment.center,
          child: Icon(icon, color: scheme.onSurfaceVariant, size: size * 0.42),
        );

    Widget child;
    if (m == null) {
      child = placeholder(Icons.notes);
    } else if (m.preview != null) {
      child = Image.memory(m.preview!, fit: BoxFit.cover, gaplessPlayback: true);
    } else if (m.thumbPath != null) {
      child = FutureBuilder<String?>(
        future: state.repo.mediaUrl(m.thumbPath!),
        builder: (context, snap) {
          final url = snap.data;
          if (url == null) return placeholder(m.isVideo ? Icons.videocam_outlined : Icons.image_outlined);
          return Image.network(
            url,
            fit: BoxFit.cover,
            errorBuilder: (_, _, _) => placeholder(m.isVideo ? Icons.videocam_outlined : Icons.image_outlined),
          );
        },
      );
    } else {
      child = placeholder(m.isVideo ? Icons.videocam_outlined : Icons.image_outlined);
    }

    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: SizedBox(
        width: size,
        height: size,
        child: Stack(
          fit: StackFit.expand,
          children: [
            child,
            if (m != null && m.isVideo)
              Align(
                alignment: Alignment.bottomLeft,
                child: Container(
                  margin: const EdgeInsets.all(4),
                  padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                  decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(6)),
                  child: Row(mainAxisSize: MainAxisSize.min, children: [
                    const Icon(Icons.play_arrow, size: 12, color: Colors.white),
                    if (m.durationMs != null)
                      Flexible(
                        child: Text(
                          formatDuration(m.durationMs!),
                          softWrap: false,
                          overflow: TextOverflow.clip,
                          style: const TextStyle(color: Colors.white, fontSize: 10),
                        ),
                      ),
                  ]),
                ),
              ),
            if (count > 1)
              Align(
                alignment: Alignment.topRight,
                child: Container(
                  margin: const EdgeInsets.all(4),
                  padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                  decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(6)),
                  child: Text('$count', style: const TextStyle(color: Colors.white, fontSize: 11)),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

String formatDuration(int ms) {
  final s = (ms / 1000).round();
  return '${s ~/ 60}:${(s % 60).toString().padLeft(2, '0')}';
}

String formatWhen(DateTime? when, DateTime now) {
  if (when == null) return 'No time set';
  final day = DateTime(when.year, when.month, when.day);
  final today = DateTime(now.year, now.month, now.day);
  final diff = day.difference(today).inDays;
  final time = DateFormat.jm().format(when);
  if (diff == 0) return 'Today, $time';
  if (diff == 1) return 'Tomorrow, $time';
  if (diff == -1) return 'Yesterday, $time';
  if (when.year == now.year) return '${DateFormat('EEE d MMM').format(when)}, $time';
  return '${DateFormat('d MMM y').format(when)}, $time';
}

/// One post in a list.
class PostCard extends StatelessWidget {
  const PostCard({super.key, required this.post, required this.state, required this.onTap});

  final Post post;
  final AppState state;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final now = DateTime.now();
    final style = postStatusStyle(context, post, now);
    final caption = post.caption.trim().isEmpty ? 'No caption' : post.caption.trim();
    final when = post.isDraft ? 'Not scheduled' : formatWhen(post.when, now);

    return Card(
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              MediaThumb(
                post.media.isEmpty ? null : post.media.first,
                state: state,
                size: 68,
                count: post.media.length,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [
                      Expanded(
                        child: Text(
                          when,
                          style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w700),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      StatusChip(style),
                    ]),
                    const SizedBox(height: 4),
                    Text(
                      caption,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: post.caption.trim().isEmpty ? theme.colorScheme.onSurfaceVariant : null,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Wrap(spacing: 6, runSpacing: 6, children: [
                      for (final t in post.targets)
                        Stack(clipBehavior: Clip.none, children: [
                          PlatformBadge(t.platform, size: 22),
                          if (t.status == 'failed')
                            Positioned(
                              right: -3,
                              top: -3,
                              child: Icon(Icons.error, size: 12, color: theme.colorScheme.error),
                            ),
                          if (t.isReminder)
                            Positioned(
                              right: -4,
                              bottom: -4,
                              child: Icon(Icons.notifications, size: 12, color: theme.colorScheme.onSurfaceVariant),
                            ),
                        ]),
                    ]),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.icon, required this.title, this.message, this.action});

  final IconData icon;
  final String title;
  final String? message;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 44, color: theme.colorScheme.onSurfaceVariant),
            const SizedBox(height: 12),
            Text(title, style: theme.textTheme.titleMedium, textAlign: TextAlign.center),
            if (message != null) ...[
              const SizedBox(height: 6),
              Text(
                message!,
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            ],
            if (action != null) ...[const SizedBox(height: 16), action!],
          ],
        ),
      ),
    );
  }
}

/// Avatar for a connected account: its picture with the platform mark on top.
class AccountAvatar extends StatelessWidget {
  const AccountAvatar(this.account, {super.key, this.size = 40});

  final SocialAccount account;
  final double size;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final initial = account.name.trim().isEmpty ? '?' : account.name.trim()[0].toUpperCase();
    return SizedBox(
      width: size + 6,
      height: size + 6,
      child: Stack(children: [
        CircleAvatar(
          radius: size / 2,
          backgroundColor: scheme.surfaceContainerHighest,
          foregroundImage: account.avatarUrl == null ? null : NetworkImage(account.avatarUrl!),
          onForegroundImageError: account.avatarUrl == null ? null : (_, _) {},
          child: Text(initial, style: TextStyle(color: scheme.onSurfaceVariant, fontWeight: FontWeight.w700)),
        ),
        Positioned(right: 0, bottom: 0, child: PlatformBadge(account.platform, size: size * 0.45)),
      ]),
    );
  }
}

/// Turns raw bytes into a tiny placeholder check (used by tests).
bool looksLikeJpeg(Uint8List bytes) => bytes.length > 3 && bytes[0] == 0xFF && bytes[1] == 0xD8;
