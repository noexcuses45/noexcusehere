import 'dart:math' as math;

import '../models/game.dart';
import '../models/league.dart';
import 'ratings.dart';
import 'stats.dart';

enum Side { home, away }

enum LegType { moneyline, handicap, total, teamTotal, margin, btts, playerProp }

/// One selection in a same-game multi.
class Leg {
  Leg({
    required this.type,
    required this.label,
    this.side,
    this.over = true,
    this.line = 0,
    this.lo = 0,
    this.hi = 0,
    this.player,
    this.prob = 0,
  });

  final LegType type;
  final String label;
  final Side? side;
  final bool over; // for totals / team totals / props: over (true) or under (false)
  final double line;
  final int lo, hi; // margin band
  final PlayerProjection? player;
  double prob; // single-leg model probability

  double get fairOdds => Stats.fairOdds(prob);

  String get key => '$type|$side|$over|$line|$lo|$hi|${player?.name}|${player?.stat}';

  Map<String, dynamic> toJson() => {
        'type': type.name,
        'label': label,
        'side': side?.name,
        'over': over,
        'line': line,
        'lo': lo,
        'hi': hi,
        'prob': prob,
        'player': player?.toJson(),
      };

  static Leg fromJson(Map<String, dynamic> j) => Leg(
        type: LegType.values.firstWhere((t) => t.name == j['type']),
        label: j['label'] ?? '',
        side: j['side'] == null ? null : Side.values.firstWhere((s) => s.name == j['side']),
        over: j['over'] ?? true,
        line: (j['line'] as num?)?.toDouble() ?? 0,
        lo: j['lo'] ?? 0,
        hi: j['hi'] ?? 0,
        prob: (j['prob'] as num?)?.toDouble() ?? 0,
        player: j['player'] == null ? null : PlayerProjection.fromJson(j['player']),
      );
}

/// Projected stat line for a player (basketball-style box score sports).
class PlayerProjection {
  PlayerProjection({
    required this.name,
    required this.side,
    required this.stat,
    required this.mean,
    required this.sd,
    this.games = 0,
    this.minutes = 0,
    this.teamCorr = 0.3,
    this.lastValues = const [],
    this.flag,
  });
  final String name;
  final Side side;
  final String stat; // PTS, REB, AST, 3PM, PRA
  final double mean;
  final double sd;
  final int games;
  final double minutes;
  final double teamCorr;
  final List<double> lastValues;
  final String? flag; // e.g. injury note

  /// Surname for compact labels, skipping suffixes like Jr. or III.
  String get shortName {
    final parts = name.split(' ').where((x) => x.isNotEmpty).toList();
    const suffixes = {'jr.', 'jr', 'sr.', 'sr', 'ii', 'iii', 'iv', 'v'};
    while (parts.length > 1 && suffixes.contains(parts.last.toLowerCase())) {
      parts.removeLast();
    }
    return parts.isEmpty ? name : parts.last;
  }

  /// Half-point line the model expects the player to beat about 70% of the time.
  double get overLine => _half(mean - 0.55 * sd).clamp(0.5, 999);

  /// Half-point line the model expects the player to stay under about 70% of the time.
  double get underLine => _half(mean + 0.55 * sd);

  /// Median line (about 50/50).
  double get line => _half(mean);

  /// Stretch line the model gives roughly a 30% chance.
  double get stretchLine => _half(mean + 0.55 * sd);

  static double _half(double x) => (x - 0.5).round() + 0.5;

  Map<String, dynamic> toJson() => {
        'name': name,
        'side': side.name,
        'stat': stat,
        'mean': mean,
        'sd': sd,
        'games': games,
        'minutes': minutes,
        'corr': teamCorr,
      };
  static PlayerProjection fromJson(Map<String, dynamic> j) => PlayerProjection(
        name: j['name'],
        side: Side.values.firstWhere((s) => s.name == j['side']),
        stat: j['stat'],
        mean: (j['mean'] as num).toDouble(),
        sd: (j['sd'] as num).toDouble(),
        games: j['games'] ?? 0,
        minutes: (j['minutes'] as num?)?.toDouble() ?? 0,
        teamCorr: (j['corr'] as num?)?.toDouble() ?? 0.3,
      );
}

/// A multi (same-game or across games) with its joint probability.
class Multi {
  Multi({required this.legs, required this.prob, this.title = ''});
  final List<Leg> legs;
  final double prob;
  final String title;
  double get fairOdds => Stats.fairOdds(prob);
  double get naiveOdds => legs.fold(1.0, (o, l) => o * l.fairOdds);
}

