/// Which scoring distribution fits the sport.
enum ScoreModel { normal, poisson }

/// A competition served by ESPN's public site API.
class League {
  const League({
    required this.id,
    required this.sport,
    required this.slug,
    required this.name,
    required this.group,
    required this.model,
    required this.priorTeamScore,
    required this.priorTeamSd,
    required this.priorHomeAdv,
    this.priorRho = 0.15,
    this.halfLifeDays = 45,
    this.periods = 2,
    this.periodMinutes = 45,
    this.hasPlayerProps = false,
    this.scoreWord = 'points',
    this.lineStep = 1.0,
    this.scoreboardParams = const {},
    this.useDateScan = false,
    this.marginBands = const [],
  });

  final String id;
  final String sport; // ESPN sport path, e.g. basketball
  final String slug; // ESPN league path, e.g. nba
  final String name;
  final String group;
  final ScoreModel model;
  final double priorTeamScore; // typical points per team per game
  final double priorTeamSd; // typical sd of a team's score (normal model)
  final double priorHomeAdv; // typical home-minus-away margin
  final double priorRho; // correlation between the two teams' scores
  final double halfLifeDays; // recency weighting
  final int periods;
  final double periodMinutes;
  final bool hasPlayerProps; // NBA-style box score available
  final String scoreWord;
  final double lineStep; // granularity for suggested lines
  final Map<String, String> scoreboardParams;
  final bool useDateScan; // too many teams: build ratings from daily scoreboards
  final List<List<int>> marginBands; // winning margin buckets for multis

  String get path => '$sport/$slug';

  /// Sport key at The Odds API, when the competition is covered there.
  String? get oddsKey => Leagues.oddsKeys[id];
  double get gameMinutes => periods * periodMinutes;
  bool get isPoisson => model == ScoreModel.poisson;
}

const _rugbyBands = [
  [1, 12],
  [13, 24],
  [25, 999]
];
const _aflBands = [
  [1, 19],
  [20, 39],
  [40, 999]
];
const _nbaBands = [
  [1, 5],
  [6, 10],
  [11, 20],
  [21, 999]
];
const _nflBands = [
  [1, 6],
  [7, 13],
  [14, 999]
];

