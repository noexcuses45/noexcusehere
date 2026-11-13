import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:url_launcher/url_launcher.dart';

import '../app_state.dart';
import '../models.dart';
import '../platforms.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// Connected social accounts, grouped by the developer app that connects them.
class AccountsScreen extends StatefulWidget {
  const AccountsScreen({super.key, required this.state});

  final AppState state;

  @override
  State<AccountsScreen> createState() => _AccountsScreenState();
}

class _AccountsScreenState extends State<AccountsScreen> {
  String? _connecting;

  AppState get state => widget.state;

  Future<void> _connect(CredentialGroup group) async {
    setState(() => _connecting = group.key);
    try {
      final url = await state.repo.startConnect(group.key);
      final opened = await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
      if (!mounted) return;
      showMessage(
        context,
        opened
            ? 'Finish signing in in your browser, then come back here.'
            : 'Could not open your browser.',
      );
    } on AppException catch (e) {
      if (mounted) showMessage(context, e.message);
    } finally {
      if (mounted) setState(() => _connecting = null);
    }
  }

  Future<void> _disconnect(SocialAccount account) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Disconnect ${account.name}?'),
        content: const Text('Posts already scheduled for this account will fail unless you reconnect it first.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Disconnect')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await state.repo.disconnectAccount(account.id);
      await state.refreshAccounts();
    } on AppException catch (e) {
      if (mounted) showMessage(context, e.message);
    }
  }

  Future<void> _setup(CredentialGroup group) async {
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => PlatformSetupScreen(state: state, group: group)),
    );
  }

  Future<void> _removeApp(CredentialGroup group) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Remove ${group.label} app details?'),
        content: const Text('Connected accounts stop working until the details are added again.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Remove')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await state.repo.clearCredentials(group.key);
      await state.refreshAccounts();
    } on AppException catch (e) {
      if (mounted) showMessage(context, e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final now = DateTime.now();

    return Scaffold(
      appBar: AppBar(title: const Text('Accounts')),
      body: RefreshIndicator(
        onRefresh: state.refreshAccounts,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 32),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 0, 4, 12),
              child: Text(
                'Each network asks you to register a free "developer app" once, so posts are sent on your behalf. '
                'Tap Set up for step-by-step instructions.',
                style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
              ),
            ),
            for (final group in kCredentialGroups)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Builder(builder: (context) {
                  final configured = state.credential(group.key) != null;
                  final accounts = state.accounts.where((a) => group.platforms.contains(a.platform)).toList();
                  return Card(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(4, 8, 4, 8),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          ListTile(
                            leading: Row(mainAxisSize: MainAxisSize.min, children: [
                              for (final p in group.platforms)
                                Padding(padding: const EdgeInsets.only(right: 4), child: PlatformBadge(p, size: 30)),
                            ]),
                            title: Text(group.label, style: const TextStyle(fontWeight: FontWeight.w700)),
                            subtitle: Text(configured
                                ? (accounts.isEmpty ? 'Ready to connect' : '${accounts.length} connected')
                                : 'Not set up yet'),
                            trailing: configured
                                ? PopupMenuButton<String>(
                                    onSelected: (v) => v == 'edit' ? _setup(group) : _removeApp(group),
                                    itemBuilder: (context) => const [
                                      PopupMenuItem(value: 'edit', child: Text('Setup guide and app details')),
                                      PopupMenuItem(value: 'remove', child: Text('Remove app details')),
                                    ],
                                  )
                                : null,
                          ),
                          for (final a in accounts)
                            ListTile(
                              leading: AccountAvatar(a),
                              title: Text(a.name, maxLines: 1, overflow: TextOverflow.ellipsis),
                              subtitle: _AccountStatus(account: a, now: now),
                              trailing: IconButton(
                                tooltip: 'Disconnect',
                                icon: const Icon(Icons.link_off),
                                onPressed: () => _disconnect(a),
                              ),
                            ),
                          Padding(
                            padding: const EdgeInsets.fromLTRB(12, 4, 12, 4),
                            child: configured
                                ? FilledButton.tonalIcon(
                                    onPressed: _connecting == null ? () => _connect(group) : null,
                                    icon: _connecting == group.key
                                        ? const SizedBox(
                                            width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                                        : const Icon(Icons.add_link),
                                    label: Text(accounts.isEmpty ? 'Connect' : 'Connect or refresh accounts'),
                                  )
                                : OutlinedButton.icon(
                                    onPressed: () => _setup(group),
                                    icon: const Icon(Icons.build_outlined),
                                    label: const Text('Set up'),
                                  ),
                          ),
                        ],
                      ),
                    ),
                  );
                }),
              ),
          ],
        ),
      ),
    );
  }
}

class _AccountStatus extends StatelessWidget {
  const _AccountStatus({required this.account, required this.now});