/// Pre-game (or live-adjusted) prediction for one fixture.
class GamePrediction {
  GamePrediction({
    required this.game,
    required this.muHome,
    required this.muAway,
    required this.sdHome,
    required this.sdAway,
    required this.rho,
    required this.confidence,
    this.homeGames = 0,
    this.awayGames = 0,
    this.players = const [],
    this.baseHome = 0,
    this.baseAway = 0,
    this.live = false,
  });

  final Game game;
  final double muHome, muAway; // expected (remaining) scores
  final double sdHome, sdAway;
  final double rho;
  final double confidence; // 0..1 based on sample size
  final int homeGames, awayGames;
  final List<PlayerProjection> players;
  final double baseHome, baseAway; // points already on the board (live)
  final bool live;

  League get league => game.league;
  bool get poisson => league.isPoisson;
  double get muTotal => baseHome + baseAway + muHome + muAway;
  double get muMargin => (baseHome + muHome) - (baseAway + muAway);
  double get sdTotal => math.sqrt(sdHome * sdHome + sdAway * sdAway + 2 * rho * sdHome * sdAway);
  double get sdMargin => math.sqrt(math.max(0.01, sdHome * sdHome + sdAway * sdAway - 2 * rho * sdHome * sdAway));
  double get projHome => baseHome + muHome;
  double get projAway => baseAway + muAway;

  // ----- Poisson tables (soccer, hockey, baseball) -----
  List<double>? _pH, _pA;
  int get _maxGoals => math.max(12, (muHome + muAway).ceil() * 4 + 8);
  void _ensureTables() {
    if (_pH != null) return;
    _pH = Stats.poissonTable(muHome, _maxGoals);
    _pA = Stats.poissonTable(muAway, _maxGoals);
  }

  /// Probability that total (final) is strictly greater than [line].
  double probOver(double line) {
    if (poisson) {
      _ensureTables();
      var p = 0.0;
      for (var h = 0; h <= _maxGoals; h++) {
        for (var a = 0; a <= _maxGoals; a++) {
          if (baseHome + baseAway + h + a > line) p += _pH![h] * _pA![a];
        }
      }
      return p;
    }
    // Normal with continuity: strictly greater than an integer line means >= line+1.
    final cut = line == line.roundToDouble() ? line + 0.5 : line;
    return Stats.normalTail(cut, muTotal, sdTotal);
  }

  double probUnder(double line) {
    if (poisson) {
      _ensureTables();
      var p = 0.0;
      for (var h = 0; h <= _maxGoals; h++) {
        for (var a = 0; a <= _maxGoals; a++) {
          if (baseHome + baseAway + h + a < line) p += _pH![h] * _pA![a];
        }
      }
      return p;
    }
    final cut = line == line.roundToDouble() ? line - 0.5 : line;
    return 1 - Stats.normalTail(cut, muTotal, sdTotal);
  }

  double probPush(double line) => (1 - probOver(line) - probUnder(line)).clamp(0.0, 1.0);

  /// P(home margin + handicap > 0). Handicap 0 = moneyline (draw excluded).
  double probHomeCover(double handicap) {
    if (poisson) {
      _ensureTables();
      var p = 0.0;
      for (var h = 0; h <= _maxGoals; h++) {
        for (var a = 0; a <= _maxGoals; a++) {
          if ((baseHome + h) - (baseAway + a) + handicap > 0) p += _pH![h] * _pA![a];
        }
      }
      return p;
    }
    final x = -handicap;
    final cut = x == x.roundToDouble() ? x + 0.5 : x;
    return Stats.normalTail(cut, muMargin, sdMargin);
  }

  double probAwayCover(double handicap) {
    if (poisson) {
      _ensureTables();
      var p = 0.0;
      for (var h = 0; h <= _maxGoals; h++) {
        for (var a = 0; a <= _maxGoals; a++) {
          if ((baseAway + a) - (baseHome + h) + handicap > 0) p += _pH![h] * _pA![a];
        }
      }
      return p;
    }
    final x = handicap;
    final cut = x == x.roundToDouble() ? x - 0.5 : x;
    return 1 - Stats.normalTail(cut, muMargin, sdMargin);
  }

