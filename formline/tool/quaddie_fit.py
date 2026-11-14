"""How well do win prices predict what a quaddie pays?

The feed gives the real quaddie dividend after the last leg but no live
"will pay" figures, so the app estimates them:

    quaddie for $1  ~=  factor x (win dividend of each leg already run)
                               x (fixed win odds of the horse in each leg still to run)

This fits the factor on finished quaddies in a folder of cached event
payloads (event_<id>.json, as saved by tool/fit_racing.dart) and reports how
close the estimate was, for 0 to 3 legs still to run.

Usage: python3 tool/quaddie_fit.py <cacheDir>
"""
import collections
import glob
import json
import math
import statistics
import sys

cache = sys.argv[1]
meetings = collections.defaultdict(dict)
for f in glob.glob(cache + '/event_*.json'):
    try:
        d = json.load(open(f))['data']
    except Exception:
        continue
    r = d.get('race') or {}
    if r.get('status') == 'Final':
        meetings[r.get('meeting_id')][r.get('race_number')] = d


def win_dividend(d, number):
    for x in d.get('dividends') or []:
        if x['product_name'] == 'Pool Win' and x['status'] == 'Final' and x['positions'][0]['runner_number'] == number:
            return x['dividend']
    return None


def fixed_odds(d, number):
    for ru in d.get('runners') or []:
        if ru.get('runner_number') == number:
            return (ru.get('odds') or {}).get('fixed_win')
    return None


rows = []  # (paid, [win dividends], [fixed odds])
for races in meetings.values():
    for n, d in races.items():
        for x in d.get('dividends') or []:
            if x['product_name'] != 'Pool Quadrella' or x['status'] != 'Final' or 'concession' in x['description'] or x['dividend'] <= 0:
                continue
            legs = [n - 3, n - 2, n - 1, n]
            for t in d.get('tote_pools') or []:
                if t['product_type'] == 'Quadrella' and t.get('quaddie'):
                    legs = t['quaddie']
            winners = [p['runner_number'] for p in x['positions']]
            if len(winners) != 4 or not all(l in races for l in legs):
                continue
            divs = [win_dividend(races[l], w) for l, w in zip(legs, winners)]
            fixed = [fixed_odds(races[l], w) for l, w in zip(legs, winners)]
            if any(not v or v <= 1 for v in divs + fixed):
                continue
            rows.append((x['dividend'], divs, fixed))

print(f'{len(meetings)} meetings, {len(rows)} quaddies with all four legs')
out = []
for pending in range(4):
    def product(r):
        p = 1.0
        for i in range(4):
            p *= r[2][i] if i >= 4 - pending else r[1][i]
        return p
    factor = math.exp(statistics.median(math.log(r[0] / product(r)) for r in rows))
    err = [abs(math.log(r[0] / (factor * product(r)))) for r in rows]
    within = lambda f: sum(1 for e in err if e <= math.log(f)) / len(err)
    print(f'{pending} legs to run: factor {factor:.2f}; within 1.5x {within(1.5) * 100:.0f}%, within 2x {within(2) * 100:.0f}%, within 3x {within(3) * 100:.0f}%')
    out.append(f'    [{factor:.2f}, {within(1.5):.2f}, {within(2):.2f}],')
print('  static const samples = %d;' % len(rows))
print('  static const List<List<double>> byPending = [\n' + '\n'.join(out) + '\n  ];')
