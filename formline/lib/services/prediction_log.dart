import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../engine/predictor.dart';
import '../engine/race_rater.dart';
import '../models/league.dart';
import '../models/race.dart';
import 'espn_api.dart';
import 'racing_api.dart';

/// One pre-game prediction the app made, kept so it can be graded later.
class LoggedGame {
  LoggedGame({
    required this.leagueId,
    required this.eventId,
    required this.date,
    required this.home,
    required this.away,
    required this.rawHome,
    required this.rawAway,
    required this.pHome,
    required this.pAway,
    this.marketTotal,
    this.marketSpread,
    this.homeScore,
    this.awayScore,
  });
  final String leagueId, eventId, home, away;
  final DateTime date;
  final double rawHome, rawAway; // projected scores before any self-correction
  final double pHome, pAway;
  final double? marketTotal, marketSpread;
  double? homeScore, awayScore;

  bool get graded => homeScore != null && awayScore != null;
  double get projTotal => rawHome + rawAway;
  double get actualTotal => (homeScore ?? 0) + (awayScore ?? 0);

  Map<String, dynamic> toJson() => {
        'l': leagueId,
        'e': eventId,
        'd': date.toIso8601String(),
        'h': home,
        'a': away,
        'rh': rawHome,
        'ra': rawAway,
        'ph': pHome,
        'pa': pAway,
        'mt': marketTotal,
        'ms': marketSpread,
        'hs': homeScore,
        'as': awayScore,
      };
  static LoggedGame fromJson(Map<String, dynamic> j) => LoggedGame(
        leagueId: j['l'],
        eventId: j['e'],
        date: DateTime.parse(j['d']),
        home: j['h'] ?? '',
        away: j['a'] ?? '',
        rawHome: (j['rh'] as num).toDouble(),
        rawAway: (j['ra'] as num).toDouble(),
        pHome: (j['ph'] as num).toDouble(),
        pAway: (j['pa'] as num).toDouble(),
        marketTotal: (j['mt'] as num?)?.toDouble(),
        marketSpread: (j['ms'] as num?)?.toDouble(),
        homeScore: (j['hs'] as num?)?.toDouble(),
        awayScore: (j['as'] as num?)?.toDouble(),
      );
}

/// The model's top pick in a race, kept so it can be graded later.
class LoggedRace {
  LoggedRace({
    required this.raceId,
    required this.label,
    required this.start,
    required this.type,
    required this.number,
    required this.name,
    required this.winProb,
    this.fixedWin,
    this.position,
  });
  final String raceId, label, type, name;
  final DateTime start;
  final int number;
  final double winProb;
  final double? fixedWin;
  int? position; // finishing position of the pick; 99 = unplaced / not in results

  bool get graded => position != null;
  bool get won => position == 1;

  Map<String, dynamic> toJson() =>
      {'r': raceId, 'l': label, 's': start.toIso8601String(), 't': type, 'n': number, 'nm': name, 'p': winProb, 'o': fixedWin, 'pos': position};
  static LoggedRace fromJson(Map<String, dynamic> j) => LoggedRace(
        raceId: j['r'],
        label: j['l'] ?? '',
        start: DateTime.parse(j['s']),
        type: j['t'] ?? 'T',
        number: j['n'] ?? 0,
        name: j['nm'] ?? '',
        winProb: (j['p'] as num).toDouble(),
        fixedWin: (j['o'] as num?)?.toDouble(),
        position: j['pos'],
      );
}

/// Record for one league over the graded predictions.
class LeagueRecord {
  int n = 0, winnersRight = 0;
  double expectedRight = 0; // sum of favourite probabilities
  double absTotalErr = 0, totalBias = 0;
  int ouWins = 0, ouLosses = 0; // model's side of the bookie total
  int atsWins = 0, atsLosses = 0; // model's side of the bookie line

  double get accuracy => n == 0 ? 0 : winnersRight / n;
  double get expected => n == 0 ? 0 : expectedRight / n;
  double get totalMae => n == 0 ? 0 : absTotalErr / n;
  double get bias => n == 0 ? 0 : totalBias / n;
}