  double get probDraw => (1 - probHomeCover(0) - probAwayCover(0)).clamp(0.0, 1.0);
  double get probHomeWin => probHomeCover(0);
  double get probAwayWin => probAwayCover(0);

  double probTeamOver(Side side, double line) {
    final mu = side == Side.home ? muHome : muAway;
    final base = side == Side.home ? baseHome : baseAway;
    if (poisson) {
      final t = Stats.poissonTable(mu, _maxGoals);
      var p = 0.0;
      for (var k = 0; k <= _maxGoals; k++) {
        if (base + k > line) p += t[k];
      }
      return p;
    }
    final sd = side == Side.home ? sdHome : sdAway;
    final cut = line == line.roundToDouble() ? line + 0.5 : line;
    return Stats.normalTail(cut, base + mu, sd);
  }

  double probTeamUnder(Side side, double line) {
    final mu = side == Side.home ? muHome : muAway;
    final base = side == Side.home ? baseHome : baseAway;
    if (poisson) {
      final t = Stats.poissonTable(mu, _maxGoals);
      var p = 0.0;
      for (var k = 0; k <= _maxGoals; k++) {
        if (base + k < line) p += t[k];
      }
      return p;
    }
    final sd = side == Side.home ? sdHome : sdAway;
    final cut = line == line.roundToDouble() ? line - 0.5 : line;
    return 1 - Stats.normalTail(cut, base + mu, sd);
  }

  double probMarginBand(Side side, int lo, int hi) {
    // P(lo <= winning margin of side <= hi)
    if (side == Side.home) {
      return (probHomeCover(-(lo - 1).toDouble()) - probHomeCover(-hi.toDouble())).clamp(0.0, 1.0);
    }
    return (probAwayCover(-(lo - 1).toDouble()) - probAwayCover(-hi.toDouble())).clamp(0.0, 1.0);
  }

  double get probBtts {
    if (!poisson) return 1;
    return (1 - Stats.poissonPmf(0, muHome)) * (1 - Stats.poissonPmf(0, muAway));
  }

  double probPlayerOver(PlayerProjection p, double line) =>
      Stats.normalTail(line, p.mean, p.sd);

  /// Single-leg probability, analytic.
  double legProb(Leg leg) {
    switch (leg.type) {
      case LegType.moneyline:
        return leg.side == Side.home ? probHomeWin : probAwayWin;
      case LegType.handicap:
        return leg.side == Side.home ? probHomeCover(leg.line) : probAwayCover(leg.line);
      case LegType.total:
        return leg.over ? probOver(leg.line) : probUnder(leg.line);
      case LegType.teamTotal:
        return leg.over ? probTeamOver(leg.side!, leg.line) : probTeamUnder(leg.side!, leg.line);
      case LegType.margin:
        return probMarginBand(leg.side!, leg.lo, leg.hi);
      case LegType.btts:
        return leg.over ? probBtts : 1 - probBtts;
      case LegType.playerProp:
        final p = leg.player!;
        return leg.over ? probPlayerOver(p, leg.line) : 1 - probPlayerOver(p, leg.line);
    }
  }

  // ----- Monte Carlo for correlated multis -----
  List<Sim>? _sims;
  int simCount = 20000;

  List<Sim> sims() {
    if (_sims != null) return _sims!;
    final rng = Rng(eventSeed());
    final out = <Sim>[];
    for (var i = 0; i < simCount; i++) {
      double h, a;
      if (poisson) {
        h = (baseHome + rng.poisson(muHome)).toDouble();
        a = (baseAway + rng.poisson(muAway)).toDouble();
      } else {
        final z1 = rng.normal();
        final z2 = rho * z1 + math.sqrt(1 - rho * rho) * rng.normal();
        h = (baseHome + muHome + sdHome * z1).roundToDouble();
        a = (baseAway + muAway + sdAway * z2).roundToDouble();
        if (h < 0) h = 0;
        if (a < 0) a = 0;
      }
      out.add(Sim(h, a, rng));
    }
    return _sims = out;
  }

  int eventSeed() => game.eventId.hashCode & 0x7fffffff;

