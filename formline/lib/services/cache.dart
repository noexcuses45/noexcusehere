import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

/// Tiny JSON cache: in-memory first, then shared preferences, with a TTL.
class Cache {
  static final Map<String, (DateTime, dynamic)> _mem = {};
  static SharedPreferences? _prefs;

  static Future<SharedPreferences> get prefs async => _prefs ??= await SharedPreferences.getInstance();

  static Future<dynamic> get(String key, Duration ttl) async {
    final m = _mem[key];
    final now = DateTime.now();
    if (m != null && now.difference(m.$1) < ttl) return m.$2;
    final p = await prefs;
    final raw = p.getString('c:$key');
    if (raw == null) return null;
    try {
      final j = json.decode(raw) as Map<String, dynamic>;
      final at = DateTime.parse(j['at']);
      if (now.difference(at) >= ttl) return null;
      _mem[key] = (at, j['v']);
      return j['v'];
    } catch (_) {
      return null;
    }
  }

  static Future<void> put(String key, dynamic value, {bool persist = true}) async {
    final now = DateTime.now();
    _mem[key] = (now, value);
    if (!persist) return;
    try {
      final p = await prefs;
      await p.setString('c:$key', json.encode({'at': now.toIso8601String(), 'v': value}));
    } catch (_) {}
  }

  /// Compact entries worth keeping between launches. Everything else under
  /// the cache prefix is a raw download from an older version and is removed:
  /// big payloads slow app start on Android and overflow storage on the web.
  static const _keep = ['mres:', 'model:', 'box:', 'odds:', 'asx:'];

  static Future<void> cleanup() async {
    try {
      final p = await prefs;
      for (final k in p.getKeys().where((k) => k.startsWith('c:')).toList()) {
        final name = k.substring(2);
        if (!_keep.any(name.startsWith)) await p.remove(k);
      }
    } catch (_) {}
  }

  static Future<void> clearAll() async {
    _mem.clear();
    final p = await prefs;
    for (final k in p.getKeys().where((k) => k.startsWith('c:')).toList()) {
      await p.remove(k);
    }
  }
}
