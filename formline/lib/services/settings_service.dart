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
  double tradeSize = 10000; // typical dollars per share trade
  double brokerage = 5; // flat dollars per trade, each way (when not using a broker preset)
  String brokerModel = 'cmc'; // 'cmc' = CMC Invest rates, 'flat' = the flat fee above
  List<String> watchlist = [];
  String planStyle = 'long'; // My plan: 'short' = short-term trades, 'long' = leaders to hold

  /// Brokerage to buy a parcel of [size] dollars.
  /// CMC Invest (Australian shares): first buy under \$1,000 per stock per day
  /// is free; otherwise the greater of \$11 or 0.10%.
  double buyFee(double size) {
    if (brokerModel != 'cmc') return brokerage;
    if (size < 1000) return 0;
    return size * 0.001 > 11 ? size * 0.001 : 11;
  }

  /// Brokerage to sell a parcel of [size] dollars (CMC: greater of \$11 or 0.10%).
  double sellFee(double size) {
    if (brokerModel != 'cmc') return brokerage;
    return size * 0.001 > 11 ? size * 0.001 : 11;
  }

  /// Buy plus sell brokerage as a fraction of the parcel.
  double get roundTripCost => tradeSize <= 0 ? 0.002 : (buyFee(tradeSize) + sellFee(tradeSize)) / tradeSize;

  /// Brokerage as a fraction of a trade, per side (average of in and out).
  double get costPerSide => roundTripCost / 2;
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
    brokerModel = p.getString('brokerModel') ?? brokerModel;
    watchlist = p.getStringList('watchlist') ?? watchlist;
    planStyle = p.getString('planStyle') ?? planStyle;
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
    await p.setString('brokerModel', brokerModel);
    await p.setStringList('watchlist', watchlist);
    await p.setString('planStyle', planStyle);
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
