import 'dart:async';

import 'package:flutter/material.dart';

import '../engine/bookie.dart';
import '../engine/predictor.dart';
import '../engine/ratings.dart';
import '../models/game.dart';
import '../models/league.dart';
import '../services/espn_api.dart';
import '../services/model_store.dart';
import '../services/odds_api.dart';
import '../services/picks_store.dart';
import '../services/prediction_log.dart';
import '../services/settings_service.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'game_screen.dart';
import 'settings_screen.dart';

/// Sports tab: pick a league and a day, see live scores, the model's
/// projections, the best same-game multi per game and the best round multi.
class SportsScreen extends StatefulWidget {
  const SportsScreen({super.key});

  @override
  State<SportsScreen> createState() => _SportsScreenState();
}

class _SportsScreenState extends State<SportsScreen> {
  League _league = Leagues.nba;
  int _dayOffset = 0;
  List<Game> _games = [];
  final Map<String, GamePrediction> _preds = {};
  final Map<String, Multi?> _bestSgm = {};
  List<Multi> _roundMultis = [];
  List<OddsEvent> _odds = [];
  String? _oddsError;
  bool _loadingGames = false;
  bool _building = false;
  String? _error;
  Timer? _timer;
  int _loadToken = 0;

  DateTime get _day => DateTime.now().add(Duration(days: _dayOffset));

  @override
  void initState() {
    super.initState();
    final fav = Settings.instance.favourites;
    if (fav.isNotEmpty) _league = Leagues.byId(fav.first) ?? Leagues.nba;
    ModelStore.instance.addListener(_onStore);
    Settings.instance.addListener(_onStore);
    OddsApi.instance.addListener(_onStore);
    _load();
    _timer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (_games.any((g) => g.isLive) || _dayOffset == 0) _refreshScores();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    ModelStore.instance.removeListener(_onStore);
    Settings.instance.removeListener(_onStore);
    OddsApi.instance.removeListener(_onStore);
    super.dispose();
  }

  void _onStore() {
    if (mounted) setState(() {});
  }

  Future<void> _load() async {
    final token = ++_loadToken;
    setState(() {
      _loadingGames = true;
      _error = null;
      _games = [];
      _preds.clear();
      _bestSgm.clear();
      _roundMultis = [];
      _odds = [];
      _oddsError = null;
    });
    _loadOdds(token);
    try {
      final games = await EspnApi.scoreboard(_league, _day);
      if (token != _loadToken) return;
      setState(() {
        _games = games;
        _loadingGames = false;
      });
      await _predictAll(token);
    } catch (e) {
      if (token != _loadToken) return;
      setState(() {
        _loadingGames = false;
        _error = 'Could not load games. Check your connection.';
      });
    }
  }

  Future<void> _loadOdds(int token) async {
    if (!OddsApi.instance.enabled || _league.oddsKey == null) return;
    try {
      final ev = await OddsApi.instance.events(_league);
      if (token != _loadToken || !mounted) return;
      setState(() => _odds = ev);
    } catch (e) {
      if (token != _loadToken || !mounted) return;
      setState(() => _oddsError = OddsApi.instance.lastError ?? 'Could not load bookmaker odds.');
    }
  }

  BookiePrices? _bookFor(Game g) => OddsApi.match(_odds, g)?.book(Settings.instance.bookie);

  List<PricedLeg> _pricedFor(Game g) {
    final p = _preds[g.eventId];
    final b = _bookFor(g);
    if (p == null || b == null || g.isLive || g.isFinal) return [];
    return BookieModel.priceLegs(p, b);
  }

  Future<void> _refreshScores() async {
    final token = _loadToken;
    try {
      final games = await EspnApi.scoreboard(_league, _day);
      if (token != _loadToken || !mounted) return;
      final model = ModelStore.instance.cached(_league);
      setState(() {
        _games = games;
        if (model != null) {
          for (final g in games) {
            if (g.isLive || g.isFinal) _preds[g.eventId] = Predictor(model).predict(g, sims: 4000);
          }
        }
      });
    } catch (_) {}
  }

