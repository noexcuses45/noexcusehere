import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/league.dart';

/// User preferences, kept deliberately small.
class Settings extends ChangeNotifier {
  Settings._();
  static final Settings instance = Settings._();

  List<String> favourites = List.of(Leagues.defaultFavourites);
  bool showMarket = true; // show bookmaker line for comparison
  int sims = 20000;
  String racingCountry = 'AUS';
  String oddsApiKey = '';
  String bookie = 'sportsbet'; // The Odds API bookmaker key
  double tradeSize = 5000; // typical dollars per share trade
  double brokerage = 5; // dollars per trade, each way
  List<String> watchlist = [];

  /// Brokerage as a fraction of a trade, per side.
  double get costPerSide => tradeSize <= 0 ? 0.001 : brokerage / tradeSize;
  bool loaded = false;

  bool get hasOddsKey => oddsApiKey.trim().isNotEmpty;

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    favourites = p.getStringList('favourites') ?? favourites;
    showMarket = p.getBool('showMarket') ?? showMarket;
    sims = p.getInt('sims') ?? sims;
    racingCountry = p.getString('racingCountry') ?? racingCountry;
    oddsApiKey = p.getString('oddsApiKey') ?? oddsApiKey;
    bookie = p.getString('bookie') ?? bookie;
    tradeSize = p.getDouble('tradeSize') ?? tradeSize;
    brokerage = p.getDouble('brokerage') ?? brokerage;
    watchlist = p.getStringList('watchlist') ?? watchlist;
    loaded = true;
    notifyListeners();
  }

  Future<void> save() async {
    final p = await SharedPreferences.getInstance();
    await p.setStringList('favourites', favourites);
    await p.setBool('showMarket', showMarket);
    await p.setInt('sims', sims);
    await p.setString('racingCountry', racingCountry);
    await p.setString('oddsApiKey', oddsApiKey.trim());
    await p.setString('bookie', bookie);
    await p.setDouble('tradeSize', tradeSize);
    await p.setDouble('brokerage', brokerage);
    await p.setStringList('watchlist', watchlist);
    notifyListeners();
  }

  void toggleFavourite(String id) {
    if (favourites.contains(id)) {
      favourites.remove(id);
    } else {
      favourites.add(id);
    }
    save();
  }
}
