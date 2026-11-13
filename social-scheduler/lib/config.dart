/// Build-time configuration.
///
/// The backend address and its *publishable* key are safe to ship inside the
/// app: every table is protected by row-level security. Override either at
/// build time with --dart-define=SUPABASE_URL=... / SUPABASE_KEY=...
class AppConfig {
  static const supabaseUrl = String.fromEnvironment('SUPABASE_URL', defaultValue: _defaultUrl);
  static const supabaseKey = String.fromEnvironment('SUPABASE_KEY', defaultValue: _defaultKey);

  // Filled in when the backend is deployed.
  static const _defaultUrl = '';
  static const _defaultKey = '';

  static bool get hasBackend => supabaseUrl.isNotEmpty && supabaseKey.isNotEmpty;

  /// Name of the Edge Function that does the publishing.
  static const functionName = 'scheduler';

  /// Where platforms send the browser back to after sign-in. This is the
  /// "redirect URL" to paste into each developer console.
  static String get callbackUrl => '$supabaseUrl/functions/v1/$functionName/oauth/callback';

  /// GitHub repository whose releases carry new APKs (tags `scheduler-v<N>`).
  static const updateRepo = 'noexcuses45/noexcusehere';
  static const updateTagPrefix = 'scheduler-v';

  static const appName = 'Social Scheduler';
}
