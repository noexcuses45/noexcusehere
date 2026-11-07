import 'package:flutter/material.dart';

import 'screens/picks_screen.dart';
import 'screens/racing_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/sports_screen.dart';
import 'services/picks_store.dart';
import 'services/settings_service.dart';
import 'services/update_service.dart';
import 'theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Settings.instance.load();
  await PicksStore.instance.load();
  runApp(const FormlineApp());
}

class FormlineApp extends StatelessWidget {
  const FormlineApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Formline',
      theme: edgeTheme(),
      debugShowCheckedModeBanner: false,
      home: const MainShell(),
    );
  }
}

class MainShell extends StatefulWidget {
  const MainShell({super.key});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  int _index = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => UpdateService.checkForUpdate(context));
  }

  @override
  Widget build(BuildContext context) {
    final screens = const [SportsScreen(), RacingScreen(), PicksScreen()];
    return Scaffold(
      appBar: AppBar(
        title: const Row(children: [
          Icon(Icons.query_stats, color: EdgeColors.lime),
          SizedBox(width: 8),
          Text('Formline', style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 0.5)),
        ]),
        actions: [
          IconButton(
            icon: const Icon(Icons.tune),
            tooltip: 'Settings',
            onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SettingsScreen())),
          ),
        ],
      ),
      body: IndexedStack(index: _index, children: screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.sports_basketball_outlined), selectedIcon: Icon(Icons.sports_basketball), label: 'Sports'),
          NavigationDestination(icon: Icon(Icons.flag_outlined), selectedIcon: Icon(Icons.flag), label: 'Racing'),
          NavigationDestination(icon: Icon(Icons.bookmark_outline), selectedIcon: Icon(Icons.bookmark), label: 'My picks'),
        ],
      ),
    );
  }
}
