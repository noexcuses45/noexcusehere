import '../models/league.dart';
import 'ratings.dart';

/// How the model performed when replayed over past seasons, predicting each
/// game from earlier games only.
class BacktestResult {
  const BacktestResult({
    required this.games,
    required this.accuracy,
    required this.totalRmse,
    required this.baseTotalRmse,
    required this.said,
    required this.won,
    required this.binN,
  });
  final int games;
  final double accuracy; // share of decided games where the favourite won
  final double totalRmse; // typical miss on the game total
  final double baseTotalRmse; // same for a plain league-average guess
  final List<double> said, won; // favourite probability bins 50-60 .. 90-100
  final List<int> binN;
}

/// Per-league model settings found by walk-forward backtest
/// (tool/backtest_sports.dart). Generated; see tool/gen_calibration.py.
class Calibration {
  static const fallback = ModelParams();

  static const Map<String, ModelParams> leagues = {
    'a-league': ModelParams(
      halfLifeMult: 1.0,
      k0: 16.0,
      longMix: 0.0,
      kAvg: 60.0,
      totalScale: 1.00,
      marginScale: 1.00,
    ),
    'afl': ModelParams(
      halfLifeMult: 2.0,
      k0: 2.0,
      longMix: 0.3,
      kAvg: 2.0,
      totalScale: 1.09,
      marginScale: 1.03,
    ),
    'bundesliga': ModelParams(
      halfLifeMult: 4.0,
      k0: 8.0,
      longMix: 0.3,
      kAvg: 60.0,
      totalScale: 1.00,
      marginScale: 1.00,
    ),
    'epl': ModelParams(
      halfLifeMult: 4.0,
      k0: 16.0,
      longMix: 0.3,
      kAvg: 60.0,
      totalScale: 1.00,
      marginScale: 1.00,
    ),
    'laliga': ModelParams(
      halfLifeMult: 4.0,
      k0: 8.0,
      longMix: 0.3,
      kAvg: 60.0,
      totalScale: 1.00,
      marginScale: 1.00,
    ),
    'ligue1': ModelParams(
      halfLifeMult: 4.0,
      k0: 8.0,
      longMix: 0.3,
      kAvg: 60.0,
      totalScale: 1.00,
      marginScale: 1.00,
    ),
    'mls': ModelParams(
      halfLifeMult: 2.0,
      k0: 16.0,
      longMix: 0.0,
      kAvg: 2.0,
      totalScale: 1.00,
      marginScale: 1.00,
    ),
    'nbl': ModelParams(
      halfLifeMult: 2.0,
      k0: 4.0,
      longMix: 0.0,
      kAvg: 2.0,
      totalScale: 1.06,
      marginScale: 1.04,
    ),
    'nfl': ModelParams(
      halfLifeMult: 2.0,
      k0: 4.0,
      longMix: 0.3,
      kAvg: 2.0,
      totalScale: 1.13,
      marginScale: 1.09,
    ),
    'nrl': ModelParams(
      halfLifeMult: 1.0,
      k0: 8.0,
      longMix: 0.3,
      kAvg: 20.0,
      totalScale: 1.06,
      marginScale: 1.07,
    ),
    'seriea': ModelParams(
      halfLifeMult: 1.0,
      k0: 8.0,
      longMix: 0.3,
      kAvg: 60.0,
      totalScale: 1.00,
      marginScale: 1.00,
    ),
    'super-rugby': ModelParams(
      halfLifeMult: 4.0,
      k0: 4.0,
      longMix: 0.3,
      kAvg: 2.0,
      totalScale: 1.08,
      marginScale: 1.11,
    ),
    'ucl': ModelParams(
      halfLifeMult: 4.0,
      k0: 8.0,
      longMix: 0.0,
      kAvg: 2.0,
      totalScale: 1.00,
      marginScale: 1.00,
    ),
    'wnba': ModelParams(
      halfLifeMult: 2.0,
      k0: 4.0,
      longMix: 0.0,
      kAvg: 2.0,
      totalScale: 1.11,
      marginScale: 1.07,
    ),
  };

  /// Typical settings per sport for competitions that were not backtested.
  static const Map<String, ModelParams> groups = {
    'Soccer': ModelParams(
      halfLifeMult: 4.0,
      k0: 8.0,
      longMix: 0.3,
      kAvg: 60.0,
      totalScale: 1.00,
      marginScale: 1.00,
    ),
    'Aussie Rules': ModelParams(
      halfLifeMult: 2.0,
      k0: 2.0,
      longMix: 0.3,
      kAvg: 2.0,
      totalScale: 1.09,
      marginScale: 1.03,
    ),
    'Basketball': ModelParams(
      halfLifeMult: 2.0,
      k0: 4.0,
      longMix: 0.0,
      kAvg: 2.0,
      totalScale: 1.08,
      marginScale: 1.05,
    ),
    'American Football': ModelParams(
      halfLifeMult: 2.0,
      k0: 4.0,
      longMix: 0.3,
      kAvg: 2.0,
      totalScale: 1.13,
      marginScale: 1.09,
    ),
    'Rugby League': ModelParams(
      halfLifeMult: 1.0,
      k0: 8.0,
      longMix: 0.3,
      kAvg: 20.0,
      totalScale: 1.06,
      marginScale: 1.07,
    ),
    'Rugby Union': ModelParams(
      halfLifeMult: 4.0,
      k0: 4.0,
      longMix: 0.3,
      kAvg: 2.0,
      totalScale: 1.08,
      marginScale: 1.11,
    ),
  };

