import 'package:flutter_test/flutter_test.dart';
import 'package:formline/engine/quaddie_value.dart';
import 'package:formline/models/race.dart';
import 'package:formline/services/racing_api.dart';
import 'package:formline/widgets/common.dart';

/// A race payload in the feed's shape. [winner] makes it a finished race.
Map<String, dynamic> payload(int number, List<double> prices, {int? winner, double? winDiv, List<Map<String, dynamic>> extra = const [], String status = 'Open'}) => {
      'data': {
        'race': {'event_id': 'r$number', 'meeting_id': 'm', 'meeting_name': 'Testville', 'race_number': number, 'status': winner == null ? status : 'Final', 'advertised_start': 1794646800},
        'runners': [
          for (var i = 0; i < prices.length; i++)
            {'entrant_id': 'e$number-$i', 'runner_number': i + 1, 'name': 'Horse ${i + 1}', 'is_scratched': prices[i] == 0, 'odds': {'fixed_win': prices[i], 'pool_win': prices[i]}},
        ],
        'results': winner == null
            ? []
            : [
                {'position': 1, 'runner_number': winner, 'name': 'Horse $winner', 'margin_length': 0},
                {'position': 2, 'runner_number': winner == 1 ? 2 : 1, 'name': 'Horse ${winner == 1 ? 2 : 1}', 'margin_length': 1.5},
              ],
        'dividends': winner == null
            ? []
            : [
                // The feed lists each dividend twice: interim, then final.
                {'product_name': 'Pool Win', 'status': 'Interim', 'dividend': (winDiv ?? prices[winner - 1]) + 0.3, 'description': '$winner', 'positions': [{'runner_number': winner, 'position': 1}]},
                {'product_name': 'Pool Win', 'status': 'Final', 'dividend': winDiv ?? prices[winner - 1], 'description': '$winner', 'positions': [{'runner_number': winner, 'position': 1}]},
                {'product_name': 'Pool Place', 'status': 'Final', 'dividend': 1.4, 'description': '$winner', 'positions': [{'runner_number': winner, 'position': 1}]},
                {'product_name': 'Pool Quinella', 'status': 'Final', 'dividend': 12.5, 'description': '1:2', 'positions': [{'runner_number': 1, 'position': 1}, {'runner_number': 2, 'position': 2}]},
                ...extra,
              ],
        'tote_pools': [
          {'product_type': 'Win', 'total': 1000},
          {'product_type': 'Quadrella', 'total': 50000, 'quaddie': [5, 6, 7, 8]},
        ],
      },
    };

Race race(int number, List<double> prices, {int? winner, double? winDiv, List<Map<String, dynamic>> extra = const []}) =>
    RacingApi.parseRace(payload(number, prices, winner: winner, winDiv: winDiv, extra: extra), 'r$number');

