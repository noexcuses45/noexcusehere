import 'package:flutter_test/flutter_test.dart';
import 'package:statedge/engine/predictor.dart';
import 'package:statedge/engine/race_rater.dart';
import 'package:statedge/engine/ratings.dart';
import 'package:statedge/engine/stats.dart';
import 'package:statedge/models/game.dart';
import 'package:statedge/models/league.dart';
import 'package:statedge/models/race.dart';

void main() {
  group('Stats', () {
    test('normal cdf is symmetric and accurate', () {
      expect(Stats.normalCdf(0), closeTo(0.5, 1e-6));
      expect(Stats.normalCdf(1.96), closeTo(0.975, 1e-4));
      expect(Stats.normalCdf(-1.96), closeTo(0.025, 1e-4));
      expect(Stats.normalInv(0.975), closeTo(1.96, 1e-3));
    });
    test('poisson table sums to one', () {
      final t = Stats.poissonTable(2.5, 20);
      expect(t.reduce((a, b) => a + b), closeTo(1, 1e-9));
      expect(t[2], closeTo(0.2565, 1e-3));
    });
    test('weighted variance equals sample variance with equal weights', () {
      final xs = [1.0, 2.0, 4.0, 7.0];
      final v = Stats.weightedVar(xs, [1, 1, 1, 1]);
      expect(v, closeTo(7.0, 1e-9));
    });
  });

  group('LeagueModel', () {
    LeagueModel fitSynthetic(League league) {
      // Three teams: A strong, B average, C weak. Round robin x4.
      final now = DateTime(2026, 11, 7);
      final strength = {'A': 1.15, 'B': 1.0, 'C': 0.87};
      final results = <GameResult>[];
      var n = 0;
      for (var round = 0; round < 4; round++) {
        for (final h in ['A', 'B', 'C']) {
          for (final a in ['A', 'B', 'C']) {
            if (h == a) continue;
            final hs = league.priorTeamScore * strength[h]! / strength[a]! + league.priorHomeAdv / 2;
            final as = league.priorTeamScore * strength[a]! / strength[h]! - league.priorHomeAdv / 2;
            results.add(GameResult(
                eventId: '${n++}', date: now.subtract(Duration(days: 30 - round * 7)), homeId: h, awayId: a, homeScore: hs, awayScore: as));
          }
        }
      }
      return LeagueModel(league)..fit(results, now: now);
    }

    test('ranks teams by strength and predicts sensible totals', () {
      final m = fitSynthetic(Leagues.nba);
      expect(m.ratingOf('A').off, greaterThan(m.ratingOf('B').off));
      expect(m.ratingOf('B').off, greaterThan(m.ratingOf('C').off));
      expect(m.ratingOf('A').def, lessThan(m.ratingOf('C').def));
      final (h, a) = m.expected('A', 'C');
      expect(h, greaterThan(a));
      expect(h + a, closeTo(2 * Leagues.nba.priorTeamScore, 25));
      expect(m.homeAdv, closeTo(Leagues.nba.priorHomeAdv, 1.5));
    });

    test('empty data falls back to priors', () {
      final m = LeagueModel(Leagues.nrl)..fit([]);
      final (h, a) = m.expected('x', 'y');
      expect(h + a, closeTo(2 * Leagues.nrl.priorTeamScore, 1e-6));
      expect(m.teamSd, closeTo(Leagues.nrl.priorTeamSd, 1e-6));
    });
  });

  group('GamePrediction', () {
    Game game(League l) => Game(
          league: l,
          eventId: 'e1',
          date: DateTime(2026, 11, 8),
          home: const TeamRef(id: 'A', abbr: 'AAA', name: 'Team A'),
          away: const TeamRef(id: 'C', abbr: 'CCC', name: 'Team C'),
          state: GameState.pre,
        );

    test('normal model: over/under probabilities are coherent', () {
      final p = GamePrediction(game: game(Leagues.nba), muHome: 115, muAway: 108, sdHome: 12, sdAway: 12, rho: 0.25, confidence: 1);
      expect(p.muTotal, 223);
      expect(p.probOver(222.5) + p.probUnder(222.5), closeTo(1, 1e-9));
      expect(p.probOver(222.5), greaterThan(0.5));
      expect(p.probOver(240.5), lessThan(0.2));
      expect(p.probOver(223) + p.probUnder(223) + p.probPush(223), closeTo(1, 1e-9));
      expect(p.probHomeWin, greaterThan(0.6));
      expect(p.probHomeCover(-6.5) + p.probAwayCover(6.5), closeTo(1, 1e-9));
      expect(p.probMarginBand(Side.home, 1, 5) + p.probMarginBand(Side.home, 6, 10) + p.probMarginBand(Side.home, 11, 20) + p.probMarginBand(Side.home, 21, 999),
          closeTo(p.probHomeWin, 1e-6));
    });

    test('poisson model: soccer probabilities add up', () {
      final l = Leagues.all.firstWhere((x) => x.id == 'epl');
      final p = GamePrediction(game: game(l), muHome: 1.6, muAway: 1.1, sdHome: 1, sdAway: 1, rho: 0, confidence: 1);
      expect(p.probHomeWin + p.probDraw + p.probAwayWin, closeTo(1, 1e-6));
      expect(p.probOver(2.5) + p.probUnder(2.5), closeTo(1, 1e-6));
      expect(p.probBtts, closeTo((1 - Stats.poissonPmf(0, 1.6)) * (1 - Stats.poissonPmf(0, 1.1)), 1e-9));
    });

    test('simulation agrees with analytic single-leg probabilities', () {
      final p = GamePrediction(game: game(Leagues.nba), muHome: 115, muAway: 108, sdHome: 12, sdAway: 12, rho: 0.25, confidence: 1);
      p.simCount = 30000;
      final over = Leg(type: LegType.total, over: true, line: 220.5, label: 'o');
      final ml = Leg(type: LegType.moneyline, side: Side.home, label: 'h');
      expect(p.jointProb([over]), closeTo(p.probOver(220.5), 1e-9));
      final joint = p.jointProb([over, ml]);
      // Joint must be below each marginal and above the independent product (positive correlation).
      expect(joint, lessThan(p.probOver(220.5)));
      expect(joint, lessThan(p.probHomeWin));
      expect(joint, greaterThan(p.probOver(220.5) * p.probHomeWin * 0.9));
      // Simulated marginal close to analytic.
      final simOver = p.jointProb([over, Leg(type: LegType.total, over: true, line: 0.5, label: 'x')]);
      expect(simOver, closeTo(p.probOver(220.5), 0.02));
    });

    test('suggests multis with compatible legs', () {
      final p = GamePrediction(game: game(Leagues.nrl), muHome: 26, muAway: 18, sdHome: 11, sdAway: 11, rho: -0.1, confidence: 1);
      p.simCount = 5000;
      final multis = p.suggestMultis(perSize: 2);
      expect(multis, isNotEmpty);
      for (final m in multis) {
        expect(m.prob, greaterThan(0));
        expect(m.prob, lessThanOrEqualTo(m.legs.map((l) => l.prob).reduce((a, b) => a < b ? a : b) + 1e-9));
        final winLegs = m.legs.where((l) => l.type == LegType.moneyline || l.type == LegType.handicap || l.type == LegType.margin);
        expect(winLegs.length, lessThanOrEqualTo(1));
      }
    });

    test('live game projection respects points on the board', () {
      final model = LeagueModel(Leagues.nba)..fit([]);
      final g = Game(
        league: Leagues.nba,
        eventId: 'live',
        date: DateTime.now(),
        home: const TeamRef(id: 'A', abbr: 'AAA', name: 'A'),
        away: const TeamRef(id: 'B', abbr: 'BBB', name: 'B'),
        state: GameState.live,
        homeScore: 60,
        awayScore: 50,
        clock: '0:00',
        period: 2,
      );
      expect(g.remainingFraction(), closeTo(0.5, 1e-9));
      final p = Predictor(model).predict(g, sims: 2000);
      expect(p.live, isTrue);
      expect(p.projHome, greaterThan(100));
      expect(p.projHome, lessThan(135));
      expect(p.muTotal, greaterThan(200));
      expect(p.probHomeWin, greaterThan(0.6));
    });
  });

  group('RaceRater', () {
    test('rates the in-form runner highest and probabilities sum to one', () {
      final race = Race(
        id: 'r1',
        meetingId: 'm',
        meetingName: 'Test',
        number: 1,
        name: 'Test Hcp',
        start: DateTime.now(),
        status: 'Open',
        type: 'T',
        distance: 1200,
        runners: [
          Runner(id: '1', number: 1, name: 'Star', form: '2111', barrier: 3, weight: 56, jockeyStarts: 50, jockeyWins: 10, prizeMoney: 400000, fixedWin: 4.0),
          Runner(id: '2', number: 2, name: 'Plodder', form: '0896', barrier: 12, weight: 58, jockeyStarts: 50, jockeyWins: 3, prizeMoney: 20000, fixedWin: 21),
          Runner(id: '3', number: 3, name: 'Middle', form: '4325', barrier: 6, weight: 57, prizeMoney: 90000, fixedWin: 6),
          Runner(id: '4', number: 4, name: 'Scratched', form: '1111', scratched: true),
        ],
      );
      final rated = RaceRater.rate(race);
      expect(rated.length, 3);
      expect(rated.first.runner.name, 'Star');
      expect(rated.last.runner.name, 'Plodder');
      expect(rated.map((r) => r.winProb).reduce((a, b) => a + b), closeTo(1, 1e-9));
      expect(rated.first.placeProb, greaterThan(rated.first.winProb));
      expect(rated.first.winEdge, isNotNull);
      final plan = QuaddiePlan.build([race], {'r1': rated});
      expect(plan.legs.single.picks, isNotEmpty);
      expect(plan.combos, greaterThanOrEqualTo(1));
    });
  });
}
