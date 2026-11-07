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
  RaceResult({required this.position, required this.number, required this.name});
  final int position;
  final int number;
  final String name;
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

  bool get hasResults => results.isNotEmpty;
}
