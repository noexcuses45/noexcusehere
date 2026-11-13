import 'package:material_ui/material_ui.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../config.dart';
import '../data/repository.dart';
import '../data/supabase_repository.dart';
import '../models.dart';

/// Sign in, or (the very first time) create the owner account.
class AuthScreen extends StatefulWidget {
  const AuthScreen({super.key, required this.onSignedIn, required this.onDemo});

  final void Function(Repository repo) onSignedIn;
  final VoidCallback onDemo;

  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  final _form = GlobalKey<FormState>();

  SupabaseRepository? _repo;
  bool? _hasOwner; // null while checking
  bool _busy = false;
  bool _hide = true;
  bool _statusFailed = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (AppConfig.hasBackend) {
      _repo = SupabaseRepository(Supabase.instance.client);
      _checkOwner();
    }
  }

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _checkOwner() async {
    setState(() {
      _hasOwner = null;
      _error = null;
      _statusFailed = false;
    });
    try {
      final has = await _repo!.hasOwner();
      if (mounted) setState(() => _hasOwner = has);
    } on AppException catch (e) {
      // Assume the usual case (sign in) and show why the check failed.
      if (mounted) {
        setState(() {
          _hasOwner = true;
          _statusFailed = true;
          _error = e.message;
        });
      }
    }
  }

  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    final repo = _repo!;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final email = _email.text.trim();
      if (_hasOwner == false) {
        await repo.claimOwner(email, _password.text);
      } else {
        await repo.signIn(email, _password.text);
        if (!await repo.isMember()) {
          await repo.signOut();
          throw AppException('This account does not have access to this app.');
        }
      }
      if (mounted) widget.onSignedIn(repo);
    } on AppException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final creating = _hasOwner == false;

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Icon(Icons.event_available, size: 52, color: theme.colorScheme.primary),
                  const SizedBox(height: 12),
                  Text(
                    AppConfig.appName,
                    textAlign: TextAlign.center,
                    style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Plan once, post to Instagram, Facebook, TikTok, YouTube and LinkedIn.',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                  ),
                  const SizedBox(height: 28),
                  if (!AppConfig.hasBackend)
                    _NoBackend(onDemo: widget.onDemo)
                  else if (_hasOwner == null)
                    const Padding(
                      padding: EdgeInsets.all(24),
                      child: Center(child: CircularProgressIndicator()),
                    )
                  else
                    Form(
                      key: _form,
                      child: AutofillGroup(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Text(
                              creating ? 'Create your owner account' : 'Sign in',
                              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                            ),
                            if (creating) ...[
                              const SizedBox(height: 4),
                              Text(
                                'This is a one-time step. Whoever creates this account owns the app; '
                                'after that, new sign-ups are closed.',
                                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                              ),
                            ],
                            const SizedBox(height: 14),
                            TextFormField(
                              controller: _email,
                              keyboardType: TextInputType.emailAddress,
                              autofillHints: const [AutofillHints.email],
                              textInputAction: TextInputAction.next,
                              decoration: const InputDecoration(labelText: 'Email'),
                              validator: (v) => (v == null || !RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(v.trim()))
                                  ? 'Enter your email address'
                                  : null,
                            ),
                            const SizedBox(height: 12),
                            TextFormField(
                              controller: _password,
                              obscureText: _hide,
                              autofillHints: [creating ? AutofillHints.newPassword : AutofillHints.password],
                              textInputAction: creating ? TextInputAction.next : TextInputAction.done,
                              onFieldSubmitted: (_) => creating ? null : _submit(),
                              decoration: InputDecoration(
                                labelText: 'Password',
                                suffixIcon: IconButton(
                                  tooltip: _hide ? 'Show password' : 'Hide password',
                                  icon: Icon(_hide ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                                  onPressed: () => setState(() => _hide = !_hide),
                                ),
                              ),
                              validator: (v) {
                                if (v == null || v.isEmpty) return 'Enter your password';
                                if (creating && v.length < 8) return 'Use at least 8 characters';
                                return null;
                              },
                            ),
                            if (creating) ...[
                              const SizedBox(height: 12),
                              TextFormField(
                                controller: _confirm,
                                obscureText: _hide,
                                textInputAction: TextInputAction.done,
                                onFieldSubmitted: (_) => _submit(),
                                decoration: const InputDecoration(labelText: 'Repeat password'),
                                validator: (v) => v != _password.text ? 'The passwords do not match' : null,
                              ),
                            ],
                            if (_error != null) ...[
                              const SizedBox(height: 12),
                              Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
                              if (_statusFailed)
                                Align(
                                  alignment: Alignment.centerLeft,
                                  child: TextButton.icon(
                                    onPressed: _busy ? null : _checkOwner,
                                    icon: const Icon(Icons.refresh, size: 18),
                                    label: const Text('Check again'),
                                  ),
                                ),
                            ],
                            const SizedBox(height: 18),
                            FilledButton(
                              onPressed: _busy ? null : _submit,
                              child: _busy
                                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                                  : Text(creating ? 'Create account' : 'Sign in'),
                            ),
                            const SizedBox(height: 8),
                            TextButton(
                              onPressed: _busy ? null : widget.onDemo,
                              child: const Text('Look around with sample data'),
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _NoBackend extends StatelessWidget {
  const _NoBackend({required this.onDemo});
  final VoidCallback onDemo;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Text(
              'This build is not connected to a server yet, so it cannot post. '
              'You can still look around with sample data.',
              style: theme.textTheme.bodyMedium,
            ),
          ),
        ),
        const SizedBox(height: 16),
        FilledButton(onPressed: onDemo, child: const Text('Open with sample data')),
      ],
    );
  }
}
