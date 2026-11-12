import 'package:flutter/foundation.dart';

import '../engine/market_clock.dart';
import '../engine/stock_scanner.dart';
import '../engine/stocks.dart';
import '../models/asx_universe.dart';
import 'asx_api.dart';
import 'practice_account.dart';

/// Loads the ASX universe and runs both share strategies. Shared by the
/// Shares page and the stock screen so prices are downloaded once.
class ShareScan extends ChangeNotifier {
  ShareScan._();
  static final ShareScan instance = ShareScan._();

  final Map<String, Series> series = {};
  ScanResult? result;
  StockScanner? scanner;
  bool loading = false;
  int done = 0, total = 0;
  String? error;
  DateTime? loadedAt;
  bool live = false; // last bar is today's unfinished session

  bool get stale => loadedAt == null || DateTime.now().difference(loadedAt!) > (MarketClock.asxOpen() ? const Duration(minutes: 15) : const Duration(hours: 3));

  Future<void> load({bool force = false}) async {
    if (loading) return;
    if (!force && result != null && !stale) return;
    loading = true;
    error = null;
    final codes = AsxUniverse.shares.keys.toList();
    done = 0;
    total = codes.length;
    notifyListeners();
    try {
      for (var b = 0; b < codes.length; b += 6) {
        await Future.wait([
          for (final code in codes.skip(b).take(6))
            () async {
              try {
                final s = await AsxApi.history(code);
                if (s != null && s.n >= 220) series[code] = s;
              } catch (_) {}
              done++;
            }()
        ]);
        notifyListeners();
      }
      final shares = [for (final c in codes) if (series[c] != null) series[c]!];
      if (shares.length < 20) {
        error = 'Could not load ASX prices. Check your connection and try again.';
      } else {
        final sc = StockScanner(shares);
        // Replay roughly the last year so entry dates of open positions are right.
        result = sc.run(from: sc.calendar.length - 260);
        scanner = sc;
        live = MarketClock.barIsLive(result!.asOf);
        loadedAt = DateTime.now();
        await PracticeAccount.instance.sync(result!);
      }
    } catch (_) {
      error = 'Could not load ASX prices. Check your connection and try again.';
    }
    loading = false;
    notifyListeners();
  }
}
