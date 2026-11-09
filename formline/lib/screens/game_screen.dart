import 'dart:async';

import 'package:flutter/material.dart';

import '../engine/bookie.dart';
import '../engine/player_props.dart';
import '../engine/predictor.dart';
import '../models/game.dart';
import '../services/espn_api.dart';
import '../services/model_store.dart';
import '../services/odds_api.dart';
import '../services/picks_store.dart';
import '../services/settings_service.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// One game: projections, over/under at any line, best same-game multis,
/// player props and a build-your-own multi.
class GameScreen extends StatefulWidget {
  const GameScreen({super.key, required this.game, required this.prediction, this.autoBuild});
  final Game game;
  final GamePrediction prediction;
  final int? autoBuild; // build an AI multi with this many legs on open

  @override
  State<GameScreen> createState() => _GameScreenState();
}

class _GameScreenState extends State<GameScreen> {
  late GamePrediction _pred;
  List<Multi> _multis = [];
  List<Leg> _cands = [];
  final Set<String> _selected = {};
  final Map<String, Leg> _customLegs = {}; // player lines picked from the props table
  String _stat = 'PTS';
  double? _line;
  final _lineCtrl = TextEditingController();
  bool _loadingPlayers = false;
  String? _playersNote;
  Map<String, String> _injuries = {};
  Multi? _aiMulti;
  int? _aiBuilding;
  int? _pendingBuild;
  late Game _game;
  List<PlayerProjection> _preProps = []; // pre-game projections (for the live box chips)
  EventSummary? _box;
  String? _boxError;
  Timer? _liveTimer;
  int _boxTeam = 0; // 0 away, 1 home
  OddsEvent? _oddsEvent;

  Game get game => _game;

  @override
  void initState() {
    super.initState();
    _game = widget.game;
    _pred = widget.prediction;
    if (_game.isLive || _game.isFinal) {
      _refreshLive();
      if (_game.isLive) _liveTimer = Timer.periodic(const Duration(seconds: 30), (_) => _refreshLive());
    }
    _line = game.marketTotal ?? _roundHalf(_pred.muTotal);
    _lineCtrl.text = num1(_line!);
    _recompute();
    _loadOdds();
    _pendingBuild = widget.autoBuild;
    if (game.league.hasPlayerProps) {
      _loadPlayers(); // for finished games this rebuilds the pre-game lines to grade them
    } else if (_pendingBuild != null) {
      _buildAi(_pendingBuild!);
    }
  }

