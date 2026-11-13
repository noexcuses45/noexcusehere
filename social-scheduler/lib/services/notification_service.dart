import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:timezone/timezone.dart' as tz;

import '../models.dart';
import '../platforms.dart';

/// "Time to post" reminders for posts the owner publishes by hand.
///
/// Reminders live on this phone only. They are rebuilt from the post list
/// every time it changes, so editing or deleting a post keeps them in step.
class NotificationService {
  NotificationService();

  static final NotificationService instance = NotificationService();

  final FlutterLocalNotificationsPlugin _plugin = FlutterLocalNotificationsPlugin();
  bool _ready = false;
  String _lastSync = '';

  /// Called with a post id when a reminder is tapped.
  void Function(String postId)? onOpenPost;

  /// Set when the app was opened by tapping a reminder.
  String? launchPostId;

  bool get supported => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  Future<void> init() async {
    if (!supported || _ready) return;
    try {
      await _plugin.initialize(
        settings: const InitializationSettings(android: AndroidInitializationSettings('ic_stat_reminder')),
        onDidReceiveNotificationResponse: (response) {
          final id = response.payload;
          if (id != null && id.isNotEmpty) onOpenPost?.call(id);
        },
      );
      final launch = await _plugin.getNotificationAppLaunchDetails();
      if (launch?.didNotificationLaunchApp ?? false) {
        launchPostId = launch!.notificationResponse?.payload;
      }
      _ready = true;
    } catch (_) {
      // Reminders are a convenience; the app works without them.
    }
  }

  /// Asks for permission to show notifications (Android 13+). Returns whether
  /// reminders can be shown.
  Future<bool> requestPermission() async {
    if (!supported) return false;
    await init();
    try {
      final android = _plugin.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();
      return await android?.requestNotificationsPermission() ?? false;
    } catch (_) {
      return false;
    }
  }

  static int _idFor(String postId) => postId.hashCode & 0x7fffffff;

  Future<void> syncReminders(List<Post> posts) async {
    if (!_ready) return;
    final now = DateTime.now();
    final due = [
      for (final p in posts)
        if (!p.isDraft &&
            p.scheduledAt != null &&
            p.scheduledAt!.isAfter(now) &&
            p.targets.any((t) => t.isReminder && t.status == 'pending'))
          p,
    ]..sort((a, b) => a.scheduledAt!.compareTo(b.scheduledAt!));

    // Skip the work when nothing relevant changed.
    final signature = due.map((p) => '${p.id}@${p.scheduledAt!.millisecondsSinceEpoch}').join(',');
    if (signature == _lastSync) return;
    _lastSync = signature;

    try {
      await _plugin.cancelAllPendingNotifications();
      // Android keeps a limited number of alarms per app; the nearest matter most.
      for (final p in due.take(60)) {
        final where = p.targets
            .where((t) => t.isReminder)
            .map((t) => platformInfo(t.platform).label)
            .toSet()
            .join(', ');
        final caption = p.caption.trim().isEmpty ? 'Tap to open the post.' : p.caption.trim();
        await _plugin.zonedSchedule(
          id: _idFor(p.id),
          title: 'Time to post to $where',
          body: caption.length > 120 ? '${caption.substring(0, 120)}...' : caption,
          // An absolute instant, so it fires correctly in any time zone.
          scheduledDate: tz.TZDateTime.from(p.scheduledAt!.toUtc(), tz.UTC),
          notificationDetails: const NotificationDetails(
            android: AndroidNotificationDetails(
              'post_reminders',
              'Posting reminders',
              channelDescription: 'Reminds you when a post you chose to publish yourself is due.',
              importance: Importance.high,
              priority: Priority.high,
            ),
          ),
          androidScheduleMode: AndroidScheduleMode.exactAllowWhileIdle,
          payload: p.id,
        );
      }
    } catch (_) {
      // Exact alarms can be refused by the system; fall back silently.
      _lastSync = '';
    }
  }
}
