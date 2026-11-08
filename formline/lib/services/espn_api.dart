import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../engine/player_props.dart';
import '../engine/ratings.dart';
import '../models/game.dart';
import '../models/league.dart';
import 'cache.dart';

/// ESPN's public site API: fixtures, live scores, results and box scores.
/// Market lines are read only so the app can show how far the stats model
/// disagrees with the bookmakers; they never feed the prediction.
class EspnApi {
  static const _base = 'https://site.api.espn.com/apis/site/v2/sports';
  static final _client = http.Client();

  static Future<Map<String, dynamic>> _getJson(String url, {Duration ttl = Duration.zero, String? cacheKey}) async {
    final key = cacheKey ?? url;
    if (ttl > Duration.zero) {
      final c = await Cache.get(key, ttl);
      if (c is Map<String, dynamic>) return c;
    }
    final res = await _client.get(Uri.parse(url), headers: {'Accept': 'application/json'}).timeout(const Duration(seconds: 20));
    if (res.statusCode != 200) {
      throw Exception('ESPN ${res.statusCode} for $url');
    }
    final j = json.decode(res.body) as Map<String, dynamic>;
    if (ttl > Duration.zero) await Cache.put(key, j, persist: ttl > const Duration(minutes: 10));
    return j;
  }

  static String _ymd(DateTime d) =>
      '${d.year}${d.month.toString().padLeft(2, '0')}${d.day.toString().padLeft(2, '0')}';

  /// All games of [league] on a (local) day. Live games are never cached.
  static Future<List<Game>> scoreboard(League league, DateTime day) async {
    // ESPN days are US-based; ask for the day and the day after so late
    // evening (Australian morning) games are not missed, then filter locally.
    final games = <String, Game>{};
    for (final d in [day.subtract(const Duration(days: 1)), day, day.add(const Duration(days: 1))]) {
      final params = {'dates': _ymd(d), ...league.scoreboardParams};
      final qs = params.entries.map((e) => '${e.key}=${e.value}').join('&');
      final isPast = d.isBefore(DateTime.now().subtract(const Duration(days: 1)));
      final j = await _getJson('$_base/${league.path}/scoreboard?$qs',
          ttl: isPast ? const Duration(days: 30) : const Duration(seconds: 25));
      for (final e in (j['events'] as List? ?? [])) {
        final g = _parseEvent(league, e as Map<String, dynamic>);
        if (g != null) games[g.eventId] = g;
      }
    }
    final start = DateTime(day.year, day.month, day.day);
    final end = start.add(const Duration(days: 1));
    final out = games.values.where((g) {
      final local = g.date.toLocal();
      return !local.isBefore(start) && local.isBefore(end);
    }).toList()
      ..sort((a, b) => a.date.compareTo(b.date));
    return out;
  }

  static Game? _parseEvent(League league, Map<String, dynamic> e) {
    try {
      final comp = (e['competitions'] as List).first as Map<String, dynamic>;
      final competitors = (comp['competitors'] as List).cast<Map<String, dynamic>>();
      final homeC = competitors.firstWhere((c) => c['homeAway'] == 'home', orElse: () => competitors.first);
      final awayC = competitors.firstWhere((c) => c['homeAway'] == 'away', orElse: () => competitors.last);
      final status = (comp['status'] ?? e['status']) as Map<String, dynamic>;
      final type = status['type'] as Map<String, dynamic>;
      final stateStr = type['state'] as String? ?? 'pre';
      final state = stateStr == 'in' ? GameState.live : (stateStr == 'post' ? GameState.post : GameState.pre);
      double? marketTotal, marketSpread;
      String? provider;
      final odds = comp['odds'] as List?;
      if (odds != null && odds.isNotEmpty) {
        final o = odds.first as Map<String, dynamic>;
        marketTotal = (o['overUnder'] as num?)?.toDouble();
        marketSpread = (o['spread'] as num?)?.toDouble();
        provider = (o['provider'] as Map?)?['name'] as String?;
      }
      List<double> lines(Map<String, dynamic> c) =>
          ((c['linescores'] as List?) ?? []).map((l) => ((l as Map)['value'] as num?)?.toDouble() ?? 0).toList();
      return Game(
        league: league,
        eventId: '${e['id']}',
        date: DateTime.parse(e['date'] as String),
        home: _team(homeC),
        away: _team(awayC),
        state: state,
        homeScore: _score(homeC['score']),
        awayScore: _score(awayC['score']),
        statusDetail: type['shortDetail'] as String? ?? type['detail'] as String? ?? '',
        clock: status['displayClock'] as String? ?? '',
        period: (status['period'] as num?)?.toInt() ?? 0,
        neutral: comp['neutralSite'] == true,
        marketTotal: marketTotal,
        marketSpread: marketSpread,
        marketProvider: provider,
        homeLinescores: lines(homeC),
        awayLinescores: lines(awayC),
      );
    } catch (_) {
      return null;
    }
  }

