import '../models/race.dart';

/// What the quaddie would pay if a given runner wins the next leg.
class WillPay {
  WillPay(this.runner, this.price, this.value);
  final Runner runner;
  final double price; // its win odds now
  final double value; // estimated quaddie dividend for $1
}

/// Follows a quaddie through its four legs: who won each leg, what the
/// quaddie paid, and, while it is still running, an estimate of what it will
/// pay for each runner in the next leg.
///
/// The feed gives the real dividend after the last leg but no live "will
/// pay" figures, so those are estimated from prices:
///   quaddie for $1 = factor x win dividend of each leg already run
///                           x win odds of the runner in each leg to come.
/// tool/quaddie_fit.py fitted the factor on finished quaddies and measured
/// how close the estimate was.
class QuaddieTracker {
  QuaddieTracker(this.legs);

  /// The four races in order; null where a race has not loaded.
  final List<Race?> legs;

  /// Finished quaddies the estimate was checked against.
  static const samples = 72;

  /// By number of legs still to run (0 to 3): [factor, share of estimates
  /// within 1.5x of the real dividend, share within 2x].
  static const List<List<double>> byPending = [
    [1.60, 0.76, 0.93],
    [1.66, 0.72, 0.92],
    [1.60, 0.69, 0.89],
    [1.71, 0.72, 0.90],
  ];

  static RaceResult? winnerOf(Race? race) => race == null || race.results.isEmpty ? null : race.results.first;

  /// A runner's win odds now: the fixed price, else the tote price.
  static double? priceOf(Runner r) {
    if (r.scratched) return null;
    if ((r.fixedWin ?? 0) > 1) return r.fixedWin;
    if ((r.poolWin ?? 0) > 1) return r.poolWin;
    return null;
  }

  static Runner? favourite(Race race) {
    Runner? best;
    for (final r in race.runners) {
      final p = priceOf(r);
      if (p == null) continue;
      if (best == null || p < priceOf(best)!) best = r;
    }
    return best;
  }

  /// How many legs have been run, counting from the first.
  int get runCount {
    var n = 0;
    while (n < legs.length && winnerOf(legs[n]) != null) {
      n++;
    }
    return n;
  }

  bool get complete => legs.length == 4 && legs.every((r) => r != null);
  bool get finished => legs.isNotEmpty && runCount == legs.length;
  bool get abandoned => legs.any((r) => r != null && r.abandoned);

  /// The leg about to be run, or null when the quaddie is over or not loaded.
  Race? get nextLeg => runCount < legs.length ? legs[runCount] : null;

  /// What a $1 win bet on the winner of leg [i] paid (the tote dividend, or
  /// its last price until the dividend is declared).
  double? winPrice(int i) {
    final race = legs[i], w = winnerOf(race);
    if (race == null || w == null) return null;
    final d = race.winDividend(w.number);
    if (d != null && d > 1) return d;
    final r = race.runner(w.number);
    if (r == null) return null;
    if ((r.poolWin ?? 0) > 1) return r.poolWin;
    return (r.fixedWin ?? 0) > 1 ? r.fixedWin : null;
  }

  /// The official quaddie dividends, declared on the last leg.
  List<Dividend> get paid {
    for (final race in legs.reversed) {
      if (race != null && race.quaddieDividends.isNotEmpty) return race.quaddieDividends;
    }
    return const [];
  }

  /// Dollars bet into the quaddie pool.
  double get pool {
    var best = 0.0;
    for (final r in legs) {
      if (r != null && r.quaddiePool > best) best = r.quaddiePool;
    }
    return best;
  }

  /// The most a $1 quaddie can pay: the whole pool after the tote's cut,
  /// which is what a lone winning $1 ticket would collect.
  static const poolShare = 0.8;

  /// Legs still to run, including the next one.
  int get pending => legs.length - runCount;

  /// Share of past estimates within 1.5x (and 2x) of the real dividend at this stage.
  double get within15 => byPending[pending.clamp(0, 3)][1];
  double get within2 => byPending[pending.clamp(0, 3)][2];

  /// True when later legs are filled in with their favourites, so the real
  /// dividend can only be this much or more (if a longer-priced horse wins).
  bool get assumesFavourites => pending > 1;

  /// Estimated quaddie dividend for each runner in the next leg, if it wins
  /// and the favourite wins every leg after it. Empty until the first leg has
  /// been run, or when prices are missing.
  List<WillPay> willPays() {
    if (!complete || abandoned || runCount == 0 || finished) return const [];
    var base = byPending[pending.clamp(0, 3)][0];
    for (var i = 0; i < runCount; i++) {
      final p = winPrice(i);
      if (p == null) return const [];
      base *= p;
    }
    for (var i = runCount + 1; i < legs.length; i++) {
      final fav = favourite(legs[i]!);
      if (fav == null) return const [];
      base *= priceOf(fav)!;
    }
    final cap = pool > 0 ? pool * poolShare : double.infinity;
    final out = <WillPay>[];
    for (final r in legs[runCount]!.runners) {
      final p = priceOf(r);
      if (p != null) out.add(WillPay(r, p, base * p > cap ? cap : base * p));
    }
    out.sort((a, b) => a.price.compareTo(b.price));
    return out;
  }
}
