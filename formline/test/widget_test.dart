// Kept under this name so `flutter create .` in CI does not drop in the
// default counter-app test, which would not compile against this app.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:formline/models/league.dart';
import 'package:formline/services/espn_api.dart';
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

  testWidgets('theme builds a dark material app', (tester) async {
    await tester.pumpWidget(MaterialApp(theme: edgeTheme(), home: const Scaffold(body: Text('Formline'))));
    expect(find.text('Formline'), findsOneWidget);
    expect(edgeTheme().brightness, Brightness.dark);
  });
}
