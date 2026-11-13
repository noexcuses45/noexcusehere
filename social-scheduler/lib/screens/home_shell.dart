import 'package:material_ui/material_ui.dart';
import 'package:url_launcher/url_launcher.dart';

import '../app_state.dart';
import '../services/update_service.dart';
import 'accounts_screen.dart';
import 'composer_screen.dart';
import 'post_detail_screen.dart';
import 'posts_screen.dart';
import 'schedule_screen.dart';
import 'tools_screen.dart';

class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.state, required this.onSignOut});

  final AppState state;
  final Future<void> Function() onSignOut;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> with WidgetsBindingObserver {
  int _tab = 0;
  AppUpdate? _update;

  AppState get state => widget.state;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    state.notifications.onOpenPost = _openPost;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final launched = state.notifications.launchPostId;
      if (launched != null) {
        state.notifications.launchPostId = null;
        _openPost(launched);
      }
    });
    if (!state.repo.isDemo) {
      UpdateService.check().then((u) {
        if (mounted && u != null) setState(() => _update = u);
      });
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    if (state.notifications.onOpenPost == _openPost) state.notifications.onOpenPost = null;
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState lifecycle) {
    // Coming back from the browser after connecting an account, or simply
    // reopening the app: pick up whatever changed on the server.
    if (lifecycle == AppLifecycleState.resumed) {
      state.refreshAccounts();
      state.refreshPosts();
    }
  }

  Future<void> _openPost(String id) async {
    if (state.post(id) == null) await state.refreshPosts();
    if (!mounted || state.post(id) == null) return;
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => PostDetailScreen(state: state, postId: id)));
  }

  Future<void> _compose({DateTime? day}) async {
    await Navigator.of(context).push(
      MaterialPageRoute(fullscreenDialog: true, builder: (_) => ComposerScreen(state: state, initialDay: day)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        final attention = state.attention(DateTime.now()).length;
        final reconnect = state.accounts.where((a) => a.needsReconnect).length;
        return Scaffold(
          body: Column(
            children: [
              if (_update != null)
                MaterialBanner(
                  content: Text('${_update!.name} is ready to install.'),
                  leading: const Icon(Icons.system_update),
                  actions: [
                    TextButton(onPressed: () => setState(() => _update = null), child: const Text('Later')),
                    TextButton(
                      onPressed: () => launchUrl(Uri.parse(_update!.url), mode: LaunchMode.externalApplication),
                      child: const Text('Download'),
                    ),
                  ],
                ),
              Expanded(
                child: IndexedStack(
                  index: _tab,
                  children: [
                    ScheduleScreen(state: state, onOpenPost: _openPost, onCompose: _compose),
                    PostsScreen(state: state, onOpenPost: _openPost, onCompose: _compose),
                    AccountsScreen(state: state),
                    ToolsScreen(state: state, onSignOut: widget.onSignOut),
                  ],
                ),
              ),
            ],
          ),
          floatingActionButton: _tab <= 1
              ? FloatingActionButton.extended(
                  onPressed: _compose,
                  icon: const Icon(Icons.add),
                  label: const Text('New post'),
                )
              : null,
          bottomNavigationBar: NavigationBar(
            selectedIndex: _tab,
            onDestinationSelected: (i) => setState(() => _tab = i),
            destinations: [
              const NavigationDestination(
                icon: Icon(Icons.calendar_month_outlined),
                selectedIcon: Icon(Icons.calendar_month),
                label: 'Calendar',
              ),
              NavigationDestination(
                icon: Badge(
                  isLabelVisible: attention > 0,
                  label: Text('$attention'),
                  child: const Icon(Icons.dynamic_feed_outlined),
                ),
                selectedIcon: const Icon(Icons.dynamic_feed),
                label: 'Posts',
              ),
              NavigationDestination(
                icon: Badge(
                  isLabelVisible: reconnect > 0,
                  label: Text('$reconnect'),
                  child: const Icon(Icons.link_outlined),
                ),
                selectedIcon: const Icon(Icons.link),
                label: 'Accounts',
              ),
              const NavigationDestination(
                icon: Icon(Icons.tune_outlined),
                selectedIcon: Icon(Icons.tune),
                label: 'Tools',
              ),
            ],
          ),
        );
      },
    );
  }
}
