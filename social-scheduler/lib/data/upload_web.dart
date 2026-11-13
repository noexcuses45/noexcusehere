import 'package:image_picker/image_picker.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

Future<void> uploadPickedFile(SupabaseClient client, String bucket, String path, XFile file, String contentType) async {
  await client.storage.from(bucket).uploadBinary(
        path,
        await file.readAsBytes(),
        fileOptions: FileOptions(contentType: contentType, upsert: true),
      );
}
