// Kept under this name so `flutter create .` in CI does not drop in the
// default counter-app test, which would not compile against this app.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:formline/models/league.dart';
import 'package:formline/engine/bookie.dart';
import 'package:formline/engine/predictor.dart';
import 'package:formline/models/game.dart';
import 'package:formline/services/espn_api.dart';
import 'package:formline/engine/ratings.dart';
import 'package:formline/services/odds_api.dart';
import 'package:formline/services/prediction_log.dart';
import 'package:formline/services/settings_service.dart';
import 'package:formline/engine/stock_calibration.dart';
import 'package:formline/theme.dart';

void main() {
  test('league ids and ESPN paths are unique', () {
    final ids = Leagues.all.map((l) => l.id).toSet();
    final paths = Leagues.all.map((l) => l.path).toSet();
    expect(ids.length, Leagues.all.length);
    expect(paths.length, Leagues.all.length);
    for (final id in Leagues.defaultFavourites) {
      expect(Leagues.byId(id), isNotNull, reason: id);
    }
  });

  test('box score parser handles basketball players and soccer rosters', () {
    final summary = EventSummary.parse({
      'header': {
        'competitions': [
          {
            'date': '2026-11-08T09:00Z',
            'status': {'type': {'completed': false}},
            'competitors': [
              {'homeAway': 'home', 'score': '55'},
              {'homeAway': 'away', 'score': '50'}
            ]
          }
        ]
      },
      'boxscore': {
        'players': [
          {
            'team': {'id': '1', 'abbreviation': 'AAA'},
            'statistics': [
              {
                'names': ['MIN', 'PTS', 'REB'],
                'athletes': [
                  {'athlete': {'displayName': 'On Court'}, 'active': true, 'starter': true, 'stats': ['20', '12', '4']},
                  {'athlete': {'displayName': 'Benched'}, 'active': false, 'didNotPlay': true, 'stats': []},
                ]
              }
            ]
          }
        ]
      },
      'rosters': [
        {
          'team': {'id': '7', 'abbreviation': 'SOC'},
          'roster': [
            {'athlete': {'displayName': 'Keeper'}, 'starter': true, 'active': true, 'position': {'abbreviation': 'G'}, 'stats': [{'abbreviation': 'SV', 'displayValue': '3'}]},
            {'athlete': {'displayName': 'Subbed'}, 'starter': true, 'subbedOut': true, 'stats': []},
            {'athlete': {'displayName': 'Sub On'}, 'subbedIn': true, 'stats': [{'abbreviation': 'G', 'displayValue': '1'}]},
            {'athlete': {'displayName': 'Unused'}, 'stats': []},
          ]
        }
      ]
    });
    final hoops = summary.tables.firstWhere((t) => t.teamId == '1');
    expect(hoops.rows.first.onField, isTrue);
    expect(hoops.rows.first.statNum(hoops.columns, 'PTS'), 12);
    expect(hoops.rows.last.dnp, isTrue);
    final soccer = summary.tables.firstWhere((t) => t.teamId == '7');
    final onPitch = soccer.rows.where((r) => r.onField).map((r) => r.name).toSet();
    expect(onPitch, {'Keeper', 'Sub On'});
    expect(soccer.rows.firstWhere((r) => r.name == 'Keeper').stat(soccer.columns, 'SV'), '3');
    expect(summary.homeScore, 55);
  });

  test('bookmaker legs are priced against the model and matched to games', () {
    final game = Game(
      league: Leagues.nba,
      eventId: 'e',
      date: DateTime.utc(2026, 11, 8, 0, 30),
      home: const TeamRef(id: '1', abbr: 'ATL', name: 'Atlanta Hawks'),
      away: const TeamRef(id: '17', abbr: 'BKN', name: 'Brooklyn Nets'),
      state: GameState.pre,
    );
    final sb = BookiePrices(key: 'sportsbet', title: 'SportsBet')
      ..homeWin = 1.40
      ..awayWin = 3.00
      ..spreadPoint = -6.5
      ..spreadHome = 1.90
      ..spreadAway = 1.90
      ..totalPoint = 226.5
      ..over = 1.90
      ..under = 1.90;
    final events = [
      OddsEvent(id: 'x', home: 'Atlanta Hawks', away: 'Brooklyn Nets', commence: DateTime.utc(2026, 11, 8, 0, 30), books: {'sportsbet': sb}),
      OddsEvent(id: 'y', home: 'Boston Celtics', away: 'Miami Heat', commence: DateTime.utc(2026, 11, 8, 0, 30), books: {}),
    ];
    expect(OddsApi.match(events, game)?.id, 'x');
    final p = GamePrediction(game: game, muHome: 118.8, muAway: 110.6, sdHome: 10.4, sdAway: 10.4, rho: 0.25, confidence: 1);
    final priced = BookieModel.priceLegs(p, sb);
    expect(priced.length, 6);
    final over = priced.firstWhere((l) => l.leg.type == LegType.total && l.leg.over);
    expect(over.leg.prob, closeTo(p.probOver(226.5), 1e-9));
    expect(over.ev, closeTo(p.probOver(226.5) * 1.90, 1e-9));
    expect(priced.first.ev, greaterThanOrEqualTo(priced.last.ev));
    final multis = BookieModel.bestMultis([priced, priced]);
    expect(multis.first.legs.length, 2);
    expect(multis.first.odds, closeTo(priced.first.odds * priced.first.odds, 1e-9));
  });

  test('prediction log grades games and self-corrects a league running hot', () {
    final log = PredictionLog.instance;
    log.games.clear();
    for (var i = 0; i < 40; i++) {
      // Model said home 60%, total 220; home wins 3 of 4, totals land 6 over.
      final homeWins = i % 4 != 0;
      log.games['g$i'] = LoggedGame(
        leagueId: 'nba',
        eventId: 'g$i',
        date: DateTime(2026, 11, 1).add(Duration(hours: i)),
        home: 'H',
        away: 'A',
        rawHome: 112,
        rawAway: 108,
        pHome: 0.6,
        pAway: 0.4,
        marketTotal: 223.5,
        marketSpread: -2.5,
        homeScore: homeWins ? 118 : 108,
        awayScore: homeWins ? 108 : 118,
      );
    }
    final r = log.leagueRecords['nba']!;
    expect(r.n, 40);
    expect(r.accuracy, closeTo(0.75, 1e-9));
    expect(r.expected, closeTo(0.6, 1e-9));
    expect(r.bias, closeTo(6, 1e-9));
    expect(r.ouWins + r.ouLosses, 40); // model 220 vs line 223.5 = under; actual 226 = over
    expect(r.ouLosses, 40);
    expect(r.atsWins, 30); // model margin +4 covers -2.5; home won by 10 in 30 games
    final bias = log.totalBias(Leagues.nba);
    expect(bias, closeTo(6 * 40 / 80, 1e-9)); // shrunk toward zero
    final model = LeagueModel(Leagues.nba)..fit([]);
    final (h0, a0) = model.expected('x', 'y');
    model.totalBias = bias;
    final (h1, a1) = model.expected('x', 'y');
    expect((h1 + a1) - (h0 + a0), closeTo(bias, 1e-9));
    log.games.clear();
  });

  test('calibrated spreads keep totals and margins consistent', () {
    final base = LeagueModel(Leagues.nba, params: const ModelParams())..fit([]);
    final wide = LeagueModel(Leagues.nba, params: const ModelParams(totalScale: 1.2, marginScale: 1.1))..fit([]);
    double sdT(LeagueModel m) => m.teamSd * m.teamSd * 2 * (1 + m.rho);
    double sdM(LeagueModel m) => m.teamSd * m.teamSd * 2 * (1 - m.rho);
    expect(sdT(wide) / sdT(base), closeTo(1.44, 1e-6));
    expect(sdM(wide) / sdM(base), closeTo(1.21, 1e-6));
  });

  test('slimmed summaries parse the same as the full payload', () {
    final full = {
      'header': {
        'competitions': [
          {
            'date': '2026-11-08T09:00Z',
            'status': {'type': {'completed': true, 'state': 'post', 'junk': 'x' * 500}},
            'competitors': [
              {'homeAway': 'home', 'score': '124', 'team': {'big': 'x' * 500}},
              {'homeAway': 'away', 'score': '121'}
            ]
          }
        ]
      },
      'boxscore': {
        'players': [
          {
            'team': {'id': '1', 'abbreviation': 'ATL', 'logo': 'x' * 500},
            'statistics': [
              {
                'names': ['MIN', 'PTS', 'FG', '3PT', 'FT', 'REB', 'AST'],
                'athletes': [
                  {'athlete': {'displayName': 'A Player', 'headshot': 'x' * 500}, 'starter': true, 'stats': ['30', '22', '8-15', '3-7', '3-3', '6', '5']},
                ]
              }
            ]
          }
        ]
      },
      'injuries': [
        {'injuries': [{'athlete': {'displayName': 'B Hurt'}, 'status': 'Out'}]}
      ],
      'pickcenter': [
        {'overUnder': 226.5, 'spread': -6.5, 'provider': {'name': 'Book'}}
      ],
      'news': {'articles': 'x' * 5000},
    };
    final slim = EspnApi.slimSummary(full);
    final a = EventSummary.parse(full), b = EventSummary.parse(slim);
    expect(slim.containsKey('news'), isFalse);
    expect(b.completed, a.completed);
    expect(b.homeScore, 124);
    expect(b.playerLines.single.pts, a.playerLines.single.pts);
    expect(b.playerLines.single.threes, 3);
    expect(b.injuries, {'B Hurt': 'Out'});
    expect(b.marketTotal, 226.5);
    expect(b.tables.single.rows.single.name, 'A Player');
  });

  test('CMC Invest brokerage and what it does to the short-term edge', () {
    final s = Settings.instance
      ..brokerModel = 'cmc'
      ..tradeSize = 900;
    expect(s.buyFee(900), 0); // first buy under \$1,000 is free
    expect(s.sellFee(900), 11);
    expect(s.buyFee(1000), 11);
    expect(s.buyFee(20000), closeTo(20, 1e-9)); // 0.10% beats \$11
    expect(s.sellFee(20000), closeTo(20, 1e-9));
    // Small parcels lose to brokerage; \$10k parcels keep most of the edge.
    expect(StockCalibration.dips.net(s.costPerSide), lessThan(0));
    s.tradeSize = 10000;
    expect(s.roundTripCost, closeTo(0.0022, 1e-9));
    expect(StockCalibration.dips.net(s.costPerSide), greaterThan(0.003));
    s
      ..brokerModel = 'flat'
      ..brokerage = 5;
    expect(s.roundTripCost, closeTo(0.001, 1e-9));
    s.brokerModel = 'cmc';
    // Baked monthly table is consistent with the headline stats.
    final trades = StockCalibration.dipMonths.fold<num>(0, (a, m) => a + m[1]);
    expect(trades, StockCalibration.dips.trades);
    final gross = StockCalibration.dipMonths.fold<num>(0, (a, m) => a + m[0]) / trades;
    expect(gross, closeTo(StockCalibration.dips.gross, 1e-4));
  });

  testWidgets('theme builds a dark material app', (tester) async {
    await tester.pumpWidget(MaterialApp(theme: edgeTheme(), home: const Scaffold(body: Text('Formline'))));
    expect(find.text('Formline'), findsOneWidget);
    expect(edgeTheme().brightness, Brightness.dark);
  });
}
