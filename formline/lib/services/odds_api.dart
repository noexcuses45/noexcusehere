import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../models/game.dart';
import '../models/league.dart';
import 'cache.dart';
import 'settings_service.dart';

/// One bookmaker's prices for one event (head-to-head, line, total).
class BookiePrices {
  BookiePrices({required this.key, required this.title});
  final String key;
  final String title;
  double? homeWin, awayWin, draw;
  double? spreadPoint; // home team's handicap, e.g. -6.5
  double? spreadHome, spreadAway;
  double? totalPoint;
  double? over, under;

  bool get hasAny => homeWin != null || totalPoint != null || spreadPoint != null;
}

/// An event from The Odds API with every bookmaker's prices.
class OddsEvent {
  OddsEvent({required this.id, required this.home, required this.away, required this.commence, required this.books});
  final String id;
  final String home;
  final String away;
  final DateTime commence;
  final Map<String, BookiePrices> books; // bookmaker key -> prices

  BookiePrices? book(String key) => books[key];
}

/// The Odds API (the-odds-api.com): Australian bookmaker prices used only to
/// compare against the model and to find value. Needs the user's own key.
class OddsApi extends ChangeNotifier {
  OddsApi._();
  static final OddsApi instance = OddsApi._();
  static const _base = 'https://api.the-odds-api.com/v4';

  int? remainingCredits;
  String? lastError;
  final Map<String, String> bookTitles = {}; // key -> title seen in responses

  static String _norm(String s) => s.toLowerCase().replaceAll(RegExp(r'[^a-z0-9 ]'), '').replaceAll(RegExp(r'\s+'), ' ').trim();

  /// All events with AU bookmaker prices for a league (cached 20 minutes).
  Future<List<OddsEvent>> events(League league) async {
    final sport = league.oddsKey;
    final key = Settings.instance.oddsApiKey.trim();
    if (sport == null || key.isEmpty) return [];
    final cacheKey = 'odds:$sport';
    final cached = await Cache.get(cacheKey, const Duration(minutes: 20));
    if (cached is List) return _parse(cached);
    final url = Uri.parse('$_base/sports/$sport/odds?regions=au&markets=h2h,spreads,totals&oddsFormat=decimal&apiKey=$key');
    final res = await http.get(url).timeout(const Duration(seconds: 20));
    final rem = res.headers['x-requests-remaining'];
    if (rem != null) remainingCredits = int.tryParse(rem);
    if (res.statusCode == 401) {
      lastError = 'Odds API key was rejected. Check it in Settings.';
      notifyListeners();
      throw Exception(lastError);
    }
    if (res.statusCode == 429) {
      lastError = 'Odds API quota used up for this month.';
      notifyListeners();
      throw Exception(lastError);
    }
    if (res.statusCode != 200) {
      lastError = 'Odds feed error ${res.statusCode}.';
      notifyListeners();
      throw Exception(lastError);
    }
    lastError = null;
    final list = json.decode(res.body) as List;
    await Cache.put(cacheKey, list);
    notifyListeners();
    return _parse(list);
  }

  List<OddsEvent> _parse(List raw) {
    final out = <OddsEvent>[];
    for (final e in raw) {
      try {
        final home = e['home_team'] as String;
        final away = e['away_team'] as String;
        final books = <String, BookiePrices>{};
        for (final b in (e['bookmakers'] as List? ?? [])) {
          final bk = BookiePrices(key: b['key'], title: b['title']);
          bookTitles[bk.key] = bk.title;
          for (final m in (b['markets'] as List? ?? [])) {
            final outcomes = (m['outcomes'] as List? ?? []).cast<Map>();
            switch (m['key']) {
              case 'h2h':
                for (final o in outcomes) {
                  final price = (o['price'] as num?)?.toDouble();
                  if (o['name'] == home) bk.homeWin = price;
                  if (o['name'] == away) bk.awayWin = price;
                  if (o['name'] == 'Draw') bk.draw = price;
                }
                break;
              case 'spreads':
                for (final o in outcomes) {
                  final price = (o['price'] as num?)?.toDouble();
                  final point = (o['point'] as num?)?.toDouble();
                  if (o['name'] == home) {
                    bk.spreadHome = price;
                    bk.spreadPoint = point;
                  }
                  if (o['name'] == away) bk.spreadAway = price;
                }
                break;
              case 'totals':
                for (final o in outcomes) {
                  final price = (o['price'] as num?)?.toDouble();
                  final point = (o['point'] as num?)?.toDouble();
                  if (o['name'] == 'Over') {
                    bk.over = price;
                    bk.totalPoint = point;
                  }
                  if (o['name'] == 'Under') bk.under = price;
                }
                break;
            }
          }
          if (bk.hasAny) books[bk.key] = bk;
        }
        out.add(OddsEvent(id: '${e['id']}', home: home, away: away, commence: DateTime.parse(e['commence_time']), books: books));
      } catch (_) {}
    }
    return out;
  }

  /// Finds the odds event for an ESPN game by team names and start time.
  static OddsEvent? match(List<OddsEvent> events, Game game) {
    final h = _norm(game.home.name), a = _norm(game.away.name);
    OddsEvent? best;
    var bestScore = 0;
    for (final e in events) {
      if (e.commence.difference(game.date).abs() > const Duration(hours: 14)) continue;
      final eh = _norm(e.home), ea = _norm(e.away);
      var score = 0;
      if (eh == h) score += 3;
      if (ea == a) score += 3;
      if (score < 6) {
        if (eh.contains(h) || h.contains(eh) || eh.split(' ').last == h.split(' ').last) score += 1;
        if (ea.contains(a) || a.contains(ea) || ea.split(' ').last == a.split(' ').last) score += 1;
      }
      if (score >= 2 && score > bestScore) {
        bestScore = score;
        best = e;
      }
    }
    return best;
  }

  /// Bookmakers seen so far, Sportsbet first.
  List<MapEntry<String, String>> get bookmakers {
    final list = bookTitles.entries.toList()..sort((x, y) => x.value.compareTo(y.value));
    list.sort((x, y) => (x.key == 'sportsbet' ? 0 : 1).compareTo(y.key == 'sportsbet' ? 0 : 1));
    return list;
  }
}
