import 'package:material_ui/material_ui.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'app_state.dart';
import 'config.dart';
import 'data/demo_repository.dart';
import 'data/repository.dart';
import 'data/supabase_repository.dart';
import 'screens/auth_screen.dart';
import 'screens/home_shell.dart';
import 'services/notification_service.dart';
import 'theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (AppConfig.hasBackend) {
    await Supabase.initialize(url: AppConfig.supabaseUrl, publishableKey: AppConfig.supabaseKey);
  }
  await NotificationService.instance.init();
  runApp(const SchedulerApp());
}

class SchedulerApp extends StatefulWidget {
  const SchedulerApp({super.key, this.initialRepository});

  /// Lets tests start straight inside the app with a prepared repository.
  final Repository? initialRepository;

  @override
  State<SchedulerApp> createState() => _SchedulerAppState();
}

class _SchedulerAppState extends State<SchedulerApp> {
  AppState? _state;
  bool _restoring = true;

  @override
  void initState() {
    super.initState();
    _restore();
  }

  Future<void> _restore() async {
    if (widget.initialRepository != null) {
      _enter(widget.initialRepository!);
      return;
    }
    if (AppConfig.hasBackend && Supabase.instance.client.auth.currentSession != null) {
      final repo = SupabaseRepository(Supabase.instance.client);
      try {
        if (await repo.isMember()) {
          _enter(repo);
          return;
        }
      } catch (_) {
        // Offline at start-up: still open the app; data loads when back online.
        _enter(repo);
        return;
      }
    }
    if (mounted) setState(() => _restoring = false);
  }

  void _enter(Repository repo) {
    final state = AppState(repo);
    setState(() {
      _state = state;
      _restoring = false;
    });
    state.loadAll();
  }

  Future<void> _signOut() async {
    final state = _state;
    if (state == null) return;
    try {
      await state.repo.signOut();
    } catch (_) {}
    await state.notifications.syncReminders(const []);
    setState(() => _state = null);
    state.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final Widget home;
    if (_restoring) {
      home = const Scaffold(body: Center(child: CircularProgressIndicator()));
    } else if (_state == null) {
      home = AuthScreen(
        onSignedIn: _enter,
        onDemo: () => _enter(DemoRepository()),
      );
    } else {
      home = HomeShell(key: ValueKey(_state), state: _state!, onSignOut: _signOut);
    }
    return MaterialApp(
      title: AppConfig.appName,
      debugShowCheckedModeBanner: false,
      theme: appTheme(Brightness.light),
      darkTheme: appTheme(Brightness.dark),
      home: home,
    );
  }
}
