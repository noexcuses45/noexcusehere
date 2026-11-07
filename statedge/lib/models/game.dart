import 'league.dart';

class TeamRef {
  const TeamRef({required this.id, required this.abbr, required this.name, this.logo, this.record});
  final String id;
  final String abbr;
  final String name;
  final String? logo;
  final String? record;

  Map<String, dynamic> toJson() => {'id': id, 'abbr': abbr, 'name': name, 'logo': logo, 'record': record};
  static TeamRef fromJson(Map<String, dynamic> j) => TeamRef(
      id: '${j['id']}', abbr: j['abbr'] ?? '', name: j['name'] ?? '', logo: j['logo'], record: j['record']);
}

/// A completed game used to fit team ratings.
class GameResult {
  const GameResult({
    required this.eventId,
    required this.date,
    required this.homeId,
    required this.awayId,
    required this.homeScore,
    required this.awayScore,
    this.neutral = false,
    this.homeAbbr = '',
    this.awayAbbr = '',
  });
  final String eventId;
  final DateTime date;
  final String homeId;
  final String awayId;
  final double homeScore;
  final double awayScore;
  final bool neutral;
  final String homeAbbr;
  final String awayAbbr;

  double get total => homeScore + awayScore;
  double get margin => homeScore - awayScore;

  Map<String, dynamic> toJson() => {
        'e': eventId,
        'd': date.toIso8601String(),
        'h': homeId,
        'a': awayId,
        'hs': homeScore,
        'as': awayScore,
        'n': neutral,
        'ha': homeAbbr,
        'aa': awayAbbr,
      };
  static GameResult fromJson(Map<String, dynamic> j) => GameResult(
        eventId: '${j['e']}',
        date: DateTime.parse(j['d']),
        homeId: '${j['h']}',
        awayId: '${j['a']}',
        homeScore: (j['hs'] as num).toDouble(),
        awayScore: (j['as'] as num).toDouble(),
        neutral: j['n'] == true,
        homeAbbr: j['ha'] ?? '',
        awayAbbr: j['aa'] ?? '',
      );
}

enum GameState { pre, live, post }

/// A fixture (upcoming, live or finished) from a scoreboard.
class Game {
  Game({
    required this.league,
    required this.eventId,
    required this.date,
    required this.home,
    required this.away,
    required this.state,
    this.homeScore,
    this.awayScore,
    this.statusDetail = '',
    this.clock = '',
    this.period = 0,
    this.neutral = false,
    this.marketTotal,
    this.marketSpread,
    this.marketProvider,
    this.homeLinescores = const [],
    this.awayLinescores = const [],
  });

  final League league;
  final String eventId;
  final DateTime date;
  final TeamRef home;
  final TeamRef away;
  final GameState state;
  final double? homeScore;
  final double? awayScore;
  final String statusDetail;
  final String clock;
  final int period;
  final bool neutral;
  final double? marketTotal; // bookmaker total, shown for comparison only
  final double? marketSpread; // home spread (negative = home favoured)
  final String? marketProvider;
  final List<double> homeLinescores;
  final List<double> awayLinescores;

  bool get isLive => state == GameState.live;
  bool get isFinal => state == GameState.post;
  String get title => '${away.abbr} @ ${home.abbr}';

  /// Fraction of regulation time still to be played (1 = not started).
  double remainingFraction() {
    if (state == GameState.pre) return 1;
    if (state == GameState.post) return 0;
    final total = league.gameMinutes;
    if (league.sport == 'baseball') {
      final done = (period - 1).clamp(0, league.periods);
      return (1 - done / league.periods).clamp(0.0, 1.0);
    }
    final p = period < 1 ? 1 : period;
    var mins = 0.0;
    final m = RegExp(r'^(\d+):(\d+)').firstMatch(clock);
    if (m != null) {
      mins = int.parse(m.group(1)!) + int.parse(m.group(2)!) / 60.0;
    } else {
      final n = double.tryParse(clock.replaceAll(RegExp(r"[^0-9.]"), ''));
      if (n != null) mins = n;
    }
    final elapsed = league.sport == 'soccer'
        ? ((p - 1) * league.periodMinutes + mins) // soccer clock counts up
        : ((p - 1) * league.periodMinutes + (league.periodMinutes - mins));
    final remaining = total - elapsed;
    if (p > league.periods) return 0.02; // overtime
    return (remaining / total).clamp(0.0, 1.0);
  }
}
