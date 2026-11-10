import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../models/race.dart';
import 'cache.dart';

/// TAB NZ affiliates feed: meetings, fields, form and results for
/// thoroughbred, harness and greyhound racing worldwide. No key needed.
class RacingApi {
  /// The feed does not allow browser (cross-origin) requests, so the web
  /// build goes through a same-origin relay configured in web/_redirects.
  static final String _base =
      kIsWeb ? Uri.base.resolve('/racing-api').toString() : 'https://api.tab.co.nz/affiliates/v1/racing';
  static final _client = http.Client();

  static Future<Map<String, dynamic>> _getJson(String url, {Duration ttl = Duration.zero}) async {
    if (ttl > Duration.zero) {
      final c = await Cache.get(url, ttl);
      if (c is Map<String, dynamic>) return c;
    }
    final res = await _client.get(Uri.parse(url), headers: {'Accept': 'application/json'}).timeout(const Duration(seconds: 20));
    if (res.statusCode != 200) throw Exception('Racing feed ${res.statusCode}');
    final j = json.decode(res.body) as Map<String, dynamic>;
    if (ttl > Duration.zero) await Cache.put(url, j, persist: false);
    return j;
  }

  static String _iso(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  static Future<List<Meeting>> meetings(DateTime day) async {
    final j = await _getJson('$_base/meetings?date_from=${_iso(day)}&date_to=${_iso(day)}', ttl: const Duration(minutes: 5));
    final out = <Meeting>[];
    for (final m in ((j['data']?['meetings']) as List? ?? [])) {
      final races = <RaceSummary>[];
      for (final r in (m['races'] as List? ?? [])) {
        try {
          races.add(RaceSummary(
            id: '${r['id']}',
            number: (r['race_number'] as num?)?.toInt() ?? 0,
            name: r['name'] as String? ?? '',
            start: DateTime.parse(r['start_time'] as String),
            status: r['status'] as String? ?? '',
            distance: (r['distance'] as num?)?.toInt() ?? 0,
          ));
        } catch (_) {}
      }
      races.sort((a, b) => a.number.compareTo(b.number));
      out.add(Meeting(
        id: '${m['meeting']}',
        name: m['name'] as String? ?? '',
        date: DateTime.tryParse(m['date'] as String? ?? '') ?? day,
        country: m['country'] as String? ?? '',
        category: m['category'] as String? ?? '',
        categoryName: m['category_name'] as String? ?? '',
        trackCondition: m['track_condition'] as String? ?? '',
        races: races,
      ));
    }
    return out;
  }

  static Future<Race> race(String raceId, {bool finished = false}) async {
    final j = await _getJson('$_base/events/$raceId', ttl: finished ? const Duration(days: 30) : const Duration(minutes: 2));
    return parseRace(j, raceId);
  }

  /// Pure parser for an event payload (also used by the calibration tool).
  static Race parseRace(Map<String, dynamic> j, String raceId) {
    final d = j['data'] as Map<String, dynamic>;
    final r = d['race'] as Map<String, dynamic>;
    final runners = <Runner>[];
    for (final x in (d['runners'] as List? ?? [])) {
      try {
        final odds = x['odds'] as Map? ?? {};
        final jp = x['jockey_past_performances'] as Map? ?? {};
        final j50 = jp['last_50_starts'] as Map? ?? {};
        final tr = jp['trainer'] as Map? ?? {};
        final w = x['weight'] as Map? ?? {};
        runners.add(Runner(
          id: '${x['entrant_id']}',
          number: (x['runner_number'] as num?)?.toInt() ?? 0,
          name: x['name'] as String? ?? '',
          scratched: x['is_scratched'] == true,
          barrier: (x['barrier'] as num?)?.toInt(),
          weight: double.tryParse('${w['total'] ?? w['allocated'] ?? ''}'),
          allowance: double.tryParse('${x['allowance_weight'] ?? ''}'),
          apprentice: (x['apprentice_indicator'] as String? ?? '').isNotEmpty,
          jockey: x['jockey'] as String? ?? '',
          trainer: x['trainer_name'] as String? ?? '',
          form: x['last_twenty_starts'] as String? ?? '',
          prizeMoney: double.tryParse('${x['prize_money'] ?? ''}') ?? 0,
          age: (x['age'] as num?)?.toInt(),
          gear: x['gear'] as String? ?? '',
          speedMap: (x['speedmap'] as Map?)?['label'] as String? ?? '',
          fixedWin: (odds['fixed_win'] as num?)?.toDouble(),
          fixedPlace: (odds['fixed_place'] as num?)?.toDouble(),
          poolWin: (odds['pool_win'] as num?)?.toDouble(),
          jockeyStarts: (j50['number_of_starts'] as num?)?.toInt() ?? 0,
          jockeyWins: (j50['number_of_wins'] as num?)?.toInt() ?? 0,
          trainerStarts: (tr['number_of_starts'] as num?)?.toInt() ?? 0,
          trainerWins: (tr['number_of_wins'] as num?)?.toInt() ?? 0,
          comment: x['form_comment'] as String?,
          silk: x['silk_url_64x64'] as String?,
        ));
      } catch (_) {}
    }
    runners.sort((a, b) => a.number.compareTo(b.number));
    final results = <RaceResult>[];
    for (final x in (d['results'] as List? ?? [])) {
      try {
        results.add(RaceResult(
          position: (x['position'] as num).toInt(),
          number: (x['runner_number'] as num?)?.toInt() ?? 0,
          name: x['name'] as String? ?? '',
        ));
      } catch (_) {}
    }
    results.sort((a, b) => a.position.compareTo(b.position));
    final startSecs = (r['advertised_start'] as num?)?.toInt();
    return Race(
      id: '${r['event_id'] ?? raceId}',
      meetingId: '${r['meeting_id'] ?? ''}',
      meetingName: r['display_meeting_name'] as String? ?? r['meeting_name'] as String? ?? '',
      number: (r['race_number'] as num?)?.toInt() ?? 0,
      name: r['description'] as String? ?? '',
      start: startSecs != null ? DateTime.fromMillisecondsSinceEpoch(startSecs * 1000, isUtc: true) : DateTime.now(),
      status: r['status'] as String? ?? '',
      type: r['type'] as String? ?? 'T',
      distance: (r['distance'] as num?)?.toInt() ?? 0,
      trackCondition: r['track_condition'] as String? ?? '',
      country: r['country'] as String? ?? '',
      comment: r['comment'] as String?,
      positionsPaid: (r['positions_paid'] as num?)?.toInt() ?? 3,
      runners: runners,
      results: results,
    );
  }
}
