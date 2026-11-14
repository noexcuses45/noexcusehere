import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../models/race.dart';
import 'cache.dart';

/// Why the racing feed could not be read, in words for the screen.
class RacingException implements Exception {
  RacingException(this.reason);
  final String reason;
  @override
  String toString() => reason;
}

/// TAB NZ affiliates feed: meetings, fields, form and results for
/// thoroughbred, harness and greyhound racing worldwide. No key needed.
class RacingApi {
  static const _direct = 'https://api.tab.co.nz/affiliates/v1/racing';

  /// The Formline website relays the same feed. The web build has to use it
  /// (the feed does not allow browser requests); the phone app falls back to
  /// it when the feed refuses a direct connection, which happens on some
  /// VPNs and overseas networks.
  static const _relay = 'https://formline-app.netlify.app/racing-api';
  static http.Client _client = http.Client();
  static bool _useRelay = false; // the direct route failed last time

  /// Lets tests stand in for the network.
  @visibleForTesting
  static void useClient(http.Client c) {
    _client = c;
    _useRelay = false;
  }

  static String _why(Object e) {
    if (e is RacingException) return e.reason;
    if (e is TimeoutException) return 'The racing feed did not answer.';
    return 'No connection to the racing feed.';
  }

  /// Fetches [path] from the feed, trying the other route if the first fails.
  static Future<Map<String, dynamic>> _fetch(String path) async {
    final bases = kIsWeb ? [Uri.base.resolve('/racing-api').toString()] : (_useRelay ? [_relay, _direct] : [_direct, _relay]);
    Object? error;
    for (final base in bases) {
      try {
        // Give up on the first route sooner, so a blocked connection does not hold things up.
        final limit = Duration(seconds: base == bases.last ? 20 : 12);
        final res = await _client.get(Uri.parse('$base$path'), headers: {'Accept': 'application/json'}).timeout(limit);
        if (res.statusCode != 200) throw RacingException('The racing feed refused the request (${res.statusCode}).');
        final j = json.decode(res.body);
        if (j is! Map<String, dynamic>) throw RacingException('The racing feed sent something unexpected.');
        _useRelay = base == _relay;
        return j;
      } catch (e) {
        // Keep the first route's reason: it is the one that says what is wrong.
        error ??= e;
      }
    }
    throw RacingException(_why(error!));
  }

  static Future<Map<String, dynamic>> _getJson(String path, {Duration ttl = Duration.zero}) async {
    final key = 'racing:$path';
    if (ttl > Duration.zero) {
      final c = await Cache.get(key, ttl);
      if (c is Map<String, dynamic>) return c;
    }
    final j = await _fetch(path);
    if (ttl > Duration.zero) await Cache.put(key, j, persist: false);
    return j;
  }

  static String _iso(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  static Future<List<Meeting>> meetings(DateTime day) async {
    final j = await _getJson('/meetings?date_from=${_iso(day)}&date_to=${_iso(day)}', ttl: const Duration(minutes: 5));
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

  /// One race with its field, and its result and dividends once run.
  /// [fresh] asks for a copy no older than half a minute (used while waiting
  /// on a result).
  static Future<Race> race(String raceId, {bool finished = false, bool fresh = false}) async {
    final ttl = finished ? const Duration(days: 30) : (fresh ? const Duration(seconds: 30) : const Duration(minutes: 2));
    final j = await _getJson('/events/$raceId', ttl: ttl);
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
          margin: (x['margin_length'] as num?)?.toDouble(),
        ));
      } catch (_) {}
    }
    results.sort((a, b) => a.position.compareTo(b.position));
    // Dividends come twice (interim, then final): keep the final one of each.
    final byKey = <String, Dividend>{};
    for (final x in (d['dividends'] as List? ?? [])) {
      try {
        final amount = (x['dividend'] as num?)?.toDouble() ?? 0;
        if (amount <= 0) continue;
        final product = (x['product_name'] as String? ?? '').replaceFirst('Pool ', '');
        final description = x['description'] as String? ?? '';
        final isFinal = '${x['status']}'.toLowerCase() == 'final';
        final key = '$product|$description';
        if (byKey.containsKey(key) && !isFinal) continue;
        byKey[key] = Dividend(
          product: product,
          amount: amount,
          numbers: [for (final p in (x['positions'] as List? ?? [])) ((p as Map)['runner_number'] as num?)?.toInt() ?? 0],
          description: description,
          isFinal: isFinal,
          pool: (x['pool_size'] as num?)?.toDouble() ?? 0,
        );
      } catch (_) {}
    }
    var quaddieLegs = const <int>[];
    var quaddiePool = 0.0;
    for (final t in (d['tote_pools'] as List? ?? [])) {
      if (t is Map && t['product_type'] == 'Quadrella' && t['quaddie'] is List) {
        quaddieLegs = [for (final n in t['quaddie'] as List) (n as num).toInt()];
        quaddiePool = (t['total'] as num?)?.toDouble() ?? 0;
      }
    }
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
      dividends: byKey.values.toList(),
      quaddieLegs: quaddieLegs,
      quaddiePool: quaddiePool,
    );
  }
}
