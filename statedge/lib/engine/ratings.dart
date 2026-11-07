import 'dart:math' as math;

import '../models/game.dart';
import '../models/league.dart';
import 'stats.dart';

/// Offence / defence strength for one team, relative to the league (1.0 = average).
class TeamRating {
  TeamRating({required this.id, this.abbr = ''});
  final String id;
  String abbr;
  double off = 1;
  double def = 1;
  int games = 0;
  final List<GameResult> recent = [];
}

/// League-wide multiplicative rating model fitted from results, with
/// recency weighting, opponent adjustment and shrinkage toward the prior.
class LeagueModel {
  LeagueModel(this.league);

  final League league;
  final Map<String, TeamRating> teams = {};
  double leagueAvg = 0; // average team score
  double homeAdv = 0; // home minus away margin
  double teamSd = 0; // sd of a team's score around its expectation
  double rho = 0; // correlation of the two teams' residuals
  int gamesFitted = 0;
  DateTime fittedAt = DateTime.now();

  bool get thin => gamesFitted < teams.length * 2;

  /// Recency weight for a result played [date].
  double weightFor(DateTime date, DateTime now) {
    final days = now.difference(date).inHours / 24.0;
    return math.exp(-math.ln2 * math.max(0, days) / league.halfLifeDays);
  }

  void fit(List<GameResult> results, {DateTime? now, Map<String, String> abbrs = const {}}) {
    now ??= DateTime.now();
    teams.clear();
    final games = results.where((g) => g.homeScore.isFinite && g.awayScore.isFinite).toList()
      ..sort((a, b) => a.date.compareTo(b.date));
    gamesFitted = games.length;
    final w = games.map((g) => weightFor(g.date, now!)).toList();

    TeamRating team(String id, String abbr) =>
        teams.putIfAbsent(id, () => TeamRating(id: id, abbr: abbr))..abbr = abbr.isNotEmpty ? abbr : teams[id]!.abbr;
    for (final g in games) {
      team(g.homeId, g.homeAbbr).games++;
      team(g.awayId, g.awayAbbr).games++;
      team(g.homeId, g.homeAbbr).recent.add(g);
      team(g.awayId, g.awayAbbr).recent.add(g);
    }
    for (final id in abbrs.keys) {
      team(id, abbrs[id]!);
    }

    // League average scoring, shrunk toward the prior with 10 games of weight.
    final scores = <double>[], sw = <double>[];
    for (var i = 0; i < games.length; i++) {
      scores.add(games[i].homeScore);
      scores.add(games[i].awayScore);
      sw.add(w[i]);
      sw.add(w[i]);
    }
    final sumW = sw.fold(0.0, (a, b) => a + b);
    const kAvg = 20.0;
    leagueAvg = sumW + kAvg > 0
        ? (Stats.weightedMean(scores, sw) * sumW + league.priorTeamScore * kAvg) / (sumW + kAvg)
        : league.priorTeamScore;

    // Home advantage, shrunk toward prior with 40 games of weight.
    final margins = <double>[], mw = <double>[];
    for (var i = 0; i < games.length; i++) {
      if (!games[i].neutral) {
        margins.add(games[i].margin);
        mw.add(w[i]);
      }
    }
    final mSum = mw.fold(0.0, (a, b) => a + b);
    const kHa = 40.0;
    homeAdv = mSum + kHa > 0
        ? (Stats.weightedMean(margins, mw) * mSum + league.priorHomeAdv * kHa) / (mSum + kHa)
        : league.priorHomeAdv;

    // Iterative proportional fitting of off/def with shrinkage (k0 pseudo-games at 1.0).
    const k0 = 4.0;
    for (var iter = 0; iter < 25; iter++) {
      final offNum = <String, double>{}, offDen = <String, double>{};
      final defNum = <String, double>{}, defDen = <String, double>{};
      for (var i = 0; i < games.length; i++) {
        final g = games[i];
        final h = teams[g.homeId]!, a = teams[g.awayId]!;
        final hm = _homeMult(g.neutral), am = _awayMult(g.neutral);
        // home scoring: avg * off_h * def_a * hm
        final expH = leagueAvg * a.def * hm;
        final expA = leagueAvg * h.def * am;
        offNum[h.id] = (offNum[h.id] ?? 0) + w[i] * g.homeScore / expH;
        offDen[h.id] = (offDen[h.id] ?? 0) + w[i];
        offNum[a.id] = (offNum[a.id] ?? 0) + w[i] * g.awayScore / expA;
        offDen[a.id] = (offDen[a.id] ?? 0) + w[i];
        final expHd = leagueAvg * a.off * am; // what home concedes
        final expAd = leagueAvg * h.off * hm;
        defNum[h.id] = (defNum[h.id] ?? 0) + w[i] * g.awayScore / expHd;
        defDen[h.id] = (defDen[h.id] ?? 0) + w[i];
        defNum[a.id] = (defNum[a.id] ?? 0) + w[i] * g.homeScore / expAd;
        defDen[a.id] = (defDen[a.id] ?? 0) + w[i];
      }
      var offMean = 0.0, defMean = 0.0;
      for (final t in teams.values) {
        t.off = ((offNum[t.id] ?? 0) + k0) / ((offDen[t.id] ?? 0) + k0);
        t.def = ((defNum[t.id] ?? 0) + k0) / ((defDen[t.id] ?? 0) + k0);
        offMean += t.off;
        defMean += t.def;
      }
      if (teams.isNotEmpty) {
        offMean /= teams.length;
        defMean /= teams.length;
        for (final t in teams.values) {
          t.off /= offMean;
          t.def /= defMean;
        }
      }
    }

    // Residual spread and correlation.
    final resH = <double>[], resA = <double>[];
    for (final g in games) {
      final e = expected(g.homeId, g.awayId, neutral: g.neutral);
      resH.add(g.homeScore - e.$1);
      resA.add(g.awayScore - e.$2);
    }
    final pooled = <double>[...resH, ...resA];
    final pooledW = <double>[...w, ...w];
    final n = pooled.length.toDouble();
    const kSd = 30.0;
    final sampleVar = pooled.length > 2 ? Stats.weightedVar(pooled, pooledW) : 0.0;
    final priorVar = league.priorTeamSd * league.priorTeamSd;
    teamSd = math.sqrt((sampleVar * n + priorVar * kSd) / (n + kSd));
    final sampleRho = games.length > 5 ? Stats.weightedCorr(resH, resA, w) : 0.0;
    rho = (sampleRho * games.length + league.priorRho * kSd) / (games.length + kSd);
    fittedAt = now;
  }

  double _homeMult(bool neutral) => neutral ? 1 : (leagueAvg + homeAdv / 2) / leagueAvg;
  double _awayMult(bool neutral) => neutral ? 1 : (leagueAvg - homeAdv / 2) / leagueAvg;

  TeamRating ratingOf(String id) => teams[id] ?? TeamRating(id: id);

  /// Expected (home, away) score.
  (double, double) expected(String homeId, String awayId, {bool neutral = false}) {
    final h = ratingOf(homeId), a = ratingOf(awayId);
    final muH = leagueAvg * h.off * a.def * _homeMult(neutral);
    final muA = leagueAvg * a.off * h.def * _awayMult(neutral);
    return (muH, muA);
  }

  /// Simple recent-form summary for display.
  List<GameResult> lastGames(String teamId, int n) {
    final r = ratingOf(teamId).recent.toList()..sort((a, b) => b.date.compareTo(a.date));
    return r.take(n).toList();
  }
}
