import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

/// Offers a one-tap update from GitHub pre-releases tagged `statedge-v<build>`.
/// (Pre-release so the fitness app's own "latest release" check ignores them.)
class UpdateService {
  static const _repo = 'noexcuses45/noexcusehere';
  static bool _checked = false;

  static Future<void> checkForUpdate(BuildContext context) async {
    if (_checked || kIsWeb) return;
    _checked = true;
    try {
      final info = await PackageInfo.fromPlatform();
      final current = int.tryParse(info.buildNumber) ?? 0;
      final res = await http.get(
        Uri.parse('https://api.github.com/repos/$_repo/releases?per_page=20'),
        headers: {'Accept': 'application/vnd.github+json'},
      ).timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return;
      String? apkUrl;
      var latest = 0;
      for (final r in (json.decode(res.body) as List)) {
        final tag = (r as Map)['tag_name'] as String? ?? '';
        if (!tag.startsWith('statedge-v')) continue;
        final n = int.tryParse(tag.substring('statedge-v'.length)) ?? 0;
        if (n <= latest) continue;
        for (final a in (r['assets'] as List? ?? [])) {
          final url = (a as Map)['browser_download_url'] as String?;
          if (url != null && url.endsWith('.apk')) {
            latest = n;
            apkUrl = url;
          }
        }
      }
      if (latest <= current || apkUrl == null || !context.mounted) return;
      final url = apkUrl;
      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Update available'),
          content: const Text('A new StatEdge build is ready. Download it, then tap the file to install. Your saved picks are kept.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Later')),
            FilledButton.icon(
              onPressed: () {
                Navigator.pop(ctx);
                launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
              },
              icon: const Icon(Icons.download),
              label: const Text('Update now'),
            ),
          ],
        ),
      );
    } catch (_) {}
  }
}
