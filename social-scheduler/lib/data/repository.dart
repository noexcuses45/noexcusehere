import 'dart:typed_data';

import 'package:image_picker/image_picker.dart';

import '../models.dart';

/// Everything the screens need from the backend. Two implementations:
/// [SupabaseRepository] (the real one) and [DemoRepository] (sample data that
/// lives in memory, used for "Try the demo" and for tests).
abstract class Repository {
  bool get isDemo;
  String? get userEmail;

  /// The redirect URL to paste into each platform's developer console.
  String get callbackUrl;

  // Accounts -------------------------------------------------------------
  Future<List<SocialAccount>> accounts();
  Future<void> disconnectAccount(String id);
  Future<List<CredentialStatus>> credentials();
  Future<void> saveCredentials(String key, String clientId, String secret, Map<String, dynamic> options);
  Future<void> clearCredentials(String key);

  /// Returns the sign-in page to open in the browser.
  Future<String> startConnect(String credentialKey);

  // Posts ----------------------------------------------------------------
  Future<List<Post>> posts();
  Future<String> savePost(PostDraft draft);
  Future<void> deletePost(String id);
  Future<void> retryPost(String id);
  Future<void> markReminderDone(String targetId);

  /// Asks the server to publish anything that is due right now.
  Future<void> publishNow();

  // Media ----------------------------------------------------------------
  Future<void> uploadBytes(String path, Uint8List bytes, String contentType);
  Future<void> uploadFile(String path, XFile file, String contentType);
  Future<String?> mediaUrl(String path);
  String newMediaPath(String extension);

  // Tools ----------------------------------------------------------------
  Future<List<Snippet>> snippets();
  Future<void> saveSnippet(Snippet snippet);
  Future<void> deleteSnippet(String id);
  Future<List<PostingSlot>> slots();
  Future<void> addSlot(int weekday, int minuteOfDay);
  Future<void> deleteSlot(String id);
  Future<CaptionIdeas> captionIdeas({
    required String brief,
    String? draft,
    String? tone,
    String? business,
    required List<String> platforms,
    bool hashtags = true,
    bool emoji = true,
  });

  Future<void> signOut();
}
