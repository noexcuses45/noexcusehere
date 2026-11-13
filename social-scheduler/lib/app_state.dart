import 'dart:async';

import 'package:flutter/foundation.dart';

import 'data/repository.dart';
import 'models.dart';
import 'services/notification_service.dart';

/// Holds the data every screen shares and keeps it fresh.
class AppState extends ChangeNotifier {
  AppState(this.repo, {NotificationService? notifications})
      : notifications = notifications ?? NotificationService.instance;

  final Repository repo;
  final NotificationService notifications;

  List<SocialAccount> accounts = const [];
  List<Post> posts = const [];
  List<Snippet> snippets = const [];
  List<PostingSlot> slots = const [];
  List<CredentialStatus> credentials = const [];

  bool loading = true;
  String? loadError;
  Timer? _poll;
  bool _disposed = false;

  SocialAccount? account(String? id) {
    if (id == null) return null;
    for (final a in accounts) {
      if (a.id == id) return a;
    }
    return null;
  }

  Post? post(String id) {
    for (final p in posts) {
      if (p.id == id) return p;
    }
    return null;
  }

  CredentialStatus? credential(String key) {
    for (final c in credentials) {
      if (c.platform == key) return c;
    }
    return null;
  }

  bool get hasAi => credential('anthropic') != null;

  /// Posts that need the owner to do something.
  List<Post> attention(DateTime now) =>
      posts.where((p) => p.status == 'failed' || p.status == 'partial' || p.reminderDue(now)).toList();

  Future<void> loadAll() async {
    try {
      final results = await Future.wait([
        repo.accounts(),
        repo.posts(),
        repo.snippets(),
        repo.slots(),
        repo.credentials(),
      ]);
      accounts = results[0] as List<SocialAccount>;
      posts = results[1] as List<Post>;
      snippets = results[2] as List<Snippet>;
      slots = results[3] as List<PostingSlot>;
      credentials = results[4] as List<CredentialStatus>;
      loadError = null;
    } on AppException catch (e) {
      loadError = e.message;
    } catch (_) {
      loadError = 'Could not load your data. Pull down to try again.';
    }
    loading = false;
    _changed();
    _afterPostsChanged();
  }

  Future<void> refreshPosts() async {
    try {
      posts = await repo.posts();
      loadError = null;
    } on AppException catch (e) {
      loadError = e.message;
    } catch (_) {}
    _changed();
    _afterPostsChanged();
  }

  Future<void> refreshAccounts() async {
    try {
      final results = await Future.wait([repo.accounts(), repo.credentials()]);
      accounts = results[0] as List<SocialAccount>;
      credentials = results[1] as List<CredentialStatus>;
    } catch (_) {}
    _changed();
  }

  Future<void> refreshTools() async {
    try {
      final results = await Future.wait([repo.snippets(), repo.slots()]);
      snippets = results[0] as List<Snippet>;
      slots = results[1] as List<PostingSlot>;
    } catch (_) {}
    _changed();
  }

  /// While something is mid-publish, check back every few seconds so the
  /// status on screen follows the server.
  void _afterPostsChanged() {
    final busy = posts.any((p) =>
        p.status == 'publishing' && p.targets.any((t) => !t.isReminder && t.status != 'published' && t.status != 'failed'));
    final soon = posts.any((p) =>
        p.status == 'scheduled' &&
        p.scheduledAt != null &&
        p.scheduledAt!.difference(DateTime.now()).inSeconds < 90 &&
        p.targets.any((t) => !t.isReminder));
    if ((busy || soon) && !repo.isDemo) {
      _poll ??= Timer.periodic(const Duration(seconds: 8), (_) => refreshPosts());
    } else {
      _poll?.cancel();
      _poll = null;
    }
    notifications.syncReminders(posts);
  }

  void _changed() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _poll?.cancel();
    super.dispose();
  }
}

/// Next free posting time: the first weekly slot after [from] that no other
/// scheduled post already occupies. Falls back to "tomorrow 9:00".
DateTime nextFreeSlot(List<PostingSlot> slots, List<Post> posts, DateTime from) {
  final taken = <int>{
    for (final p in posts)
      if (p.status == 'scheduled' && p.scheduledAt != null) p.scheduledAt!.millisecondsSinceEpoch ~/ 60000,
  };
  if (slots.isNotEmpty) {
    for (var day = 0; day < 60; day++) {
      final date = DateTime(from.year, from.month, from.day + day);
      final todays = slots.where((s) => s.weekday == date.weekday).toList()
        ..sort((a, b) => a.minuteOfDay.compareTo(b.minuteOfDay));
      for (final s in todays) {
        final at = DateTime(date.year, date.month, date.day, s.minuteOfDay ~/ 60, s.minuteOfDay % 60);
        if (at.isAfter(from.add(const Duration(minutes: 5))) && !taken.contains(at.millisecondsSinceEpoch ~/ 60000)) {
          return at;
        }
      }
    }
  }
  return DateTime(from.year, from.month, from.day + 1, 9);
}
