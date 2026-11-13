import 'package:material_ui/material_ui.dart';

import 'models.dart';

/// A way of posting to a platform (Instagram: feed / reel / story, ...).
class PostTypeOption {
  const PostTypeOption(this.key, this.label, this.hint);
  final String key;
  final String label;
  final String hint;
}

class PlatformInfo {
  const PlatformInfo({
    required this.key,
    required this.label,
    required this.color,
    required this.captionLimit,
    required this.postTypes,
    required this.credentialKey,
    this.icon,
    this.glyph,
  });

  final String key;
  final String label;
  final Color color;
  final IconData? icon;

  /// Short text mark used when there is no icon (LinkedIn's "in").
  final String? glyph;
  final int captionLimit;
  final List<PostTypeOption> postTypes;

  /// Which developer-app credentials connect this platform.
  final String credentialKey;
}

const kPlatforms = <String, PlatformInfo>{
  'instagram': PlatformInfo(
    key: 'instagram',
    label: 'Instagram',
    color: Color(0xFFD62976),
    icon: Icons.camera_alt_outlined,
    captionLimit: 2200,
    credentialKey: 'meta',
    postTypes: [
      PostTypeOption('feed', 'Post', 'Photo, carousel or video in the feed'),
      PostTypeOption('reel', 'Reel', 'One vertical video'),
      PostTypeOption('story', 'Story', 'One photo or video, gone in 24 h'),
    ],
  ),
  'facebook': PlatformInfo(
    key: 'facebook',
    label: 'Facebook',
    color: Color(0xFF1877F2),
    icon: Icons.facebook,
    captionLimit: 5000,
    credentialKey: 'meta',
    postTypes: [
      PostTypeOption('feed', 'Post', 'Text, link, photos or a video'),
      PostTypeOption('reel', 'Reel', 'One vertical video, 3-90 seconds'),
    ],
  ),
  'tiktok': PlatformInfo(
    key: 'tiktok',
    label: 'TikTok',
    color: Color(0xFF111111),
    icon: Icons.tiktok,
    captionLimit: 2200,
    credentialKey: 'tiktok',
    postTypes: [
      PostTypeOption('inbox', 'Send to inbox', 'Arrives in TikTok as a draft; you tap Post'),
      PostTypeOption('direct', 'Post directly', 'Needs a TikTok-approved developer app'),
    ],
  ),
  'youtube': PlatformInfo(
    key: 'youtube',
    label: 'YouTube',
    color: Color(0xFFFF0000),
    icon: Icons.play_arrow_rounded,
    captionLimit: 4900,
    credentialKey: 'google',
    postTypes: [
      PostTypeOption('video', 'Video', 'Vertical videos up to 3 minutes become Shorts'),
    ],
  ),
  'linkedin': PlatformInfo(
    key: 'linkedin',
    label: 'LinkedIn',
    color: Color(0xFF0A66C2),
    glyph: 'in',
    captionLimit: 3000,
    credentialKey: 'linkedin',
    postTypes: [
      PostTypeOption('feed', 'Post', 'Text, link, photos or a video'),
    ],
  ),
  'other': PlatformInfo(
    key: 'other',
    label: 'Another app',
    color: Color(0xFF5F6368),
    icon: Icons.share_outlined,
    captionLimit: 100000,
    credentialKey: '',
    postTypes: [PostTypeOption('feed', 'Post', '')],
  ),
};

/// Order platforms appear in throughout the app.
const kPlatformOrder = ['instagram', 'facebook', 'tiktok', 'youtube', 'linkedin'];

PlatformInfo platformInfo(String key) => kPlatforms[key] ?? kPlatforms['other']!;

/// Developer-app credential groups, in the order shown on the Accounts tab.
class CredentialGroup {
  const CredentialGroup(this.key, this.label, this.platforms);
  final String key;
  final String label;
  final List<String> platforms;
}

const kCredentialGroups = [
  CredentialGroup('meta', 'Facebook & Instagram', ['instagram', 'facebook']),
  CredentialGroup('tiktok', 'TikTok', ['tiktok']),
  CredentialGroup('google', 'YouTube', ['youtube']),
  CredentialGroup('linkedin', 'LinkedIn', ['linkedin']),
];

class Issue {
  const Issue(this.message, {this.blocking = true});
  final String message;

  /// Blocking issues stop the post from being scheduled; the rest are advice.
  final bool blocking;
}

int _hashtagCount(String text) => RegExp(r'(^|\s)#[\p{L}\p{N}_]+', unicode: true).allMatches(text).length;

String _mb(int bytes) => '${(bytes / (1024 * 1024)).toStringAsFixed(0)} MB';

