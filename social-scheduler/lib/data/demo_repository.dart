import 'dart:typed_data';

import 'package:image_picker/image_picker.dart';

import '../models.dart';
import 'repository.dart';

/// Sample data held in memory. Nothing here talks to the internet, so the
/// whole app can be explored (and tested) without a backend.
class DemoRepository implements Repository {
  DemoRepository({DateTime? now}) {
    final t = now ?? DateTime.now();
    DateTime at(int dayOffset, int hour, [int minute = 0]) =>
        DateTime(t.year, t.month, t.day + dayOffset, hour, minute);

    _accounts.addAll([
      SocialAccount(id: 'a-ig', platform: 'instagram', name: 'Harbour Cafe', username: 'harbourcafe'),
      SocialAccount(id: 'a-fb', platform: 'facebook', name: 'Harbour Cafe'),
      SocialAccount(id: 'a-tt', platform: 'tiktok', name: 'Harbour Cafe', username: 'harbourcafe'),
      SocialAccount(id: 'a-yt', platform: 'youtube', name: 'Harbour Cafe', username: 'harbourcafe'),
      SocialAccount(
        id: 'a-li',
        platform: 'linkedin',
        name: 'Sam Rivers',
        tokenExpiresAt: t.add(const Duration(days: 9)),
      ),
    ]);
    _credentials.addAll([
      CredentialStatus(platform: 'meta', clientId: '1234567890'),
      CredentialStatus(platform: 'tiktok', clientId: 'aw1demo'),
      CredentialStatus(platform: 'google', clientId: 'demo.apps.googleusercontent.com'),
      CredentialStatus(platform: 'linkedin', clientId: '86demo'),
    ]);

    PostMedia photo() => PostMedia(kind: 'image', mimeType: 'image/jpeg', width: 1080, height: 1350, sizeBytes: 800000);
    PostMedia video() => PostMedia(
        kind: 'video', mimeType: 'video/mp4', width: 1080, height: 1920, sizeBytes: 24000000, durationMs: 32000);

    _posts.addAll([
      Post(
        id: 'p1',
        caption: 'Winter menu starts Monday. Slow-cooked lamb shoulder, roast pumpkin soup and '
            'a sticky date pudding we have been testing all month.\n\n#wintermenu #harbourcafe #brunch',
        status: 'scheduled',
        scheduledAt: at(1, 7, 30),
        media: [photo(), photo()],
        targets: [
          PostTarget(id: 't1', platform: 'instagram', accountId: 'a-ig', postType: 'feed'),
          PostTarget(id: 't2', platform: 'facebook', accountId: 'a-fb', postType: 'feed'),
        ],
      ),
      Post(
        id: 'p2',
        caption: 'Behind the bar with Jo: how we pour a flat white.',
        status: 'scheduled',
        scheduledAt: at(2, 12, 0),
        media: [video()],
        targets: [
          PostTarget(id: 't3', platform: 'instagram', accountId: 'a-ig', postType: 'reel'),
          PostTarget(id: 't4', platform: 'tiktok', accountId: 'a-tt', postType: 'inbox'),
          PostTarget(
            id: 't5',
            platform: 'youtube',
            accountId: 'a-yt',
            postType: 'video',
            options: {'title': 'How we pour a flat white', 'privacy_status': 'public'},
          ),
        ],
      ),
      Post(
        id: 'p3',
        caption: 'We are hiring a weekend barista. Two years of experience, early starts, great team.',
        linkUrl: 'https://example.com/jobs',
        status: 'scheduled',
        scheduledAt: at(4, 9, 0),
        targets: [
          PostTarget(id: 't6', platform: 'linkedin', accountId: 'a-li'),
          PostTarget(id: 't7', platform: 'facebook', accountId: 'a-fb'),
        ],
      ),
      Post(
        id: 'p4',
        caption: 'Trending audio idea: morning rush in 15 seconds.',
        status: 'publishing',
        scheduledAt: at(0, 8, 0),
        media: [video()],
        targets: [PostTarget(id: 't8', platform: 'tiktok', mode: 'reminder', postType: 'direct')],
      ),
      Post(
        id: 'p5',
        caption: 'Thank you for a huge long weekend. Back to normal hours from tomorrow.',
        status: 'published',
        scheduledAt: at(-2, 17, 0),
        publishedAt: at(-2, 17, 0),
        media: [photo()],
        targets: [
          PostTarget(
            id: 't9',
            platform: 'instagram',
            accountId: 'a-ig',
            status: 'published',
            permalink: 'https://www.instagram.com/',
            publishedAt: at(-2, 17, 0),
          ),
          PostTarget(
            id: 't10',
            platform: 'facebook',
            accountId: 'a-fb',
            status: 'published',
            permalink: 'https://www.facebook.com/',
            publishedAt: at(-2, 17, 0),
          ),
        ],
      ),
      Post(
        id: 'p6',
        caption: 'New loyalty cards are in. Ten coffees and the next one is on us.',
        status: 'partial',
        scheduledAt: at(-1, 10, 0),
        publishedAt: at(-1, 10, 0),
        media: [photo()],
        targets: [
          PostTarget(
            id: 't11',
            platform: 'facebook',
            accountId: 'a-fb',
            status: 'published',
            permalink: 'https://www.facebook.com/',
            publishedAt: at(-1, 10, 0),
          ),
          PostTarget(
            id: 't12',
            platform: 'instagram',
            accountId: 'a-ig',
            status: 'failed',
            error: 'Instagram rejected the media. The photo is wider than 1.91:1 - crop it and retry.',
          ),
        ],
      ),
      Post(
        id: 'p7',
        caption: 'Idea: staff picks for the new pastry cabinet.',
        status: 'draft',
        createdAt: at(-3, 14, 0),
        media: [photo()],
        targets: [PostTarget(id: 't13', platform: 'instagram', accountId: 'a-ig')],
      ),
    ]);

    _snippets.addAll([
      Snippet(id: 's1', kind: 'hashtags', title: 'Everyday', body: '#harbourcafe #coffee #brunch #localeats #supportlocal'),
      Snippet(id: 's2', kind: 'hashtags', title: 'Hiring', body: '#hiring #hospitalityjobs #barista'),
      Snippet(
        id: 's3',
        kind: 'caption',
        title: 'Opening hours sign-off',
        body: 'Open 6:30 to 3, seven days. Kitchen closes at 2:30.',
      ),
    ]);
    _slots.addAll([
      PostingSlot(id: 'k1', weekday: 1, minuteOfDay: 7 * 60 + 30),
      PostingSlot(id: 'k2', weekday: 3, minuteOfDay: 12 * 60),
      PostingSlot(id: 'k3', weekday: 5, minuteOfDay: 17 * 60),
      PostingSlot(id: 'k4', weekday: 6, minuteOfDay: 9 * 60),
    ]);
  }

