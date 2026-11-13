import 'dart:typed_data';

DateTime? _date(dynamic v) => v == null ? null : DateTime.parse(v as String).toLocal();
Map<String, dynamic> _map(dynamic v) => v is Map ? Map<String, dynamic>.from(v) : <String, dynamic>{};

class SocialAccount {
  SocialAccount({
    required this.id,
    required this.platform,
    required this.name,
    this.username,
    this.avatarUrl,
    this.status = 'active',
    this.statusDetail,
    this.meta = const {},
    this.tokenExpiresAt,
  });

  final String id;
  final String platform;
  final String name;
  final String? username;
  final String? avatarUrl;
  final String status;
  final String? statusDetail;
  final Map<String, dynamic> meta;
  final DateTime? tokenExpiresAt;

  bool get needsReconnect => status == 'needs_reconnect';

  /// Days until the connection itself runs out (null = does not expire).
  int? daysLeft(DateTime now) => tokenExpiresAt?.difference(now).inDays;

  String get handle => username == null || username!.isEmpty ? name : '@$username';

  factory SocialAccount.fromJson(Map<String, dynamic> j) => SocialAccount(
        id: j['id'] as String,
        platform: j['platform'] as String,
        name: (j['name'] as String?) ?? '',
        username: j['username'] as String?,
        avatarUrl: j['avatar_url'] as String?,
        status: (j['status'] as String?) ?? 'active',
        statusDetail: j['status_detail'] as String?,
        meta: _map(j['meta']),
        tokenExpiresAt: _date(j['token_expires_at']),
      );
}

class PostMedia {
  PostMedia({
    required this.kind,
    this.storagePath,
    this.thumbPath,
    required this.mimeType,
    this.sizeBytes = 0,
    this.width,
    this.height,
    this.durationMs,
    this.preview,
  });

  final String kind; // image | video
  final String? storagePath;
  final String? thumbPath;
  final String mimeType;
  final int sizeBytes;
  final int? width;
  final int? height;
  final int? durationMs;

  /// Small preview kept in memory for media picked in this session.
  final Uint8List? preview;

  bool get isVideo => kind == 'video';
  double? get aspect => (width != null && height != null && height! > 0) ? width! / height! : null;

  factory PostMedia.fromJson(Map<String, dynamic> j) => PostMedia(
        kind: j['kind'] as String,
        storagePath: j['storage_path'] as String?,
        thumbPath: j['thumb_path'] as String?,
        mimeType: (j['mime_type'] as String?) ?? 'application/octet-stream',
        sizeBytes: (j['size_bytes'] as num?)?.toInt() ?? 0,
        width: (j['width'] as num?)?.toInt(),
        height: (j['height'] as num?)?.toInt(),
        durationMs: (j['duration_ms'] as num?)?.toInt(),
      );

  Map<String, dynamic> toJson() => {
        'kind': kind,
        'storage_path': storagePath,
        'thumb_path': thumbPath,
        'mime_type': mimeType,
        'size_bytes': sizeBytes,
        'width': width,
        'height': height,
        'duration_ms': durationMs,
      };
}

class PostTarget {
  PostTarget({
    this.id,
    required this.platform,
    this.accountId,
    this.mode = 'auto',
    this.postType = 'feed',
    this.captionOverride,
    Map<String, dynamic>? options,
    this.status = 'pending',
    this.error,
    this.note,
    this.permalink,
    this.publishedAt,
  }) : options = options ?? {};

  final String? id;
  final String platform;
  final String? accountId;
  final String mode; // auto | reminder
  String postType;
  String? captionOverride;
  final Map<String, dynamic> options;
  final String status; // pending | processing | published | failed
  final String? error;
  final String? note;
  final String? permalink;
  final DateTime? publishedAt;

  bool get isReminder => mode == 'reminder';

  factory PostTarget.fromJson(Map<String, dynamic> j) => PostTarget(
        id: j['id'] as String?,
        platform: j['platform'] as String,
        accountId: j['account_id'] as String?,
        mode: (j['mode'] as String?) ?? 'auto',
        postType: (j['post_type'] as String?) ?? 'feed',
        captionOverride: j['caption_override'] as String?,
        options: _map(j['options']),
        status: (j['status'] as String?) ?? 'pending',
        error: j['error'] as String?,
        note: j['note'] as String?,
        permalink: j['permalink'] as String?,
        publishedAt: _date(j['published_at']),
      );