class RaceRecord {
  int n = 0, wins = 0, places = 0, priced = 0;
  double expected = 0, returned = 0;
  double get strike => n == 0 ? 0 : wins / n;
  double get expectedStrike => n == 0 ? 0 : expected / n;
  double get roi => priced == 0 ? 0 : (returned - priced) / priced;
}

/// Logs every prediction and grades it once the event is over, so the app
/// can show its real record and correct a league that runs hot or cold.
class PredictionLog extends ChangeNotifier {
  PredictionLog._();
  static final PredictionLog instance = PredictionLog._();

  final Map<String, LoggedGame> games = {};
  final Map<String, LoggedRace> races = {};
  bool loaded = false;
  bool grading = false;
  bool _dirty = false;

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    try {
      for (final j in (json.decode(p.getString('predlog.games') ?? '[]') as List)) {
        final g = LoggedGame.fromJson(Map<String, dynamic>.from(j));
        games[g.eventId] = g;
      }
      for (final j in (json.decode(p.getString('predlog.races') ?? '[]') as List)) {
        final r = LoggedRace.fromJson(Map<String, dynamic>.from(j));
        races[r.raceId] = r;
      }
    } catch (_) {}
    loaded = true;
    notifyListeners();
  }

  Future<void> save() async {
    if (!_dirty) return;
    _dirty = false;
    // Keep the newest 1500 games and 1500 races.
    void trim<T>(Map<String, T> m, DateTime Function(T) when, int keep) {
      if (m.length <= keep) return;
      final keys = m.keys.toList()..sort((a, b) => when(m[b] as T).compareTo(when(m[a] as T)));
      for (final k in keys.skip(keep)) {
        m.remove(k);
      }
    }

    trim<LoggedGame>(games, (g) => g.date, 1500);
    trim<LoggedRace>(races, (r) => r.start, 1500);
    final p = await SharedPreferences.getInstance();
    await p.setString('predlog.games', json.encode(games.values.map((g) => g.toJson()).toList()));
    await p.setString('predlog.races', json.encode(races.values.map((r) => r.toJson()).toList()));
  }

  /// Records (or refreshes) the pre-game prediction for a fixture.
  void recordGame(GamePrediction p, {double appliedBias = 0}) {
    final g = p.game;
    if (g.isLive || g.isFinal || p.live) return;
    if (games[g.eventId]?.graded == true) return;
    games[g.eventId] = LoggedGame(
      leagueId: g.league.id,
      eventId: g.eventId,
      date: g.date,
      home: g.home.abbr,
      away: g.away.abbr,
      rawHome: p.muHome - appliedBias / 2,
      rawAway: p.muAway - appliedBias / 2,
      pHome: p.probHomeWin,
      pAway: p.probAwayWin,
      marketTotal: g.marketTotal,
      marketSpread: g.marketSpread,
    );
    _dirty = true;
  }

  /// Records the model's top pick for a race that has not been run.
  void recordRace(Race race, List<RunnerRating> rated) {
    if (rated.isEmpty || race.hasResults) return;
    if (races[race.id]?.graded == true) return;
    final top = rated.first;
    races[race.id] = LoggedRace(
      raceId: race.id,
      label: '${race.meetingName} R${race.number}',
      start: race.start,
      type: race.type,
      number: top.runner.number,
      name: top.runner.name,
      winProb: top.winProb,
      fixedWin: top.runner.fixedWin,
    );
    _dirty = true;
  }

  /// Grades finished events. Safe to call often; it only fetches what it needs.
  Future<int> gradePending() async {
    if (grading) return 0;
    grading = true;
    notifyListeners();
    var graded = 0;
    try {
      final now = DateTime.now();
      // Sports: one scoreboard request covers a league-day.
      final pending = games.values.where((g) => !g.graded && now.difference(g.date) > const Duration(hours: 3)).toList();
      final groups = <String, List<LoggedGame>>{};
      for (final g in pending) {
        final d = g.date.toLocal();
        groups.putIfAbsent('${g.leagueId}|${d.year}-${d.month}-${d.day}', () => []).add(g);
      }
      var requests = 0;
      for (final entry in groups.entries) {
        if (requests >= 15) break;
        final league = Leagues.byId(entry.value.first.leagueId);
        if (league == null) continue;
        requests++;
        try {
          final board = await EspnApi.scoreboard(league, entry.value.first.date.toLocal());
          for (final lg in entry.value) {
            for (final g in board) {
              if (g.eventId == lg.eventId && g.isFinal && g.homeScore != null && g.awayScore != null) {
                lg.homeScore = g.homeScore;
                lg.awayScore = g.awayScore;
                graded++;
                _dirty = true;
              }
            }
            // Postponed or vanished games: stop retrying after 5 days.
            if (!lg.graded && now.difference(lg.date) > const Duration(days: 5)) {
              games.remove(lg.eventId);
              _dirty = true;
            }
          }
        } catch (_) {}
      }
      // Racing: one request per race, capped per pass.
      final racePending = races.values.where((r) => !r.graded && now.difference(r.start) > const Duration(minutes: 20)).toList()
        ..sort((a, b) => a.start.compareTo(b.start));
      for (final lr in racePending.take(30)) {
        try {
          final race = await RacingApi.race(lr.raceId);
          if (race.results.isNotEmpty) {
            final hit = race.results.where((x) => x.number == lr.number).toList();
            lr.position = hit.isEmpty ? 99 : hit.first.position;
            graded++;
            _dirty = true;
          } else if (now.difference(lr.start) > const Duration(days: 3)) {
            races.remove(lr.raceId);
            _dirty = true;
          }
        } catch (_) {}
      }
      await save();
    } finally {
      grading = false;
      notifyListeners();
    }
    return graded;
  }

  Map<String, LeagueRecord> get leagueRecords {
    final out = <String, LeagueRecord>{};
    for (final g in games.values.where((g) => g.graded)) {
      final r = out.putIfAbsent(g.leagueId, () => LeagueRecord());
      final hs = g.homeScore!, as = g.awayScore!;
      r.n++;
      final favHome = g.pHome >= g.pAway;
      final z = g.pHome + g.pAway;
      r.expectedRight += z > 0 ? (favHome ? g.pHome : g.pAway) / z : 0.5;
      if (hs != as && (hs > as) == favHome) r.winnersRight++;
      r.absTotalErr += (g.actualTotal - g.projTotal).abs();
      r.totalBias += g.actualTotal - g.projTotal;
      if (g.marketTotal != null && g.actualTotal != g.marketTotal && g.projTotal != g.marketTotal) {
        final modelOver = g.projTotal > g.marketTotal!;
        (g.actualTotal > g.marketTotal!) == modelOver ? r.ouWins++ : r.ouLosses++;
      }
      if (g.marketSpread != null) {
        // marketSpread is the home handicap (negative = home favoured).
        final modelMargin = g.rawHome - g.rawAway;
        final cover = (hs - as) + g.marketSpread!;
        final modelCover = modelMargin + g.marketSpread!;
        if (cover != 0 && modelCover != 0) (cover > 0) == (modelCover > 0) ? r.atsWins++ : r.atsLosses++;
      }
    }
    return out;
  }

  Map<String, RaceRecord> get raceRecords {
    final out = <String, RaceRecord>{};
    for (final r in races.values.where((r) => r.graded)) {
      final rec = out.putIfAbsent(r.type, () => RaceRecord());
      rec.n++;
      rec.expected += r.winProb;
      if (r.won) rec.wins++;
      if ((r.position ?? 99) <= 3) rec.places++;
      if (r.fixedWin != null && r.fixedWin! > 1) {
        rec.priced++;
        if (r.won) rec.returned += r.fixedWin!;
      }
    }
    return out;
  }

  int get pendingCount => games.values.where((g) => !g.graded).length + races.values.where((r) => !r.graded).length;

  /// Shrunk average miss on totals for a league (actual minus projected),
  /// from its most recent graded games. Used to self-correct projections.
  double totalBias(League league) {
    final recent = games.values.where((g) => g.graded && g.leagueId == league.id).toList()..sort((a, b) => b.date.compareTo(a.date));
    final sample = recent.take(120).toList();
    if (sample.length < 15) return 0;
    final sum = sample.fold(0.0, (s, g) => s + (g.actualTotal - g.projTotal));
    final shrunk = sum / (sample.length + 40);
    final cap = league.priorTeamScore * 2 * 0.04;
    return shrunk.clamp(-cap, cap);
  }
}
