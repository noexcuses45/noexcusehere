import 'dart:math' as math;

/// One trading day for a share: closing price and volume.
class Bar {
  const Bar(this.date, this.close, this.volume);
  final DateTime date;
  final double close;
  final double volume;
}

/// A price history with indicators computed once and cached.
class Series {
  Series(this.code, this.bars) : n = bars.length;
  final String code;
  final List<Bar> bars;
  final int n;

  final Map<String, List<double>> _cache = {};

  double close(int i) => bars[i].close;

  /// Simple moving average of closes; NaN until enough history.
  List<double> sma(int len) => _cache.putIfAbsent('sma$len', () {
        final out = List<double>.filled(n, double.nan);
        var sum = 0.0;
        for (var i = 0; i < n; i++) {
          sum += bars[i].close;
          if (i >= len) sum -= bars[i - len].close;
          if (i >= len - 1) out[i] = sum / len;
        }
        return out;
      });

  /// Wilder's RSI of closes.
  List<double> rsi(int len) => _cache.putIfAbsent('rsi$len', () {
        final out = List<double>.filled(n, double.nan);
        var gain = 0.0, loss = 0.0;
        for (var i = 1; i < n; i++) {
          final ch = bars[i].close - bars[i - 1].close;
          final g = ch > 0 ? ch : 0.0, l = ch < 0 ? -ch : 0.0;
          if (i <= len) {
            gain += g / len;
            loss += l / len;
          } else {
            gain = (gain * (len - 1) + g) / len;
            loss = (loss * (len - 1) + l) / len;
          }
          if (i >= len) out[i] = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss);
        }
        return out;
      });

  /// Standard deviation of closes over [len] days (for Bollinger bands).
  List<double> sd(int len) => _cache.putIfAbsent('sd$len', () {
        final out = List<double>.filled(n, double.nan);
        final m = sma(len);
        for (var i = len - 1; i < n; i++) {
          var s = 0.0;
          for (var j = i - len + 1; j <= i; j++) {
            s += (bars[j].close - m[i]) * (bars[j].close - m[i]);
          }
          out[i] = math.sqrt(s / len);
        }
        return out;
      });

  /// Typical daily move as a fraction of price (average absolute change, 14 days).
  List<double> vol() => _cache.putIfAbsent('vol', () {
        const len = 14;
        final out = List<double>.filled(n, double.nan);
        var sum = 0.0;
        for (var i = 1; i < n; i++) {
          sum += (bars[i].close / bars[i - 1].close - 1).abs();
          if (i > len) sum -= (bars[i - len].close / bars[i - len - 1].close - 1).abs();
          if (i >= len) out[i] = sum / len;
        }
        return out;
      });

  /// Highest close of the previous [len] days, excluding today.
  List<double> priorHigh(int len) => _cache.putIfAbsent('hi$len', () {
        final out = List<double>.filled(n, double.nan);
        for (var i = len; i < n; i++) {
          var h = bars[i - 1].close;
          for (var j = i - len; j < i; j++) {
            if (bars[j].close > h) h = bars[j].close;
          }
          out[i] = h;
        }
        return out;
      });

  List<double> avgVolume(int len) => _cache.putIfAbsent('av$len', () {
        final out = List<double>.filled(n, double.nan);
        var sum = 0.0;
        for (var i = 0; i < n; i++) {
          sum += bars[i].volume;
          if (i >= len) sum -= bars[i - len].volume;
          if (i >= len - 1) out[i] = sum / len;
        }
        return out;
      });

  /// Number of consecutive lower closes ending at day [i].
  int downStreak(int i) {
    var k = 0;
    while (i - k > 0 && bars[i - k].close < bars[i - k - 1].close) {
      k++;
    }
    return k;
  }
}

/// A rule-based trade setup evaluated on daily closes.
abstract class Setup {
  const Setup();
  String get id;
  String get name;
  String get blurb; // one line: what it looks for
  String get sellRule; // plain-English exit
  int get maxHold; // trading days
  double get stopVols; // stop distance in typical daily moves
  int get warmup; // bars needed before the rule can fire

  /// True when the setup triggers at the close of day [i].
  bool entry(Series s, int i);

  /// True when the profit-taking / trend exit triggers at the close of day [i].
  bool exit(Series s, int entryIdx, int i);

  /// The price level the sell rule is waiting for, for display (may be NaN).
  double exitLevel(Series s, int i);