  static TeamRef _team(Map<String, dynamic> c) {
    final t = c['team'] as Map<String, dynamic>;
    final records = c['records'] as List?;
    return TeamRef(
      id: '${t['id']}',
      abbr: t['abbreviation'] as String? ?? (t['shortDisplayName'] as String? ?? '???'),
      name: t['displayName'] as String? ?? t['name'] as String? ?? '',
      logo: t['logo'] as String? ?? ((t['logos'] as List?)?.isNotEmpty == true ? (t['logos'] as List).first['href'] : null),
      record: records != null && records.isNotEmpty ? (records.first as Map)['summary'] as String? : null,
    );
  }

  static double? _score(dynamic s) {
    if (s == null) return null;
    if (s is num) return s.toDouble();
    if (s is String) return double.tryParse(s);
    if (s is Map) return (s['value'] as num?)?.toDouble() ?? double.tryParse('${s['displayValue']}');
    return null;
  }

  /// Teams of a league: id -> abbreviation.
  static Future<Map<String, String>> teams(League league) async {
    final j = await _getJson('$_base/${league.path}/teams?limit=500', ttl: const Duration(days: 7));
    final out = <String, String>{};
    try {
      final leagues = ((j['sports'] as List).first['leagues'] as List);
      for (final l in leagues) {
        for (final t in (l['teams'] as List)) {
          final team = t['team'] as Map<String, dynamic>;
          out['${team['id']}'] = team['abbreviation'] as String? ?? '';
        }
      }
    } catch (_) {}
    return out;
  }

  /// Finished games from a team's schedule for a season (null = current).
  static Future<List<GameResult>> teamResults(League league, String teamId, {int? season}) async {
    final url = '$_base/${league.path}/teams/$teamId/schedule${season != null ? '?season=$season' : ''}';
    final j = await _getJson(url, ttl: season != null ? const Duration(days: 30) : const Duration(hours: 3));
    final out = <GameResult>[];
    for (final e in (j['events'] as List? ?? [])) {
      final r = _parseResult(e as Map<String, dynamic>);
      if (r != null) out.add(r);
    }
    return out;
  }

  static int? seasonYear(Map<String, dynamic> scheduleJson) =>
      ((scheduleJson['requestedSeason'] ?? scheduleJson['season']) as Map?)?['year'] as int?;

  static GameResult? _parseResult(Map<String, dynamic> e) {
    try {
      final comp = (e['competitions'] as List).first as Map<String, dynamic>;
      final status = (comp['status'] ?? e['status']) as Map<String, dynamic>;
      final type = status['type'] as Map<String, dynamic>;
      if (type['completed'] != true && type['state'] != 'post') return null;
      final competitors = (comp['competitors'] as List).cast<Map<String, dynamic>>();
      final homeC = competitors.firstWhere((c) => c['homeAway'] == 'home');
      final awayC = competitors.firstWhere((c) => c['homeAway'] == 'away');
      final hs = _score(homeC['score']), as = _score(awayC['score']);
      if (hs == null || as == null) return null;
      return GameResult(
        eventId: '${e['id']}',
        date: DateTime.parse(e['date'] as String),
        homeId: '${homeC['team']['id']}',
        awayId: '${awayC['team']['id']}',
        homeScore: hs,
        awayScore: as,
        neutral: comp['neutralSite'] == true,
        homeAbbr: homeC['team']['abbreviation'] as String? ?? '',
        awayAbbr: awayC['team']['abbreviation'] as String? ?? '',
      );
    } catch (_) {
      return null;
    }
  }