  Future<void> _predictAll(int token) async {
    if (_games.isEmpty) return;
    setState(() => _building = true);
    LeagueModel model;
    try {
      model = await ModelStore.instance.model(_league);
    } catch (_) {
      if (token == _loadToken && mounted) {
        setState(() {
          _building = false;
          _error = 'Could not build ratings for ${_league.name}.';
        });
      }
      return;
    }
    if (token != _loadToken || !mounted) return;
    final predictor = Predictor(model);
    for (final g in _games) {
      if (token != _loadToken || !mounted) return;
      final p = predictor.predict(g, sims: 6000);
      _preds[g.eventId] = p;
      PredictionLog.instance.recordGame(p, appliedBias: model.totalBias);
      if (!g.isFinal && !g.isLive) {
        final multis = p.suggestMultis(perSize: 1);
        multis.sort((a, b) => _score(b).compareTo(_score(a)));
        _bestSgm[g.eventId] = multis.isEmpty ? null : multis.first;
      }
      setState(() {});
      await Future.delayed(Duration.zero);
    }
    if (token != _loadToken || !mounted) return;
    setState(() {
      _roundMultis = RoundMulti.build(_preds.values.toList());
      _building = false;
    });
    PredictionLog.instance.save();
  }

  /// Favour multis that pay around $2-$4 but still carry a real chance.
  double _score(Multi m) => m.prob * (m.legs.length >= 3 ? 1.15 : 1.0) * (m.prob > 0.25 ? 1 : 0.5);