  @override
  void dispose() {
    _liveTimer?.cancel();
    _lineCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadOdds() async {
    if (!Settings.instance.hasOddsKey || game.league.oddsKey == null || game.isFinal) return;
    try {
      final ev = await OddsApi.instance.events(game.league);
      if (!mounted) return;
      setState(() => _oddsEvent = OddsApi.match(ev, game));
    } catch (_) {}
  }

  Widget _bookieCard(GamePrediction p) {
    final ev = _oddsEvent;
    if (ev == null) return const SizedBox();
    final settings = Settings.instance;
    final books = ev.books.keys.toList();
    if (books.isEmpty) return const SizedBox();
    final key = books.contains(settings.bookie) ? settings.bookie : books.first;
    final b = ev.books[key]!;
    final priced = BookieModel.priceLegs(p, b);
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Row(children: [
              Icon(Icons.storefront, color: EdgeColors.lime, size: 20),
              SizedBox(width: 8),
              Expanded(child: Text('Bookie odds vs the model', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
            ]),
            const SizedBox(height: 8),
            SizedBox(
              height: 36,
              child: ListView(scrollDirection: Axis.horizontal, children: [
                for (final k in books)
                  Padding(
                    padding: const EdgeInsets.only(right: 6),
                    child: ChoiceChip(
                      label: Text(ev.books[k]!.title),
                      selected: k == key,
                      visualDensity: VisualDensity.compact,
                      onSelected: (_) {
                        settings.bookie = k;
                        settings.save();
                        setState(() {});
                      },
                    ),
                  ),
              ]),
            ),
            const SizedBox(height: 6),
            for (final l in priced)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 3),
                child: Row(children: [
                  Expanded(child: Text(l.leg.label, style: const TextStyle(fontWeight: FontWeight.w600))),
                  SizedBox(width: 58, child: Text('\$${odds(l.odds)}', textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w800))),
                  const SizedBox(width: 10),
                  SizedBox(width: 76, child: Muted('fair \$${odds(l.fairOdds)}')),
                  SizedBox(
                    width: 52,
                    child: Text('${l.edge >= 0 ? '+' : ''}${(l.edge * 100).round()}%',
                        textAlign: TextAlign.right,
                        style: TextStyle(fontWeight: FontWeight.w800, color: l.edge >= 0.03 ? EdgeColors.win : (l.edge < 0 ? EdgeColors.coral : EdgeColors.muted))),
                  ),
                ]),
              ),
            if (priced.isNotEmpty && priced.first.edge >= 0.03)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Text('Best bet here: ${priced.first.leg.label} @ \$${odds(priced.first.odds)}', style: const TextStyle(color: EdgeColors.win, fontWeight: FontWeight.w800)),
              ),
            const SizedBox(height: 4),
            const Muted('Green = the bookie pays more than the model\'s fair price. Same-game multi prices are set in the bookie app.'),
          ]),
        ),
      ),
    );
  }

  /// Swap in live-adjusted player projections when the game is under way.
  void _applyLiveProps() {
    if (!game.isLive || _box == null || _preProps.isEmpty) return;
    final live = PlayerProps.liveAdjust(_preProps,
        tables: _box!.tables, homeTeamId: game.home.id, awayTeamId: game.away.id, remaining: game.remainingFraction());
    _pred = GamePrediction(
      game: game,
      muHome: _pred.muHome,
      muAway: _pred.muAway,
      sdHome: _pred.sdHome,
      sdAway: _pred.sdAway,
      rho: _pred.rho,
      confidence: _pred.confidence,
      homeGames: _pred.homeGames,
      awayGames: _pred.awayGames,
      players: live,
      baseHome: _pred.baseHome,
      baseAway: _pred.baseAway,
      live: _pred.live,
    );
  }

  Future<void> _refreshLive() async {
    try {
      final box = await EspnApi.summary(game.league, game.eventId, finished: game.isFinal, live: game.isLive);
      Game? fresh;
      if (game.isLive) fresh = await EspnApi.refreshGame(game);
      if (!mounted) return;
      setState(() {
        _box = box;
        if (fresh != null) {
          _game = fresh;
          final model = ModelStore.instance.cached(game.league);
          if (model != null) {
            _pred = Predictor(model).predict(fresh, sims: _pred.simCount);
          }
          if (!fresh.isLive) _liveTimer?.cancel();
        }
        _applyLiveProps();
        _cands = _pred.candidateLegs(minProb: 0.5);
        _multis = game.isFinal ? [] : _pred.suggestMultis(perSize: 2)
          ..sort((a, b) => b.prob.compareTo(a.prob));
        _boxError = null;
      });
    } catch (_) {
      if (mounted && _box == null) setState(() => _boxError = 'Box score not available yet. Pull down or come back shortly.');
    }
  }

  Future<void> _buildAi(int legs) async {
    _pendingBuild = null;
    setState(() => _aiBuilding = legs);
    await Future.delayed(const Duration(milliseconds: 50)); // let the spinner paint
    final m = _pred.buildMulti(legs);
    if (!mounted) return;
    setState(() {
      _aiMulti = m;
      _aiBuilding = null;
    });
  }

  double _roundHalf(double x) => (x * 2).round() / 2 == x ? x + 0.5 : ((x * 2).round() / 2).roundToDouble() + 0.5;

  void _recompute() {
    _pred.simCount = Settings.instance.sims;
    _cands = _pred.candidateLegs(minProb: 0.5);
    _multis = game.isFinal ? [] : _pred.suggestMultis(perSize: 2);
    _multis.sort((a, b) => b.prob.compareTo(a.prob));
    setState(() {});
  }

  Future<void> _loadPlayers() async {
    setState(() => _loadingPlayers = true);
    try {
      final model = await ModelStore.instance.model(game.league);
      try {
        final summary = await EspnApi.summary(game.league, game.eventId);
        _injuries = summary.injuries;
      } catch (_) {
        // Injury list is a nice-to-have; box scores still give us the props.
      }
      // Only games played before this one, so finished games are graded on
      // what the model would have said before tip-off.
      final home = await EspnApi.recentPlayerLines(game.league, model, game.home.id, before: game.date);
      final away = await EspnApi.recentPlayerLines(game.league, model, game.away.id, before: game.date);
      final players = PlayerProps.project(game: game, model: model, homeLines: home, awayLines: away, injuries: _injuries, maxPlayersPerTeam: 9);
      _preProps = players;
      _pred = GamePrediction(
        game: game,
        muHome: _pred.muHome,
        muAway: _pred.muAway,
        sdHome: _pred.sdHome,
        sdAway: _pred.sdAway,
        rho: _pred.rho,
        confidence: _pred.confidence,
        homeGames: _pred.homeGames,
        awayGames: _pred.awayGames,
        players: players,
        baseHome: _pred.baseHome,
        baseAway: _pred.baseAway,
        live: _pred.live,
      );
      _applyLiveProps();
      _playersNote = players.isEmpty ? 'No recent box scores found for player props.' : null;
      _recompute();
    } catch (_) {
      _playersNote = 'Player props unavailable right now.';
    }
    if (mounted) setState(() => _loadingPlayers = false);
    if (_pendingBuild != null) _buildAi(_pendingBuild!);
  }

  @override
  Widget build(BuildContext context) {
    final p = _pred;
    final selectedLegs = [..._cands.where((l) => _selected.contains(l.key)), ..._customLegs.values];
    final jointP = selectedLegs.isEmpty ? 0.0 : p.jointProb(selectedLegs);
    final settings = Settings.instance;
    return Scaffold(
      appBar: AppBar(title: Text('${game.away.abbr} @ ${game.home.abbr}')),
      body: ListView(padding: const EdgeInsets.fromLTRB(12, 0, 12, 32), children: [
        _header(p),
        if (game.isLive || game.isFinal) ...[
          SectionTitle(game.isLive ? 'Live box score' : 'Box score',
              trailing: game.isLive ? const Muted('updates every 30s') : null),
          _liveBox(p),
        ],
        if (!game.isFinal) ...[
          _bookieCard(p),
          const SizedBox(height: 10),
          _aiCard(),
          const SectionTitle('Best same-game multis'),
          if (_multis.isEmpty) const Muted('No confident combinations for this game.'),
          for (final m in _multis.take(6)) _multiCard(m),
          const SectionTitle('Over / under'),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Expanded(child: Text('Projected total ${num1(p.muTotal)} ${game.league.scoreWord}', style: const TextStyle(fontWeight: FontWeight.w700))),
                  if (game.marketTotal != null && settings.showMarket) Muted('Bookie ${num1(game.marketTotal!)}'),
                ]),
                const SizedBox(height: 10),
                Row(children: [
                  SizedBox(
                    width: 110,
                    child: TextField(
                      controller: _lineCtrl,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      decoration: const InputDecoration(labelText: 'Line', isDense: true, border: OutlineInputBorder()),
                      onChanged: (v) => setState(() => _line = double.tryParse(v)),
                    ),
                  ),
                  const SizedBox(width: 14),
                  if (_line != null) ...[
                    Expanded(child: _ouBox('Over', p.probOver(_line!))),
                    const SizedBox(width: 8),
                    Expanded(child: _ouBox('Under', p.probUnder(_line!))),
                  ],
                ]),
                const SizedBox(height: 10),
                Wrap(spacing: 6, runSpacing: 6, children: [
                  for (final l in _quickLines(p))
                    ActionChip(
                      label: Text('${num1(l)}: O ${pct(p.probOver(l))}'),
                      visualDensity: VisualDensity.compact,
                      onPressed: () => setState(() {
                        _line = l;
                        _lineCtrl.text = num1(l);
                      }),
                    ),
                ]),
                const SizedBox(height: 8),
                Muted('Team totals: ${game.home.abbr} ${num1(p.projHome)} ± ${num1(p.sdHome)} · ${game.away.abbr} ${num1(p.projAway)} ± ${num1(p.sdAway)}'),
              ]),
            ),
          ),
          if (game.league.hasPlayerProps) ...[
            SectionTitle('Player props', trailing: _loadingPlayers ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : null),
            if (_playersNote != null) Muted(_playersNote!),
            if (_loadingPlayers && p.players.isEmpty) const Muted('Reading recent box scores...'),
            _propsSection(p),
          ],
          const SectionTitle('Build your own multi'),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Wrap(spacing: 6, runSpacing: 6, children: [
                  for (final l in _cands.where((l) => l.type != LegType.playerProp))
                    FilterChip(
                      label: Text('${l.label} ${pct(l.prob)}'),
                      selected: _selected.contains(l.key),
                      visualDensity: VisualDensity.compact,
                      onSelected: (s) => setState(() => s ? _selected.add(l.key) : _selected.remove(l.key)),
                    ),
                ]),
                if (_cands.any((l) => l.type == LegType.playerProp)) ...[
                  const SizedBox(height: 8),
                  const Muted('Player legs'),
                  const SizedBox(height: 4),
                  Wrap(spacing: 6, runSpacing: 6, children: [
                    for (final l in _cands.where((l) => l.type == LegType.playerProp && l.over))
                      FilterChip(
                        label: Text('${l.label} ${pct(l.prob)}'),
                        selected: _selected.contains(l.key),
                        visualDensity: VisualDensity.compact,
                        onSelected: (s) => setState(() => s ? _selected.add(l.key) : _selected.remove(l.key)),
                      ),
                  ]),
                ],
                const Divider(height: 20),
                Row(children: [
                  Expanded(
                    child: selectedLegs.isEmpty
                        ? const Muted('Tap legs to combine them. Correlation between legs is handled by simulation.')
                        : Text('${selectedLegs.length} legs · ${pct(jointP)} · fair \$${odds(1 / (jointP > 0 ? jointP : 0.001))}',
                            style: const TextStyle(fontWeight: FontWeight.w800)),
                  ),
                  if (selectedLegs.isNotEmpty)
                    FilledButton.icon(
                      onPressed: () => _save(Multi(legs: selectedLegs, prob: jointP), 'Custom SGM'),
                      icon: const Icon(Icons.bookmark_add, size: 18),
                      label: const Text('Save'),
                    ),
                ]),
              ]),
            ),
          ),
        ],
        if (!game.isFinal) const SectionTitle('How sure is the model?'),
        if (!game.isFinal) InfoBanner(
          'Based on ${p.homeGames} ${game.home.abbr} and ${p.awayGames} ${game.away.abbr} rated games. '
          'Confidence ${pct(p.confidence)}. Scores are modelled as ${p.poisson ? 'Poisson goals' : 'correlated normal totals (ρ ${p.rho.toStringAsFixed(2)})'}.'
          '${_injuries.isNotEmpty ? '\nInjury list: ${_injuries.entries.take(6).map((e) => '${e.key} (${e.value})').join(', ')}' : ''}',
        ),
      ]),
    );
  }

  List<double> _quickLines(GamePrediction p) {
    final step = p.poisson ? 0.5 : (p.sdTotal / 3).clamp(1, 10).roundToDouble();
    final mid = _roundHalf(p.muTotal);
    return [for (var k = -2; k <= 2; k++) (mid + k * step).clamp(0.5, 9999).toDouble()];
  }

  Widget _ouBox(String label, double prob) => Container(
        padding: const EdgeInsets.symmetric(vertical: 8),
        decoration: BoxDecoration(color: probColor(prob).withValues(alpha: 0.12), borderRadius: BorderRadius.circular(10)),
        child: Column(children: [
          Text(label, style: const TextStyle(fontSize: 12, color: EdgeColors.muted)),
          Text(pct(prob), style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: probColor(prob))),
          Muted('\$${odds(1 / (prob > 0 ? prob : 0.001))}'),
        ]),
      );

  Widget _header(GamePrediction p) {
    Widget team(TeamRef t, double? score, double proj, double win) => Expanded(
          child: Column(children: [
            if (t.logo != null) Image.network(t.logo!, width: 44, height: 44, errorBuilder: (_, _, _) => const SizedBox(height: 44)),
            const SizedBox(height: 6),
            Text(t.name, textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w800)),
            if (t.record != null) Muted(t.record!),
            const SizedBox(height: 6),
            if (score != null && (game.isLive || game.isFinal))
              Text(num1(score), style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w900)),
            if (!game.isFinal) ...[
              Muted('Projected ${num1(proj)}'),
              const SizedBox(height: 4),
              ProbPill(win),
            ],
          ]),
        );
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(children: [
          Text(game.isLive ? 'LIVE · ${game.statusDetail}' : (game.isFinal ? 'Final' : timeLocal(game.date)),
              style: TextStyle(color: game.isLive ? EdgeColors.coral : EdgeColors.muted, fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            team(game.away, game.awayScore, p.projAway, p.probAwayWin),
            const Padding(padding: EdgeInsets.only(top: 40), child: Text('@', style: TextStyle(color: EdgeColors.muted, fontSize: 18))),
            team(game.home, game.homeScore, p.projHome, p.probHomeWin),
          ]),
          if (!game.isFinal && p.poisson && p.probDraw > 0.02) ...[
            const SizedBox(height: 6),
            Muted('Draw ${pct(p.probDraw)}'),
          ],
          if (game.isFinal) _finalVsModel(),
          if (!game.isFinal) ...[
            const SizedBox(height: 8),
            Text('Model line: ${p.muMargin >= 0 ? game.home.abbr : game.away.abbr} by ${num1(p.muMargin.abs())}'
                '${game.marketSpread != null && Settings.instance.showMarket ? ' · bookie ${game.home.abbr} ${game.marketSpread! > 0 ? '+' : ''}${num1(game.marketSpread!)}' : ''}'),
          ],
        ]),
      ),
    );
  }

  /// Finished game: what the model projected before tip-off vs the result.
  Widget _finalVsModel() {
    final model = ModelStore.instance.cached(game.league);
    final hs = game.homeScore, as = game.awayScore;
    if (model == null || hs == null || as == null) return const SizedBox();
    final (muH, muA) = model.expected(game.home.id, game.away.id, neutral: game.neutral);
    final total = hs + as;
    final projTotal = muH + muA;
    final winnerRight = (muH - muA) * (hs - as) > 0;
    final totalSide = total > projTotal ? 'Over' : 'Under';
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Column(children: [
        Text('Pre-game model: ${game.away.abbr} ${num1(muA)} - ${game.home.abbr} ${num1(muH)} · total ${num1(projTotal)}',
            style: const TextStyle(fontSize: 13, color: EdgeColors.muted)),
        const SizedBox(height: 4),
        Wrap(spacing: 8, children: [
          _verdict(winnerRight ? 'Winner ✓' : 'Winner ✗', winnerRight),
          _verdict('Total $total · $totalSide the ${num1(projTotal)} projection', true, neutral: true),
          if (game.marketTotal != null && Settings.instance.showMarket)
            _verdict('Bookie ${num1(game.marketTotal!)} · ${total > game.marketTotal! ? 'Over' : (total < game.marketTotal! ? 'Under' : 'Push')}', true, neutral: true),
        ]),
      ]),
    );
  }

  Widget _verdict(String text, bool good, {bool neutral = false}) {
    final c = neutral ? EdgeColors.sky : (good ? EdgeColors.win : EdgeColors.loss);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(color: c.withValues(alpha: 0.15), borderRadius: BorderRadius.circular(8)),
      child: Text(text, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: c)),
    );
  }

  Widget _multiCard(Multi m) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Card(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 8, 8),
            child: Row(children: [
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  for (final l in m.legs) LegRow(l.label, l.prob),
                  Muted('${m.legs.length} legs · fair \$${odds(m.fairOdds)}'),
                ]),
              ),
              Column(children: [
                ProbPill(m.prob, big: true),
                IconButton(
                  icon: const Icon(Icons.bookmark_add_outlined),
                  tooltip: 'Save',
                  onPressed: () => _save(m, 'Same-game multi'),
                ),
              ]),
            ]),
          ),
        ),
      );

  Widget _liveBox(GamePrediction p) {
    final box = _box;
    if (box == null) {
      return Padding(
        padding: const EdgeInsets.all(8),
        child: _boxError != null
            ? Row(children: [
                Expanded(child: Muted(_boxError!)),
                TextButton(onPressed: _refreshLive, child: const Text('Retry')),
              ])
            : const Muted('Loading box score...'),
      );
    }
    final teams = [game.away, game.home];
    final team = teams[_boxTeam];
    final tables = box.tables.where((t) => t.teamId == team.id).toList();
    final remaining = game.remainingFraction();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        for (var i = 0; i < 2; i++)
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: ChoiceChip(
              label: Text(teams[i].abbr),
              selected: _boxTeam == i,
              visualDensity: VisualDensity.compact,
              onSelected: (_) => setState(() => _boxTeam = i),
            ),
          ),
        const Spacer(),
        if (game.isLive && tables.any((t) => t.rows.any((r) => r.onField)))
          const Row(children: [Icon(Icons.circle, size: 8, color: EdgeColors.win), SizedBox(width: 4), Muted('on court / pitch')]),
      ]),
      const SizedBox(height: 8),
      if (tables.isEmpty) const Muted('No player box score for this competition yet.'),
      if (game.league.hasPlayerProps && tables.isNotEmpty && _preProps.isNotEmpty)
        _basketballLive(p, team, tables.first, remaining)
      else
        for (final t in tables) _genericTable(t),
    ]);
  }

  double _currentStat(BoxRow r, BoxTable t, String stat) => switch (stat) {
        'PTS' => r.statNum(t.columns, 'PTS'),
        'REB' => r.statNum(t.columns, 'REB'),
        'AST' => r.statNum(t.columns, 'AST'),
        '3PM' => r.statNum(t.columns, '3PT'),
        _ => r.statNum(t.columns, 'PTS') + r.statNum(t.columns, 'REB') + r.statNum(t.columns, 'AST'),
      };

  /// Basketball: each player's live numbers against their expected line,
  /// or on finished games whether they reached it.
  Widget _basketballLive(GamePrediction p, TeamRef team, BoxTable t, double remaining) {
    final side = team.id == game.home.id ? Side.home : Side.away;
    final rows = t.rows.where((r) => !r.dnp).toList()
      ..sort((a, b) {
        final c = (b.onField ? 1 : 0).compareTo(a.onField ? 1 : 0);
        return c != 0 ? c : b.statNum(t.columns, 'MIN').compareTo(a.statNum(t.columns, 'MIN'));
      });
    var hits = 0, graded = 0;
    if (game.isFinal) {
      for (final r in rows) {
        for (final pr in _preProps.where((x) => x.name == r.name && x.side == side)) {
          graded++;
          if (_currentStat(r, t, pr.stat) >= pr.line) hits++;
        }
      }
    }
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 6, 12, 6),
        child: Column(children: [
          if (game.isFinal && graded > 0)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(children: [
                Expanded(child: Text('Model lines hit: $hits of $graded', style: const TextStyle(fontWeight: FontWeight.w800))),
                ProbPill(hits / graded),
              ]),
            ),
          for (final r in rows)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  if (r.onField) const Padding(padding: EdgeInsets.only(right: 6), child: Icon(Icons.circle, size: 8, color: EdgeColors.win)),
                  Expanded(child: Text(r.name, style: const TextStyle(fontWeight: FontWeight.w700))),
                  Muted('${r.stat(t.columns, 'MIN')} min · ${r.stat(t.columns, 'PTS')} pts · ${r.stat(t.columns, 'REB')} reb · ${r.stat(t.columns, 'AST')} ast'),
                ]),
                Builder(builder: (_) {
                  final projs = _preProps.where((x) => x.name == r.name && x.side == side).toList();
                  if (projs.isEmpty) return const SizedBox();
                  final played = r.statNum(t.columns, 'MIN') > 0;
                  return Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Wrap(spacing: 6, runSpacing: 4, children: [
                      for (final pr in projs)
                        Builder(builder: (_) {
                          final cur = _currentStat(r, t, pr.stat);
                          final line = pr.line;
                          if (game.isFinal) {
                            final hit = cur >= line;
                            final c = hit ? EdgeColors.win : EdgeColors.loss;
                            return Container(
                              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                              decoration: BoxDecoration(color: c.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(8)),
                              child: Text('${pr.stat} ${num1(cur)} vs ${num1(line)} ${hit ? '✓' : '✗'}',
                                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: c)),
                            );
                          }
                          final prob = GamePrediction.liveReachProb(pr, cur, line, remaining, played: played);
                          final c = probColor(prob);
                          return Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                            decoration: BoxDecoration(color: c.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(8)),
                            child: Text('${pr.stat} ${num1(cur)} → ${num1(line)}  ${pct(prob)}',
                                style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: c)),
                          );
                        }),
                    ]),
                  );
                }),
              ]),
            ),
          Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Muted(game.isFinal
                  ? 'actual vs the line the model had before the game (✓ reached it)'
                  : 'now → expected line, with the live chance of getting there')),
        ]),
      ),
    );
  }

  Widget _genericTable(BoxTable t) {
    final cols = t.columns.take(8).toList();
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: DataTable(
              headingRowHeight: 32,
              dataRowMinHeight: 30,
              dataRowMaxHeight: 34,
              columnSpacing: 14,
              horizontalMargin: 4,
              headingTextStyle: const TextStyle(color: EdgeColors.muted, fontSize: 11, fontWeight: FontWeight.w700),
              dataTextStyle: const TextStyle(fontSize: 12.5),
              columns: [
                DataColumn(label: Text(t.title)),
                for (final c in cols) DataColumn(label: Text(c)),
              ],
              rows: [
                for (final r in t.rows.where((r) => !r.dnp || !game.isLive))
                  DataRow(cells: [
                    DataCell(Row(children: [
                      if (r.onField) const Padding(padding: EdgeInsets.only(right: 4), child: Icon(Icons.circle, size: 7, color: EdgeColors.win)),
                      Text(r.position.isNotEmpty ? '${r.name} (${r.position})' : r.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                    ])),
                    for (var i = 0; i < cols.length; i++) DataCell(Text(i < r.stats.length ? r.stats[i] : '')),
                  ]),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _aiCard() {
    final m = _aiMulti;
    final loadingProps = _loadingPlayers && game.league.hasPlayerProps;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.smart_toy_outlined, color: EdgeColors.lime, size: 20),
            const SizedBox(width: 8),
            const Expanded(child: Text('AI same-game multi', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
            if (_aiBuilding != null || (loadingProps && _pendingBuild != null))
              const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
          ]),
          const SizedBox(height: 4),
          const Muted('Builds the multi with the best chance of landing from player props, totals, winner and team totals. Pick how many legs.'),
          const SizedBox(height: 8),
          Row(children: [
            for (final n in [3, 5, 10])
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: FilledButton.tonal(
                  style: FilledButton.styleFrom(
                    backgroundColor: m != null && m.legs.length == n ? EdgeColors.lime : EdgeColors.lime.withValues(alpha: 0.15),
                    foregroundColor: m != null && m.legs.length == n ? EdgeColors.navy : EdgeColors.lime,
                  ),
                  onPressed: _aiBuilding != null ? null : () => loadingProps ? setState(() => _pendingBuild = n) : _buildAi(n),
                  child: Text('$n legs'),
                ),
              ),
          ]),
          if (loadingProps && _pendingBuild != null) const Padding(padding: EdgeInsets.only(top: 8), child: Muted('Waiting for player box scores...')),
          if (m != null) ...[
            const Divider(height: 20),
            Row(children: [
              Expanded(child: Text(m.title, style: const TextStyle(fontWeight: FontWeight.w800))),
              ProbPill(m.prob, big: true),
            ]),
            const SizedBox(height: 6),
            for (final l in m.legs) LegRow(l.label, l.prob),
            const SizedBox(height: 6),
            Row(children: [
              Expanded(child: Muted('Fair odds \$${odds(m.fairOdds)} · only bet it if your bookie pays more.')),
              FilledButton.icon(
                onPressed: () => _save(m, m.title),
                icon: const Icon(Icons.bookmark_add, size: 18),
                label: const Text('Save'),
              ),
            ]),
            if (m.legs.length < (_aiBuilding ?? m.legs.length) || m.legs.length < 3)
              const Padding(padding: EdgeInsets.only(top: 6), child: Muted('Fewer legs than asked: not enough confident selections in this game.')),
          ],
        ]),
      ),
    );
  }

  static const _statLabels = {'PTS': 'Points', 'REB': 'Rebounds', 'AST': 'Assists', '3PM': 'Threes', 'PRA': 'Pts+Reb+Ast'};

  Widget _propsSection(GamePrediction p) {
    if (p.players.isEmpty) return const SizedBox();
    final rows = p.players.where((x) => x.stat == _stat).toList()..sort((a, b) => b.mean.compareTo(a.mean));
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SizedBox(
        height: 40,
        child: ListView(scrollDirection: Axis.horizontal, children: [
          for (final st in PlayerProps.stats)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(_statLabels[st] ?? st),
                selected: st == _stat,
                visualDensity: VisualDensity.compact,
                onSelected: (_) => setState(() => _stat = st),
              ),
            ),
        ]),
      ),
      const SizedBox(height: 4),
      const Muted('Tap a line to add it to your multi. Lines: likely · even · stretch.'),
      const SizedBox(height: 6),
      Card(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 6, 12, 6),
          child: Column(children: [
            if (rows.isEmpty) const Padding(padding: EdgeInsets.all(8), child: Muted('No players with enough minutes for this stat.')),
            for (final r in rows) _propRow(p, r),
          ]),
        ),
      ),
    ]);
  }

  Widget _propRow(GamePrediction p, PlayerProjection r) {
    final side = r.side == Side.home ? game.home.abbr : game.away.abbr;
    final last = r.lastValues.map((v) => v.toStringAsFixed(0)).join(' ');
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text('${r.name} · $side', style: const TextStyle(fontWeight: FontWeight.w700))),
          Muted('avg ${r.mean.toStringAsFixed(1)} · ${r.minutes.round()} min'),
        ]),
        if (r.flag != null) Text(r.flag!, style: const TextStyle(color: EdgeColors.coral, fontSize: 12)),
        if (last.isNotEmpty) Muted('Last games: $last'),
        const SizedBox(height: 4),
        Row(children: [
          for (final line in {r.overLine, r.line, r.stretchLine})
            Padding(
              padding: const EdgeInsets.only(right: 6),
              child: _linePill(p, r, line),
            ),
        ]),
      ]),
    );
  }

  Widget _linePill(GamePrediction p, PlayerProjection r, double line) {
    final prob = p.probPlayerOver(r, line);
    final key = 'custom|${r.name}|${r.stat}|$line';
    final selected = _customLegs.containsKey(key);
    final c = probColor(prob);
    return InkWell(
      borderRadius: BorderRadius.circular(10),
      onTap: () => setState(() {
        if (selected) {
          _customLegs.remove(key);
        } else {
          _customLegs[key] = Leg(
            type: LegType.playerProp,
            player: r,
            over: true,
            line: line,
            label: '${r.shortName} $line+ ${r.stat}',
            prob: prob,
          );
        }
      }),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: selected ? EdgeColors.lime : c.withValues(alpha: 0.14),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: selected ? EdgeColors.lime : Colors.transparent),
        ),
        child: Text('${num1(line)}+  ${pct(prob)}',
            style: TextStyle(fontWeight: FontWeight.w800, fontSize: 13, color: selected ? EdgeColors.navy : c)),
      ),
    );
  }

  Future<void> _save(Multi m, String kind) async {
    await PicksStore.instance.add(Pick(
      id: '${DateTime.now().microsecondsSinceEpoch}',
      kind: 'sport',
      createdAt: DateTime.now(),
      title: '${game.away.abbr} @ ${game.home.abbr}: ${m.legs.map((l) => l.label).join(' + ')}',
      detail: '$kind · ${game.league.name} · ${timeLocal(game.date)}',
      prob: m.prob,
      leagueId: game.league.id,
      eventId: game.eventId,
      eventDate: game.date,
      legs: m.legs,
    ));
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Saved to My picks')));
  }
}