  bool _legHits(Leg leg, Sim s) {
    switch (leg.type) {
      case LegType.moneyline:
        return leg.side == Side.home ? s.h > s.a : s.a > s.h;
      case LegType.handicap:
        return leg.side == Side.home ? s.h - s.a + leg.line > 0 : s.a - s.h + leg.line > 0;
      case LegType.total:
        return leg.over ? s.h + s.a > leg.line : s.h + s.a < leg.line;
      case LegType.teamTotal:
        final v = leg.side == Side.home ? s.h : s.a;
        return leg.over ? v > leg.line : v < leg.line;
      case LegType.margin:
        final m = leg.side == Side.home ? s.h - s.a : s.a - s.h;
        return m >= leg.lo && m <= leg.hi;
      case LegType.btts:
        return leg.over ? (s.h > 0 && s.a > 0) : !(s.h > 0 && s.a > 0);
      case LegType.playerProp:
        final p = leg.player!;
        final teamMu = p.side == Side.home ? projHome : projAway;
        final teamSd = p.side == Side.home ? sdHome : sdAway;
        final teamScore = p.side == Side.home ? s.h : s.a;
        final zTeam = teamSd > 0 ? (teamScore - teamMu) / teamSd : 0.0;
        final zOwn = s.playerZ(p.name + p.stat);
        final v = p.mean + p.sd * (p.teamCorr * zTeam + math.sqrt(1 - p.teamCorr * p.teamCorr) * zOwn);
        return leg.over ? v > leg.line : v < leg.line;
    }
  }

  /// Joint probability of all legs hitting, with correlation via simulation.
  double jointProb(List<Leg> legs) {
    if (legs.isEmpty) return 0;
    if (legs.length == 1) return legProb(legs.first);
    final ss = sims();
    var hits = 0;
    for (final s in ss) {
      var ok = true;
      for (final l in legs) {
        if (!_legHits(l, s)) {
          ok = false;
          break;
        }
      }
      if (ok) hits++;
    }
    return hits / ss.length;
  }

  // ----- Candidate legs and suggested multis -----

  /// Nearest half-point line to [x] (x.5 lines can't push).
  double _halfLine(double x) => (x - 0.5).round() + 0.5;

  /// Legs the model likes, each with its probability.
  List<Leg> candidateLegs({double minProb = 0.52}) {
    final legs = <Leg>[];
    final h = game.home.abbr, a = game.away.abbr;
    void add(Leg l) {
      l.prob = legProb(l);
      if (l.prob >= minProb && l.prob <= 0.97) legs.add(l);
    }

    add(Leg(type: LegType.moneyline, side: Side.home, label: '$h to win'));
    add(Leg(type: LegType.moneyline, side: Side.away, label: '$a to win'));

    // Handicaps around the model margin so the favourite "covers" comfortably.
    final fav = muMargin >= 0 ? Side.home : Side.away;
    final favAbbr = fav == Side.home ? h : a;
    final dogAbbr = fav == Side.home ? a : h;
    final m = muMargin.abs();
    for (final frac in [0.35, 0.6]) {
      final hcp = _halfLine(m * frac);
      if (hcp >= 0.5 && m * frac >= 0.75) {
        add(Leg(type: LegType.handicap, side: fav, line: -hcp, label: '$favAbbr -$hcp'));
      }
    }
    final dogLine = _halfLine(m * 1.4 + (poisson ? 0.5 : 2));
    add(Leg(type: LegType.handicap, side: fav == Side.home ? Side.away : Side.home, line: dogLine, label: '$dogAbbr +$dogLine'));

    // Totals at lines offset from the projection.
    final step = poisson ? 0.5 : math.max(1.0, sdTotal * 0.25).roundToDouble();
    for (final k in [0.5, 1.0, 1.5]) {
      final under = _halfLine(muTotal + k * step * (poisson ? 1 : 2));
      final over = _halfLine(muTotal - k * step * (poisson ? 1 : 2));
      add(Leg(type: LegType.total, over: false, line: under, label: 'Under $under'));
      if (over > 0) add(Leg(type: LegType.total, over: true, line: over, label: 'Over $over'));
    }
    // Team totals.
    for (final side in Side.values) {
      final mu = side == Side.home ? projHome : projAway;
      final sd = side == Side.home ? sdHome : sdAway;
      final nm = side == Side.home ? h : a;
      final o = _halfLine(mu - (poisson ? 1 : sd * 0.6));
      final u = _halfLine(mu + (poisson ? 1 : sd * 0.6));
      if (o > 0) add(Leg(type: LegType.teamTotal, side: side, over: true, line: o, label: '$nm over $o'));
      add(Leg(type: LegType.teamTotal, side: side, over: false, line: u, label: '$nm under $u'));
    }
    // Margin bands for the favourite.
    for (final band in league.marginBands) {
      final lbl = band[1] >= 999 ? '$favAbbr by ${band[0]}+' : '$favAbbr by ${band[0]}-${band[1]}';
      add(Leg(type: LegType.margin, side: fav, lo: band[0], hi: band[1], label: lbl));
    }
    if (poisson && league.sport == 'soccer') {
      add(Leg(type: LegType.btts, over: true, label: 'Both teams to score'));
      add(Leg(type: LegType.btts, over: false, label: 'Not both teams to score'));
    }
    // Player props at the nearest half line.
    for (final p in players) {
      final nm = p.shortName;
      // Overs only (that is how player legs are bet in multis), and only lines
      // that mean something: nobody wants "0.5+ threes" in a multi.
      const minLine = {'PTS': 9.5, 'REB': 3.5, 'AST': 2.5, '3PM': 1.5, 'PRA': 14.5};
      if (p.overLine < (minLine[p.stat] ?? 0)) continue;
      add(Leg(type: LegType.playerProp, player: p, over: true, line: p.overLine, label: '$nm ${p.overLine}+ ${p.stat}'));
    }
    legs.sort((x, y) => y.prob.compareTo(x.prob));
    return legs;
  }

