/// Racing models (thoroughbred, harness, greyhound) from the TAB affiliates feed.
class Meeting {
  Meeting({
    required this.id,
    required this.name,
    required this.date,
    required this.country,
    required this.category,
    required this.categoryName,
    this.trackCondition = '',
    this.races = const [],
  });
  final String id;
  final String name;
  final DateTime date;
  final String country;
  final String category; // T, H, G
  final String categoryName;
  final String trackCondition;
  final List<RaceSummary> races;

  String get categoryLabel => category == 'T' ? 'Horses' : (category == 'H' ? 'Harness' : (category == 'G' ? 'Greyhounds' : categoryName));
}

class RaceSummary {
  RaceSummary({
    required this.id,
    required this.number,
    required this.name,
    required this.start,
    required this.status,
    this.distance = 0,
  });
  final String id;
  final int number;
  final String name;
  final DateTime start;
  final String status;
  final int distance;

  bool get isFinal => status.toLowerCase().contains('final') || status.toLowerCase().contains('paying') || status.toLowerCase() == 'closed';
  bool get isOpen => status.toLowerCase() == 'open';
}

class Runner {
  Runner({
    required this.id,
    required this.number,
    required this.name,
    this.scratched = false,
    this.barrier,
    this.weight,
    this.allowance,
    this.apprentice = false,
    this.jockey = '',
    this.trainer = '',
    this.form = '',
    this.prizeMoney = 0,
    this.age,
    this.gear = '',
    this.speedMap = '',
    this.fixedWin,
    this.fixedPlace,
    this.poolWin,
    this.jockeyStarts = 0,
    this.jockeyWins = 0,
    this.trainerStarts = 0,
    this.trainerWins = 0,
    this.comment,
    this.silk,
  });
  final String id;
  final int number;
  final String name;
  final bool scratched;
  final int? barrier;
  final double? weight;
  final double? allowance;
  final bool apprentice;
  final String jockey;
  final String trainer;
  final String form; // last twenty starts, oldest first
  final double prizeMoney;
  final int? age;
  final String gear;
  final String speedMap;
  final double? fixedWin;
  final double? fixedPlace;
  final double? poolWin;
  final int jockeyStarts, jockeyWins, trainerStarts, trainerWins;
  final String? comment;
  final String? silk;
}

class RaceResult {
  RaceResult({required this.position, required this.number, required this.name, this.margin});
  final int position;
  final int number;
  final String name;
  final double? margin; // lengths behind the winner
}

/// A tote dividend for a $1 bet.
class Dividend {
  Dividend({required this.product, required this.amount, required this.numbers, this.description = '', this.isFinal = true, this.pool = 0});
  final String product; // Win, Place, Quinella, Exacta, Trifecta, First4, Double, Treble, Quadrella, Early Quadrella
  final double amount;
  final List<int> numbers; // runner numbers in order (one per leg for multi-race bets)
  final String description;
  final bool isFinal; // false while the result is interim
  final double pool;

  /// Consolation dividends for substitutes, not the main result.
  bool get concession => description.contains('concession');
}

class Race {
  Race({
    required this.id,
    required this.meetingId,
    required this.meetingName,
    required this.number,
    required this.name,
    required this.start,
    required this.status,
    required this.type,
    required this.distance,
    this.trackCondition = '',
    this.country = '',
    this.comment,
    this.positionsPaid = 3,
    this.runners = const [],
    this.results = const [],
    this.dividends = const [],
    this.quaddieLegs = const [],
    this.quaddiePool = 0,
  });
  final String id;
  final String meetingId;
  final String meetingName;
  final int number;
  final String name;
  final DateTime start;
  final String status;
  final String type; // T, H, G
  final int distance;
  final String trackCondition;
  final String country;
  final String? comment;
  final int positionsPaid;
  final List<Runner> runners;
  final List<RaceResult> results;
  final List<Dividend> dividends;
  final List<int> quaddieLegs; // race numbers of the quaddie this race is part of
  final double quaddiePool;

  bool get hasResults => results.isNotEmpty;
  bool get isFinal => status.toLowerCase() == 'final';
  bool get abandoned => status.toLowerCase().contains('abandon');

  Runner? runner(int number) {
    for (final r in runners) {
      if (r.number == number) return r;
    }
    return null;
  }

  Iterable<Dividend> _of(String product) => dividends.where((d) => d.product == product && !d.concession);

  /// What a $1 win bet on [number] paid, or null if it did not win.
  double? winDividend(int number) {
    for (final d in _of('Win')) {
      if (d.numbers.isNotEmpty && d.numbers.first == number) return d.amount;
    }
    return null;
  }

  double? placeDividend(int number) {
    for (final d in _of('Place')) {
      if (d.numbers.isNotEmpty && d.numbers.first == number) return d.amount;
    }
    return null;
  }

  /// The first dividend of an exotic bet (Quinella, Exacta, Trifecta, First4).
  Dividend? exotic(String product) => _of(product).isEmpty ? null : _of(product).first;

  /// Quaddie dividends declared on this race (more than one after a dead heat).
  List<Dividend> get quaddieDividends => _of('Quadrella').toList();
}
