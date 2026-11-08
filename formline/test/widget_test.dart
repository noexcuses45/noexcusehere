// Kept under this name so `flutter create .` in CI does not drop in the
// default counter-app test, which would not compile against this app.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:formline/models/league.dart';
import 'package:formline/engine/bookie.dart';
import 'package:formline/engine/predictor.dart';
import 'package:formline/models/game.dart';
import 'package:formline/services/espn_api.dart';
import 'package:formline/services/odds_api.dart';
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

  testWidgets('theme builds a dark material app', (tester) async {
    await tester.pumpWidget(MaterialApp(theme: edgeTheme(), home: const Scaffold(body: Text('Formline'))));
    expect(find.text('Formline'), findsOneWidget);
    expect(edgeTheme().brightness, Brightness.dark);
  });
}
