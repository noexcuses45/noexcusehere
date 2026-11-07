// Kept under this name so `flutter create .` in CI does not drop in the
// default counter-app test, which would not compile against this app.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:formline/models/league.dart';
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

  testWidgets('theme builds a dark material app', (tester) async {
    await tester.pumpWidget(MaterialApp(theme: edgeTheme(), home: const Scaffold(body: Text('Formline'))));
    expect(find.text('Formline'), findsOneWidget);
    expect(edgeTheme().brightness, Brightness.dark);
  });
}