  static const Map<String, BacktestResult> backtests = {
    'a-league': BacktestResult(
      games: 302,
      accuracy: 0.6284,
      totalRmse: 1.72,
      baseTotalRmse: 1.75,
      said: [0.544, 0.643, 0.736, 0.000, 0.000],
      won: [0.644, 0.590, 0.636, 0.000, 0.000],
      binN: [146, 61, 11, 0, 0],
    ),
    'afl': BacktestResult(
      games: 414,
      accuracy: 0.7129,
      totalRmse: 29.68,
      baseTotalRmse: 30.33,
      said: [0.550, 0.648, 0.752, 0.848, 0.938],
      won: [0.533, 0.578, 0.889, 0.906, 1.000],
      binN: [107, 128, 72, 64, 40],
    ),
    'bundesliga': BacktestResult(
      games: 550,
      accuracy: 0.6852,
      totalRmse: 1.74,
      baseTotalRmse: 1.80,
      said: [0.547, 0.646, 0.742, 0.839, 0.931],
      won: [0.588, 0.653, 0.765, 0.917, 0.750],
      binN: [148, 124, 85, 48, 8],
    ),
    'epl': BacktestResult(
      games: 671,
      accuracy: 0.6720,
      totalRmse: 1.60,
      baseTotalRmse: 1.58,
      said: [0.549, 0.645, 0.749, 0.847, 0.913],
      won: [0.569, 0.677, 0.822, 0.962, 1.000],
      binN: [216, 164, 90, 26, 1],
    ),
    'laliga': BacktestResult(
      games: 692,
      accuracy: 0.7140,
      totalRmse: 1.57,
      baseTotalRmse: 1.62,
      said: [0.551, 0.647, 0.747, 0.846, 0.918],
      won: [0.629, 0.646, 0.773, 0.875, 1.000],
      binN: [170, 147, 110, 72, 22],
    ),
    'ligue1': BacktestResult(
      games: 547,
      accuracy: 0.6800,
      totalRmse: 1.80,
      baseTotalRmse: 1.82,
      said: [0.553, 0.647, 0.744, 0.832, 0.922],
      won: [0.561, 0.703, 0.707, 0.905, 1.000],
      binN: [148, 128, 99, 42, 8],
    ),
    'mls': BacktestResult(
      games: 1009,
      accuracy: 0.6360,
      totalRmse: 1.80,
      baseTotalRmse: 1.82,
      said: [0.549, 0.645, 0.736, 0.820, 0.000],
      won: [0.551, 0.706, 0.697, 0.800, 0.000],
      binN: [336, 262, 142, 10, 0],
    ),
    'nbl': BacktestResult(
      games: 294,
      accuracy: 0.6190,
      totalRmse: 18.45,
      baseTotalRmse: 19.56,
      said: [0.550, 0.648, 0.743, 0.840, 0.912],
      won: [0.560, 0.589, 0.679, 0.870, 1.000],
      binN: [109, 107, 53, 23, 2],
    ),
    'nfl': BacktestResult(
      games: 464,
      accuracy: 0.6364,
      totalRmse: 14.49,
      baseTotalRmse: 15.13,
      said: [0.547, 0.645, 0.745, 0.837, 0.911],
      won: [0.571, 0.634, 0.722, 0.758, 1.000],
      binN: [191, 145, 90, 33, 3],
    ),
    'nrl': BacktestResult(
      games: 409,
      accuracy: 0.6127,
      totalRmse: 13.82,
      baseTotalRmse: 13.96,
      said: [0.549, 0.645, 0.729, 0.817, 0.000],
      won: [0.582, 0.643, 0.686, 1.000, 0.000],
      binN: [232, 140, 35, 1, 0],
    ),
    'seriea': BacktestResult(
      games: 685,
      accuracy: 0.7199,
      totalRmse: 1.55,
      baseTotalRmse: 1.54,
      said: [0.551, 0.649, 0.744, 0.837, 0.909],
      won: [0.591, 0.710, 0.791, 0.960, 1.000],
      binN: [159, 162, 134, 50, 2],
    ),
    'super-rugby': BacktestResult(
      games: 160,
      accuracy: 0.7107,
      totalRmse: 15.44,
      baseTotalRmse: 15.46,
      said: [0.550, 0.652, 0.750, 0.843, 0.902],
      won: [0.625, 0.643, 0.833, 0.774, 1.000],
      binN: [48, 42, 36, 31, 2],
    ),
    'ucl': BacktestResult(
      games: 306,
      accuracy: 0.7461,
      totalRmse: 1.97,
      baseTotalRmse: 1.94,
      said: [0.547, 0.647, 0.746, 0.837, 0.927],
      won: [0.656, 0.725, 0.792, 0.963, 1.000],
      binN: [90, 80, 53, 27, 6],
    ),
    'wnba': BacktestResult(
      games: 608,
      accuracy: 0.6464,
      totalRmse: 19.07,
      baseTotalRmse: 20.52,
      said: [0.551, 0.649, 0.743, 0.831, 0.915],
      won: [0.491, 0.670, 0.817, 0.846, 0.667],
      binN: [226, 212, 115, 52, 3],
    ),
  };

  static ModelParams forLeague(League league) =>
      leagues[league.id] ?? groups[league.group] ?? fallback;
}