  double stopPrice(Series s, int entryIdx) {
    final v = s.vol()[entryIdx];
    final dist = (v.isNaN ? 0.02 : v) * stopVols;
    return s.close(entryIdx) * (1 - dist.clamp(0.02, 0.25));
  }
}

/// Uptrend + sharp short-term dip (2-day RSI under 10). Sell on the bounce.
class PullbackSetup extends Setup {
  const PullbackSetup();
  @override
  String get id => 'pullback';
  @override
  String get name => 'Dip in an uptrend';
  @override
  String get blurb => 'Long-term uptrend, sharp 2-3 day sell-off. Buys the dip and sells the bounce.';
  @override
  String get sellRule => 'Sell when it closes back above its 5-day average, or after 10 trading days.';
  @override
  int get maxHold => 10;
  @override
  double get stopVols => 6;
  @override
  int get warmup => 205;
  @override
  bool entry(Series s, int i) {
    final ma200 = s.sma(200)[i], r = s.rsi(2)[i];
    if (ma200.isNaN || r.isNaN) return false;
    return s.close(i) > ma200 && r < 10;
  }

  @override
  bool exit(Series s, int entryIdx, int i) => s.close(i) > s.sma(5)[i];
  @override
  double exitLevel(Series s, int i) => s.sma(5)[i];
}

/// Uptrend + three or more lower closes in a row. Sell on the bounce.
class DownStreakSetup extends Setup {
  const DownStreakSetup();
  @override
  String get id => 'streak';
  @override
  String get name => 'Three down days in an uptrend';
  @override
  String get blurb => 'Long-term uptrend with three or more lower closes in a row.';
  @override
  String get sellRule => 'Sell when it closes back above its 5-day average, or after 10 trading days.';
  @override
  int get maxHold => 10;
  @override
  double get stopVols => 6;
  @override
  int get warmup => 205;
  @override
  bool entry(Series s, int i) {
    final ma200 = s.sma(200)[i], ma5 = s.sma(5)[i];
    if (ma200.isNaN) return false;
    return s.close(i) > ma200 && s.close(i) < ma5 && s.downStreak(i) >= 3;
  }

  @override
  bool exit(Series s, int entryIdx, int i) => s.close(i) > s.sma(5)[i];
  @override
  double exitLevel(Series s, int i) => s.sma(5)[i];
}

/// Uptrend + close below the lower Bollinger band. Sell back at the 20-day average.
class BandDipSetup extends Setup {
  const BandDipSetup();
  @override
  String get id => 'band';
  @override
  String get name => 'Stretched below its range';
  @override
  String get blurb => 'Long-term uptrend, but the price has dropped unusually far below its 20-day average.';
  @override
  String get sellRule => 'Sell when it closes back above its 20-day average, or after 15 trading days.';
  @override
  int get maxHold => 15;
  @override
  double get stopVols => 7;
  @override
  int get warmup => 205;
  @override
  bool entry(Series s, int i) {
    final ma200 = s.sma(200)[i], ma20 = s.sma(20)[i], sd20 = s.sd(20)[i];
    if (ma200.isNaN || sd20.isNaN) return false;
    return s.close(i) > ma200 && s.close(i) < ma20 - 2 * sd20;
  }

  @override
  bool exit(Series s, int entryIdx, int i) => s.close(i) > s.sma(20)[i];
  @override
  double exitLevel(Series s, int i) => s.sma(20)[i];
}

/// New 100-day closing high on heavy volume. Ride the trend.
class BreakoutSetup extends Setup {
  const BreakoutSetup();
  @override
  String get id => 'breakout';
  @override
  String get name => 'Breakout to new highs';
  @override
  String get blurb => 'Closes at a new 100-day high on at least 1.5x normal volume, in a long-term uptrend.';
  @override
  String get sellRule => 'Sell when it closes below its 20-day average, or after 30 trading days.';
  @override
  int get maxHold => 30;
  @override
  double get stopVols => 5;
  @override
  int get warmup => 205;
  @override
  bool entry(Series s, int i) {
    final ma200 = s.sma(200)[i], hi = s.priorHigh(100)[i], av = s.avgVolume(20)[i];
    if (ma200.isNaN || hi.isNaN || av.isNaN) return false;
    return s.close(i) > hi && s.close(i) > ma200 && s.bars[i].volume > 1.5 * av;
  }

