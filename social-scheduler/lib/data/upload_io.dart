import 'dart:io';

import 'package:image_picker/image_picker.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

/// Streams the file from disk, so a large video never sits in memory.
Future<void> uploadPickedFile(SupabaseClient client, String bucket, String path, XFile file, String contentType) async {
  await client.storage.from(bucket).upload(
        path,
        File(file.path),
        fileOptions: FileOptions(contentType: contentType, upsert: true),
      );
}