  final SocialAccount account;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final colors = StatusColors(scheme);
    final label = platformInfo(account.platform).label;
    final handle = account.username == null ? label : '$label  @${account.username}';
    if (account.needsReconnect) {
      return Text('Needs reconnecting - tap Connect below', style: TextStyle(color: scheme.error));
    }
    final days = account.daysLeft(now);
    if (days != null && days <= 14) {
      return Text(
        days < 0 ? 'Access has run out - connect again' : 'Connect again within $days days to keep posting',
        style: TextStyle(color: days < 0 ? scheme.error : colors.warning),
      );
    }
    return Text(handle);
  }
}

class _Guide {
  const _Guide({
    required this.intro,
    required this.site,
    required this.steps,
    required this.idLabel,
    required this.secretLabel,
    this.notes = const [],
    this.optionKey,
    this.optionLabel,
    this.optionHelp,
  });

  final String intro;
  final String site;
  final List<String> steps;
  final String idLabel;
  final String secretLabel;
  final List<String> notes;
  final String? optionKey;
  final String? optionLabel;
  final String? optionHelp;
}

const _guides = <String, _Guide>{
  'meta': _Guide(
    intro: 'One Meta app covers both Facebook Pages and Instagram. Instagram must be a Business or Creator '
        'account that is linked to a Facebook Page you manage.',
    site: 'https://developers.facebook.com/apps',
    steps: [
      'Open developers.facebook.com, choose My Apps, then Create App. Pick the use cases for managing your Page '
          'and for Instagram content, with app type Business.',
      'In the app, open Facebook Login for Business > Settings and paste the redirect URL below into '
          '"Valid OAuth Redirect URIs".',
      'Open App settings > Basic. Add a privacy policy link, then copy the App ID and App secret into the boxes here.',
      'Switch the app from Development to Live (top of the dashboard). While it is in Development, '
          'posts it publishes are visible only to you.',
      'Save, go back, tap Connect, and on Facebook\'s screen tick every Page and Instagram account you want to use.',
    ],
    idLabel: 'App ID',
    secretLabel: 'App secret',
    notes: [
      'You are the only user of your own app, so Meta\'s App Review is not needed.',
    ],
  ),
  'tiktok': _Guide(
    intro: 'TikTok is the strictest network. Until TikTok has reviewed a developer app, it cannot post publicly, '
        'and TikTok states it does not approve tools used only for your own accounts.',
    site: 'https://developers.tiktok.com/apps',
    steps: [
      'Open developers.tiktok.com, choose Manage apps, and create an app.',
      'Add the products Login Kit and Content Posting API. Under Content Posting API switch on Upload.',
      'In Login Kit, add the redirect URL below under Web.',
      'Add the scopes user.info.basic and video.upload.',
      'Create a Sandbox for the app and add your own TikTok account as a target user, so you can use it without review.',
      'Copy the Client key and Client secret into the boxes here, save, then tap Connect.',
    ],
    idLabel: 'Client key',
    secretLabel: 'Client secret',
    optionKey: 'direct_post',
    optionLabel: 'TikTok has approved Direct Post for my app',
    optionHelp: 'Leave this off unless TikTok has audited your app. Without approval, direct posts are forced to '
        'private and only work on private accounts.',
    notes: [
      '"Send to inbox" delivers the video to your TikTok app as a draft; you add a sound and tap Post there.',
      'If TikTok will not enable this for your app, use a reminder instead: the app notifies you at the '
          'scheduled time and hands the video and caption to TikTok. That always works.',
    ],
  ),
  'google': _Guide(
    intro: 'YouTube uploads go through a Google Cloud project that you own.',
    site: 'https://console.cloud.google.com/',
    steps: [
      'Open console.cloud.google.com and create a project.',
      'Under APIs & Services > Library, enable "YouTube Data API v3".',
      'Under Google Auth Platform (OAuth consent screen), choose External, fill in the app name and your email, '
          'then publish the app so its status is "In production". If it stays in Testing, access expires every 7 days.',
      'Under Clients, create an OAuth client of type "Web application" and add the redirect URL below as an '
          'authorised redirect URI.',
      'Copy the Client ID and Client secret into the boxes here, save, then tap Connect.',
      'Google shows "Google hasn\'t verified this app". Because it is your own app, choose Advanced, then continue.',
    ],
    idLabel: 'Client ID',
    secretLabel: 'Client secret',
    notes: [
      'Vertical videos up to 3 minutes are shown as Shorts automatically.',
      'Google allows 100 uploads a day per project by default.',
    ],
  ),
  'linkedin': _Guide(
    intro: 'Posting to your personal LinkedIn profile works straight away. Company Pages need extra approval '
        'from LinkedIn.',
    site: 'https://www.linkedin.com/developers/apps',
    steps: [
      'Open linkedin.com/developers and create an app (LinkedIn asks you to link it to a Company Page).',
      'On the Products tab, add "Share on LinkedIn" and "Sign In with LinkedIn using OpenID Connect".',
      'On the Auth tab, add the redirect URL below under "Authorized redirect URLs for your app".',
      'Copy the Client ID and Primary Client Secret into the boxes here, save, then tap Connect.',
    ],
    idLabel: 'Client ID',
    secretLabel: 'Client secret',
    optionKey: 'pages',
    optionLabel: 'LinkedIn has approved the Community Management API for my app',
    optionHelp: 'Needed to post as a Company Page. LinkedIn only grants it to registered businesses, on a '
        'separate app with no other products. Leave this off to post to your personal profile.',
    notes: [
      'LinkedIn makes you connect again every 60 days. The Accounts tab warns you two weeks before.',
    ],
  ),
};

