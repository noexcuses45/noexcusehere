import 'package:health/health.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

/// Reads today's steps from Health Connect (synced there by the user's
/// watch app - Samsung Health, Garmin Connect, Fitbit, etc.) and saves
/// them to the backend. Returns the step count, or null if Health
/// Connect is unavailable or permission was denied.
class StepsService {
  final _health = Health();

  Future<int?> syncTodayFromWatch() async {
    try {
      await _health.configure();
      final granted =
          await _health.requestAuthorization([HealthDataType.STEPS]);
      if (!granted) return null;
      final now = DateTime.now();
      final midnight = DateTime(now.year, now.month, now.day);
      final steps = await _health.getTotalStepsInInterval(midnight, now);
      if (steps == null) return null;
      await saveSteps(steps, source: 'health');
      return steps;
    } catch (_) {
      return null;
    }
  }

  Future<void> saveSteps(int steps, {String source = 'manual'}) async {
    final client = Supabase.instance.client;
    final userId = client.auth.currentUser!.id;
    final today = DateTime.now();
    final day =
        '${today.year.toString().padLeft(4, '0')}-${today.month.toString().padLeft(2, '0')}-${today.day.toString().padLeft(2, '0')}';
    await client.from('nx_daily_steps').upsert({
      'user_id': userId,
      'day': day,
      'steps': steps,
      'source': source,
      'updated_at': DateTime.now().toIso8601String(),
    });
  }
}