  @override
  bool exit(Series s, int entryIdx, int i) => s.close(i) < s.sma(20)[i];
  @override
  double exitLevel(Series s, int i) => s.sma(20)[i];
}

/// Any trend: extreme 2-day RSI (under 5). A pure oversold bounce.
class OversoldSetup extends Setup {
  const OversoldSetup();
  @override
  String get id => 'oversold';
  @override
  String get name => 'Deeply oversold';
  @override
  String get blurb => 'Extreme short-term sell-off, regardless of the longer trend.';
  @override
  String get sellRule => 'Sell when it closes back above its 5-day average, or after 7 trading days.';
  @override
  int get maxHold => 7;
  @override
  double get stopVols => 6;
  @override
  int get warmup => 30;
  @override
  bool entry(Series s, int i) {
    final r = s.rsi(2)[i];
    return !r.isNaN && r < 5;
  }

  @override
  bool exit(Series s, int entryIdx, int i) => s.close(i) > s.sma(5)[i];
  @override
  double exitLevel(Series s, int i) => s.sma(5)[i];
}

/// One completed (or still open) trade from a setup.
class Trade {
  Trade({
    required this.setup,
    required this.code,
    required this.signalIdx,
    required this.entryIdx,
    required this.entryPrice,
    required this.stop,
  });
  final Setup setup;
  final String code;
  final int signalIdx;
  final int entryIdx;
  final double entryPrice;
  final double stop;
  int? exitIdx;
  double? exitPrice;
  String exitReason = ''; // rule | stop | time

  bool get open => exitIdx == null;
  int get days => (exitIdx ?? entryIdx) - entryIdx;

  /// Return after costs ([cost] per side, as a fraction).
  double netReturn(double cost) => exitPrice == null ? 0 : (exitPrice! * (1 - cost)) / (entryPrice * (1 + cost)) - 1;
}

/// Replays a setup over a price history. No overlapping trades.
class Simulator {
  /// [entryDelay] 0 = buy at the signal day's close, 1 = buy at the next close.
  static List<Trade> run(Series s, Setup setup, {int entryDelay = 0, int from = 0}) {
    final trades = <Trade>[];
    var i = math.max(setup.warmup, from);
    while (i < s.n) {
      if (!setup.entry(s, i)) {
        i++;
        continue;
      }
      final e = i + entryDelay;
      if (e >= s.n) {
        // Signal fired on the last bar and the entry is still ahead.
        trades.add(Trade(setup: setup, code: s.code, signalIdx: i, entryIdx: s.n - 1, entryPrice: s.close(s.n - 1), stop: setup.stopPrice(s, i)));
        break;
      }
      final t = Trade(setup: setup, code: s.code, signalIdx: i, entryIdx: e, entryPrice: s.close(e), stop: setup.stopPrice(s, i));
      var j = e + 1;
      for (; j < s.n; j++) {
        final c = s.close(j);
        if (c <= t.stop) {
          t.exitReason = 'stop';
        } else if (setup.exit(s, e, j)) {
          t.exitReason = 'rule';
        } else if (j - e >= setup.maxHold) {
          t.exitReason = 'time';
        } else {
          continue;
        }
        t.exitIdx = j;
        t.exitPrice = c;
        break;
      }
      trades.add(t);
      if (t.open) break;
      i = t.exitIdx! + 1;
    }
    return trades;
  }
}

/// Win rate and average result of a list of closed trades.
class TradeStats {
  TradeStats(Iterable<Trade> trades, {this.cost = 0.001}) {
    for (final t in trades) {
      if (t.open) continue;
      final r = t.netReturn(cost);
      n++;
      sum += r;
      if (r > 0) {
        wins++;
        gains += r;
      } else {
        losses -= r;
      }
      days += t.days;
    }
  }
  final double cost;
  int n = 0, wins = 0, days = 0;
  double sum = 0, gains = 0, losses = 0;

  double get winRate => n == 0 ? 0 : wins / n;
  double get avg => n == 0 ? 0 : sum / n;
  double get avgDays => n == 0 ? 0 : days / n;
  double get profitFactor => losses == 0 ? (gains > 0 ? 99 : 0) : gains / losses;
}

/// The setups the app scans for. Kept or dropped based on the backtest.
const List<Setup> allSetups = [PullbackSetup(), DownStreakSetup(), BandDipSetup(), BreakoutSetup(), OversoldSetup()];