  bool _compatible(Leg x, Leg y) {
    if (x.type == y.type && x.type != LegType.playerProp && x.type != LegType.teamTotal) return false;
    if ((x.type == LegType.moneyline || x.type == LegType.handicap || x.type == LegType.margin) &&
        (y.type == LegType.moneyline || y.type == LegType.handicap || y.type == LegType.margin)) {
      return false;
    }
    if (x.type == LegType.teamTotal && y.type == LegType.teamTotal && x.side == y.side) return false;
    if (x.type == LegType.playerProp && y.type == LegType.playerProp && x.player!.name == y.player!.name) return false;
    if ((x.type == LegType.total && y.type == LegType.teamTotal) || (x.type == LegType.teamTotal && y.type == LegType.total)) {
      return false; // a game total already says most of what a team total says
    }
    return true;
  }

  /// Best same-game multis: highest joint probability for 2, 3 and 4 legs.
  List<Multi> suggestMultis({int perSize = 3}) {
    final cands = candidateLegs(minProb: 0.58).take(16).toList();
    final out = <Multi>[];
    for (final size in [2, 3, 4]) {
      final combos = <Multi>[];
      void rec(int start, List<Leg> cur) {
        if (cur.length == size) {
          combos.add(Multi(legs: List.of(cur), prob: jointProb(cur)));
          return;
        }
        for (var i = start; i < cands.length; i++) {
          final playerLegs = cur.where((c) => c.type == LegType.playerProp).length;
          if (cands[i].type == LegType.playerProp && playerLegs >= 2) continue;
          if (cands[i].type == LegType.playerProp && cur.length == size - 1 && playerLegs == cur.length) continue;
          if (cur.every((c) => _compatible(c, cands[i]))) {
            cur.add(cands[i]);
            rec(i + 1, cur);
            cur.removeLast();
          }
        }
      }

      rec(0, []);
      combos.sort((x, y) => y.prob.compareTo(x.prob));
      out.addAll(combos.take(perSize));
    }
    return out;
  }

  /// Builds the same-game multi with the best chance of landing for a
  /// given number of legs: greedy search over compatible legs (player
  /// props included), scoring each step by the simulated joint probability.
  Multi buildMulti(int legs) {
    final cands = candidateLegs(minProb: 0.5);
    // Keep the search fast: strongest 48 legs, one per player.
    final seenPlayers = <String>{};
    final pool = <Leg>[];
    for (final l in cands) {
      if (l.type == LegType.playerProp) {
        if (!seenPlayers.add(l.player!.name)) continue;
      }
      pool.add(l);
      if (pool.length >= 48) break;
    }
    final chosen = <Leg>[];
    var best = 0.0;
    while (chosen.length < legs) {
      Leg? pick;
      var pickProb = -1.0;
      for (final l in pool) {
        if (chosen.contains(l)) continue;
        if (!chosen.every((c) => _compatible(c, l))) continue;
        final p = jointProb([...chosen, l]);
        if (p > pickProb) {
          pickProb = p;
          pick = l;
        }
      }
      if (pick == null) break;
      chosen.add(pick);
      best = pickProb;
    }
    if (chosen.length == 1) best = chosen.first.prob;
    return Multi(legs: chosen, prob: best, title: '${chosen.length}-leg AI multi');
  }

