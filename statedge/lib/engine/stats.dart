import 'dart:math' as math;

/// Small statistics toolkit used by the predictor. Pure Dart, no dependencies.
class Stats {
  /// Standard normal cumulative distribution function.
  static double normalCdf(double z) {
    // Abramowitz & Stegun 7.1.26 via erf approximation (max error 1.5e-7).
    final t = 1.0 / (1.0 + 0.2316419 * z.abs());
    final d = 0.3989422804014327 * math.exp(-z * z / 2);
    final p = d *
        t *
        (0.319381530 +
            t * (-0.356563782 +
                t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
    return z >= 0 ? 1 - p : p;
  }

  /// P(X > x) for X ~ N(mu, sd).
  static double normalTail(double x, double mu, double sd) {
    if (sd <= 0) return x < mu ? 1 : 0;
    return 1 - normalCdf((x - mu) / sd);
  }

  /// Inverse normal CDF (Acklam's algorithm).
  static double normalInv(double p) {
    if (p <= 0) return double.negativeInfinity;
    if (p >= 1) return double.infinity;
    const a = [
      -3.969683028665376e+01,
      2.209460984245205e+02,
      -2.759285104469687e+02,
      1.383577518672690e+02,
      -3.066479806614716e+01,
      2.506628277459239e+00
    ];
    const b = [
      -5.447609879822406e+01,
      1.615858368580409e+02,
      -1.556989798598866e+02,
      6.680131188771972e+01,
      -1.328068155288572e+01
    ];
    const c = [
      -7.784894002430293e-03,
      -3.223964580411365e-01,
      -2.400758277161838e+00,
      -2.549732539343734e+00,
      4.374664141464968e+00,
      2.938163982698783e+00
    ];
    const d = [
      7.784695709041462e-03,
      3.224671290700398e-01,
      2.445134137142996e+00,
      3.754408661907416e+00
    ];
    const pLow = 0.02425;
    const pHigh = 1 - pLow;
    double q, r;
    if (p < pLow) {
      q = math.sqrt(-2 * math.log(p));
      return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q +
              c[5]) /
          ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
    } else if (p <= pHigh) {
      q = p - 0.5;
      r = q * q;
      return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r +
              a[5]) *
          q /
          (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    } else {
      q = math.sqrt(-2 * math.log(1 - p));
      return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q +
              c[5]) /
          ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
    }
  }

  /// Poisson probability mass function.
  static double poissonPmf(int k, double lambda) {
    if (lambda <= 0) return k == 0 ? 1 : 0;
    var logp = -lambda + k * math.log(lambda);
    for (var i = 2; i <= k; i++) {
      logp -= math.log(i);
    }
    return math.exp(logp);
  }

  /// Poisson pmf table from 0..max (truncated, renormalised).
  static List<double> poissonTable(double lambda, int max) {
    final out = List<double>.filled(max + 1, 0);
    var sum = 0.0;
    for (var k = 0; k <= max; k++) {
      out[k] = poissonPmf(k, lambda);
      sum += out[k];
    }
    if (sum > 0) {
      for (var k = 0; k <= max; k++) {
        out[k] /= sum;
      }
    }
    return out;
  }

  static double weightedMean(List<double> xs, List<double> ws) {
    var s = 0.0, w = 0.0;
    for (var i = 0; i < xs.length; i++) {
      s += xs[i] * ws[i];
      w += ws[i];
    }
    return w > 0 ? s / w : 0;
  }

  static double weightedVar(List<double> xs, List<double> ws) {
    if (xs.length < 2) return 0;
    final m = weightedMean(xs, ws);
    var s = 0.0, w = 0.0;
    for (var i = 0; i < xs.length; i++) {
      s += ws[i] * (xs[i] - m) * (xs[i] - m);
      w += ws[i];
    }
    // Reliability-weights correction so that equal weights give the sample variance.
    final n = xs.length.toDouble();
    return w > 0 ? s / w * n / (n - 1) : 0;
  }

  static double weightedCorr(List<double> xs, List<double> ys, List<double> ws) {
    if (xs.length < 3) return 0;
    final mx = weightedMean(xs, ws), my = weightedMean(ys, ws);
    var sxy = 0.0, sxx = 0.0, syy = 0.0;
    for (var i = 0; i < xs.length; i++) {
      sxy += ws[i] * (xs[i] - mx) * (ys[i] - my);
      sxx += ws[i] * (xs[i] - mx) * (xs[i] - mx);
      syy += ws[i] * (ys[i] - my) * (ys[i] - my);
    }
    if (sxx <= 0 || syy <= 0) return 0;
    return (sxy / math.sqrt(sxx * syy)).clamp(-0.95, 0.95);
  }

  static double mean(Iterable<double> xs) {
    var s = 0.0;
    var n = 0;
    for (final x in xs) {
      s += x;
      n++;
    }
    return n > 0 ? s / n : 0;
  }

  static double stdDev(List<double> xs) {
    if (xs.length < 2) return 0;
    final m = mean(xs);
    var s = 0.0;
    for (final x in xs) {
      s += (x - m) * (x - m);
    }
    return math.sqrt(s / (xs.length - 1));
  }

  /// Decimal odds that correspond to a probability (no margin).
  static double fairOdds(double p) => p <= 0 ? 999 : (1 / p).clamp(1.01, 999);
}

/// Deterministic random with Box-Muller normal sampling.
class Rng {
  Rng([int seed = 12345]) : _r = math.Random(seed);
  final math.Random _r;
  double? _spare;

  double uniform() => _r.nextDouble();

  double normal() {
    if (_spare != null) {
      final s = _spare!;
      _spare = null;
      return s;
    }
    double u, v, s;
    do {
      u = _r.nextDouble() * 2 - 1;
      v = _r.nextDouble() * 2 - 1;
      s = u * u + v * v;
    } while (s >= 1 || s == 0);
    final m = math.sqrt(-2 * math.log(s) / s);
    _spare = v * m;
    return u * m;
  }

  int poisson(double lambda) {
    if (lambda <= 0) return 0;
    if (lambda > 30) {
      final x = (lambda + math.sqrt(lambda) * normal()).round();
      return x < 0 ? 0 : x;
    }
    final l = math.exp(-lambda);
    var k = 0;
    var p = 1.0;
    do {
      k++;
      p *= _r.nextDouble();
    } while (p > l);
    return k - 1;
  }
}
