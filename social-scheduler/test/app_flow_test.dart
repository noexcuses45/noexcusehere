import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:social_scheduler/data/demo_repository.dart';
import 'package:social_scheduler/main.dart';

/// Drives the real screens against the in-memory sample data, on a phone-sized
/// surface, so layout errors and broken flows fail the build.
void main() {
  late DemoRepository repo;

  Future<void> open(WidgetTester tester, {Size size = const Size(412, 915)}) async {
    SharedPreferences.setMockInitialValues({});
    tester.view.physicalSize = size * 2.0;
    tester.view.devicePixelRatio = 2.0;
    addTearDown(tester.view.reset);
    repo = DemoRepository();
    await tester.pumpWidget(SchedulerApp(initialRepository: repo));
    await tester.pumpAndSettle();
  }

  testWidgets('calendar opens with today selected and shows the day\'s posts', (tester) async {
    await open(tester);
    expect(find.text('Today'), findsWidgets);
    expect(find.text('New post'), findsOneWidget);
    // The demo has a reminder due today.
    expect(find.text('Your turn'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('every tab renders on a small phone without layout errors', (tester) async {
    await open(tester, size: const Size(360, 640));
    for (final tab in ['Posts', 'Accounts', 'Tools', 'Calendar']) {
      await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text(tab)));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull, reason: '$tab tab');
    }
  });

  testWidgets('posts tab: upcoming, to do, drafts, published', (tester) async {
    await open(tester);
    await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text('Posts')));
    await tester.pumpAndSettle();

    expect(find.textContaining('Winter menu starts Monday'), findsOneWidget);

    await tester.tap(find.text('To do'));
    await tester.pumpAndSettle();
    expect(find.textContaining('New loyalty cards'), findsOneWidget); // partly failed
    expect(find.textContaining('Trending audio idea'), findsOneWidget); // reminder due

    await tester.tap(find.widgetWithText(Tab, 'Drafts'));
    await tester.pumpAndSettle();
    expect(find.textContaining('staff picks'), findsOneWidget);

    await tester.tap(find.widgetWithText(Tab, 'Published'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Thank you for a huge long weekend'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a failed post shows the reason and can be retried', (tester) async {
    await open(tester);
    await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text('Posts')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('To do'));
    await tester.pumpAndSettle();
    await tester.tap(find.textContaining('New loyalty cards'));
    await tester.pumpAndSettle();

    expect(find.textContaining('wider than 1.91:1'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Retry failed'), 200, scrollable: find.byType(Scrollable).last);
    await tester.tap(find.text('Retry failed'));
    await tester.pumpAndSettle();

    final post = (await repo.posts()).firstWhere((p) => p.id == 'p6');
    expect(post.status, 'published');
    expect(find.text('Retry failed'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a due reminder can be ticked off', (tester) async {
    await open(tester);
    await tester.tap(find.textContaining('Trending audio idea'));
    await tester.pumpAndSettle();
    expect(find.text('Your turn to post'), findsOneWidget);
    expect(find.text('Share to app'), findsOneWidget);

    await tester.tap(find.text('I posted it'));
    await tester.pumpAndSettle();
    final post = (await repo.posts()).firstWhere((p) => p.id == 'p4');
    expect(post.targets.single.status, 'published');
    expect(find.text('Posted by you'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('composing: validation stops a bad post, then it schedules', (tester) async {
    await open(tester);
    await tester.tap(find.text('New post'));
    await tester.pumpAndSettle();

    await tester.enterText(find.widgetWithText(TextField, 'Caption'), 'Fresh sourdough every morning');
    await tester.pump();

    // Nothing ticked yet.
    await tester.tap(find.widgetWithText(FilledButton, 'Schedule'));
    await tester.pump();
    expect(find.text('Choose at least one place to post to.'), findsOneWidget);
    await tester.pumpAndSettle(const Duration(seconds: 5));

    // Instagram needs media, so ticking it blocks scheduling a text-only post.
    final list = find.byType(Scrollable).first;
    final instagram = find.widgetWithText(CheckboxListTile, 'Instagram');
    final facebook = find.widgetWithText(CheckboxListTile, 'Facebook');
    await tester.scrollUntilVisible(instagram, 150, scrollable: list);
    await tester.tap(instagram);
    await tester.pumpAndSettle();
    expect(find.text('Instagram needs a photo or a video.'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Schedule'));
    await tester.pumpAndSettle();
    expect(find.text('Instagram needs a photo or a video.'), findsNWidgets(2)); // inline + message
    await tester.pumpAndSettle(const Duration(seconds: 5));

    // Swap to Facebook, which accepts text-only posts.
    await tester.tap(instagram);
    await tester.pumpAndSettle();
    await tester.tap(facebook);
    await tester.pumpAndSettle();

    final before = (await repo.posts()).length;
    await tester.tap(find.widgetWithText(FilledButton, 'Schedule'));
    await tester.pumpAndSettle();

    final posts = await repo.posts();
    expect(posts.length, before + 1);
    final created = posts.firstWhere((p) => p.caption == 'Fresh sourdough every morning');
    expect(created.status, 'scheduled');
    expect(created.targets.single.platform, 'facebook');
    expect(created.scheduledAt!.isAfter(DateTime.now()), isTrue);
    expect(find.textContaining('Scheduled for'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('composing: a reminder-only post and a draft both save', (tester) async {
    await open(tester);
    await tester.tap(find.text('New post'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Caption'), 'Post with a trending sound');
    final list = find.byType(Scrollable).first;
    await tester.scrollUntilVisible(find.widgetWithText(FilterChip, 'TikTok'), 150, scrollable: list);
    await tester.tap(find.widgetWithText(FilterChip, 'TikTok'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Schedule'));
    await tester.pumpAndSettle();

    var created = (await repo.posts()).firstWhere((p) => p.caption == 'Post with a trending sound');
    expect(created.targets.single.mode, 'reminder');
    expect(created.targets.single.platform, 'tiktok');

    await tester.tap(find.text('New post'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Caption'), 'Half-finished idea');
    await tester.tap(find.text('Save draft'));
    await tester.pumpAndSettle();
    created = (await repo.posts()).firstWhere((p) => p.caption == 'Half-finished idea');
    expect(created.status, 'draft');
    expect(tester.takeException(), isNull);
  });

  testWidgets('composing: YouTube options appear and unsaved changes are protected', (tester) async {
    await open(tester);
    await tester.tap(find.text('New post'));
    await tester.pumpAndSettle();
    final list = find.byType(Scrollable).first;
    final youtube = find.widgetWithText(CheckboxListTile, 'YouTube');
    await tester.scrollUntilVisible(youtube, 150, scrollable: list);
    await tester.tap(youtube);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Made for kids'), 150, scrollable: list);
    expect(find.text('Video title'), findsOneWidget);
    expect(find.text('Visibility'), findsOneWidget);

    await tester.tap(find.byTooltip('Close'));
    await tester.pumpAndSettle();
    expect(find.text('Discard this post?'), findsOneWidget);
    await tester.tap(find.text('Discard'));
    await tester.pumpAndSettle();
    expect(find.text('New post'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('accounts: setup guide shows the redirect URL and saves details', (tester) async {
    await open(tester);
    await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text('Accounts')));
    await tester.pumpAndSettle();
    expect(find.text('Facebook & Instagram'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.textContaining('Connect again within'), // LinkedIn access running out
      200,
      scrollable: find.byType(Scrollable).first,
    );

    await repo.clearCredentials('google');
    await tester.drag(find.byType(Scrollable).first, const Offset(0, 600));
    await tester.pumpAndSettle();
    await tester.drag(find.byType(Scrollable).first, const Offset(0, 300)); // pull to refresh
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Set up'), 200, scrollable: find.byType(Scrollable).first);
    await tester.ensureVisible(find.text('Set up'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Set up'));
    await tester.pumpAndSettle();

    expect(find.text('YouTube setup'), findsOneWidget);
    expect(find.textContaining('/functions/v1/scheduler/oauth/callback'), findsOneWidget);
    final form = find.byType(Scrollable).first;
    await tester.scrollUntilVisible(find.widgetWithText(TextField, 'Client secret'), 200, scrollable: form);
    await tester.enterText(find.widgetWithText(TextField, 'Client ID'), 'my-id');
    await tester.enterText(find.widgetWithText(TextField, 'Client secret'), 'my-secret');
    await tester.scrollUntilVisible(find.text('Save'), 200, scrollable: form);
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect((await repo.credentials()).any((c) => c.platform == 'google' && c.clientId == 'my-id'), isTrue);
    expect(tester.takeException(), isNull);
  });

  testWidgets('tools: hashtag groups and posting times can be added', (tester) async {
    await open(tester);
    await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text('Tools')));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Hashtag groups'));
    await tester.pumpAndSettle();
    expect(find.text('Everyday'), findsOneWidget);
    await tester.tap(find.text('Add'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Name'), 'Weekend');
    await tester.enterText(find.widgetWithText(TextField, 'Hashtags'), '#weekend #brunch');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Weekend'), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();

    await tester.tap(find.text('Posting times'));
    await tester.pumpAndSettle();
    expect(find.text('Monday'), findsOneWidget);
    expect(find.textContaining('7:30'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('dark theme renders', (tester) async {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
    await open(tester);
    expect(Theme.of(tester.element(find.text('New post'))).brightness, Brightness.dark);
    expect(tester.takeException(), isNull);
  });
}