  /// Fits a league model from every team's recent results (cached for 3 hours).
  static Future<LeagueModel> buildModel(League league, {void Function(int done, int total)? progress}) async {
    final key = 'model:${league.id}';
    final cached = await Cache.get(key, const Duration(hours: 3));
    final model = LeagueModel(league);
    if (cached is Map && cached['results'] is List) {
      final results = (cached['results'] as List).map((j) => GameResult.fromJson(Map<String, dynamic>.from(j))).toList();
      model.fit(results, abbrs: Map<String, String>.from(cached['abbrs'] ?? {}));
      return model;
    }
    final byId = <String, GameResult>{};
    Map<String, String> abbrs = {};
    if (league.useDateScan) {
      final today = DateTime.now();
      const days = 40;
      var done = 0;
      for (var batch = 0; batch < days; batch += 6) {
        final futures = <Future<void>>[];
        for (var i = batch; i < batch + 6 && i < days; i++) {
          final d = today.subtract(Duration(days: i));
          futures.add(() async {
            try {
              final params = {'dates': _ymd(d), ...league.scoreboardParams};
              final qs = params.entries.map((e) => '${e.key}=${e.value}').join('&');
              final j = await _getJson('$_base/${league.path}/scoreboard?$qs', ttl: i == 0 ? const Duration(minutes: 10) : const Duration(days: 30));
              for (final e in (j['events'] as List? ?? [])) {
                final r = _parseResult(e as Map<String, dynamic>);
                if (r != null) byId[r.eventId] = r;
              }
            } catch (_) {}
            done++;
            progress?.call(done, days);
          }());
        }
        await Future.wait(futures);
      }
    } else {
      abbrs = await teams(league);
      final ids = abbrs.keys.toList();
      var done = 0;
      Future<void> fetchAll({int? season}) async {
        for (var batch = 0; batch < ids.length; batch += 6) {
          final futures = <Future<void>>[];
          for (var i = batch; i < batch + 6 && i < ids.length; i++) {
            futures.add(() async {
              try {
                for (final r in await teamResults(league, ids[i], season: season)) {
                  byId[r.eventId] = r;
                }
              } catch (_) {}
              done++;
              progress?.call(done, ids.length * (season == null ? 1 : 2));
            }());
          }
          await Future.wait(futures);
        }
      }

      await fetchAll();
      // Early season: add last season so ratings are not built on 2 games.
      if (byId.length < ids.length * 2.5 && ids.isNotEmpty) {
        final year = byId.values.isNotEmpty
            ? byId.values.map((r) => r.date.year).reduce((a, b) => a > b ? a : b)
            : DateTime.now().year;
        await fetchAll(season: year);
        if (byId.length < ids.length * 2.5) await fetchAll(season: year - 1);
      }
    }
    final results = byId.values.toList();
    await Cache.put(key, {
      'results': results.map((r) => r.toJson()).toList(),
      'abbrs': abbrs,
    });
    model.fit(results, abbrs: abbrs);
    return model;
  }

  /// Box score lines + injuries + market line for an event.
  /// [live] fetches fresh data every 20 seconds without persisting it.
  static Future<EventSummary> summary(League league, String eventId, {bool finished = false, bool live = false}) async {
    final ttl = live ? const Duration(seconds: 20) : (finished ? const Duration(days: 60) : const Duration(minutes: 15));
    final j = await _getJson('$_base/${league.path}/summary?event=$eventId', ttl: ttl, cacheKey: 'sum:${league.id}:$eventId');
    return EventSummary.parse(j);
  }

  /// Latest scoreboard entry for one event (score, clock, state).
  static Future<Game?> refreshGame(Game game) async {
    final games = await scoreboard(game.league, game.date.toLocal());
    for (final g in games) {
      if (g.eventId == game.eventId) return g;
    }
    return null;
  }