  @override
  Widget build(BuildContext context) {
    final settings = Settings.instance;
    final favs = settings.favourites.map(Leagues.byId).whereType<League>().toList();
    if (!favs.contains(_league)) favs.insert(0, _league);
    final progress = ModelStore.instance.progress[_league.id];
    return Column(children: [
      SizedBox(
        height: 48,
        child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12), children: [
          for (final l in favs)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(l.name.split(' (').first),
                selected: l == _league,
                onSelected: (_) {
                  setState(() => _league = l);
                  _load();
                },
              ),
            ),
          ActionChip(
            avatar: const Icon(Icons.add, size: 16),
            label: const Text('More'),
            onPressed: _pickLeague,
          ),
        ]),
      ),
      SizedBox(
        height: 40,
        child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12), children: [
          for (final off in [-1, 0, 1, 2, 3])
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(dayLabel(DateTime.now().add(Duration(days: off)))),
                selected: off == _dayOffset,
                visualDensity: VisualDensity.compact,
                onSelected: (_) {
                  setState(() => _dayOffset = off);
                  _load();
                },
              ),
            ),
        ]),
      ),
      if (OddsApi.instance.enabled && _league.oddsKey != null && _odds.isNotEmpty)
        SizedBox(
          height: 40,
          child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12), children: [
            for (final b in OddsApi.instance.bookmakers.where((b) => _odds.any((e) => e.books.containsKey(b.key))))
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ChoiceChip(
                  avatar: b.key == settings.bookie ? null : const Icon(Icons.storefront_outlined, size: 14),
                  label: Text(b.value),
                  selected: b.key == settings.bookie,
                  visualDensity: VisualDensity.compact,
                  onSelected: (_) {
                    settings.bookie = b.key;
                    settings.save();
                  },
                ),
              ),
          ]),
        ),
      if (progress != null)
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
          child: Column(children: [
            LinearProgressIndicator(value: progress.$2 == 0 ? null : progress.$1 / progress.$2, color: EdgeColors.lime, backgroundColor: Colors.white12),
            const SizedBox(height: 4),
            Muted('Crunching ${_league.name} results (${progress.$1}/${progress.$2})...'),
          ]),
        ),
      Expanded(child: _body()),
    ]);
  }

  Widget _body() {
    if (_error != null) return ErrorRetry(_error!, onRetry: _load);
    if (_loadingGames) return const Center(child: CircularProgressIndicator());
    if (_games.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text('No ${_league.name} games ${dayLabel(_day).toLowerCase()}.', style: const TextStyle(color: EdgeColors.muted)),
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.fromLTRB(12, 4, 12, 24), children: [
        if (!OddsApi.instance.enabled && _league.oddsKey != null) _oddsBanner(),
        if (_oddsError != null) Padding(padding: const EdgeInsets.only(bottom: 8), child: InfoBanner(_oddsError!, icon: Icons.warning_amber_outlined)),
        if (_odds.isNotEmpty) ...[_bookieCard(), const SizedBox(height: 10)],
        if (_roundMultis.isNotEmpty) _roundCard(),
        if (_building && _roundMultis.isEmpty)
          const Padding(padding: EdgeInsets.all(8), child: Muted('Building best multi for this round...')),
        for (final g in _games) _gameCard(g),
        const SizedBox(height: 8),
        const InfoBanner('Predictions come from team scoring ratings fitted to recent results (opponent-adjusted, recency-weighted). Bookmaker lines are shown for comparison only and never feed the model.'),
      ]),
    );
  }

  Widget _oddsBanner() {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Card(
        child: ListTile(
          leading: const Icon(Icons.storefront_outlined, color: EdgeColors.lime),
          title: const Text('Compare Sportsbet, TAB, Ladbrokes, Neds and more', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
          subtitle: const Muted('Add a free Odds API key in Settings and the app will show each bookie\'s prices against the model and the best multi to take with them.'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SettingsScreen())),
        ),
      ),
    );
  }

  Widget _bookieCard() {
    final bookieName = OddsApi.instance.bookTitles[Settings.instance.bookie] ?? Settings.instance.bookie;
    final perGame = [for (final g in _games) _pricedFor(g)].where((l) => l.isNotEmpty).toList();
    final multis = BookieModel.bestMultis(perGame);
    final valueLegs = perGame.expand((l) => l).where((l) => l.edge >= 0.03).toList()..sort((x, y) => y.edge.compareTo(x.edge));
    if (perGame.isEmpty) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Muted(_preds.isEmpty ? 'Pricing $bookieName odds against the model...' : '$bookieName has no prices up yet for these games.'),
        ),
      );
    }
    final BookieMulti? m = multis.isEmpty ? null : multis.firstWhere((x) => x.legs.length == 3, orElse: () => multis.last);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.storefront, color: EdgeColors.lime, size: 20),
            const SizedBox(width: 8),
            Expanded(child: Text(m == null ? 'Value at $bookieName' : 'Best multi to take at $bookieName', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
            if (m != null) Text('\$${odds(m.odds)}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18, color: EdgeColors.lime)),
          ]),
          if (m != null) ...[
            const SizedBox(height: 8),
            for (final l in m.legs) _pricedRow(l),
            const SizedBox(height: 6),
            Text('Model chance ${pct(m.prob)} · fair \$${odds(1 / (m.prob > 0 ? m.prob : 0.001))} · value ${m.ev >= 1 ? '+' : ''}${((m.ev - 1) * 100).round()}%',
                style: TextStyle(fontWeight: FontWeight.w700, color: m.ev >= 1 ? EdgeColors.win : EdgeColors.coral)),
            const SizedBox(height: 6),
            Wrap(spacing: 6, children: [
              for (final x in multis)
                ActionChip(
                  label: Text('${x.legs.length} legs \$${odds(x.odds)} · ${pct(x.prob)}'),
                  visualDensity: VisualDensity.compact,
                  onPressed: () => _showBookieMulti(x, bookieName),
                ),
            ]),
          ] else
            const Padding(padding: EdgeInsets.only(top: 6), child: Muted('Only one game priced, so no multi yet.')),
          if (valueLegs.isNotEmpty) ...[
            const Divider(height: 18),
            Text('Value singles at $bookieName', style: const TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            for (final l in valueLegs.take(4)) _pricedRow(l),
          ],
          const SizedBox(height: 4),
          const Muted('Same-game multi prices are set inside the bookie app; these are their single-leg prices, combined as a normal multi.'),
        ]),
      ),
    );
  }

  Widget _pricedRow(PricedLeg l) {
    final g = l.game.game;
    final good = l.edge >= 0.03;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(children: [
        Icon(good ? Icons.trending_up : Icons.remove, size: 16, color: good ? EdgeColors.win : EdgeColors.muted),
        const SizedBox(width: 8),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(l.leg.label, style: const TextStyle(fontWeight: FontWeight.w600)),
            Muted('${g.away.abbr} @ ${g.home.abbr} · model ${pct(l.leg.prob)} · fair \$${odds(l.fairOdds)}'),
          ]),
        ),
        Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
          Text('\$${odds(l.odds)}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)),
          Text('${l.edge >= 0 ? '+' : ''}${(l.edge * 100).round()}%', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: good ? EdgeColors.win : EdgeColors.muted)),
        ]),
      ]),
    );
  }

  void _showBookieMulti(BookieMulti m, String bookieName) {
    showModalBottomSheet(
      context: context,
      backgroundColor: EdgeColors.navyCard,
      builder: (_) => Padding(
        padding: const EdgeInsets.all(20),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: Text('${m.legs.length}-leg multi at $bookieName', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16))),
            Text('\$${odds(m.odds)}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18, color: EdgeColors.lime)),
          ]),
          const SizedBox(height: 8),
          for (final l in m.legs) _pricedRow(l),
          const SizedBox(height: 8),
          Muted('Model chance ${pct(m.prob)} · value ${m.ev >= 1 ? '+' : ''}${((m.ev - 1) * 100).round()}%'),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: () async {
              Navigator.pop(context);
              for (final l in m.legs) {
                await PicksStore.instance.add(Pick(
                  id: '${DateTime.now().microsecondsSinceEpoch}-${l.leg.key.hashCode}',
                  kind: 'sport',
                  createdAt: DateTime.now(),
                  title: '${l.game.game.away.abbr} @ ${l.game.game.home.abbr}: ${l.leg.label}',
                  detail: '$bookieName multi leg · ${_league.name}',
                  prob: l.leg.prob,
                  leagueId: _league.id,
                  eventId: l.game.game.eventId,
                  eventDate: l.game.game.date,
                  legs: [l.leg],
                  bookOdds: l.odds,
                ));
              }
              if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${m.legs.length} legs saved to My picks')));
            },
            icon: const Icon(Icons.bookmark_add),
            label: const Text('Save to my picks'),
          ),
        ]),
      ),
    );
  }

  Widget _roundCard() {
    // Show the 3-leg if available, else the biggest we have.
    final m = _roundMultis.firstWhere((x) => x.legs.length == 3, orElse: () => _roundMultis.last);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.auto_awesome, color: EdgeColors.lime, size: 20),
            const SizedBox(width: 8),
            Expanded(child: Text('Best multi for this ${_league.name} round', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15))),
            ProbPill(m.prob, big: true),
          ]),
          const SizedBox(height: 8),
          for (final l in m.legs) LegRow(l.label, l.prob, sub: _gameOfLeg(l)),
          const SizedBox(height: 6),
          Row(children: [
            Expanded(child: Muted('Fair odds \$${odds(m.fairOdds)} — only worth it if your bookie pays more.')),
            TextButton.icon(
              onPressed: () => _savePick(m, '${_league.name} round multi'),
              icon: const Icon(Icons.bookmark_add_outlined, size: 18),
              label: const Text('Save'),
            ),
          ]),
          Wrap(spacing: 6, children: [
            for (final x in _roundMultis)
              ActionChip(
                label: Text('${x.legs.length} legs ${pct(x.prob)} · \$${odds(x.fairOdds)}'),
                visualDensity: VisualDensity.compact,
                onPressed: () => _showMulti(x, '${x.legs.length}-leg ${_league.name} round multi'),
              ),
          ]),
        ]),
      ),
    );
  }

  String? _gameOfLeg(Leg l) {
    for (final p in _preds.values) {
      if (p.candidateLegs(minProb: 0.55).any((c) => c.key == l.key && c.label == l.label)) {
        return '${p.game.away.abbr} @ ${p.game.home.abbr} · ${timeLocal(p.game.date)}';
      }
    }
    return null;
  }

  void _showMulti(Multi m, String title) {
    showModalBottomSheet(
      context: context,
      backgroundColor: EdgeColors.navyCard,
      builder: (_) => Padding(
        padding: const EdgeInsets.all(20),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16))), ProbPill(m.prob, big: true)]),
          const SizedBox(height: 8),
          for (final l in m.legs) LegRow(l.label, l.prob, sub: _gameOfLeg(l)),
          const SizedBox(height: 8),
          Muted('Fair odds \$${odds(m.fairOdds)}'),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: () {
              Navigator.pop(context);
              _savePick(m, title);
            },
            icon: const Icon(Icons.bookmark_add),
            label: const Text('Save to my picks'),
          ),
        ]),
      ),
    );
  }

  Future<void> _savePick(Multi m, String title) async {
    // A round multi spans games: save one pick per leg so each grades itself.
    for (final l in m.legs) {
      GamePrediction? owner;
      for (final p in _preds.values) {
        if (p.candidateLegs(minProb: 0.55).any((c) => c.key == l.key)) {
          owner = p;
          break;
        }
      }
      if (owner == null) continue;
      await PicksStore.instance.add(Pick(
        id: '${DateTime.now().microsecondsSinceEpoch}-${l.key.hashCode}',
        kind: 'sport',
        createdAt: DateTime.now(),
        title: '${owner.game.away.abbr} @ ${owner.game.home.abbr}: ${l.label}',
        detail: '$title leg · ${_league.name}',
        prob: l.prob,
        leagueId: _league.id,
        eventId: owner.game.eventId,
        eventDate: owner.game.date,
        legs: [l],
      ));
    }
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${m.legs.length} legs saved to My picks')));
  }

  Widget _gameCard(Game g) {
    final p = _preds[g.eventId];
    final sgm = _bestSgm[g.eventId];
    final settings = Settings.instance;
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Card(
        child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: p == null ? null : () => Navigator.push(context, MaterialPageRoute(builder: (_) => GameScreen(game: g, prediction: p))),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                _statusChip(g),
                const Spacer(),
                if (p != null && !g.isFinal) Muted('Model ${num1(p.projAway)} - ${num1(p.projHome)}'),
              ]),
              const SizedBox(height: 8),
              _teamRow(g.away, g.awayScore, g, p?.probAwayWin),
              const SizedBox(height: 4),
              _teamRow(g.home, g.homeScore, g, p?.probHomeWin),
              if (p != null && !g.isFinal) ...[
                const Divider(height: 18),
                Row(children: [
                  const Icon(Icons.swap_vert, size: 16, color: EdgeColors.sky),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      g.marketTotal != null && settings.showMarket
                          ? 'Total ${num1(p.muTotal)} projected · bookie ${num1(g.marketTotal!)} → ${p.totalLean(g.marketTotal!)}'
                          : 'Total ${num1(p.muTotal)} projected (${_league.scoreWord})',
                      style: const TextStyle(fontSize: 13),
                    ),
                  ),
                ]),
                if (g.isLive)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Muted('Live projection: ${num1(p.projAway)} - ${num1(p.projHome)} final'),
                  ),
                ..._bookieLines(g),
                if (!g.isLive)
                  Align(
                    alignment: Alignment.centerRight,
                    child: TextButton.icon(
                      style: TextButton.styleFrom(foregroundColor: EdgeColors.lime, visualDensity: VisualDensity.compact),
                      onPressed: () => _askAiLegs(g, p),
                      icon: const Icon(Icons.smart_toy_outlined, size: 18),
                      label: const Text('AI multi'),
                    ),
                  ),
                if (sgm != null) ...[
                  Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    const Icon(Icons.auto_awesome, size: 16, color: EdgeColors.lime),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text('Best SGM: ${sgm.legs.map((l) => l.label).join(' + ')}',
                          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                    ),
                    const SizedBox(width: 6),
                    Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
                      ProbPill(sgm.prob),
                      Muted('\$${odds(sgm.fairOdds)}'),
                    ]),
                  ]),
                ],
              ],
              if (p == null && _building)
                const Padding(padding: EdgeInsets.only(top: 8), child: Muted('Rating...')),
            ]),
          ),
        ),
      ),
    );
  }

  List<Widget> _bookieLines(Game g) {
    final b = _bookFor(g);
    if (b == null) return [];
    final name = OddsApi.instance.bookTitles[Settings.instance.bookie] ?? Settings.instance.bookie;
    final priced = _pricedFor(g);
    final parts = <String>[];
    if (b.homeWin != null && b.awayWin != null) parts.add('${g.home.abbr} \$${odds(b.homeWin!)} · ${g.away.abbr} \$${odds(b.awayWin!)}');
    if (b.totalPoint != null && b.over != null) parts.add('O/U ${num1(b.totalPoint!)} \$${odds(b.over!)}/\$${odds(b.under ?? b.over!)}');
    final best = priced.isEmpty ? null : priced.first;
    return [
      const SizedBox(height: 6),
      Row(children: [
        const Icon(Icons.storefront_outlined, size: 16, color: EdgeColors.muted),
        const SizedBox(width: 6),
        Expanded(child: Text('$name: ${parts.join(' · ')}', style: const TextStyle(fontSize: 12.5, color: EdgeColors.muted))),
      ]),
      if (best != null && best.edge >= 0.03)
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text('Best bet at $name: ${best.leg.label} @ \$${odds(best.odds)} · model ${pct(best.leg.prob)} · +${(best.edge * 100).round()}% value',
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: EdgeColors.win)),
        ),
    ];
  }

  Future<void> _askAiLegs(Game g, GamePrediction p) async {
    final n = await showModalBottomSheet<int>(
      context: context,
      backgroundColor: EdgeColors.navyCard,
      builder: (_) => Padding(
        padding: const EdgeInsets.all(20),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Build a same-game multi for ${g.away.abbr} @ ${g.home.abbr}', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
          const SizedBox(height: 4),
          const Muted('Best chance of landing, using player props, totals, winner and team totals. How many legs?'),
          const SizedBox(height: 14),
          Row(children: [
            for (final n in [3, 5, 10])
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: FilledButton(onPressed: () => Navigator.pop(context, n), child: Text('$n legs')),
                ),
              ),
          ]),
        ]),
      ),
    );
    if (n == null || !mounted) return;
    Navigator.push(context, MaterialPageRoute(builder: (_) => GameScreen(game: g, prediction: p, autoBuild: n)));
  }

  Widget _statusChip(Game g) {
    if (g.isLive) {
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(color: EdgeColors.coral.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(8)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          const Icon(Icons.circle, size: 8, color: EdgeColors.coral),
          const SizedBox(width: 6),
          Text('LIVE ${g.statusDetail}', style: const TextStyle(color: EdgeColors.coral, fontWeight: FontWeight.w800, fontSize: 12)),
        ]),
      );
    }
    if (g.isFinal) return const Muted('FINAL', size: 12);
    return Muted(timeLocal(g.date), size: 12);
  }

  Widget _teamRow(TeamRef t, double? score, Game g, double? winProb) {
    return Row(children: [
      if (t.logo != null)
        Image.network(t.logo!, width: 24, height: 24, errorBuilder: (_, _, _) => const SizedBox(width: 24))
      else
        const SizedBox(width: 24),
      const SizedBox(width: 8),
      Expanded(child: Text(t.name, style: const TextStyle(fontWeight: FontWeight.w700), overflow: TextOverflow.ellipsis)),
      if (t.record != null) Padding(padding: const EdgeInsets.only(right: 8), child: Muted(t.record!)),
      if (winProb != null && !g.isFinal) ProbPill(winProb),
      if (score != null && (g.isLive || g.isFinal))
        Padding(
          padding: const EdgeInsets.only(left: 10),
          child: Text(num1(score), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
        ),
    ]);
  }

  Future<void> _pickLeague() async {
    final chosen = await showModalBottomSheet<League>(
      context: context,
      backgroundColor: EdgeColors.navyCard,
      isScrollControlled: true,
      builder: (_) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.8,
        builder: (_, ctrl) => ListView(controller: ctrl, padding: const EdgeInsets.all(16), children: [
          const Text('All competitions', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 18)),
          const SizedBox(height: 4),
          const Muted('Star a league to keep it on your home row.'),
          for (final group in Leagues.groups) ...[
            SectionTitle(group),
            for (final l in Leagues.all.where((l) => l.group == group))
              ListTile(
                dense: true,
                title: Text(l.name),
                trailing: IconButton(
                  icon: Icon(Settings.instance.favourites.contains(l.id) ? Icons.star : Icons.star_border,
                      color: Settings.instance.favourites.contains(l.id) ? EdgeColors.lime : EdgeColors.muted),
                  onPressed: () {
                    Settings.instance.toggleFavourite(l.id);
                    setState(() {});
                    Navigator.pop(context, _league);
                    _pickLeague();
                  },
                ),
                onTap: () => Navigator.pop(context, l),
              ),
          ],
        ]),
      ),
    );
    if (chosen != null && chosen != _league) {
      setState(() => _league = chosen);
      _load();
    } else {
      setState(() {});
    }
  }
}
