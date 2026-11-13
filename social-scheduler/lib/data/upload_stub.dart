import 'package:image_picker/image_picker.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

Future<void> uploadPickedFile(SupabaseClient client, String bucket, String path, XFile file, String contentType) {
  throw UnsupportedError('File upload is not supported on this platform.');
}