  /// Recent box scores for a team's last [n] finished games.
  static Future<List<PlayerGameLine>> recentPlayerLines(League league, LeagueModel model, String teamId, {int n = 8}) async {
    final games = model.lastGames(teamId, n);
    final out = <PlayerGameLine>[];
    final futures = games.map((g) async {
      try {
        final s = await summary(league, g.eventId, finished: true);
        out.addAll(s.playerLines.where((l) => l.teamId == teamId));
      } catch (_) {}
    });
    await Future.wait(futures);
    return out;
  }
}

/// One player's row in a live/final box score, any sport.
class BoxRow {
  BoxRow({
    required this.name,
    required this.stats,
    this.position = '',
    this.active = false,
    this.starter = false,
    this.dnp = false,
    this.onField = false,
  });
  final String name;
  final List<String> stats;
  final String position;
  final bool active;
  final bool starter;
  final bool dnp;
  final bool onField; // currently on the court / pitch (best effort)

  String stat(List<String> columns, String col) {
    final i = columns.indexOf(col);
    return i >= 0 && i < stats.length ? stats[i] : '';
  }

  double statNum(List<String> columns, String col) {
    final v = stat(columns, col);
    return double.tryParse(v.split('-').first.split('/').first) ?? 0;
  }
}

/// A box score table (one team, one stat category).
class BoxTable {
  BoxTable({required this.teamId, required this.teamAbbr, required this.title, required this.columns, required this.rows});
  final String teamId;
  final String teamAbbr;
  final String title;
  final List<String> columns;
  final List<BoxRow> rows;
}

class EventSummary {
  EventSummary({
    required this.playerLines,
    required this.injuries,
    this.tables = const [],
    this.marketTotal,
    this.marketSpread,
    this.marketProvider,
    this.homeScore,
    this.awayScore,
    this.completed = false,
  });
  final List<PlayerGameLine> playerLines;
  final Map<String, String> injuries; // player name -> status
  final List<BoxTable> tables; // generic box score, any sport
  final double? marketTotal, marketSpread;
  final String? marketProvider;
  final double? homeScore, awayScore;
  final bool completed;