  Map<String, dynamic> toJson() => {
        'platform': platform,
        'account_id': accountId,
        'mode': mode,
        'post_type': postType,
        'caption_override': captionOverride,
        'options': options,
      };

  PostTarget copy() => PostTarget(
        platform: platform,
        accountId: accountId,
        mode: mode,
        postType: postType,
        captionOverride: captionOverride,
        options: Map<String, dynamic>.from(options),
      );
}

class Post {
  Post({
    required this.id,
    this.caption = '',
    this.linkUrl,
    this.status = 'draft',
    this.scheduledAt,
    this.publishedAt,
    this.createdAt,
    this.media = const [],
    this.targets = const [],
  });

  final String id;
  final String caption;
  final String? linkUrl;
  final String status; // draft | scheduled | publishing | published | partial | failed
  final DateTime? scheduledAt;
  final DateTime? publishedAt;
  final DateTime? createdAt;
  final List<PostMedia> media;
  final List<PostTarget> targets;

  bool get isDraft => status == 'draft';
  bool get canEdit => status == 'draft' || status == 'scheduled' || status == 'failed';

  /// A reminder whose time has come and that has not been ticked off yet.
  bool reminderDue(DateTime now) =>
      !isDraft &&
      scheduledAt != null &&
      !scheduledAt!.isAfter(now) &&
      targets.any((t) => t.isReminder && t.status == 'pending');

  bool get hasFailure => targets.any((t) => t.status == 'failed');

  /// The moment this post belongs to on the calendar.
  DateTime? get when => scheduledAt ?? publishedAt;

  factory Post.fromJson(Map<String, dynamic> j) {
    final media = ((j['post_media'] as List?) ?? const [])
        .map((m) => Map<String, dynamic>.from(m as Map))
        .toList()
      ..sort((a, b) => ((a['position'] as num?) ?? 0).compareTo((b['position'] as num?) ?? 0));
    return Post(
      id: j['id'] as String,
      caption: (j['caption'] as String?) ?? '',
      linkUrl: j['link_url'] as String?,
      status: (j['status'] as String?) ?? 'draft',
      scheduledAt: _date(j['scheduled_at']),
      publishedAt: _date(j['published_at']),
      createdAt: _date(j['created_at']),
      media: media.map(PostMedia.fromJson).toList(),
      targets: ((j['post_targets'] as List?) ?? const [])
          .map((t) => PostTarget.fromJson(Map<String, dynamic>.from(t as Map)))
          .toList(),
    );
  }
}

/// What the composer hands to the repository.
class PostDraft {
  PostDraft({
    this.id,
    required this.caption,
    this.linkUrl,
    required this.schedule,
    this.scheduledAt,
    required this.media,
    required this.targets,
  });

  final String? id;
  final String caption;
  final String? linkUrl;

  /// false = keep as a draft.
  final bool schedule;
  final DateTime? scheduledAt;
  final List<PostMedia> media;
  final List<PostTarget> targets;
}

class Snippet {
  Snippet({this.id, required this.kind, required this.title, required this.body});

  final String? id;
  final String kind; // hashtags | caption
  final String title;
  final String body;

  factory Snippet.fromJson(Map<String, dynamic> j) => Snippet(
        id: j['id'] as String?,
        kind: j['kind'] as String,
        title: (j['title'] as String?) ?? '',
        body: (j['body'] as String?) ?? '',
      );
}

class PostingSlot {
  PostingSlot({this.id, required this.weekday, required this.minuteOfDay});

  final String? id;
  final int weekday; // 1 = Monday
  final int minuteOfDay;

  factory PostingSlot.fromJson(Map<String, dynamic> j) => PostingSlot(
        id: j['id'] as String?,
        weekday: (j['weekday'] as num).toInt(),
        minuteOfDay: (j['minute_of_day'] as num).toInt(),
      );
}

class CredentialStatus {
  CredentialStatus({required this.platform, this.clientId, this.options = const {}});

  final String platform; // meta | tiktok | google | linkedin | anthropic
  final String? clientId;
  final Map<String, dynamic> options;

  factory CredentialStatus.fromJson(Map<String, dynamic> j) => CredentialStatus(
        platform: j['platform'] as String,
        clientId: j['client_id'] as String?,
        options: _map(j['options']),
      );
}

class CaptionIdeas {
  CaptionIdeas({required this.captions, required this.hashtags});
  final List<String> captions;
  final List<String> hashtags;
}

/// Thrown with a message that is safe to show to the user as-is.
class AppException implements Exception {
  AppException(this.message);
  final String message;
  @override
  String toString() => message;
}