  final _accounts = <SocialAccount>[];
  final _credentials = <CredentialStatus>[];
  final _posts = <Post>[];
  final _snippets = <Snippet>[];
  final _slots = <PostingSlot>[];
  int _seq = 100;

  String _id(String prefix) => '$prefix${_seq++}';

  @override
  bool get isDemo => true;

  @override
  String? get userEmail => 'demo@example.com';

  @override
  String get callbackUrl => 'https://your-project.supabase.co/functions/v1/scheduler/oauth/callback';

  @override
  Future<List<SocialAccount>> accounts() async => List.of(_accounts);

  @override
  Future<void> disconnectAccount(String id) async => _accounts.removeWhere((a) => a.id == id);

  @override
  Future<List<CredentialStatus>> credentials() async => List.of(_credentials);

  @override
  Future<void> saveCredentials(String key, String clientId, String secret, Map<String, dynamic> options) async {
    _credentials.removeWhere((c) => c.platform == key);
    _credentials.add(CredentialStatus(platform: key, clientId: clientId, options: options));
  }

  @override
  Future<void> clearCredentials(String key) async => _credentials.removeWhere((c) => c.platform == key);

  @override
  Future<String> startConnect(String credentialKey) async =>
      throw AppException('Connecting real accounts is switched off in the demo.');

  @override
  Future<List<Post>> posts() async => List.of(_posts);

