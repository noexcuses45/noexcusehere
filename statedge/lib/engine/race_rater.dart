import 'dart:math' as math;

import '../models/race.dart';
import 'race_features.dart';
import 'stats.dart';

/// A rated runner with win/place probabilities from form, not from the market.
class RunnerRating {
  RunnerRating({required this.runner, required this.rating, required this.reasons});
  final Runner runner;
  final double rating;
  final List<String> reasons;
  double winProb = 0;
  double placeProb = 0;
  double get fairWin => Stats.fairOdds(winProb);
  double get fairPlace => Stats.fairOdds(placeProb);

  /// How much better the TAB fixed price is than the model's fair price (>0 = value).
  double? get winEdge {
    final o = runner.fixedWin;
    if (o == null || o <= 1) return null;
    return o * winProb - 1; // expected return per unit
  }
}

/// Form-based rating: a conditional-logit model fitted on ~2,000 finished
/// thoroughbred races (tool/fit_racing.dart). Inputs are form only: recent
/// placings, strike rates, prizemoney class, jockey/trainer strike rates,
/// barrier, weight, speed map, freshness, age and gear. Prices never feed it.
class RaceRater {
  /// Fitted weights, one per RaceFeatures.names entry (raw feature scale).
  static const weights = [
    3.4648, // formScore
    -0.4894, // noForm
    -0.0444, // lastWin
    0.0795, // lastPlace
    -0.2666, // winRate
    -0.4785, // placeRate
    0.1150, // logStarts
    0.3814, // prizeRel
    2.8066, // jockeySR
    2.2560, // trainerSR
    -0.0453, // barrierPos
    -0.2091, // barrierSprint
    0.0364, // weightRel
    0.1598, // leader
    0.0939, // back
    -0.1578, // firstUp
    -0.2447, // ageRel
    -0.2185, // apprentice
    -0.0092, // blinkers
  ];

  static List<RunnerRating> rate(Race race) {
    final runners = race.runners.where((r) => !r.scratched).toList();
    if (runners.isEmpty) return [];
    final x = RaceFeatures.matrix(race, runners);
    final out = <RunnerRating>[];
    for (var i = 0; i < runners.length; i++) {
      var score = 0.0;
      for (var f = 0; f < weights.length; f++) {
        score += weights[f] * x[i][f];
      }
      out.add(RunnerRating(runner: runners[i], rating: score, reasons: _reasons(runners[i], x[i], race)));
    }
    final maxR = out.map((r) => r.rating).reduce(math.max);
    var z = 0.0;
    for (final r in out) {
      z += math.exp(r.rating - maxR);
    }
    for (final r in out) {
      r.winProb = math.exp(r.rating - maxR) / z;
    }
    final paid = race.positionsPaid > 0 ? race.positionsPaid : (runners.length >= 8 ? 3 : 2);
    _placeProbs(out, paid);
    out.sort((a, b) => b.winProb.compareTo(a.winProb));
    return out;
  }

  static List<String> _reasons(Runner r, List<double> x, Race race) {
    final out = <String>[];
    final form = RaceFeatures.parseForm(r.form);
    final recent = form.reversed.where((p) => p != null).take(3).toList();
    if (x[1] == 1) out.add('No form shown');
    if (recent.isNotEmpty && recent.first == 1) out.add('Last start winner');
    if (recent.where((p) => p! <= 3).length >= 2) out.add('Placed 2 of last 3');
    if (x[0] >= 0.6 && recent.length >= 3) out.add('Consistent recent form');
    if (x[7] >= 0.6) out.add('Racing below its class');
    if (x[7] <= -0.6) out.add('Stepping up in class');
    if (x[8] >= 0.07) out.add('In-form jockey ${((x[8] + 0.11) * 100).round()}%');
    if (x[9] >= 0.08) out.add('Strong trainer strike rate');
    if (x[11] >= 0.75) out.add('Wide barrier in a sprint');
    if (x[13] == 1) out.add('Should settle on pace');
    if (x[15] == 1) out.add('First-up from a spell');
    if (x[16] <= -1.5) out.add('Lightly raced youngster');
    return out;
  }

  static void _placeProbs(List<RunnerRating> rs, int paid) {
    final rng = Rng(7);
    const n = 3000;
    final counts = List<int>.filled(rs.length, 0);
    final strengths = rs.map((r) => r.winProb).toList();
    for (var s = 0; s < n; s++) {
      final remaining = List<int>.generate(rs.length, (i) => i);
      final str = List<double>.of(strengths);
      for (var pos = 0; pos < paid && remaining.isNotEmpty; pos++) {
        var tot = 0.0;
        for (final i in remaining) {
          tot += str[i];
        }
        var u = rng.uniform() * tot;
        var pick = remaining.last;
        for (final i in remaining) {
          u -= str[i];
          if (u <= 0) {
            pick = i;
            break;
          }
        }
        counts[pick]++;
        remaining.remove(pick);
      }
    }
    for (var i = 0; i < rs.length; i++) {
      rs[i].placeProb = counts[i] / n;
    }
  }




}

/// Quaddie / multi-race suggestion: top picks per leg and the combo count.
class QuaddiePlan {
  QuaddiePlan({required this.legs});
  final List<QuaddieLeg> legs;
  int get combos => legs.fold(1, (c, l) => c * l.picks.length);
  double get prob => legs.fold(1.0, (p, l) => p * l.picks.fold(0.0, (s, r) => s + r.winProb));

  static QuaddiePlan build(List<Race> races, Map<String, List<RunnerRating>> ratings) {
    final legs = <QuaddieLeg>[];
    for (final race in races) {
      final rated = ratings[race.id] ?? [];
      if (rated.isEmpty) continue;
      // Take runners until the leg covers ~50% of the win probability, 1-3 picks.
      final picks = <RunnerRating>[];
      var cum = 0.0;
      for (final r in rated) {
        picks.add(r);
        cum += r.winProb;
        if (cum >= 0.5 || picks.length >= 3) break;
      }
      // Standouts (>40%) can be taken alone.
      if (rated.first.winProb >= 0.4) {
        picks
          ..clear()
          ..add(rated.first);
      }
      legs.add(QuaddieLeg(race: race, picks: picks));
    }
    return QuaddiePlan(legs: legs);
  }
}

class QuaddieLeg {
  QuaddieLeg({required this.race, required this.picks});
  final Race race;
  final List<RunnerRating> picks;
  double get prob => picks.fold(0.0, (s, r) => s + r.winProb);
}