void main() {
  test('results and dividends are read from the feed', () {
    final r = race(5, [3.0, 5.0, 9.0], winner: 2, winDiv: 5.6);
    expect(r.isFinal, isTrue);
    expect(r.results.first.number, 2);
    expect(r.results[1].margin, 1.5);
    // The final dividend wins over the interim one.
    expect(r.winDividend(2), 5.6);
    expect(r.winDividend(1), isNull);
    expect(r.placeDividend(2), 1.4);
    expect(r.exotic('Quinella')!.amount, 12.5);
    expect(r.exotic('Trifecta'), isNull);
    expect(r.quaddieLegs, [5, 6, 7, 8]);
    expect(r.quaddiePool, 50000);
  });

  test('quaddie: what it paid once all four legs are run', () {
    final quad = {
      'product_name': 'Pool Quadrella',
      'status': 'Final',
      'dividend': 271.6,
      'description': '2/1/3/1',
      'pool_size': 50000,
      'positions': [for (final (i, n) in [2, 1, 3, 1].indexed) {'runner_number': n, 'position': i + 1}],
    };
    // A consolation dividend for a substitute is not the quaddie result.
    final concession = {...quad, 'dividend': 14.3, 'description': 'concession:2/1/3/2'};
    final t = QuaddieTracker([
      race(5, [3.0, 5.0, 9.0], winner: 2),
      race(6, [2.0, 4.0], winner: 1),
      race(7, [4.0, 6.0, 3.0], winner: 3),
      race(8, [1.6, 8.0], winner: 1, extra: [quad, concession]),
    ]);
    expect(t.finished, isTrue);
    expect(t.runCount, 4);
    expect(t.paid.single.amount, 271.6);
    expect(t.paid.single.numbers, [2, 1, 3, 1]);
    expect(t.willPays(), isEmpty);
  });

  test('quaddie: before the last leg, what each runner would make it pay', () {
    final t = QuaddieTracker([
      race(5, [3.0, 5.0, 9.0], winner: 2, winDiv: 5.6),
      race(6, [2.0, 4.0], winner: 1, winDiv: 2.2),
      race(7, [4.0, 6.0, 3.0], winner: 3, winDiv: 3.4),
      race(8, [1.6, 8.0, 0]), // runner 3 is scratched
    ]);
    expect(t.runCount, 3);
    expect(t.pending, 1);
    expect(t.assumesFavourites, isFalse);
    expect(t.nextLeg!.number, 8);
    final pays = t.willPays();
    expect(pays.map((w) => w.runner.number), [1, 2]); // cheapest first, scratching left out
    final base = QuaddieTracker.byPending[1][0] * 5.6 * 2.2 * 3.4;
    expect(pays[0].value, closeTo(base * 1.6, 1e-9));
    expect(pays[1].value, closeTo(base * 8.0, 1e-9));
    // An estimate never exceeds what the pool could pay a lone winning ticket.
    expect(t.pool, 50000);
    final roughie = QuaddieTracker([
      race(5, [3.0, 150.0], winner: 2, winDiv: 150),
      race(6, [2.0, 90.0], winner: 2, winDiv: 90),
      race(7, [4.0, 60.0], winner: 2, winDiv: 60),
      race(8, [1.6, 8.0]),
    ]).willPays();
    expect(roughie.last.value, 50000 * QuaddieTracker.poolShare);
  });

  test('quaddie: in leg 3 of 4, values assume the favourite wins the last leg', () {
    final t = QuaddieTracker([
      race(5, [3.0, 5.0, 9.0], winner: 2, winDiv: 5.6),
      race(6, [2.0, 4.0], winner: 1, winDiv: 2.2),
      race(7, [4.0, 6.0, 3.0]),
      race(8, [8.0, 1.6]),
    ]);
    expect(t.runCount, 2);
    expect(t.assumesFavourites, isTrue);
    expect(QuaddieTracker.favourite(t.legs[3]!)!.number, 2);
    final pays = t.willPays();
    expect(pays.map((w) => w.runner.number), [3, 1, 2]);
    final base = QuaddieTracker.byPending[2][0] * 5.6 * 2.2 * 1.6;
    expect(pays.first.value, closeTo(base * 3.0, 1e-9));
    // Nothing to show before the first leg is run, or while a race is missing.
    expect(QuaddieTracker([race(5, [3.0]), race(6, [2.0]), race(7, [4.0]), race(8, [1.6])]).willPays(), isEmpty);
    expect(QuaddieTracker([race(5, [3.0], winner: 1), null, race(7, [4.0]), race(8, [1.6])]).willPays(), isEmpty);
  });

  test('the estimate table covers every stage and was checked on real quaddies', () {
    expect(QuaddieTracker.byPending.length, 4);
    for (final row in QuaddieTracker.byPending) {
      expect(row[0], inInclusiveRange(1.3, 2.0));
      expect(row[2], greaterThan(0.85)); // within double or half, most of the time
    }
    expect(QuaddieTracker.samples, greaterThan(50));
  });

  test('payout formatting', () {
    expect(payout(3.4), '\$3.40');
    expect(payout(10186.2), '\$10,186.20');
    expect(roughly(18.44), '\$18.4');
    expect(roughly(243.7), '\$245');
    expect(roughly(3421), '\$3,400');
    expect([1, 2, 3, 4, 11, 12, 13, 21].map(ordinal).toList(), ['1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st']);
  });
}
