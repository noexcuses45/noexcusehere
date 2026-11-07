import 'package:flutter/material.dart';

import '../models/league.dart';
import '../services/cache.dart';
import '../services/settings_service.dart';
import '../theme.dart';
import '../widgets/common.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  @override
  Widget build(BuildContext context) {
    final s = Settings.instance;
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(padding: const EdgeInsets.fromLTRB(12, 0, 12, 32), children: [
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
