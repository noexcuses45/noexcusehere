import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../theme.dart';

String pct(double p) => '${(p * 100).round()}%';
String odds(double o) => o >= 100 ? o.toStringAsFixed(0) : (o >= 10 ? o.toStringAsFixed(1) : o.toStringAsFixed(2));
/// A dividend or payout, with cents and thousands separators.
String payout(double v) => '\$${NumberFormat('#,##0.00').format(v)}';

/// A rounded estimate of a payout, to about two or three figures.
String roughly(double v) {
  if (v < 20) return '\$${v.toStringAsFixed(v < 10 ? 2 : 1)}';
  final step = v < 200 ? 1 : (v < 1000 ? 5 : (v < 10000 ? 50 : 500));
  return '\$${NumberFormat('#,##0').format((v / step).round() * step)}';
}

String ordinal(int n) {
  if (n % 100 >= 11 && n % 100 <= 13) return '${n}th';
  return switch (n % 10) { 1 => '${n}st', 2 => '${n}nd', 3 => '${n}rd', _ => '${n}th' };
}

String num1(double x) => x == x.roundToDouble() ? x.toStringAsFixed(0) : x.toStringAsFixed(1);
String timeLocal(DateTime d) => DateFormat('EEE h:mma').format(d.toLocal()).replaceAll('AM', 'am').replaceAll('PM', 'pm');
String clockLocal(DateTime d) => DateFormat('h:mma').format(d.toLocal()).replaceAll('AM', 'am').replaceAll('PM', 'pm');
String dayLabel(DateTime d) {
  final now = DateTime.now();
  final today = DateTime(now.year, now.month, now.day);
  final day = DateTime(d.year, d.month, d.day);
  final diff = day.difference(today).inDays;
  if (diff == 0) return 'Today';
  if (diff == 1) return 'Tomorrow';
  if (diff == -1) return 'Yesterday';
  return DateFormat('EEE d MMM').format(d);
}

Color probColor(double p) {
  if (p >= 0.7) return EdgeColors.win;
  if (p >= 0.55) return EdgeColors.lime;
  if (p >= 0.4) return EdgeColors.sky;
  return EdgeColors.muted;
}

class ProbPill extends StatelessWidget {
  const ProbPill(this.p, {super.key, this.big = false});
  final double p;
  final bool big;
  @override
  Widget build(BuildContext context) {
    final c = probColor(p);
    return Container(
      padding: EdgeInsets.symmetric(horizontal: big ? 12 : 8, vertical: big ? 6 : 3),
      decoration: BoxDecoration(color: c.withValues(alpha: 0.18), borderRadius: BorderRadius.circular(20)),
      child: Text(pct(p), style: TextStyle(color: c, fontWeight: FontWeight.w800, fontSize: big ? 18 : 13)),
    );
  }
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key, this.trailing});
  final String text;
  final Widget? trailing;
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 18, 4, 8),
      child: Row(children: [
        Expanded(child: Text(text, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800, letterSpacing: 0.2))),
        ?trailing,
      ]),
    );
  }
}

class Muted extends StatelessWidget {
  const Muted(this.text, {super.key, this.size = 12});
  final String text;
  final double size;
  @override
  Widget build(BuildContext context) => Text(text, style: TextStyle(color: EdgeColors.muted, fontSize: size));
}

class InfoBanner extends StatelessWidget {
  const InfoBanner(this.text, {super.key, this.icon = Icons.info_outline});
  final String text;
  final IconData icon;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.05), borderRadius: BorderRadius.circular(12)),
        child: Row(children: [
          Icon(icon, size: 18, color: EdgeColors.muted),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: const TextStyle(color: EdgeColors.muted, fontSize: 12.5))),
        ]),
      );
}

class ErrorRetry extends StatelessWidget {
  const ErrorRetry(this.message, {super.key, required this.onRetry});
  final String message;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.cloud_off, size: 40, color: EdgeColors.muted),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center, style: const TextStyle(color: EdgeColors.muted)),
            const SizedBox(height: 12),
            FilledButton(onPressed: onRetry, child: const Text('Try again')),
          ]),
        ),
      );
}

/// A multi leg row: label on the left, probability pill on the right.
class LegRow extends StatelessWidget {
  const LegRow(this.label, this.prob, {super.key, this.sub});
  final String label;
  final double prob;
  final String? sub;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          const Icon(Icons.check_circle, size: 16, color: EdgeColors.limeDark),
          const SizedBox(width: 8),
          Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: const TextStyle(fontWeight: FontWeight.w600)),
            if (sub != null) Muted(sub!),
          ])),
          ProbPill(prob),
        ]),
      );
}