/// Step-by-step instructions plus the two values each network hands out.
class PlatformSetupScreen extends StatefulWidget {
  const PlatformSetupScreen({super.key, required this.state, required this.group});

  final AppState state;
  final CredentialGroup group;

  @override
  State<PlatformSetupScreen> createState() => _PlatformSetupScreenState();
}

class _PlatformSetupScreenState extends State<PlatformSetupScreen> {
  final _id = TextEditingController();
  final _secret = TextEditingController();
  bool _option = false;
  bool _saving = false;

  _Guide get _guide => _guides[widget.group.key]!;

  @override
  void initState() {
    super.initState();
    final existing = widget.state.credential(widget.group.key);
    _id.text = existing?.clientId ?? '';
    if (_guide.optionKey != null) _option = existing?.options[_guide.optionKey] == true;
  }

  @override
  void dispose() {
    _id.dispose();
    _secret.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_id.text.trim().isEmpty || _secret.text.trim().isEmpty) {
      showMessage(context, 'Fill in both boxes. For safety the secret is never shown again, so re-enter it to change anything.');
      return;
    }
    setState(() => _saving = true);
    try {
      await widget.state.repo.saveCredentials(
        widget.group.key,
        _id.text.trim(),
        _secret.text.trim(),
        {if (_guide.optionKey != null) _guide.optionKey!: _option},
      );
      await widget.state.refreshAccounts();
      if (!mounted) return;
      final messenger = ScaffoldMessenger.of(context);
      Navigator.of(context).pop();
      messenger.showSnackBar(const SnackBar(content: Text('Saved. Now tap Connect.')));
    } on AppException catch (e) {
      if (mounted) showMessage(context, e.message);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final guide = _guide;
    final callback = widget.state.repo.callbackUrl;
    final configured = widget.state.credential(widget.group.key) != null;

    return Scaffold(
      appBar: AppBar(title: Text('${widget.group.label} setup')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 32),
        children: [
          Text(guide.intro, style: theme.textTheme.bodyMedium),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: () => launchUrl(Uri.parse(guide.site), mode: LaunchMode.externalApplication),
            icon: const Icon(Icons.open_in_new, size: 18),
            label: Text('Open ${Uri.parse(guide.site).host}'),
          ),
          const SizedBox(height: 16),
          for (var i = 0; i < guide.steps.length; i++)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                CircleAvatar(
                  radius: 12,
                  backgroundColor: scheme.primaryContainer,
                  child: Text('${i + 1}', style: TextStyle(fontSize: 12, color: scheme.onPrimaryContainer)),
                ),
                const SizedBox(width: 10),
                Expanded(child: Text(guide.steps[i], style: theme.textTheme.bodyMedium)),
              ]),
            ),
          const SizedBox(height: 6),
          Text('Redirect URL', style: theme.textTheme.labelLarge),
          const SizedBox(height: 6),
          Card(
            child: ListTile(
              title: SelectableText(callback, style: theme.textTheme.bodySmall),
              trailing: IconButton(
                tooltip: 'Copy',
                icon: const Icon(Icons.copy),
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: callback));
                  if (context.mounted) showMessage(context, 'Redirect URL copied.');
                },
              ),
            ),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _id,
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(labelText: guide.idLabel),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _secret,
            obscureText: true,
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: guide.secretLabel,
              helperText: configured ? 'Already saved. Enter it again to make changes.' : 'Stored on the server only.',
            ),
          ),
          if (guide.optionKey != null)
            SwitchListTile(
              contentPadding: const EdgeInsets.only(top: 8),
              title: Text(guide.optionLabel!),
              subtitle: Text(guide.optionHelp!),
              value: _option,
              onChanged: (v) => setState(() => _option = v),
            ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _saving ? null : _save,
            child: _saving
                ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Save'),
          ),
          if (guide.notes.isNotEmpty) ...[
            const SizedBox(height: 20),
            Text('Good to know', style: theme.textTheme.labelLarge),
            const SizedBox(height: 6),
            for (final n in guide.notes)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Text('- $n', style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
              ),
          ],
        ],
      ),
    );
  }
}
