import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

/// Checks GitHub Releases for a newer build and offers a one-tap update.
/// Releases are published automatically by the build workflow.
class UpdateService {
  static const _repo = 'noexcuses45/noexcusehere';
  static bool _checked = false;

  static Future<void> checkForUpdate(BuildContext context) async {
    if (_checked) return;
    _checked = true;
    try {
      final info = await PackageInfo.fromPlatform();
      final current = int.tryParse(info.buildNumber) ?? 0;

      final res = await http.get(
        Uri.parse('https://api.github.com/repos/$_repo/releases/latest'),
        headers: {'Accept': 'application/vnd.github+json'},
      ).timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return;

      final data = json.decode(res.body) as Map<String, dynamic>;
      final tag = (data['tag_name'] as String? ?? '').replaceFirst('v', '');
      final latest = int.tryParse(tag) ?? 0;
      if (latest <= current) return;

      String? apkUrl;
      for (final a in (data['assets'] as List? ?? [])) {
        final url = (a as Map)['browser_download_url'] as String?;
        if (url != null && url.endsWith('.apk')) apkUrl = url;
      }
      if (apkUrl == null || !context.mounted) return;
      final url = apkUrl;

      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Update available'),
          content: const Text(
              'A new version of No Excuse Here is ready. Download it, then tap the file to install - your data and login are kept.'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Later'),
            ),
            FilledButton.icon(
              onPressed: () {
                Navigator.pop(ctx);
                launchUrl(Uri.parse(url),
                    mode: LaunchMode.externalApplication);
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
