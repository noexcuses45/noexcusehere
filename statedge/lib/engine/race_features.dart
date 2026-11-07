import 'dart:math' as math;

import '../models/race.dart';

/// Form-only features for a runner, relative to its race. Shared by the app's
/// rater and the offline fitting tool so both see exactly the same numbers.
class RaceFeatures {
  static const names = [
    'formScore', // weighted recent placings (0..1), 0.3 if unknown
    'noForm', // 1 if no starts shown
    'lastWin', // last start was a win
    'lastPlace', // last start top 3
    'winRate', // career wins / starts (from last 20)
    'placeRate',
    'logStarts', // log(1 + starts)
    'prizeRel', // log prizemoney per start, relative to race mean
    'jockeySR', // jockey strike rate - 0.11 (0 if unknown)
    'trainerSR', // trainer strike rate - 0.12
    'barrierPos', // (barrier-1)/(field-1)
    'barrierSprint', // barrierPos when distance <= 1400
    'weightRel', // kg above race mean (thoroughbreds)
    'leader', // speed map: leader / on pace
    'back', // speed map: back / off pace
    'firstUp', // returning from a spell
    'ageRel', // age - race mean age
    'apprentice',
    'blinkers',
  ];

  static List<List<double>> matrix(Race race, List<Runner> rs) {
    final n = rs.length;
    final weights = rs.map((r) => r.weight ?? 0).where((w) => w > 0).toList();
    final meanW = weights.isEmpty ? 0.0 : weights.reduce((a, b) => a + b) / weights.length;
    final ages = rs.map((r) => (r.age ?? 0).toDouble()).where((a) => a > 0).toList();
    final meanAge = ages.isEmpty ? 0.0 : ages.reduce((a, b) => a + b) / ages.length;
    final prize = rs.map(_logPrizePerStart).toList();
    final meanPrize = prize.reduce((a, b) => a + b) / n;
    final sprint = race.distance > 0 && race.distance <= 1400 ? 1.0 : 0.0;
    return List.generate(n, (i) {
      final r = rs[i];
      final form = parseForm(r.form);
      final starts = form.where((p) => p != null).length;
      final recent = form.reversed.toList();
      final lastPl = recent.firstWhere((p) => p != null, orElse: () => null);
      final wins = form.where((p) => p == 1).length;
      final places = form.where((p) => p != null && p <= 3).length;
      final barrierPos = r.barrier != null && n > 1 ? ((r.barrier! - 1) / (n - 1)).clamp(0.0, 1.5) : 0.5;
      final sm = r.speedMap.toLowerCase();
      return [
        formScore(form),
        starts == 0 ? 1.0 : 0.0,
        lastPl == 1 ? 1.0 : 0.0,
        lastPl != null && lastPl <= 3 ? 1.0 : 0.0,
        starts > 0 ? wins / starts : 0.0,
        starts > 0 ? places / starts : 0.0,
        math.log(1 + starts),
        prize[i] - meanPrize,
        r.jockeyStarts >= 10 ? r.jockeyWins / r.jockeyStarts - 0.11 : 0.0,
        r.trainerStarts >= 10 ? r.trainerWins / r.trainerStarts - 0.12 : 0.0,
        barrierPos,
        barrierPos * sprint,
        r.weight != null && meanW > 0 && race.type == 'T' ? r.weight! - meanW : 0.0,
        sm.contains('leader') || sm.contains('on pace') ? 1.0 : 0.0,
        sm.contains('back') || sm.contains('off pace') ? 1.0 : 0.0,
        form.isNotEmpty && form.last == null ? 1.0 : 0.0,
        r.age != null && meanAge > 0 ? r.age! - meanAge : 0.0,
        r.apprentice ? 1.0 : 0.0,
        r.gear.toLowerCase().contains('blinkers') ? 1.0 : 0.0,
      ];
    });
  }

  static double _logPrizePerStart(Runner r) {
    final starts = parseForm(r.form).where((p) => p != null).length;
    if (r.prizeMoney <= 0 || starts == 0) return math.log(20000);
    return math.log(r.prizeMoney / starts + 1000);
  }

  /// Weighted recent-placings score in 0..1 (most recent counts most).
  static double formScore(List<int?> form) {
    final recent = form.reversed.take(10).toList();
    var s = 0.0, w = 0.0;
    var starts = 0;
    for (final pl in recent) {
      if (pl == null) continue;
      final wt = math.pow(0.8, starts).toDouble();
      s += wt * placeScore(pl);
      w += wt;
      starts++;
    }
    return w > 0 ? s / w : 0.3;
  }

  /// Parses a form string like "1x2340" into placings (null = spell), oldest first.
  static List<int?> parseForm(String form) {
    final out = <int?>[];
    for (final ch in form.toLowerCase().split('')) {
      if (ch == 'x' || ch == '/' || ch == '-') {
        out.add(null);
      } else if (ch == '0') {
        out.add(10);
      } else if (RegExp(r'[1-9]').hasMatch(ch)) {
        out.add(int.parse(ch));
      } else if (ch == 'f' || ch == 'p' || ch == 'u' || ch == 'l') {
        out.add(12);
      }
    }
    return out;
  }

  static double placeScore(int place) {
    switch (place) {
      case 1:
        return 1.0;
      case 2:
        return 0.78;
      case 3:
        return 0.62;
      case 4:
        return 0.48;
      case 5:
        return 0.38;
      case 6:
      case 7:
        return 0.26;
      case 8:
      case 9:
        return 0.18;
      default:
        return 0.08;
    }
  }
}
