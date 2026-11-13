import 'dart:math';
import 'dart:typed_data';

import 'package:image_picker/image_picker.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../config.dart';
import '../models.dart';
import 'repository.dart';
import 'upload_stub.dart'
    if (dart.library.io) 'upload_io.dart'
    if (dart.library.js_interop) 'upload_web.dart';

const _bucket = 'media';

/// Turns any backend failure into a sentence that can be shown as-is.
AppException friendly(Object error) {
  if (error is AppException) return error;
  if (error is FunctionException) {
    final details = error.details;
    if (details is Map && details['error'] is String) return AppException(details['error'] as String);
    if (error.status == 404) {
      return AppException('The server side of this app has not been set up yet, so signing in is not possible.');
    }
    if (error.status == 0) {
      return AppException('Could not reach this app\'s server. Check your connection and try again.');
    }
    return AppException('The server could not complete that (code ${error.status}). Try again.');
  }
  if (error is AuthException) {
    final msg = error.message.toLowerCase();
    if (msg.contains('invalid login')) return AppException('Wrong email or password.');
    return AppException(error.message);
  }
  if (error is PostgrestException) return AppException(error.message);
  if (error is StorageException) {
    final msg = error.message.toLowerCase();
    if (error.statusCode == '413' || msg.contains('exceeded the maximum allowed size')) {
      return AppException(
        'That file is larger than your storage upload limit. Raise the limit in Supabase '
        '(Storage > Settings > Upload file size limit) or use a smaller file.',
      );
    }
    return AppException('Upload failed: ${error.message}');
  }
  final text = error.toString();
  if (text.contains('SocketException') || text.contains('ClientException') || text.contains('Failed host lookup')) {
    return AppException('No internet connection. Check your connection and try again.');
  }
  return AppException('Something went wrong. Try again.');
}

class SupabaseRepository implements Repository {
  SupabaseRepository(this.client);

  final SupabaseClient client;
  final _signed = <String, ({String url, DateTime expires})>{};
  final _random = Random.secure();

  @override
  bool get isDemo => false;

  @override
  String? get userEmail => client.auth.currentUser?.email;

  @override
  String get callbackUrl => AppConfig.callbackUrl;

  Future<T> _guard<T>(Future<T> Function() run) async {
    try {
      return await run();
    } catch (e) {
      throw friendly(e);
    }
  }

  Future<dynamic> _call(String route, {Map<String, dynamic>? body, HttpMethod method = HttpMethod.post}) async {
    final res = await client.functions.invoke('${AppConfig.functionName}/$route', body: body, method: method);
    return res.data;
  }

  // --- sign-in helpers (used before a repository is "active") -------------

  /// Whether the workspace already has an owner.
  Future<bool> hasOwner() => _guard(() async {
        final data = await _call('status', method: HttpMethod.get);
        return data is Map && data['has_owner'] == true;
      });

  Future<void> claimOwner(String email, String password) => _guard(() async {
        await _call('claim-owner', body: {'email': email, 'password': password});
        await client.auth.signInWithPassword(email: email, password: password);
      });

  Future<void> signIn(String email, String password) =>
      _guard(() => client.auth.signInWithPassword(email: email, password: password));

  Future<bool> isMember() => _guard(() async {
        final uid = client.auth.currentUser?.id;
        if (uid == null) return false;
        final rows = await client.from('members').select('user_id').eq('user_id', uid);
        return rows.isNotEmpty;
      });

  // --- accounts -----------------------------------------------------------

  @override
  Future<List<SocialAccount>> accounts() => _guard(() async {
        final rows = await client.from('social_accounts').select().order('platform').order('name');
        return rows.map(SocialAccount.fromJson).toList();
      });

  @override
  Future<void> disconnectAccount(String id) =>
      _guard(() => client.from('social_accounts').delete().eq('id', id));

  @override
  Future<List<CredentialStatus>> credentials() => _guard(() async {
        final rows = await client.rpc('credentials_status') as List;
        return rows.map((r) => CredentialStatus.fromJson(Map<String, dynamic>.from(r as Map))).toList();
      });

  @override
  Future<void> saveCredentials(String key, String clientId, String secret, Map<String, dynamic> options) =>
      _guard(() => client.rpc('set_platform_credentials', params: {
            'p_platform': key,
            'p_client_id': clientId,
            'p_client_secret': secret,
            'p_options': options,
          }));

  @override
  Future<void> clearCredentials(String key) =>
      _guard(() => client.rpc('clear_platform_credentials', params: {'p_platform': key}));

  @override
  Future<String> startConnect(String credentialKey) => _guard(() async {
        final data = await _call('oauth/start', body: {'platform': credentialKey});
        final url = data is Map ? data['url'] as String? : null;
        if (url == null) throw AppException('The server did not return a sign-in page.');
        return url;
      });

  // --- posts --------------------------------------------------------------

  @override
  Future<List<Post>> posts() => _guard(() async {
        final rows = await client
            .from('posts')
            .select('*, post_media(*), post_targets(*)')
            .order('created_at', ascending: false)
            .limit(500);
        return rows.map(Post.fromJson).toList();
      });

