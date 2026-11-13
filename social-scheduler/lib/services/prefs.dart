import 'package:shared_preferences/shared_preferences.dart';

/// Small on-device preferences.
class Prefs {
  static const _shrink = 'shrink_videos';
  static const _business = 'ai_business';
  static const _tone = 'ai_tone';

  static Future<bool> shrinkVideos() async {
    try {
      return (await SharedPreferences.getInstance()).getBool(_shrink) ?? true;
    } catch (_) {
      return true;
    }
  }

  static Future<void> setShrinkVideos(bool value) async {
    try {
      await (await SharedPreferences.getInstance()).setBool(_shrink, value);
    } catch (_) {}
  }

  static Future<String> business() async {
    try {
      return (await SharedPreferences.getInstance()).getString(_business) ?? '';
    } catch (_) {
      return '';
    }
  }

  static Future<void> setBusiness(String value) async {
    try {
      await (await SharedPreferences.getInstance()).setString(_business, value);
    } catch (_) {}
  }

  static Future<String> tone() async {
    try {
      return (await SharedPreferences.getInstance()).getString(_tone) ?? 'Friendly';
    } catch (_) {
      return 'Friendly';
    }
  }

  static Future<void> setTone(String value) async {
    try {
      await (await SharedPreferences.getInstance()).setString(_tone, value);
    } catch (_) {}
  }
}
