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
  and the top three for every race with fair odds vs the TAB price and a VALUE tag.
- **My picks** — save any multi or horse; it is graded automatically once the event finishes, so you
  can see hit rate vs the model's expected rate and your return.

## How the model works

Team sports: every team's recent results are fitted to a multiplicative offence/defence rating
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
