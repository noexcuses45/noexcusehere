import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../engine/stock_scanner.dart';
import '../engine/stocks.dart';
import 'settings_service.dart';

/// One practice trade taken from a BUY signal.
class PracticeTrade {
  PracticeTrade({
    required this.code,
    required this.kind,
    required this.entryDate,
    required this.entryPrice,
    required this.dollars,
    required this.buyFee,
    this.exitDate,
    this.exitPrice,
    this.sellFee = 0,
    this.exitReason = '',
    double? lastPrice,
  }) : lastPrice = lastPrice ?? entryPrice;

  final String code;
  final String kind; // practice: 'dip' or 'leader'; My plan: 'plan' (leaders) or 'short' (short-term)
  final DateTime entryDate;
  final double entryPrice;
  final double dollars; // cash put in, including the buy fee
  final double buyFee;
  DateTime? exitDate;
  double? exitPrice;
  double sellFee;
  String exitReason;
  double lastPrice;

  bool get open => exitDate == null;
  double get shares => (dollars - buyFee) / entryPrice;

  /// What the position is worth now (open) or what came back (closed).
  double get value => open ? shares * lastPrice : shares * exitPrice! - sellFee;

  /// Profit or loss in dollars. Open trades do not yet include the sell fee.
  double get pnl => value - dollars;

  Map<String, dynamic> toJson() => {
        'c': code,
        'k': kind,
        'ed': entryDate.toIso8601String(),
        'ep': entryPrice,
        'd': dollars,
        'bf': buyFee,
        'xd': exitDate?.toIso8601String(),
        'xp': exitPrice,
        'sf': sellFee,
        'xr': exitReason,
        'lp': lastPrice,
      };

  static PracticeTrade fromJson(Map<String, dynamic> j) => PracticeTrade(
        code: j['c'],
        kind: j['k'],
        entryDate: DateTime.parse(j['ed']),
        entryPrice: (j['ep'] as num).toDouble(),
        dollars: (j['d'] as num).toDouble(),
        buyFee: (j['bf'] as num).toDouble(),
        exitDate: j['xd'] == null ? null : DateTime.parse(j['xd']),
        exitPrice: (j['xp'] as num?)?.toDouble(),
        sellFee: (j['sf'] as num?)?.toDouble() ?? 0,
        exitReason: j['xr'] ?? '',
        lastPrice: (j['lp'] as num?)?.toDouble(),
      );
}

/// A pretend share account: follow the app's BUY signals with play money and
/// real brokerage, and see whether the balance builds before risking cash.
class PracticeAccount extends ChangeNotifier {
  PracticeAccount._();
  static final PracticeAccount instance = PracticeAccount._();

  double start = 0; // 0 = not set up yet
  DateTime? startedAt;
  final List<PracticeTrade> trades = [];

  bool get active => start > 0;
  double get cash => start - trades.fold(0.0, (s, t) => s + t.dollars) + trades.where((t) => !t.open).fold(0.0, (s, t) => s + t.value);
  double get equity => cash + trades.where((t) => t.open).fold(0.0, (s, t) => s + t.value);
  double get totalPnl => equity - start;
  List<PracticeTrade> get openTrades => trades.where((t) => t.open).toList();
  List<PracticeTrade> get closedTrades => trades.where((t) => !t.open).toList()..sort((a, b) => b.exitDate!.compareTo(a.exitDate!));
  double get feesPaid => trades.fold(0.0, (s, t) => s + t.buyFee + t.sellFee);

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    start = p.getDouble('practice.start') ?? 0;
    final at = p.getString('practice.startedAt');
    startedAt = at == null ? null : DateTime.tryParse(at);
    trades.clear();
    try {
      for (final j in (json.decode(p.getString('practice.trades') ?? '[]') as List)) {
        trades.add(PracticeTrade.fromJson(Map<String, dynamic>.from(j)));
      }
    } catch (_) {}
    notifyListeners();
  }

  Future<void> _save() async {
    final p = await SharedPreferences.getInstance();
    await p.setDouble('practice.start', start);
    if (startedAt != null) await p.setString('practice.startedAt', startedAt!.toIso8601String());
    await p.setString('practice.trades', json.encode(trades.map((t) => t.toJson()).toList()));
    notifyListeners();
  }

  /// Starts (or restarts) the practice account with [balance] play dollars.
  Future<void> reset(double balance) async {
    start = balance;
    startedAt = DateTime.now();
    trades.clear();
    await _save();
  }

  bool has(String code, String kind, DateTime entryDate) =>
      trades.any((t) => t.code == code && t.kind == kind && t.entryDate == entryDate);

  /// Buys [dollars] worth at the signal's price, paying the buy brokerage.
  Future<bool> buy({required String code, required String kind, required DateTime entryDate, required double entryPrice, required double dollars}) async {
    if (!active || dollars < 20 || dollars > cash + 0.005 || has(code, kind, entryDate)) return false;
    final fee = Settings.instance.buyFee(dollars);
    if (fee >= dollars) return false;
    trades.add(PracticeTrade(code: code, kind: kind, entryDate: entryDate, entryPrice: entryPrice, dollars: dollars, buyFee: fee));
    await _save();
    return true;
  }

  /// Closes a trade at [price], paying the sell brokerage on the proceeds.
  void close(PracticeTrade t, DateTime date, double price, String reason) {
    t
      ..exitDate = date
      ..exitPrice = price
      ..exitReason = reason
      ..sellFee = Settings.instance.sellFee(t.shares * price);
  }

  /// Records that the user sold [t] at [price].
  Future<void> sell(PracticeTrade t, DateTime date, double price, String reason) async {
    if (!t.open) return;
    close(t, date, price, reason);
    await _save();
  }

  /// Brings open trades up to date with the latest scan: marks prices, and
  /// sells short-term and leader practice trades where the rule has said
  /// sell. My plan trades (kinds 'plan' and 'short') are only re-priced: the
  /// user marks those as sold, because they may be real.
  Future<void> sync(ScanResult r, [Map<String, Series> series = const {}]) async {
    if (!active) return;
    var changed = false;
    for (final t in trades.where((t) => t.open)) {
      if (t.kind == 'plan' || t.kind == 'short') {
        final s = series[t.code];
        if (s != null && s.close(s.n - 1) != t.lastPrice) {
          t.lastPrice = s.close(s.n - 1);
          changed = true;
        }
        continue;
      }
      if (t.kind == 'dip') {
        final done = r.closedDips.where((d) => d.code == t.code && d.entryDate == t.entryDate).toList();
        if (done.isNotEmpty) {
          close(t, done.first.exitDate!, done.first.exitPrice!, done.first.exitReason);
          changed = true;
          continue;
        }
        final live = r.dips.where((d) => d.code == t.code && d.entryDate == t.entryDate && d.open).toList();
        if (live.isNotEmpty && live.first.priceNow != t.lastPrice) {
          t.lastPrice = live.first.priceNow;
          changed = true;
        }
      } else {
        final done = r.closedLeaders.where((d) => d.code == t.code && d.entryDate == t.entryDate).toList();
        if (done.isNotEmpty) {
          close(t, done.first.exitDate!, done.first.exitPrice!, done.first.exitReason);
          changed = true;
          continue;
        }
        final live = r.leaders.where((d) => d.code == t.code && d.entryDate == t.entryDate && d.open).toList();
        if (live.isNotEmpty && live.first.priceNow != t.lastPrice) {
          t.lastPrice = live.first.priceNow;
          changed = true;
        }
      }
    }
    if (changed) await _save();
  }
}
