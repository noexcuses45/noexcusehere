import 'package:health/health.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

/// Summary of what was read from the watch via Health Connect.
/// Any field the watch doesn't provide is null.
class HealthSummary {
  final int? steps;
  final int? restingHr;
  final int? avgHr;
  final double? hrvMs;
  final int? sleepMinutes;
  HealthSummary(
      {this.steps, this.restingHr, this.avgHr, this.hrvMs, this.sleepMinutes});
}

/// Reads steps, heart rate, HRV and sleep from Health Connect (synced
/// there by the user's watch app - Samsung Health, Garmin Connect,
/// Fitbit, etc.) and saves them to the backend. Returns null only if
/// Health Connect is unavailable or permission was denied entirely.
class StepsService {
  final _health = Health();

  static const _types = [
    HealthDataType.STEPS,
    HealthDataType.HEART_RATE,
    HealthDataType.RESTING_HEART_RATE,
    HealthDataType.HEART_RATE_VARIABILITY_RMSSD,
    HealthDataType.SLEEP_SESSION,
  ];

  Future<HealthSummary?> syncTodayFromWatch() async {
    try {
      await _health.configure();
      final granted = await _health.requestAuthorization(_types);
      if (!granted) return null;
      final now = DateTime.now();
      final midnight = DateTime(now.year, now.month, now.day);

      int? steps;
      try {
        steps = await _health.getTotalStepsInInterval(midnight, now);
      } catch (_) {}

      final avgHr = _avg(await _read(HealthDataType.HEART_RATE, midnight, now));
      final restingHr = _latest(
          await _read(HealthDataType.RESTING_HEART_RATE, midnight, now));
      final hrvList = await _read(
          HealthDataType.HEART_RATE_VARIABILITY_RMSSD, midnight, now);
      final double? hrvMs = hrvList.isEmpty ? null : hrvList.last;

      int? sleepMinutes;
      try {
        final sleepStart = midnight.subtract(const Duration(hours: 6));
        final sessions = await _health.getHealthDataFromTypes(
            types: [HealthDataType.SLEEP_SESSION],
            startTime: sleepStart,
            endTime: now);
        var total = 0;
        for (final p in sessions) {
          total += p.dateTo.difference(p.dateFrom).inMinutes;
        }
        if (total > 0) sleepMinutes = total.clamp(0, 1439).toInt();
      } catch (_) {}

      if (steps != null) await saveSteps(steps, source: 'health');
      await _saveHealth(
          restingHr: restingHr?.round(),
          avgHr: avgHr?.round(),
          hrvMs: hrvMs,
          sleepMinutes: sleepMinutes);

      return HealthSummary(
          steps: steps,
          restingHr: restingHr?.round(),
          avgHr: avgHr?.round(),
          hrvMs: hrvMs,
          sleepMinutes: sleepMinutes);
    } catch (_) {
      return null;
    }
  }

  Future<List<double>> _read(
      HealthDataType type, DateTime start, DateTime end) async {
    try {
      final points = await _health.getHealthDataFromTypes(
          types: [type], startTime: start, endTime: end);
      final values = <double>[];
      for (final p in points) {
        final v = p.value;
        if (v is NumericHealthValue) {
          values.add(v.numericValue.toDouble());
        }
      }
      return values;
    } catch (_) {
      return [];
    }
  }

  double? _avg(List<double> values) {
    if (values.isEmpty) return null;
    return values.reduce((a, b) => a + b) / values.length;
  }

  double? _latest(List<double> values) => values.isEmpty ? null : values.last;

  String _todayKey() {
    final today = DateTime.now();
    final m = today.month.toString().padLeft(2, '0');
    final d = today.day.toString().padLeft(2, '0');
    return '${today.year}-$m-$d';
  }

  Future<void> saveSteps(int steps, {String source = 'manual'}) async {
    final client = Supabase.instance.client;
    await client.from('nx_daily_steps').upsert({
      'user_id': client.auth.currentUser!.id,
      'day': _todayKey(),
      'steps': steps,
      'source': source,
      'updated_at': DateTime.now().toIso8601String(),
    });
  }

  Future<void> _saveHealth(
      {int? restingHr, int? avgHr, double? hrvMs, int? sleepMinutes}) async {
    if (restingHr == null &&
        avgHr == null &&
        hrvMs == null &&
        sleepMinutes == null) {
      return;
    }
    final client = Supabase.instance.client;
    final row = <String, dynamic>{
      'user_id': client.auth.currentUser!.id,
      'day': _todayKey(),
      'updated_at': DateTime.now().toIso8601String(),
    };
    if (restingHr != null) row['resting_hr'] = restingHr;
    if (avgHr != null) row['avg_hr'] = avgHr;
    if (hrvMs != null) row['hrv_ms'] = hrvMs;
    if (sleepMinutes != null) row['sleep_minutes'] = sleepMinutes;
    await client.from('nx_daily_health').upsert(row);
  }
}
