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
  bool loaded = false;

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    favourites = p.getStringList('favourites') ?? favourites;
    showMarket = p.getBool('showMarket') ?? showMarket;
    sims = p.getInt('sims') ?? sims;
    racingCountry = p.getString('racingCountry') ?? racingCountry;
    loaded = true;
    notifyListeners();
  }

  Future<void> save() async {
    final p = await SharedPreferences.getInstance();
    await p.setStringList('favourites', favourites);
    await p.setBool('showMarket', showMarket);
    await p.setInt('sims', sims);
    await p.setString('racingCountry', racingCountry);
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
