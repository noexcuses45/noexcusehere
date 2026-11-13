import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';

import '../config.dart';

class AppUpdate {
  AppUpdate({required this.build, required this.url, required this.name});
  final int build;
  final String url;
  final String name;
}

/// Looks for a newer APK among this repository's GitHub releases. Scheduler
/// releases are tagged `scheduler-v<build number>` so they never mix with
/// other apps published from the same repository.
class UpdateService {
  static Future<String> currentVersion() async {
    try {
      final info = await PackageInfo.fromPlatform();
      return '${info.version} (${info.buildNumber})';
    } catch (_) {
      return 'unknown';
    }
  }

  static Future<AppUpdate?> check() async {
    if (kIsWeb) return null;
    try {
      final info = await PackageInfo.fromPlatform();
      final current = int.tryParse(info.buildNumber) ?? 0;
      final res = await http.get(
        Uri.parse('https://api.github.com/repos/${AppConfig.updateRepo}/releases?per_page=30'),
        headers: {'Accept': 'application/vnd.github+json'},
      ).timeout(const Duration(seconds: 10));
      if (res.statusCode != 200) return null;

      AppUpdate? best;
      for (final r in json.decode(res.body) as List) {
        final release = r as Map<String, dynamic>;
        final tag = (release['tag_name'] as String?) ?? '';
        if (!tag.startsWith(AppConfig.updateTagPrefix) || release['draft'] == true) continue;
        final build = int.tryParse(tag.substring(AppConfig.updateTagPrefix.length)) ?? 0;
        if (build <= current || (best != null && build <= best.build)) continue;
        for (final a in (release['assets'] as List? ?? const [])) {
          final url = (a as Map)['browser_download_url'] as String?;
          if (url != null && url.endsWith('.apk')) {
            best = AppUpdate(build: build, url: url, name: (release['name'] as String?) ?? tag);
          }
        }
      }
      return best;
    } catch (_) {
      return null;
    }
  }
}
