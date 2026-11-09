"""Turns backtest result files (tool/backtest_sports.dart) into lib/engine/calibration.dart.
Usage: python3 tool/gen_calibration.py <resultsDir> lib/engine/calibration.dart
"""
import glob
import json
import statistics
import sys

GROUP = {
    'nba': 'Basketball', 'nbl': 'Basketball', 'wnba': 'Basketball',
    'nrl': 'Rugby League', 'afl': 'Aussie Rules', 'nfl': 'American Football',
    'nhl': 'Ice Hockey', 'mlb': 'Baseball',
    'super-rugby': 'Rugby Union', 'prem-rugby': 'Rugby Union', 'urc': 'Rugby Union', 'top14': 'Rugby Union',
}

results = {}
for f in sorted(glob.glob(sys.argv[1] + '/_results_*.json')):
    results.update(json.load(open(f)))

def params(r):
    return (f"ModelParams(halfLifeMult: {r['halfLifeMult']}, k0: {r['k0']}, longMix: {r['longMix']}, kAvg: {r['kAvg']}, "
            f"totalScale: {r['totalScale']:.2f}, marginScale: {r['marginScale']:.2f})")

groups = {}
for lid, r in results.items():
    groups.setdefault(GROUP.get(lid, 'Soccer'), []).append(r)

def median_params(rs):
    return {k: statistics.median(r[k] for r in rs) for k in ('halfLifeMult', 'k0', 'longMix', 'kAvg', 'totalScale', 'marginScale')}

src = open(sys.argv[2]).read()
head = src[:src.index('class Calibration {')]
out = [head, 'class Calibration {\n', '  static const fallback = ModelParams();\n\n', '  static const Map<String, ModelParams> leagues = {\n']
for lid, r in results.items():
    out.append(f"    '{lid}': {params(r)},\n")
out.append('  };\n\n  /// Typical settings per sport for competitions that were not backtested.\n  static const Map<String, ModelParams> groups = {\n')
for g, rs in groups.items():
    out.append(f"    '{g}': {params(median_params(rs))},\n")
out.append('  };\n\n  static const Map<String, BacktestResult> backtests = {\n')
for lid, r in results.items():
    said = ', '.join(f'{x:.3f}' for x in r['said'])
    won = ', '.join(f'{x:.3f}' for x in r['won'])
    binn = ', '.join(str(int(x)) for x in r['binN'])
    out.append(f"    '{lid}': BacktestResult(games: {r['games']}, accuracy: {r['accuracy']:.4f}, totalRmse: {r['totalRmse']:.2f}, "
               f"baseTotalRmse: {r['baseTotalRmse']:.2f}, said: [{said}], won: [{won}], binN: [{binn}]),\n")
out.append('  };\n\n  static ModelParams forLeague(League league) => leagues[league.id] ?? groups[league.group] ?? fallback;\n}\n')
open(sys.argv[2], 'w').write(''.join(out))
print('leagues:', len(results), 'groups:', list(groups))
