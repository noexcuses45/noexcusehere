import '../services/odds_api.dart';
import 'predictor.dart';

/// A leg priced by a bookmaker, with the model's view of it.
class PricedLeg {
  PricedLeg({required this.leg, required this.odds, required this.game});
  final Leg leg;
  final double odds;
  final GamePrediction game;

  /// Expected return per unit staked (1.0 = break even).
  double get ev => leg.prob * odds;
  double get edge => ev - 1;
  double get fairOdds => leg.fairOdds;
}

/// Model probabilities for the legs a bookmaker actually prices.
class BookieModel {
  static List<PricedLeg> priceLegs(GamePrediction p, BookiePrices b) {
    final out = <PricedLeg>[];
    final h = p.game.home.abbr, a = p.game.away.abbr;
    void add(Leg leg, double? odds) {
      if (odds == null || odds <= 1) return;
      leg.prob = p.legProb(leg);
      out.add(PricedLeg(leg: leg, odds: odds, game: p));
    }

    add(Leg(type: LegType.moneyline, side: Side.home, label: '$h to win'), b.homeWin);
    add(Leg(type: LegType.moneyline, side: Side.away, label: '$a to win'), b.awayWin);
    if (b.spreadPoint != null) {
      final sp = b.spreadPoint!;
      add(Leg(type: LegType.handicap, side: Side.home, line: sp, label: '$h ${sp > 0 ? '+' : ''}${_fmt(sp)}'), b.spreadHome);
      add(Leg(type: LegType.handicap, side: Side.away, line: -sp, label: '$a ${-sp > 0 ? '+' : ''}${_fmt(-sp)}'), b.spreadAway);
    }
    if (b.totalPoint != null) {
      final t = b.totalPoint!;
      add(Leg(type: LegType.total, over: true, line: t, label: 'Over ${_fmt(t)}'), b.over);
      add(Leg(type: LegType.total, over: false, line: t, label: 'Under ${_fmt(t)}'), b.under);
    }
    out.sort((x, y) => y.ev.compareTo(x.ev));
    return out;
  }

  static String _fmt(double x) => x == x.roundToDouble() ? x.toStringAsFixed(0) : x.toStringAsFixed(1);

  /// Best multi to place at one bookmaker: the highest-value leg from each
  /// game (one per game), combined by expected value.
  static List<BookieMulti> bestMultis(List<List<PricedLeg>> perGame, {List<int> sizes = const [2, 3, 4, 5]}) {
    final picks = <PricedLeg>[];
    for (final legs in perGame) {
      if (legs.isEmpty) continue;
      // Prefer value, but never take a leg the model gives under 35%.
      final ok = legs.where((l) => l.leg.prob >= 0.35).toList();
      if (ok.isEmpty) continue;
      picks.add(ok.first);
    }
    picks.sort((x, y) => y.ev.compareTo(x.ev));
    final out = <BookieMulti>[];
    for (final n in sizes) {
      if (picks.length < n) continue;
      out.add(BookieMulti(picks.take(n).toList()));
    }
    return out;
  }
}

class BookieMulti {
  BookieMulti(this.legs);
  final List<PricedLeg> legs;
  double get odds => legs.fold(1.0, (o, l) => o * l.odds);
  double get prob => legs.fold(1.0, (p, l) => p * l.leg.prob);
  double get ev => prob * odds;
}
