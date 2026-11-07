import 'dart:async';

import 'package:flutter/material.dart';

import '../engine/predictor.dart';
import '../engine/ratings.dart';
import '../models/game.dart';
import '../models/league.dart';
import '../services/espn_api.dart';
import '../services/model_store.dart';
import '../services/picks_store.dart';
import '../services/settings_service.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'game_screen.dart';

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
    _load();
    _timer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (_games.any((g) => g.isLive) || _dayOffset == 0) _refreshScores();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    ModelStore.instance.removeListener(_onStore);
    super.dispose();
  }

  void _onStore() => setState(() {});

  Future<void> _load() async {
    final token = ++_loadToken;
    setState(() {
      _loadingGames = true;
      _error = null;
      _games = [];
      _preds.clear();
      _bestSgm.clear();
      _roundMultis = [];
    });
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
        if (_roundMultis.isNotEmpty) _roundCard(),
        if (_building && _roundMultis.isEmpty)
          const Padding(padding: EdgeInsets.all(8), child: Muted('Building best multi for this round...')),
        for (final g in _games) _gameCard(g),
        const SizedBox(height: 8),
        const InfoBanner('Predictions come from team scoring ratings fitted to recent results (opponent-adjusted, recency-weighted). Bookmaker lines are shown for comparison only and never feed the model.'),
      ]),
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
