import 'package:flutter_test/flutter_test.dart';
import 'package:formline/engine/predictor.dart';
import 'package:formline/engine/race_rater.dart';
import 'package:formline/engine/ratings.dart';
import 'package:formline/engine/stats.dart';
import 'package:formline/models/game.dart';
import 'package:formline/models/league.dart';
import 'package:formline/models/race.dart';
import 'package:formline/engine/market_clock.dart';
import 'package:formline/engine/stock_scanner.dart';
import 'package:formline/engine/stocks.dart';
import 'package:formline/engine/player_props.dart';
import 'package:formline/services/espn_api.dart';

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
      final m = LeagueModel(Leagues.nrl, params: const ModelParams())..fit([]);
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

    test('AI multi builder returns the requested legs with a coherent probability', () {
      final p = GamePrediction(game: game(Leagues.nba), muHome: 115, muAway: 108, sdHome: 12, sdAway: 12, rho: 0.25, confidence: 1, players: [
        PlayerProjection(name: 'A One', side: Side.home, stat: 'PTS', mean: 24, sd: 7),
        PlayerProjection(name: 'A One', side: Side.home, stat: 'REB', mean: 8, sd: 3),
        PlayerProjection(name: 'B Two', side: Side.away, stat: 'AST', mean: 7, sd: 2.5),
        PlayerProjection(name: 'C Three', side: Side.away, stat: 'PTS', mean: 18, sd: 6),
      ]);
      p.simCount = 4000;
      final m = p.buildMulti(5);
      expect(m.legs.length, 5);
      expect(m.legs.map((l) => l.player?.name).whereType<String>().toSet().length,
          m.legs.where((l) => l.type == LegType.playerProp).length, reason: 'one leg per player');
      expect(m.prob, greaterThan(0.05));
      expect(m.prob, lessThanOrEqualTo(m.legs.map((l) => l.prob).reduce((a, b) => a < b ? a : b) + 1e-9));
      final m3 = p.buildMulti(3);
      expect(m3.legs.length, 3);
      expect(m3.prob, greaterThanOrEqualTo(m.prob));
    });

    test('live reach probability behaves sensibly', () {
      final pr = PlayerProjection(name: 'X', side: Side.home, stat: 'PTS', mean: 20, sd: 6);
      expect(GamePrediction.liveReachProb(pr, 22, 19.5, 0.5), 1);
      expect(GamePrediction.liveReachProb(pr, 5, 19.5, 0), 0);
      final half = GamePrediction.liveReachProb(pr, 10, 19.5, 0.5);
      expect(half, closeTo(0.5, 0.1)); // on pace: needs 9.5 more with 10 expected
      expect(GamePrediction.liveReachProb(pr, 4, 19.5, 0.5), lessThan(half));
      expect(GamePrediction.liveReachProb(pr, 0, 19.5, 0.3, played: false), lessThan(GamePrediction.liveReachProb(pr, 0, 19.5, 0.3)));
    });

    test('live-adjusted projections add current stats to the remaining share', () {
      final pre = [PlayerProjection(name: 'Star', side: Side.home, stat: 'PTS', mean: 20, sd: 6), PlayerProjection(name: 'Out', side: Side.home, stat: 'PTS', mean: 10, sd: 4)];
      final table = BoxTable(teamId: 'H', teamAbbr: 'H', title: 'Players', columns: ['MIN', 'PTS'], rows: [
        BoxRow(name: 'Star', stats: ['18', '14'], active: true),
        BoxRow(name: 'Out', stats: [], dnp: true),
      ]);
      final live = PlayerProps.liveAdjust(pre, tables: [table], homeTeamId: 'H', awayTeamId: 'A', remaining: 0.5);
      expect(live.length, 1);
      expect(live.first.mean, closeTo(24, 1e-9));
      expect(live.first.sd, closeTo(6 * 0.7071, 1e-3));
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

  group('Shares', () {
    Series make(String code, double Function(int) price, {int n = 320}) => Series(code, [
          for (var i = 0; i < n; i++) Bar(DateTime.utc(2025, 1, 1).add(Duration(days: i)), price(i), 1000),
        ]);

    test('indicators: moving average and RSI', () {
      final s = make('A', (i) => 10.0 + i, n: 30);
      expect(s.sma(5)[4], closeTo(12, 1e-9));
      expect(s.sma(5)[29], closeTo(37, 1e-9));
      expect(s.sma(5)[3].isNaN, isTrue);
      expect(s.rsi(2)[29], 100); // only gains
      final down = make('B', (i) => 100.0 - i, n: 30);
      expect(down.rsi(2)[29], closeTo(0, 1e-9));
      expect(down.downStreak(29), 29);
    });

    test('scanner buys the strongest shares and sells when the trend breaks', () {
      // Ten steady risers of different speeds, plus one that rises then collapses.
      final list = <Series>[
        for (var k = 0; k < 14; k++) make('S$k', (i) => 10 * (1 + 0.0005 * (k + 1) * i)),
        make('CRASH', (i) => i < 300 ? 10 * (1 + 0.01 * i) : 40 - 3.0 * (i - 300)),
      ];
      final sc = StockScanner(list);
      final ranksEarly = sc.ranksAt(290);
      expect(ranksEarly.rank['CRASH'], 1);
      expect(ranksEarly.rank['S13'], 2);
      final res = sc.run(from: 210);
      // The crashed share must have been sold, and for the right reason.
      final crash = res.closedLeaders.where((p) => p.code == 'CRASH').toList();
      expect(crash, isNotEmpty);
      expect(crash.last.exitReason, contains('200-day'));
      expect(res.leaders.where((l) => l.open).map((l) => l.code), isNot(contains('CRASH')));
      // Strong steady risers are held; the weakest are not.
      final held = res.leaders.where((l) => l.open).map((l) => l.code).toSet();
      expect(held, contains('S13'));
      expect(held, isNot(contains('S0')));
      for (final l in res.leaders.where((l) => l.open)) {
        expect(l.priceNow, greaterThan(0));
        expect(l.trendLevel, lessThan(l.priceNow));
      }
    });

    test('dip trades fire only in leaders, and exit on the bounce', () {
      // LEAD climbs strongly, has a sharp two-day dip at day 280, then recovers.
      double lead(int i) {
        final base = 10 * (1 + 0.012 * i);
        if (i == 280) return base * 0.95;
        if (i == 281) return base * 0.92;
        return base;
      }

      // LAG barely rises and has the same dip: it must not be traded.
      double lag(int i) {
        final base = 10 * (1 + 0.0002 * i);
        if (i == 280) return base * 0.95;
        if (i == 281) return base * 0.92;
        return base;
      }

      final list = <Series>[
        make('LEAD', lead),
        make('LAG', lag),
        for (var k = 0; k < 12; k++) make('F$k', (i) => 10 * (1 + 0.0006 * (k + 1) * i)),
      ];
      final res = StockScanner(list).run(from: 210);
      final leadTrades = res.closedDips.where((t) => t.code == 'LEAD').toList();
      expect(leadTrades, isNotEmpty);
      expect(leadTrades.first.exitReason, contains('5-day'));
      expect(leadTrades.first.gain, greaterThan(0));
      expect(res.closedDips.where((t) => t.code == 'LAG'), isEmpty);
    });

    test('Sydney clock handles daylight saving and the trading session', () {
      // 11 Nov 2026 01:00 UTC = 12:00 AEDT (UTC+11), a Wednesday: market open.
      final summer = DateTime.utc(2026, 11, 11, 1);
      expect(MarketClock.sydneyOffset(summer).inHours, 11);
      expect(MarketClock.asxOpen(summer), isTrue);
      // 15 Jun 2026 01:00 UTC = 11:00 AEST (UTC+10), a Monday: market open.
      final winter = DateTime.utc(2026, 6, 15, 1);
      expect(MarketClock.sydneyOffset(winter).inHours, 10);
      expect(MarketClock.asxOpen(winter), isTrue);
      // 11 Nov 2026 08:00 UTC = 19:00 AEDT: closed.
      expect(MarketClock.asxOpen(DateTime.utc(2026, 11, 11, 8)), isFalse);
      // Saturday.
      expect(MarketClock.asxOpen(DateTime.utc(2026, 11, 14, 1)), isFalse);
      expect(MarketClock.barIsLive(DateTime.utc(2026, 11, 11), summer), isTrue);
      expect(MarketClock.barIsLive(DateTime.utc(2026, 11, 10), summer), isFalse);
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