  @override
  Future<String> savePost(PostDraft draft) async {
    final id = draft.id ?? _id('p');
    _posts.removeWhere((p) => p.id == id);
    _posts.add(Post(
      id: id,
      caption: draft.caption,
      linkUrl: draft.linkUrl,
      status: draft.schedule ? 'scheduled' : 'draft',
      scheduledAt: draft.scheduledAt,
      createdAt: DateTime.now(),
      media: draft.media,
      targets: [
        for (final t in draft.targets)
          PostTarget(
            id: _id('t'),
            platform: t.platform,
            accountId: t.accountId,
            mode: t.mode,
            postType: t.postType,
            captionOverride: t.captionOverride,
            options: t.options,
          ),
      ],
    ));
    return id;
  }

  @override
  Future<void> deletePost(String id) async => _posts.removeWhere((p) => p.id == id);

  Post _withTargets(Post p, List<PostTarget> targets, String status) => Post(
        id: p.id,
        caption: p.caption,
        linkUrl: p.linkUrl,
        status: status,
        scheduledAt: p.scheduledAt,
        publishedAt: status == 'published' ? DateTime.now() : p.publishedAt,
        createdAt: p.createdAt,
        media: p.media,
        targets: targets,
      );

  @override
  Future<void> retryPost(String id) async {
    final i = _posts.indexWhere((p) => p.id == id);
    if (i < 0) return;
    final p = _posts[i];
    _posts[i] = _withTargets(
      p,
      [
        for (final t in p.targets)
          t.status == 'failed'
              ? PostTarget(
                  id: t.id,
                  platform: t.platform,
                  accountId: t.accountId,
                  mode: t.mode,
                  postType: t.postType,
                  options: t.options,
                  status: 'published',
                  publishedAt: DateTime.now(),
                )
              : t,
      ],
      'published',
    );
  }

  @override
  Future<void> markReminderDone(String targetId) async {
    final i = _posts.indexWhere((p) => p.targets.any((t) => t.id == targetId));
    if (i < 0) return;
    final p = _posts[i];
    final targets = [
      for (final t in p.targets)
        t.id == targetId
            ? PostTarget(
                id: t.id,
                platform: t.platform,
                accountId: t.accountId,
                mode: t.mode,
                postType: t.postType,
                options: t.options,
                status: 'published',
                publishedAt: DateTime.now(),
              )
            : t,
    ];
    final allDone = targets.every((t) => t.status == 'published');
    _posts[i] = _withTargets(p, targets, allDone ? 'published' : p.status);
  }

  @override
  Future<void> publishNow() async {}

  @override
  Future<void> uploadBytes(String path, Uint8List bytes, String contentType) async {}

  @override
  Future<void> uploadFile(String path, XFile file, String contentType) async {}

  @override
  Future<String?> mediaUrl(String path) async => null;

  @override
  String newMediaPath(String extension) => 'demo/${_id('m')}.$extension';

  @override
  Future<List<Snippet>> snippets() async => List.of(_snippets);

  @override
  Future<void> saveSnippet(Snippet s) async {
    if (s.id != null) _snippets.removeWhere((x) => x.id == s.id);
    _snippets.add(Snippet(id: s.id ?? _id('s'), kind: s.kind, title: s.title, body: s.body));
  }

  @override
  Future<void> deleteSnippet(String id) async => _snippets.removeWhere((s) => s.id == id);

  @override
  Future<List<PostingSlot>> slots() async => List.of(_slots);

  @override
  Future<void> addSlot(int weekday, int minuteOfDay) async {
    if (_slots.any((s) => s.weekday == weekday && s.minuteOfDay == minuteOfDay)) return;
    _slots.add(PostingSlot(id: _id('k'), weekday: weekday, minuteOfDay: minuteOfDay));
  }

  @override
  Future<void> deleteSlot(String id) async => _slots.removeWhere((s) => s.id == id);

  @override
  Future<CaptionIdeas> captionIdeas({
    required String brief,
    String? draft,
    String? tone,
    String? business,
    required List<String> platforms,
    bool hashtags = true,
    bool emoji = true,
  }) async {
    final topic = brief.trim().isEmpty ? 'your post' : brief.trim();
    return CaptionIdeas(
      captions: [
        'Sample idea (demo): $topic',
        'Sample idea (demo): Here is the story behind $topic, and why we think you will like it.',
        'Sample idea (demo): $topic. Come and see for yourself this week.',
      ],
      hashtags: hashtags ? ['#sample', '#demo', '#localbusiness'] : [],
    );
  }

  @override
  Future<void> signOut() async {}
}