/// Checks one destination against that platform's rules, so problems show up
/// in the composer instead of as a failed post later.
List<Issue> validateTarget({
  required PostTarget target,
  required String caption,
  required String? linkUrl,
  required List<PostMedia> media,
}) {
  final info = platformInfo(target.platform);
  final text = (target.captionOverride?.trim().isNotEmpty ?? false) ? target.captionOverride! : caption;
  final images = media.where((m) => !m.isVideo).toList();
  final videos = media.where((m) => m.isVideo).toList();
  final issues = <Issue>[];
  final name = info.label;

  if (target.isReminder) {
    return issues; // You post these by hand; nothing to enforce.
  }

  if (text.length > info.captionLimit) {
    issues.add(Issue('$name captions can be at most ${info.captionLimit} characters (this is ${text.length}).'));
  }

  int? seconds(PostMedia m) => m.durationMs == null ? null : (m.durationMs! / 1000).round();

  switch (target.platform) {
    case 'instagram':
      if (media.isEmpty) {
        issues.add(const Issue('Instagram needs a photo or a video.'));
        break;
      }
      if (_hashtagCount(text) > 30) {
        issues.add(const Issue('Instagram allows at most 30 hashtags.'));
      }
      if (target.postType == 'story') {
        if (media.length > 1) issues.add(const Issue('An Instagram story holds one photo or video.'));
        final s = videos.isEmpty ? null : seconds(videos.first);
        if (s != null && s > 60) issues.add(const Issue('Instagram story videos can be at most 60 seconds.'));
      } else if (target.postType == 'reel') {
        if (videos.length != 1 || images.isNotEmpty) {
          issues.add(const Issue('An Instagram Reel is exactly one video.'));
        }
      } else {
        if (media.length > 20) issues.add(const Issue('Instagram carousels hold at most 20 items.'));
        for (final m in images) {
          final a = m.aspect;
          if (a != null && (a < 0.79 || a > 1.92)) {
            issues.add(const Issue(
              'Instagram may reject photos taller than 4:5 or wider than 1.91:1. Crop it first to be safe.',
              blocking: false,
            ));
            break;
          }
        }
      }
      for (final v in videos) {
        final s = seconds(v);
        if (s != null && s < 3) issues.add(const Issue('Instagram videos must be at least 3 seconds.'));
        if (s != null && s > 15 * 60) issues.add(const Issue('Instagram videos can be at most 15 minutes.'));
        if (v.sizeBytes > 300 * 1024 * 1024) {
          issues.add(Issue('Instagram videos can be at most 300 MB (this is ${_mb(v.sizeBytes)}).'));
        }
      }
    case 'facebook':
      if (media.isEmpty && text.trim().isEmpty && (linkUrl ?? '').trim().isEmpty) {
        issues.add(const Issue('A Facebook post needs text, a link, a photo or a video.'));
      }
      if (videos.isNotEmpty && media.length > 1) {
        issues.add(const Issue('A Facebook video post holds one video and no photos.'));
      }
      if (target.postType == 'reel') {
        if (videos.length != 1) {
          issues.add(const Issue('A Facebook Reel is exactly one video.'));
        } else {
          final s = seconds(videos.first);
          if (s != null && (s < 3 || s > 90)) {
            issues.add(const Issue('Facebook Reels must be 3 to 90 seconds long.'));
          }
        }
      }
    case 'tiktok':
      if (videos.length != 1 || images.isNotEmpty) {
        issues.add(const Issue(
          'TikTok auto-posting takes exactly one video. For photo posts, use a reminder instead.',
        ));
      } else {
        final s = seconds(videos.first);
        if (s != null && s > 10 * 60) issues.add(const Issue('TikTok videos can be at most 10 minutes.'));
      }
      if (target.postType == 'direct' && (target.options['privacy_level'] ?? '') == '') {
        issues.add(const Issue('Choose who can see the TikTok post.'));
      }
    case 'youtube':
      if (videos.length != 1 || images.isNotEmpty) {
        issues.add(const Issue('A YouTube post is exactly one video.'));
      }
      final title = (target.options['title'] as String?)?.trim() ?? '';
      final firstLine = text.split('\n').firstWhere((l) => l.trim().isNotEmpty, orElse: () => '').trim();
      if (title.isEmpty && firstLine.isEmpty) {
        issues.add(const Issue('Give the YouTube video a title.'));
      } else if ((title.isEmpty ? firstLine : title).length > 100) {
        issues.add(Issue(
          title.isEmpty
              ? 'The first line of the caption becomes the YouTube title and will be cut at 100 characters. Add a shorter title.'
              : 'YouTube titles can be at most 100 characters.',
          blocking: title.isNotEmpty,
        ));
      }
    case 'linkedin':
      if (media.isEmpty && text.trim().isEmpty && (linkUrl ?? '').trim().isEmpty) {
        issues.add(const Issue('A LinkedIn post needs text, a link, a photo or a video.'));
      }
      if (videos.isNotEmpty && media.length > 1) {
        issues.add(const Issue('A LinkedIn video post holds one video and no photos.'));
      }
      if (images.length > 20) issues.add(const Issue('LinkedIn posts hold at most 20 photos.'));
      for (final v in videos) {
        final s = seconds(v);
        if (s != null && (s < 3 || s > 30 * 60)) {
          issues.add(const Issue('LinkedIn videos must be between 3 seconds and 30 minutes.'));
        }
        if (v.sizeBytes > 500 * 1024 * 1024) {
          issues.add(Issue('LinkedIn videos can be at most 500 MB (this is ${_mb(v.sizeBytes)}).'));
        }
      }
  }
  return issues;
}

/// Sensible starting options when an account is ticked in the composer.
PostTarget defaultTargetFor(SocialAccount account, List<PostMedia> media) {
  final onlyVideo = media.length == 1 && media.first.isVideo;
  switch (account.platform) {
    case 'instagram':
      return PostTarget(platform: 'instagram', accountId: account.id, postType: onlyVideo ? 'reel' : 'feed');
    case 'tiktok':
      return PostTarget(platform: 'tiktok', accountId: account.id, postType: 'inbox');
    case 'youtube':
      return PostTarget(
        platform: 'youtube',
        accountId: account.id,
        postType: 'video',
        options: {'privacy_status': 'public', 'made_for_kids': false},
      );
    default:
      return PostTarget(platform: account.platform, accountId: account.id, postType: 'feed');
  }
}