/// Catalogue of supported competitions, grouped by sport.
class Leagues {
  static const nba = League(
      id: 'nba',
      sport: 'basketball',
      slug: 'nba',
      name: 'NBA',
      group: 'Basketball',
      model: ScoreModel.normal,
      priorTeamScore: 114,
      priorTeamSd: 12,
      priorHomeAdv: 2.5,
      priorRho: 0.25,
      halfLifeDays: 30,
      periods: 4,
      periodMinutes: 12,
      hasPlayerProps: true,
      marginBands: _nbaBands);
  static const nbl = League(
      id: 'nbl',
      sport: 'basketball',
      slug: 'nbl',
      name: 'NBL (Australia)',
      group: 'Basketball',
      model: ScoreModel.normal,
      priorTeamScore: 88,
      priorTeamSd: 11,
      priorHomeAdv: 3,
      priorRho: 0.2,
      halfLifeDays: 45,
      periods: 4,
      periodMinutes: 10,
      hasPlayerProps: true,
      marginBands: _nbaBands);
  static const wnba = League(
      id: 'wnba',
      sport: 'basketball',
      slug: 'wnba',
      name: 'WNBA',
      group: 'Basketball',
      model: ScoreModel.normal,
      priorTeamScore: 83,
      priorTeamSd: 10,
      priorHomeAdv: 2.5,
      priorRho: 0.2,
      halfLifeDays: 30,
      periods: 4,
      periodMinutes: 10,
      hasPlayerProps: true,
      marginBands: _nbaBands);
  static const ncaab = League(
      id: 'ncaab',
      sport: 'basketball',
      slug: 'mens-college-basketball',
      name: 'NCAA Basketball (M)',
      group: 'Basketball',
      model: ScoreModel.normal,
      priorTeamScore: 74,
      priorTeamSd: 10.5,
      priorHomeAdv: 3.5,
      priorRho: 0.2,
      halfLifeDays: 40,
      periods: 2,
      periodMinutes: 20,
      hasPlayerProps: true,
      scoreboardParams: {'groups': '50', 'limit': '400'},
      useDateScan: true,
      marginBands: _nbaBands);
  static const nfl = League(
      id: 'nfl',
      sport: 'football',
      slug: 'nfl',
      name: 'NFL',
      group: 'American Football',
      model: ScoreModel.normal,
      priorTeamScore: 22.5,
      priorTeamSd: 10,
      priorHomeAdv: 1.5,
      priorRho: 0.05,
      halfLifeDays: 70,
      periods: 4,
      periodMinutes: 15,
      marginBands: _nflBands);
  static const ncaaf = League(
      id: 'ncaaf',
      sport: 'football',
      slug: 'college-football',
      name: 'NCAA Football',
      group: 'American Football',
      model: ScoreModel.normal,
      priorTeamScore: 28,
      priorTeamSd: 13,
      priorHomeAdv: 3,
      priorRho: 0.05,
      halfLifeDays: 70,
      periods: 4,
      periodMinutes: 15,
      scoreboardParams: {'groups': '80', 'limit': '400'},
      useDateScan: true,
      marginBands: _nflBands);
  static const cfl = League(
      id: 'cfl',
      sport: 'football',
      slug: 'cfl',
      name: 'CFL',
      group: 'American Football',
      model: ScoreModel.normal,
      priorTeamScore: 25,
      priorTeamSd: 10,
      priorHomeAdv: 2,
      halfLifeDays: 70,
      periods: 4,
      periodMinutes: 15,
      marginBands: _nflBands);
  static const nhl = League(
      id: 'nhl',
      sport: 'hockey',
      slug: 'nhl',
      name: 'NHL',
      group: 'Ice Hockey',
      model: ScoreModel.poisson,
      priorTeamScore: 3.0,
      priorTeamSd: 1.7,
      priorHomeAdv: 0.2,
      halfLifeDays: 40,
      periods: 3,
      periodMinutes: 20,
      scoreWord: 'goals',
      lineStep: 0.5);
  static const mlb = League(
      id: 'mlb',
      sport: 'baseball',
      slug: 'mlb',
      name: 'MLB',
      group: 'Baseball',
      model: ScoreModel.poisson,
      priorTeamScore: 4.4,
      priorTeamSd: 3,
      priorHomeAdv: 0.2,
      halfLifeDays: 40,
      periods: 9,
      periodMinutes: 20,
      scoreWord: 'runs',
      lineStep: 0.5);
  static const afl = League(
      id: 'afl',
      sport: 'australian-football',
      slug: 'afl',
      name: 'AFL',
      group: 'Aussie Rules',
      model: ScoreModel.normal,
      priorTeamScore: 80,
      priorTeamSd: 20,
      priorHomeAdv: 7,
      priorRho: -0.1,
      halfLifeDays: 60,
      periods: 4,
      periodMinutes: 30,
      marginBands: _aflBands);
  static const nrl = League(
      id: 'nrl',
      sport: 'rugby-league',
      slug: '3',
      name: 'NRL',
      group: 'Rugby League',
      model: ScoreModel.normal,
      priorTeamScore: 22,
      priorTeamSd: 11,
      priorHomeAdv: 3,
      priorRho: -0.1,
      halfLifeDays: 60,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const superRugby = League(
      id: 'super-rugby',
      sport: 'rugby',
      slug: '242041',
      name: 'Super Rugby Pacific',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 27,
      priorTeamSd: 12,
      priorHomeAdv: 4,
      priorRho: -0.1,
      halfLifeDays: 60,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const sixNations = League(
      id: 'six-nations',
      sport: 'rugby',
      slug: '180659',
      name: 'Six Nations',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 22,
      priorTeamSd: 11,
      priorHomeAdv: 4,
      halfLifeDays: 120,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const rugbyChampionship = League(
      id: 'rugby-championship',
      sport: 'rugby',
      slug: '244293',
      name: 'Rugby Championship',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 24,
      priorTeamSd: 11,
      priorHomeAdv: 4,
      halfLifeDays: 120,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const premRugby = League(
      id: 'prem-rugby',
      sport: 'rugby',
      slug: '267979',
      name: 'Gallagher Prem (England)',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 26,
      priorTeamSd: 11,
      priorHomeAdv: 4,
      halfLifeDays: 60,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const urc = League(
      id: 'urc',
      sport: 'rugby',
      slug: '270557',
      name: 'United Rugby Championship',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 26,
      priorTeamSd: 11,
      priorHomeAdv: 4,
      halfLifeDays: 60,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const top14 = League(
      id: 'top14',
      sport: 'rugby',
      slug: '270559',
      name: 'French Top 14',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 25,
      priorTeamSd: 11,
      priorHomeAdv: 6,
      halfLifeDays: 60,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const champCup = League(
      id: 'champions-cup',
      sport: 'rugby',
      slug: '271937',
      name: 'Champions Cup (Rugby)',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 26,
      priorTeamSd: 12,
      priorHomeAdv: 4,
      halfLifeDays: 90,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const rugbyWorldCup = League(
      id: 'rugby-world-cup',
      sport: 'rugby',
      slug: '164205',
      name: 'Rugby World Cup',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 26,
      priorTeamSd: 14,
      priorHomeAdv: 0,
      halfLifeDays: 180,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const rugbyTests = League(
      id: 'rugby-tests',
      sport: 'rugby',
      slug: '289234',
      name: 'International Tests (Rugby)',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 25,
      priorTeamSd: 13,
      priorHomeAdv: 4,
      halfLifeDays: 180,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const mlr = League(
      id: 'mlr',
      sport: 'rugby',
      slug: '289262',
      name: 'Major League Rugby',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 27,
      priorTeamSd: 12,
      priorHomeAdv: 4,
      halfLifeDays: 60,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const npc = League(
      id: 'npc',
      sport: 'rugby',
      slug: '270563',
      name: 'NPC (New Zealand)',
      group: 'Rugby Union',
      model: ScoreModel.normal,
      priorTeamScore: 28,
      priorTeamSd: 12,
      priorHomeAdv: 4,
      halfLifeDays: 60,
      periods: 2,
      periodMinutes: 40,
      marginBands: _rugbyBands);
  static const nll = League(
      id: 'nll',
      sport: 'lacrosse',
      slug: 'nll',
      name: 'NLL Lacrosse',
      group: 'Lacrosse',
      model: ScoreModel.normal,
      priorTeamScore: 11,
      priorTeamSd: 3,
      priorHomeAdv: 0.6,
      halfLifeDays: 60,
      periods: 4,
      periodMinutes: 15,
      scoreWord: 'goals',
      lineStep: 0.5);

  static League soccer(String id, String slug, String name,
          {double homeAdv = 0.35, double avg = 1.4, double halfLife = 90}) =>
      League(
          id: id,
          sport: 'soccer',
          slug: slug,
          name: name,
          group: 'Soccer',
          model: ScoreModel.poisson,
          priorTeamScore: avg,
          priorTeamSd: 1.2,
          priorHomeAdv: homeAdv,
          halfLifeDays: halfLife,
          periods: 2,
          periodMinutes: 45,
          scoreWord: 'goals',
          lineStep: 0.5);

  static final List<League> all = [
    nba, nbl, wnba, ncaab,
    nrl, afl,
    nfl, ncaaf, cfl,
    nhl, mlb, nll,
    superRugby, sixNations, rugbyChampionship, premRugby, urc, top14, champCup,
    rugbyWorldCup, rugbyTests, mlr, npc,
    soccer('epl', 'eng.1', 'English Premier League'),
    soccer('championship', 'eng.2', 'English Championship'),
    soccer('fa-cup', 'eng.fa', 'FA Cup', halfLife: 120),
    soccer('laliga', 'esp.1', 'Spanish LALIGA'),
    soccer('bundesliga', 'ger.1', 'German Bundesliga', avg: 1.55),
    soccer('seriea', 'ita.1', 'Italian Serie A'),
    soccer('ligue1', 'fra.1', 'French Ligue 1'),
    soccer('eredivisie', 'ned.1', 'Dutch Eredivisie', avg: 1.6),
    soccer('primeira', 'por.1', 'Portuguese Primeira Liga'),
    soccer('scot-prem', 'sco.1', 'Scottish Premiership'),
    soccer('ucl', 'uefa.champions', 'UEFA Champions League', halfLife: 120),
    soccer('uel', 'uefa.europa', 'UEFA Europa League', halfLife: 120),
    soccer('uecl', 'uefa.europa.conf', 'UEFA Conference League', halfLife: 120),
    soccer('a-league', 'aus.1', 'A-League Men (Australia)', avg: 1.5),
    soccer('a-league-w', 'aus.w.1', 'A-League Women', avg: 1.5),
    soccer('mls', 'usa.1', 'MLS'),
    soccer('nwsl', 'usa.nwsl', 'NWSL'),
    soccer('jleague', 'jpn.1', 'Japanese J.League'),
    soccer('k-league', 'kor.1', 'Korean K League'),
    soccer('ligamx', 'mex.1', 'Liga MX'),
    soccer('brasileirao', 'bra.1', 'Brazilian Serie A'),
    soccer('argentina', 'arg.1', 'Argentine Liga Profesional', avg: 1.2),
    soccer('saudi', 'ksa.1', 'Saudi Pro League'),
    soccer('super-lig', 'tur.1', 'Turkish Super Lig'),
    soccer('belgium', 'bel.1', 'Belgian Pro League'),
    soccer('wsl', 'eng.w.1', "Women's Super League (England)"),
    soccer('wcq-uefa', 'fifa.worldq.uefa', 'World Cup Qualifying - UEFA', homeAdv: 0.4, halfLife: 240),
    soccer('wcq-conmebol', 'fifa.worldq.conmebol', 'World Cup Qualifying - CONMEBOL', homeAdv: 0.5, halfLife: 240),
    soccer('wcq-afc', 'fifa.worldq.afc', 'World Cup Qualifying - AFC', homeAdv: 0.4, halfLife: 240),
    soccer('wcq-concacaf', 'fifa.worldq.concacaf', 'World Cup Qualifying - CONCACAF', homeAdv: 0.4, halfLife: 240),
    soccer('world-cup', 'fifa.world', 'FIFA World Cup', homeAdv: 0.1, halfLife: 240),
    soccer('euro', 'uefa.euro', 'UEFA Euro', homeAdv: 0.1, halfLife: 240),
    soccer('copa-america', 'conmebol.america', 'Copa America', homeAdv: 0.1, halfLife: 240),
    soccer('club-world-cup', 'fifa.cwc', 'FIFA Club World Cup', homeAdv: 0.1, halfLife: 240),
    soccer('libertadores', 'conmebol.libertadores', 'Copa Libertadores', homeAdv: 0.45, halfLife: 120),
    soccer('afc-cl', 'afc.champions', 'AFC Champions League', halfLife: 120),
  ];

  static League? byId(String id) {
    for (final l in all) {
      if (l.id == id) return l;
    }
    return null;
  }

  static List<String> get groups {
    final out = <String>[];
    for (final l in all) {
      if (!out.contains(l.group)) out.add(l.group);
    }
    return out;
  }

  /// The Odds API sport keys for competitions it covers.
  static const oddsKeys = {
    'nba': 'basketball_nba',
    'nbl': 'basketball_nbl',
    'wnba': 'basketball_wnba',
    'ncaab': 'basketball_ncaab',
    'nrl': 'rugbyleague_nrl',
    'afl': 'aussierules_afl',
    'nfl': 'americanfootball_nfl',
    'ncaaf': 'americanfootball_ncaaf',
    'nhl': 'icehockey_nhl',
    'mlb': 'baseball_mlb',
    'six-nations': 'rugbyunion_six_nations',
    'epl': 'soccer_epl',
    'championship': 'soccer_efl_champ',
    'laliga': 'soccer_spain_la_liga',
    'bundesliga': 'soccer_germany_bundesliga',
    'seriea': 'soccer_italy_serie_a',
    'ligue1': 'soccer_france_ligue_one',
    'eredivisie': 'soccer_netherlands_eredivisie',
    'primeira': 'soccer_portugal_primeira_liga',
    'scot-prem': 'soccer_spl',
    'ucl': 'soccer_uefa_champs_league',
    'uel': 'soccer_uefa_europa_league',
    'a-league': 'soccer_australia_aleague',
    'mls': 'soccer_usa_mls',
    'jleague': 'soccer_japan_j_league',
    'ligamx': 'soccer_mexico_ligamx',
    'brasileirao': 'soccer_brazil_campeonato',
    'saudi': 'soccer_saudi_arabia_pro_league',
    'super-lig': 'soccer_turkey_super_league',
    'belgium': 'soccer_belgium_first_div',
  };

  /// Leagues shown on the Live tab unless the user changes them.
  static const defaultFavourites = ['nba', 'nbl', 'nrl', 'afl', 'epl', 'a-league', 'nfl', 'nhl'];
}