  /// Plain-English lean on the total at [line].
  String totalLean(double line) {
    final o = probOver(line);
    if (o >= 0.5) return 'Over ${(o * 100).round()}%';
    return 'Under ${((1 - o - probPush(line)) * 100).round()}%';
  }
}

class Sim {
  Sim(this.h, this.a, this._rng);
  final double h, a;
  final Rng _rng;
  final Map<String, double> _z = {};
  double playerZ(String key) => _z.putIfAbsent(key, () => _rng.normal());
}

/// Builds predictions from a fitted league model.
class Predictor {
  Predictor(this.model);
  final LeagueModel model;

  GamePrediction predict(Game game, {List<PlayerProjection> players = const [], int sims = 20000}) {
    final (muH, muA) = model.expected(game.home.id, game.away.id, neutral: game.neutral);
    final hg = model.ratingOf(game.home.id).games, ag = model.ratingOf(game.away.id).games;
    final conf = (math.min(hg, ag) / 10.0).clamp(0.1, 1.0) * (model.thin ? 0.6 : 1.0);
    final sd = model.teamSd;
    final pred = GamePrediction(
      game: game,
      muHome: muH,
      muAway: muA,
      sdHome: sd,
      sdAway: sd,
      rho: model.rho,
      confidence: conf,
      homeGames: hg,
      awayGames: ag,
      players: players,
    );
    pred.simCount = sims;
    if (game.isLive || game.isFinal) return _liveAdjust(pred, game);
    return pred;
  }

  GamePrediction _liveAdjust(GamePrediction pre, Game game) {
    final f = game.remainingFraction();
    final hs = game.homeScore ?? 0, as = game.awayScore ?? 0;
    if (f <= 0 || game.isFinal) {
      return GamePrediction(
        game: game,
        muHome: 0,
        muAway: 0,
        sdHome: 0.01,
        sdAway: 0.01,
        rho: 0,
        confidence: 1,
        baseHome: hs,
        baseAway: as,
        live: true,
      );
    }
    final played = 1 - f;
    // Blend pre-game rate with observed pace (observed gets weight as the game progresses).
    final wObs = played * 0.5;
    final rateH = played > 0.05 ? hs / played : pre.muHome;
    final rateA = played > 0.05 ? as / played : pre.muAway;
    final muH = ((1 - wObs) * pre.muHome + wObs * rateH) * f;
    final muA = ((1 - wObs) * pre.muAway + wObs * rateA) * f;
    final scale = math.sqrt(f);
    final p = GamePrediction(
      game: game,
      muHome: muH,
      muAway: muA,
      sdHome: math.max(pre.sdHome * scale, pre.poisson ? 0.05 : 1.0),
      sdAway: math.max(pre.sdAway * scale, pre.poisson ? 0.05 : 1.0),
      rho: pre.rho,
      confidence: pre.confidence,
      homeGames: pre.homeGames,
      awayGames: pre.awayGames,
      players: const [],
      baseHome: hs,
      baseAway: as,
      live: true,
    );
    p.simCount = pre.simCount;
    return p;
  }
}

/// Best multi across several games of a round: picks the strongest
/// compatible leg from each game (one leg per game) and combines them.
class RoundMulti {
  static List<Multi> build(List<GamePrediction> preds, {int maxLegs = 5}) {
    final best = <(GamePrediction, Leg)>[];
    for (final p in preds) {
      if (p.live || p.game.isFinal) continue;
      final legs = p.candidateLegs(minProb: 0.55)
          .where((l) => l.type != LegType.playerProp)
          .toList();
      if (legs.isEmpty) continue;
      // Prefer confident, score-shaped legs; weight by model confidence.
      legs.sort((x, y) => (y.prob * (0.7 + 0.3 * p.confidence)).compareTo(x.prob * (0.7 + 0.3 * p.confidence)));
      best.add((p, legs.first));
    }
    best.sort((x, y) => y.$2.prob.compareTo(x.$2.prob));
    final out = <Multi>[];
    for (final n in [2, 3, 4, maxLegs]) {
      if (best.length < n || n < 2) continue;
      final legs = best.take(n).map((e) => e.$2).toList();
      final prob = legs.fold(1.0, (acc, l) => acc * l.prob); // games are independent
      out.add(Multi(
        legs: legs,
        prob: prob,
        title: '$n-leg round multi',
      ));
    }
    return out;
  }
}
