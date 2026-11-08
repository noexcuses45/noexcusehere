import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../models/league.dart';
import '../services/cache.dart';
import '../services/odds_api.dart';
import '../services/settings_service.dart';
import '../theme.dart';
import '../widgets/common.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final TextEditingController _keyCtrl = TextEditingController(text: Settings.instance.oddsApiKey);

  static const bookies = {
    'sportsbet': 'Sportsbet',
    'tab': 'TAB',
    'ladbrokes_au': 'Ladbrokes',
    'neds': 'Neds',
    'pointsbetau': 'PointsBet',
    'unibet': 'Unibet',
    'betr_au': 'Betr',
    'playup': 'PlayUp',
    'picklebet_au': 'Picklebet',
    'betright': 'Bet Right',
    'tabtouch': 'TABtouch',
    'bet365_au': 'Bet365 (paid feed plans only)',
    'dabble_au': 'Dabble (paid feed plans only)',
    'betfair_ex_au': 'Betfair Exchange',
  };

  @override
  void dispose() {
    _keyCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = Settings.instance;
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(padding: const EdgeInsets.fromLTRB(12, 0, 12, 32), children: [
        const SectionTitle('Bookmaker odds'),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Muted('Prices for Sportsbet, TAB, Ladbrokes, Neds, PointsBet, Unibet, Betr and more come from The Odds API. Sign up there for a free key (no card needed), paste it here, and the app compares every bookie against the model.'),
              const SizedBox(height: 10),
              TextField(
                controller: _keyCtrl,
                obscureText: true,
                decoration: InputDecoration(
                  labelText: 'Odds API key',
                  border: const OutlineInputBorder(),
                  isDense: true,
                  suffixIcon: IconButton(
                    icon: const Icon(Icons.save_outlined),
                    tooltip: 'Save key',
                    onPressed: () {
                      s.oddsApiKey = _keyCtrl.text.trim();
                      s.save();
                      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Key saved')));
                      setState(() {});
                    },
                  ),
                ),
                onSubmitted: (v) {
                  s.oddsApiKey = v.trim();
                  s.save();
                  setState(() {});
                },
              ),
              const SizedBox(height: 8),
              Row(children: [
                TextButton.icon(
                  onPressed: () => launchUrl(Uri.parse('https://the-odds-api.com/'), mode: LaunchMode.externalApplication),
                  icon: const Icon(Icons.open_in_new, size: 16),
                  label: const Text('Get a free key at the-odds-api.com'),
                ),
                const Spacer(),
                if (OddsApi.instance.remainingCredits != null) Muted('${OddsApi.instance.remainingCredits} credits left'),
              ]),
              const Muted('Each league you open costs 3 credits and is cached for 20 minutes. The free plan gives 500 a month.'),
            ]),
          ),
        ),
        ListTile(
          title: const Text('Your bookmaker'),
          subtitle: const Muted('Used for the "best multi to take" and value flags.'),
          trailing: DropdownButton<String>(
            value: bookies.containsKey(s.bookie) ? s.bookie : 'sportsbet',
            dropdownColor: EdgeColors.navyCard,
            items: [for (final e in bookies.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
            onChanged: (v) {
              if (v == null) return;
              s.bookie = v;
              s.save();
              setState(() {});
            },
          ),
        ),
        const SectionTitle('Model'),
        SwitchListTile(
          title: const Text('Show bookmaker lines for comparison'),
          subtitle: const Muted('Never used by the model; only to show where it disagrees.'),
          value: s.showMarket,
          activeThumbColor: EdgeColors.lime,
          onChanged: (v) {
            s.showMarket = v;
            s.save();
            setState(() {});
          },
        ),
        ListTile(
          title: const Text('Simulation detail'),
          subtitle: const Muted('More simulations = steadier multi probabilities, slower on old phones.'),
          trailing: DropdownButton<int>(
            value: s.sims,
            dropdownColor: EdgeColors.navyCard,
            items: const [
              DropdownMenuItem(value: 5000, child: Text('Fast')),
              DropdownMenuItem(value: 20000, child: Text('Normal')),
              DropdownMenuItem(value: 50000, child: Text('Precise')),
            ],
            onChanged: (v) {
              if (v == null) return;
              s.sims = v;
              s.save();
              setState(() {});
            },
          ),
        ),
        ListTile(
          title: const Text('Clear cached results'),
          subtitle: const Muted('Forces fresh downloads of schedules and ratings.'),
          trailing: const Icon(Icons.delete_sweep_outlined),
          onTap: () async {
            await Cache.clearAll();
            if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Cache cleared')));
          },
        ),
        const SectionTitle('Leagues on your home row'),
        for (final group in Leagues.groups) ...[
          Padding(padding: const EdgeInsets.fromLTRB(16, 10, 0, 2), child: Muted(group, size: 13)),
          for (final l in Leagues.all.where((l) => l.group == group))
            CheckboxListTile(
              dense: true,
              title: Text(l.name),
              value: s.favourites.contains(l.id),
              activeColor: EdgeColors.lime,
              checkColor: EdgeColors.navy,
              onChanged: (_) {
                s.toggleFavourite(l.id);
                setState(() {});
              },
            ),
        ],
      ]),
    );
  }
}
