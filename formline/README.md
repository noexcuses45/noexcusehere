# Formline — stats-only predictor for overs/unders, same-game multis and racing

Formline rates teams and horses from **actual results and form**, never from bookmaker prices.
It then tells you, in plain numbers, the best multi for a round, the best same-game multi for
each game, the over/under lean at any line, and the best horses per leg for a quaddie.

What it shows:

- **Sports tab** — pick a competition (NBA, NBL, NRL, AFL, NFL, NHL, MLB, rugby union, 40+ soccer
  leagues worldwide, college sports, lacrosse) and a day. Live scores refresh every 30 seconds.
  Each game shows the model's projected score, total, win chances, live projection when in play,
  and the **best same-game multi**. The top card is the **best multi for the round**.
- **Game screen** — over/under at any line you type, suggested multis with joint probability and
  fair odds, player props (points, rebounds, assists, threes, PRA) for basketball, injuries, and a
  build-your-own multi where leg correlation is handled by simulation.
- **Racing tab** — thoroughbred, harness and greyhound meetings worldwide (AUS, NZ, UK, IRE, FR,
  USA, SAF...). Each meeting shows **quaddie picks** (top-rated runners per leg, combos, chance)
  and the top three for every race with fair odds vs the TAB price and a VALUE tag. After each race
  the card shows the placings with win, place and exotic dividends, and how the model's top pick
  went (results come in by themselves while the meeting is open). The quaddie card follows the
  TAB's own four legs: the winner and win dividend of each leg, whether our picks are still alive,
  what the quaddie **paid**, and while it is running what a $1 quaddie **would pay** if each horse
  wins the next leg. The feed has no live will-pays, so those are estimated from win prices
  (`lib/engine/quaddie_value.dart`); `tool/quaddie_fit.py` checked the estimate on 72 finished
  quaddies, where it was within double or half the real payout about nine times in ten.
- **My picks** — save any multi or horse; it is graded automatically once the event finishes, so you
  can see hit rate vs the model's expected rate and your return.

## Shares (ASX)

A separate **Shares** tab scans about 120 large ASX companies using the exchange's own daily price
history and shows two rule-based lists, each with exact buy and sell rules:

- **Short-term trades (days):** a sharp dip (2-day RSI under 10) in a share that is in the top 10% by
  six-month momentum and above its 200-day average. Sell when it closes back above its 5-day average,
  after 10 trading days, or if it closes 12% below the buy price.
- **Leaders to hold (weeks to months):** buy a share when it enters the top 10 by six-month momentum
  while above its 200-day average; sell when it drops out of the top 25 or closes below that average.

`tool/backtest_stocks.dart` replays these exact rules (the app and the backtest share
`lib/engine/stock_scanner.dart`) over ten years and also reports the ideas that were tested and left
out because they did not beat costs. Results are baked into `lib/engine/stock_calibration.dart` with
`tool/gen_stock_calibration.py` and shown on the page. The page takes your trade size and brokerage
and tells you whether the short-term edge survives your costs. Prices are daily closes, so this is
end-of-day trading, not minute-by-minute day trading. General information only, not financial advice.

**My plan and Today.** Enter a starting amount and pick a way to trade; the **Today** card then gives
a routine for the day with Sydney times (and the phone's own time if it is on another time zone):

- *Leaders to hold:* at 10:15am, what to sell, what to hold, and what to buy with how many dollars.
- *Short-term trades:* at 3:50pm, ten minutes before the close, because the rule was tested on closing
  prices. In the morning the card already shows the price each leading share must be under at 3:50pm to
  be a buy, and for a share you hold, the price above which it is a sell (otherwise hold another day).
  These lines are worked out from earlier closes in `lib/engine/day_plan.dart`, and a test checks they
  agree with the scanner the backtest uses.

`tool/plan_backtest.dart` and `tool/short_backtest.dart` replay both routines for a real account
(whole shares, CMC Invest brokerage) at each starting amount, and the page shows the result for your
amount. With CMC's $11 minimum fee the leaders plan pays from about $2,000 and short-term trading from
about $7,500; under $2,000 short-term trading lost the whole account to brokerage. A true one-day trade
(sell at the next close) made only +0.14% a trade before brokerage, so it is listed as tested and left out.

## How good is it?

The app keeps itself honest in two ways, both shown under **My picks → Model record**:

- **Backtest.** `tool/backtest_sports.dart` replays about three seasons per league, predicting every
  game from earlier games only, tunes the model's settings and measures how often a "70%" call wins.
  The tuned settings live in `lib/engine/calibration.dart` (regenerate with `tool/gen_calibration.py`).
- **Live record.** Every prediction the app makes (sports and racing top picks) is logged and graded
  automatically once the event finishes: winners right vs expected, average miss on totals, the
  model's side of the bookmaker total and line, and racing strike rate and return. If a league's
  totals run consistently over or under projection, projections are nudged to correct it.

## How the model works

Team sports: the whole league's results over the last 14 months (one request per month) are fitted to a multiplicative offence/defence rating
model (opponent-adjusted, recency-weighted, shrunk to league priors, home advantage estimated from
the data). Scores are modelled as correlated normals (basketball, football codes) or Poisson
(soccer, hockey, baseball). Multis are priced by Monte Carlo simulation so correlated legs
(e.g. home win + over) are priced honestly. Bookmaker lines from the feed are displayed only to
show where the model disagrees.

Racing: a form rating from the last 20 starts (recency-weighted placings), career strike rate,
prizemoney per start (class), jockey and trainer strike rates, barrier, weight, speed map and gear.
Ratings become win probabilities via a softmax; place probabilities come from Plackett-Luce
simulation. Quaddie legs take runners until ~60% of the win chance is covered (standouts alone).

Data: ESPN's public site API (fixtures, live scores, results, box scores) and the TAB NZ affiliates
racing feed (fields, form, results). No keys required.

## Get the APK

Push to `main`/`master` (or run the "Build Formline APK" workflow manually). GitHub Actions builds
the APK and attaches it to the run under Artifacts, and publishes it as a pre-release tagged
`formline-vN`. The app checks those pre-releases and offers one-tap updates.

Build locally:

```
cd formline
flutter create --platforms android --org com.noexcusehere --project-name formline .
flutter pub get
flutter build apk --release
```

Run the tests with `flutter test`.

Nothing here is betting advice. Probabilities are model estimates; bet responsibly.
