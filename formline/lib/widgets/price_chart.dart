import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../engine/stocks.dart';
import '../theme.dart';

/// A simple line chart: price, its 200-day average, and optional level lines.
class PriceChart extends StatelessWidget {
  const PriceChart({super.key, required this.series, this.days = 250, this.levels = const {}});
  final Series series;
  final int days;
  final Map<String, (double, Color)> levels; // label -> (price, colour)

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 200,
      child: CustomPaint(painter: _ChartPainter(series, days, levels), size: Size.infinite),
    );
  }
}

class _ChartPainter extends CustomPainter {
  _ChartPainter(this.s, this.days, this.levels);
  final Series s;
  final int days;
  final Map<String, (double, Color)> levels;

  @override
  void paint(Canvas canvas, Size size) {
    final n = s.n;
    final start = math.max(0, n - days);
    if (n - start < 2) return;
    final ma = s.sma(200);
    var lo = double.infinity, hi = -double.infinity;
    for (var i = start; i < n; i++) {
      lo = math.min(lo, s.close(i));
      hi = math.max(hi, s.close(i));
      if (!ma[i].isNaN) {
        lo = math.min(lo, ma[i]);
        hi = math.max(hi, ma[i]);
      }
    }
    for (final l in levels.values) {
      if (l.$1 > 0 && l.$1 > lo * 0.8 && l.$1 < hi * 1.2) {
        lo = math.min(lo, l.$1);
        hi = math.max(hi, l.$1);
      }
    }
    final pad = (hi - lo) * 0.08 + 1e-9;
    lo -= pad;
    hi += pad;
    const right = 54.0;
    final w = size.width - right, h = size.height;
    double x(int i) => (i - start) / (n - 1 - start) * w;
    double y(double v) => h - (v - lo) / (hi - lo) * h;

    final grid = Paint()
      ..color = Colors.white10
      ..strokeWidth = 1;
    final label = TextPainter(textDirection: TextDirection.ltr);
    for (var k = 0; k <= 3; k++) {
      final v = lo + (hi - lo) * k / 3;
      canvas.drawLine(Offset(0, y(v)), Offset(w, y(v)), grid);
      label.text = TextSpan(text: _fmt(v), style: const TextStyle(color: EdgeColors.muted, fontSize: 10));
      label.layout();
      label.paint(canvas, Offset(w + 6, y(v) - 6));
    }

    Path line(double Function(int) val, bool Function(int) ok) {
      final p = Path();
      var started = false;
      for (var i = start; i < n; i++) {
        if (!ok(i)) continue;
        if (!started) {
          p.moveTo(x(i), y(val(i)));
          started = true;
        } else {
          p.lineTo(x(i), y(val(i)));
        }
      }
      return p;
    }

    canvas.drawPath(
        line((i) => ma[i], (i) => !ma[i].isNaN),
        Paint()
          ..color = EdgeColors.sky.withValues(alpha: 0.7)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.4);
    final up = s.close(n - 1) >= s.close(start);
    canvas.drawPath(
        line((i) => s.close(i), (i) => true),
        Paint()
          ..color = up ? EdgeColors.lime : EdgeColors.coral
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2
          ..strokeJoin = StrokeJoin.round);

    levels.forEach((name, l) {
      if (l.$1 <= lo || l.$1 >= hi) return;
      final paint = Paint()
        ..color = l.$2.withValues(alpha: 0.8)
        ..strokeWidth = 1;
      for (var dx = 0.0; dx < w; dx += 8) {
        canvas.drawLine(Offset(dx, y(l.$1)), Offset(math.min(dx + 4, w), y(l.$1)), paint);
      }
      label.text = TextSpan(text: name, style: TextStyle(color: l.$2, fontSize: 10, fontWeight: FontWeight.w700));
      label.layout();
      label.paint(canvas, Offset(w - label.width - 4, y(l.$1) - 13));
    });
  }

  static String _fmt(double v) => v >= 100 ? v.toStringAsFixed(0) : (v >= 10 ? v.toStringAsFixed(1) : v.toStringAsFixed(2));

  @override
  bool shouldRepaint(covariant _ChartPainter old) => old.s != s || old.days != days || old.levels != levels;
}