  static EventSummary parse(Map<String, dynamic> j) {
    final lines = <PlayerGameLine>[];
    DateTime date = DateTime.now();
    bool completed = false;
    double? hs, as;
    try {
      final comp = (j['header']['competitions'] as List).first as Map<String, dynamic>;
      date = DateTime.parse(comp['date'] as String);
      completed = comp['status']['type']['completed'] == true;
      for (final c in (comp['competitors'] as List)) {
        final sc = double.tryParse('${c['score']}');
        if (c['homeAway'] == 'home') hs = sc;
        if (c['homeAway'] == 'away') as = sc;
      }
    } catch (_) {}
    try {
      for (final team in (j['boxscore']['players'] as List? ?? [])) {
        final teamId = '${team['team']['id']}';
        for (final stat in (team['statistics'] as List? ?? [])) {
          final names = (stat['names'] as List? ?? []).cast<String>();
          int idx(String n) => names.indexOf(n);
          final iMin = idx('MIN'), iPts = idx('PTS'), iReb = idx('REB'), iAst = idx('AST'), i3 = idx('3PT');
          if (iPts < 0) continue;
          for (final a in (stat['athletes'] as List? ?? [])) {
            final stats = (a['stats'] as List? ?? []).cast<String>();
            if (stats.isEmpty) continue;
            double val(int i) => i >= 0 && i < stats.length ? (double.tryParse(stats[i]) ?? 0) : 0;
            double made(int i) => i >= 0 && i < stats.length ? (double.tryParse(stats[i].split('-').first) ?? 0) : 0;
            final dnp = a['didNotPlay'] == true;
            lines.add(PlayerGameLine(
              name: a['athlete']['displayName'] as String,
              teamId: teamId,
              date: date,
              minutes: dnp ? 0 : val(iMin),
              pts: val(iPts),
              reb: val(iReb),
              ast: val(iAst),
              threes: made(i3),
              started: a['starter'] == true,
            ));
          }
        }
      }
    } catch (_) {}
    final tables = <BoxTable>[];
    final live = !completed;
    try {
      for (final team in (j['boxscore']['players'] as List? ?? [])) {
        final teamId = '${team['team']['id']}';
        final abbr = team['team']['abbreviation'] as String? ?? '';
        for (final stat in (team['statistics'] as List? ?? [])) {
          final name = stat['name'] as String? ?? '';
          if (name == 'skaters') continue; // NHL duplicates forwards + defenses
          final cols = ((stat['labels'] ?? stat['names']) as List? ?? []).cast<String>();
          final rows = <BoxRow>[];
          for (final a in (stat['athletes'] as List? ?? [])) {
            final stats = (a['stats'] as List? ?? []).map((x) => '$x').toList();
            final dnp = a['didNotPlay'] == true;
            final pos = a['athlete']?['position'];
            final active = a['active'] == true;
            rows.add(BoxRow(
              name: a['athlete']?['displayName'] as String? ?? '',
              stats: stats,
              position: pos is Map ? (pos['abbreviation'] as String? ?? '') : '',
              active: active,
              starter: a['starter'] == true,
              dnp: dnp,
              onField: live && active && !dnp,
            ));
          }
          if (rows.isNotEmpty) {
            tables.add(BoxTable(
              teamId: teamId,
              teamAbbr: abbr,
              title: (stat['text'] as String?) ?? (name.isEmpty ? 'Players' : name[0].toUpperCase() + name.substring(1)),
              columns: cols,
              rows: rows,
            ));
          }
        }
      }
      // Soccer: rosters with on-pitch flags.
      for (final r in (j['rosters'] as List? ?? [])) {
        final teamId = '${r['team']?['id']}';
        final abbr = r['team']?['abbreviation'] as String? ?? '';
        const wanted = ['G', 'SHOT', 'SOG', 'A', 'FC', 'YC', 'SV'];
        final rows = <BoxRow>[];
        for (final x in (r['roster'] as List? ?? [])) {
          final statList = (x['stats'] as List? ?? []).cast<Map>();
          String val(String abb) {
            for (final st in statList) {
              if (st['abbreviation'] == abb) return '${st['displayValue'] ?? st['value'] ?? ''}';
            }
            return '';
          }
          final starter = x['starter'] == true, subbedIn = x['subbedIn'] == true, subbedOut = x['subbedOut'] == true;
          final pos = x['position'];
          rows.add(BoxRow(
            name: x['athlete']?['displayName'] as String? ?? '',
            stats: wanted.map(val).toList(),
            position: pos is Map ? (pos['abbreviation'] as String? ?? '') : '',
            active: x['active'] == true,
            starter: starter,
            dnp: !starter && !subbedIn,
            onField: live && ((starter && !subbedOut) || (subbedIn && !subbedOut)),
          ));
        }
        rows.sort((a, b) => (b.onField ? 1 : 0).compareTo(a.onField ? 1 : 0));
        if (rows.isNotEmpty) tables.add(BoxTable(teamId: teamId, teamAbbr: abbr, title: 'Players', columns: wanted, rows: rows));
      }
    } catch (_) {}
    final injuries = <String, String>{};
    try {
      for (final t in (j['injuries'] as List? ?? [])) {
        for (final i in (t['injuries'] as List? ?? [])) {
          final name = i['athlete']?['displayName'];
          final status = i['status'] ?? i['details']?['type'];
          if (name != null && status != null) injuries['$name'] = '$status';
        }
      }
    } catch (_) {}
    double? mt, ms;
    String? prov;
    try {
      final pc = j['pickcenter'] as List? ?? [];
      if (pc.isNotEmpty) {
        mt = (pc.first['overUnder'] as num?)?.toDouble();
        ms = (pc.first['spread'] as num?)?.toDouble();
        prov = pc.first['provider']?['name'];
      }
    } catch (_) {}
    return EventSummary(
      playerLines: lines,
      injuries: injuries,
      tables: tables,
      marketTotal: mt,
      marketSpread: ms,
      marketProvider: prov,
      homeScore: hs,
      awayScore: as,
      completed: completed,
    );
  }
}
