import 'package:flutter_test/flutter_test.dart';
import 'package:social_scheduler/app_state.dart';
import 'package:social_scheduler/models.dart';
import 'package:social_scheduler/platforms.dart';

PostMedia photo({int w = 1080, int h = 1350}) => PostMedia(kind: 'image', mimeType: 'image/jpeg', width: w, height: h);
PostMedia video({int seconds = 30, int mb = 20}) => PostMedia(
      kind: 'video',
      mimeType: 'video/mp4',
      width: 1080,
      height: 1920,
      durationMs: seconds * 1000,
      sizeBytes: mb * 1024 * 1024,
    );

List<Issue> check(PostTarget t, {String caption = 'Hello', String? link, List<PostMedia> media = const []}) =>
    validateTarget(target: t, caption: caption, linkUrl: link, media: media);

bool blocked(List<Issue> issues) => issues.any((i) => i.blocking);

void main() {
  group('validation', () {
    test('instagram needs media, and a reel needs exactly one video', () {
      final feed = PostTarget(platform: 'instagram', accountId: 'a', postType: 'feed');
      expect(blocked(check(feed)), isTrue);
      expect(blocked(check(feed, media: [photo()])), isFalse);

      final reel = PostTarget(platform: 'instagram', accountId: 'a', postType: 'reel');
      expect(blocked(check(reel, media: [photo()])), isTrue);
      expect(blocked(check(reel, media: [video()])), isFalse);
      expect(blocked(check(reel, media: [video(), video()])), isTrue);
    });

    test('instagram warns (without blocking) about extreme photo shapes', () {
      final feed = PostTarget(platform: 'instagram', accountId: 'a', postType: 'feed');
      final issues = check(feed, media: [photo(w: 3000, h: 1000)]);
      expect(issues, hasLength(1));
      expect(issues.first.blocking, isFalse);
    });

    test('instagram story holds one item; captions and hashtags are limited', () {
      final story = PostTarget(platform: 'instagram', accountId: 'a', postType: 'story');
      expect(blocked(check(story, media: [photo(), photo()])), isTrue);

      final feed = PostTarget(platform: 'instagram', accountId: 'a', postType: 'feed');
      expect(blocked(check(feed, caption: 'x' * 2201, media: [photo()])), isTrue);
      final tags = List.generate(31, (i) => '#tag$i').join(' ');
      expect(blocked(check(feed, caption: tags, media: [photo()])), isTrue);
    });

    test('facebook accepts text or a link alone, but not nothing', () {
      final fb = PostTarget(platform: 'facebook', accountId: 'a');
      expect(blocked(check(fb, caption: '')), isTrue);
      expect(blocked(check(fb, caption: '', link: 'https://example.com')), isFalse);
      expect(blocked(check(fb, caption: 'Hi')), isFalse);
      expect(blocked(check(fb, media: [video(), photo()])), isTrue);

      final reel = PostTarget(platform: 'facebook', accountId: 'a', postType: 'reel');
      expect(blocked(check(reel, media: [video(seconds: 120)])), isTrue);
      expect(blocked(check(reel, media: [video(seconds: 60)])), isFalse);
    });

    test('tiktok takes one video and direct posts need a privacy choice', () {
      final inbox = PostTarget(platform: 'tiktok', accountId: 'a', postType: 'inbox');
      expect(blocked(check(inbox, media: [photo()])), isTrue);
      expect(blocked(check(inbox, media: [video()])), isFalse);

      final direct = PostTarget(platform: 'tiktok', accountId: 'a', postType: 'direct');
      expect(blocked(check(direct, media: [video()])), isTrue);
      direct.options['privacy_level'] = 'SELF_ONLY';
      expect(blocked(check(direct, media: [video()])), isFalse);
    });

    test('youtube needs a video and a usable title', () {
      final yt = PostTarget(platform: 'youtube', accountId: 'a', postType: 'video');
      expect(blocked(check(yt, media: [photo()])), isTrue);
      expect(blocked(check(yt, caption: '', media: [video()])), isTrue);
      expect(blocked(check(yt, caption: 'A short title', media: [video()])), isFalse);
      // A long first line is cut by the server: advice only.
      final long = check(yt, caption: 'x' * 150, media: [video()]);
      expect(long, isNotEmpty);
      expect(blocked(long), isFalse);
      yt.options['title'] = 'y' * 101;
      expect(blocked(check(yt, media: [video()])), isTrue);
    });

    test('linkedin limits', () {
      final li = PostTarget(platform: 'linkedin', accountId: 'a');
      expect(blocked(check(li, caption: 'x' * 3001)), isTrue);
      expect(blocked(check(li, media: [video(mb: 600)])), isTrue);
      expect(blocked(check(li, media: [photo(), photo()])), isFalse);
    });

    test('reminders are never blocked, and a custom caption is what gets checked', () {
      final reminder = PostTarget(platform: 'tiktok', mode: 'reminder');
      expect(check(reminder, caption: 'x' * 9999), isEmpty);

      final li = PostTarget(platform: 'linkedin', accountId: 'a', captionOverride: 'x' * 3001);
      expect(blocked(check(li, caption: 'short')), isTrue);
    });
  });

  group('defaults', () {
    test('a single video defaults Instagram to a Reel and TikTok to the inbox', () {
      final ig = SocialAccount(id: '1', platform: 'instagram', name: 'x');
      expect(defaultTargetFor(ig, [video()]).postType, 'reel');
      expect(defaultTargetFor(ig, [photo()]).postType, 'feed');
      final tt = SocialAccount(id: '2', platform: 'tiktok', name: 'x');
      expect(defaultTargetFor(tt, [video()]).postType, 'inbox');
      final yt = SocialAccount(id: '3', platform: 'youtube', name: 'x');
      expect(defaultTargetFor(yt, [video()]).options['privacy_status'], 'public');
    });
  });

  group('posting times', () {
    final monday9 = PostingSlot(weekday: 1, minuteOfDay: 9 * 60);
    final wednesday17 = PostingSlot(weekday: 3, minuteOfDay: 17 * 60);

    test('picks the next slot after now', () {
      // Sunday 8 Nov 2026, noon.
      final from = DateTime(2026, 11, 8, 12);
      expect(nextFreeSlot([monday9, wednesday17], const [], from), DateTime(2026, 11, 9, 9));
    });

    test('skips a slot that already has a scheduled post', () {
      final from = DateTime(2026, 11, 8, 12);
      final taken = Post(id: 'p', status: 'scheduled', scheduledAt: DateTime(2026, 11, 9, 9));
      expect(nextFreeSlot([monday9, wednesday17], [taken], from), DateTime(2026, 11, 11, 17));
    });

    test('a slot only minutes away is not offered', () {
      final from = DateTime(2026, 11, 9, 8, 58);
      expect(nextFreeSlot([monday9], const [], from), DateTime(2026, 11, 16, 9));
    });

    test('falls back to tomorrow morning when no times are set', () {
      expect(nextFreeSlot(const [], const [], DateTime(2026, 11, 8, 12)), DateTime(2026, 11, 9, 9));
    });
  });

  group('post state', () {
    test('a due reminder asks for attention; a future one does not', () {
      final now = DateTime(2026, 11, 13, 10);
      Post p(DateTime at, String targetStatus) => Post(
            id: 'p',
            status: 'publishing',
            scheduledAt: at,
            targets: [PostTarget(platform: 'tiktok', mode: 'reminder', status: targetStatus)],
          );
      expect(p(DateTime(2026, 11, 13, 9), 'pending').reminderDue(now), isTrue);
      expect(p(DateTime(2026, 11, 13, 11), 'pending').reminderDue(now), isFalse);
      expect(p(DateTime(2026, 11, 13, 9), 'published').reminderDue(now), isFalse);
    });

    test('parses a post row as the server returns it, media in order', () {
      final post = Post.fromJson({
        'id': 'abc',
        'caption': 'Hi',
        'status': 'scheduled',
        'scheduled_at': '2026-11-14T02:30:00+00:00',
        'post_media': [
          {'kind': 'image', 'position': 1, 'storage_path': 'b.jpg', 'mime_type': 'image/jpeg'},
          {'kind': 'video', 'position': 0, 'storage_path': 'a.mp4', 'mime_type': 'video/mp4', 'duration_ms': 1500},
        ],
        'post_targets': [
          {'id': 't', 'platform': 'youtube', 'account_id': 'acc', 'post_type': 'video', 'options': {'title': 'T'}},
        ],
      });
      expect(post.media.map((m) => m.storagePath), ['a.mp4', 'b.jpg']);
      expect(post.targets.single.options['title'], 'T');
      expect(post.scheduledAt!.toUtc(), DateTime.utc(2026, 11, 14, 2, 30));
      expect(post.canEdit, isTrue);
    });
  });
}
