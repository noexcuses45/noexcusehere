import 'package:flutter/foundation.dart';

import '../engine/ratings.dart';
import '../models/league.dart';
import 'espn_api.dart';

/// Keeps fitted league models in memory and reports build progress.
class ModelStore extends ChangeNotifier {
  ModelStore._();
  static final ModelStore instance = ModelStore._();

  final Map<String, LeagueModel> _models = {};
  final Map<String, Future<LeagueModel>> _inflight = {};
  final Map<String, (int, int)> progress = {};

  LeagueModel? cached(League league) => _models[league.id];

  Future<LeagueModel> model(League league, {bool force = false}) {
    if (!force) {
      final m = _models[league.id];
      if (m != null && DateTime.now().difference(m.fittedAt) < const Duration(hours: 3)) return Future.value(m);
      final f = _inflight[league.id];
      if (f != null) return f;
    }
    final fut = EspnApi.buildModel(league, progress: (d, t) {
      progress[league.id] = (d, t);
      notifyListeners();
    }).then((m) {
      _models[league.id] = m;
      _inflight.remove(league.id);
      progress.remove(league.id);
      notifyListeners();
      return m;
    }).catchError((e) {
      _inflight.remove(league.id);
      progress.remove(league.id);
      notifyListeners();
      throw e;
    });
    _inflight[league.id] = fut;
    return fut;
  }
}