  @override
  Future<String> savePost(PostDraft draft) => _guard(() async {
        final id = await client.rpc('save_post', params: {
          'p_id': draft.id,
          'p_caption': draft.caption,
          'p_link_url': draft.linkUrl,
          'p_status': draft.schedule ? 'scheduled' : 'draft',
          'p_scheduled_at': draft.scheduledAt?.toUtc().toIso8601String(),
          'p_media': draft.media.map((m) => m.toJson()).toList(),
          'p_targets': draft.targets.map((t) => t.toJson()).toList(),
        });
        return id as String;
      });

  @override
  Future<void> deletePost(String id) => _guard(() async {
        final media = await client.from('post_media').select('storage_path, thumb_path').eq('post_id', id);
        await client.from('posts').delete().eq('id', id);
        final paths = <String>[
          for (final m in media) ...[
            if (m['storage_path'] != null) m['storage_path'] as String,
            if (m['thumb_path'] != null) m['thumb_path'] as String,
          ],
        ];
        if (paths.isEmpty) return;
        // Files may still be used by a duplicate of this post.
        final stillUsed = await client
            .from('post_media')
            .select('storage_path, thumb_path')
            .or('storage_path.in.(${paths.map((p) => '"$p"').join(',')}),thumb_path.in.(${paths.map((p) => '"$p"').join(',')})');
        final keep = <String>{
          for (final m in stillUsed) ...[
            if (m['storage_path'] != null) m['storage_path'] as String,
            if (m['thumb_path'] != null) m['thumb_path'] as String,
          ],
        };
        final remove = paths.where((p) => !keep.contains(p)).toList();
        if (remove.isNotEmpty) {
          try {
            await client.storage.from(_bucket).remove(remove);
          } catch (_) {
            // Leftover files are cleaned up later; the post itself is gone.
          }
        }
      });

  @override
  Future<void> retryPost(String id) => _guard(() async {
        await client.rpc('retry_post', params: {'p_post': id});
        await publishNow();
      });

  @override
  Future<void> markReminderDone(String targetId) => _guard(() => client.from('post_targets').update({
        'status': 'published',
        'published_at': DateTime.now().toUtc().toIso8601String(),
      }).eq('id', targetId));

  @override
  Future<void> publishNow() async {
    try {
      await _call('publish-due', body: {});
    } catch (_) {
      // The every-minute server job will pick it up anyway.
    }
  }

  // --- media --------------------------------------------------------------

  @override
  String newMediaPath(String extension) {
    final uid = client.auth.currentUser?.id ?? 'anon';
    final id = List.generate(16, (_) => _random.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
    return '$uid/${DateTime.now().toUtc().millisecondsSinceEpoch}-$id.$extension';
  }

  @override
  Future<void> uploadBytes(String path, Uint8List bytes, String contentType) =>
      _guard(() => client.storage.from(_bucket).uploadBinary(
            path,
            bytes,
            fileOptions: FileOptions(contentType: contentType, upsert: true),
          ));

  @override
  Future<void> uploadFile(String path, XFile file, String contentType) =>
      _guard(() => uploadPickedFile(client, _bucket, path, file, contentType));

  @override
  Future<String?> mediaUrl(String path) async {
    final hit = _signed[path];
    if (hit != null && hit.expires.isAfter(DateTime.now())) return hit.url;
    try {
      final url = await client.storage.from(_bucket).createSignedUrl(path, 3600);
      _signed[path] = (url: url, expires: DateTime.now().add(const Duration(minutes: 50)));
      return url;
    } catch (_) {
      return null;
    }
  }

  // --- tools --------------------------------------------------------------

  @override
  Future<List<Snippet>> snippets() => _guard(() async {
        final rows = await client.from('snippets').select().order('title');
        return rows.map(Snippet.fromJson).toList();
      });

  @override
  Future<void> saveSnippet(Snippet s) => _guard(() async {
        final row = {'kind': s.kind, 'title': s.title, 'body': s.body};
        if (s.id == null) {
          await client.from('snippets').insert(row);
        } else {
          await client.from('snippets').update(row).eq('id', s.id!);
        }
      });

  @override
  Future<void> deleteSnippet(String id) => _guard(() => client.from('snippets').delete().eq('id', id));

  @override
  Future<List<PostingSlot>> slots() => _guard(() async {
        final rows = await client.from('posting_slots').select().order('weekday').order('minute_of_day');
        return rows.map(PostingSlot.fromJson).toList();
      });

  @override
  Future<void> addSlot(int weekday, int minuteOfDay) => _guard(() => client
      .from('posting_slots')
      .upsert({'weekday': weekday, 'minute_of_day': minuteOfDay}, onConflict: 'weekday,minute_of_day'));

  @override
  Future<void> deleteSlot(String id) => _guard(() => client.from('posting_slots').delete().eq('id', id));

  @override
  Future<CaptionIdeas> captionIdeas({
    required String brief,
    String? draft,
    String? tone,
    String? business,
    required List<String> platforms,
    bool hashtags = true,
    bool emoji = true,
  }) =>
      _guard(() async {
        final data = await _call('ai/caption', body: {
          'brief': brief,
          'draft': draft,
          'tone': tone,
          'business': business,
          'platforms': platforms,
          'hashtags': hashtags,
          'emoji': emoji,
        });
        final map = Map<String, dynamic>.from(data as Map);
        return CaptionIdeas(
          captions: ((map['captions'] as List?) ?? const []).map((e) => e.toString()).toList(),
          hashtags: ((map['hashtags'] as List?) ?? const []).map((e) => e.toString()).toList(),
        );
      });

  @override
  Future<void> signOut() => _guard(() => client.auth.signOut());
}
