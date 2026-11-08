import 'dart:math' as math;

import '../models/game.dart';
import '../services/espn_api.dart' show BoxTable;
import 'predictor.dart';
import 'ratings.dart';
import 'stats.dart';

/// One player's line from a finished game box score.
class PlayerGameLine {
  PlayerGameLine({
    required this.name,
    required this.teamId,
    required this.date,
    required this.minutes,
    required this.pts,
    required this.reb,
    required this.ast,
    required this.threes,
    this.started = false,
  });
  final String name;
  final String teamId;
  final DateTime date;
  final double minutes, pts, reb, ast, threes;
  final bool started;
  double get pra => pts + reb + ast;
}

/// Turns recent box scores into projections for the upcoming game.
class PlayerProps {
  static const stats = ['PTS', 'REB', 'AST', '3PM', 'PRA'];

  /// Current value of [stat] for a box score row.
  static double currentValue(BoxTable t, String name, String stat) {
    for (final r in t.rows) {
      if (r.name != name) continue;
      switch (stat) {
        case 'PTS':
          return r.statNum(t.columns, 'PTS');
        case 'REB':
          return r.statNum(t.columns, 'REB');
        case 'AST':
          return r.statNum(t.columns, 'AST');
        case '3PM':
          return r.statNum(t.columns, '3PT');
        default:
          return r.statNum(t.columns, 'PTS') + r.statNum(t.columns, 'REB') + r.statNum(t.columns, 'AST');
      }
    }
    return 0;
  }

  /// Re-centres pre-game projections on what has already happened:
  /// final = current + remaining share of the pre-game expectation.
  static List<PlayerProjection> liveAdjust(
    List<PlayerProjection> pre, {
    required List<BoxTable> tables,
    required String homeTeamId,
    required String awayTeamId,
    required double remaining,
  }) {
    final out = <PlayerProjection>[];
    for (final p in pre) {
      final teamId = p.side == Side.home ? homeTeamId : awayTeamId;
      BoxTable? table;
      for (final t in tables) {
        if (t.teamId == teamId) {
          table = t;
          break;
        }
      }
      if (table == null) {
        out.add(p);
        continue;
      }
      final row = table.rows.where((r) => r.name == p.name).toList();
      final played = row.isNotEmpty && row.first.statNum(table.columns, 'MIN') > 0;
      if (row.isNotEmpty && row.first.dnp) continue; // not playing tonight
      final factor = played ? 1.0 : (remaining > 0.75 ? 1.0 : 0.4);
      final cur = currentValue(table, p.name, p.stat);
      out.add(PlayerProjection(
        name: p.name,
        side: p.side,
        stat: p.stat,
        mean: cur + p.mean * remaining * factor,
        sd: math.max(p.sd * math.sqrt(remaining), 0.3),
        games: p.games,
        minutes: p.minutes,
        teamCorr: p.teamCorr,
        lastValues: p.lastValues,
        flag: p.flag,
      ));
    }
    return out;
  }

  static List<PlayerProjection> project({
    required Game game,
    required LeagueModel model,
    required List<PlayerGameLine> homeLines,
    required List<PlayerGameLine> awayLines,
    Map<String, String> injuries = const {},
    int maxPlayersPerTeam = 6,
  }) {
    final out = <PlayerProjection>[];
    for (final side in Side.values) {
      final lines = side == Side.home ? homeLines : awayLines;
      final oppId = side == Side.home ? game.away.id : game.home.id;
      final oppDef = model.ratingOf(oppId).def; // >1 means opponent concedes more
      final byPlayer = <String, List<PlayerGameLine>>{};
      for (final l in lines) {
        byPlayer.putIfAbsent(l.name, () => []).add(l);
      }
      final teamDates = lines.map((l) => l.date).toSet().toList()..sort((a, b) => b.compareTo(a));
      final projections = <PlayerProjection>[];
      byPlayer.forEach((name, ls) {
        ls.sort((a, b) => b.date.compareTo(a.date));
        final played = ls.where((l) => l.minutes > 0).toList();
        if (played.length < 3) return;
        final avgMin = Stats.mean(played.map((l) => l.minutes));
        if (avgMin < 15) return;
        final weights = List.generate(played.length, (i) => math.pow(0.85, i).toDouble());
        final missedLast = teamDates.isNotEmpty && played.first.date != teamDates.first;
        String? flag = injuries[name];
        if (flag == null && missedLast) flag = 'Missed last game';
        if (flag != null && flag.toLowerCase().contains('out')) return;
        for (final stat in stats) {
          final vals = played.map((l) {
            switch (stat) {
              case 'PTS':
                return l.pts;
              case 'REB':
                return l.reb;
              case 'AST':
                return l.ast;
              case '3PM':
                return l.threes;
              default:
                return l.pra;
            }
          }).toList();
          var mean = Stats.weightedMean(vals, weights);
          if (mean < (stat == '3PM' ? 1.0 : 3.0)) continue;
          // Opponent adjustment: scoring stats scale with the opponent's defence.
          final adj = stat == 'REB' ? math.pow(oppDef, 0.3) : math.pow(oppDef, 0.7);
          mean *= adj;
          final sampleSd = math.sqrt(Stats.weightedVar(vals, weights));
          // Floor the spread: counting stats are at least Poisson-ish noisy.
          final sd = math.max(sampleSd, math.sqrt(mean) * 0.9);
          projections.add(PlayerProjection(
            name: name,
            side: side,
            stat: stat,
            mean: mean,
            sd: sd,
            games: played.length,
            minutes: avgMin,
            teamCorr: stat == 'PTS' || stat == 'PRA' ? 0.35 : (stat == '3PM' ? 0.25 : 0.12),
            lastValues: vals.take(5).toList(),
            flag: flag,
          ));
        }
      });
      // Keep the most-used players so the list stays short.
      final names = <String>{};
      projections.sort((a, b) => b.minutes.compareTo(a.minutes));
      for (final p in projections) {
        names.add(p.name);
        if (names.length > maxPlayersPerTeam) break;
      }
      names.remove(names.length > maxPlayersPerTeam ? names.last : '');
      out.addAll(projections.where((p) => names.contains(p.name)));
    }
    return out;
  }
}
