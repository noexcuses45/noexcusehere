import 'package:flutter/foundation.dart';
import 'package:image_picker/image_picker.dart';
import 'package:video_compress/video_compress.dart';

import '../data/repository.dart';
import '../models.dart';
import 'image_tools.dart';

const _videoExtensions = {'mp4', 'mov', 'm4v', 'webm', 'mkv', '3gp', 'avi'};

/// Videos above this size are re-encoded before upload (when enabled), which
/// keeps them inside common storage limits and makes uploads much faster.
const kShrinkVideosAbove = 48 * 1024 * 1024;

String _extension(String name) {
  final dot = name.lastIndexOf('.');
  return dot < 0 ? '' : name.substring(dot + 1).toLowerCase();
}

bool isVideoFile(XFile file) {
  final mime = file.mimeType ?? '';
  if (mime.startsWith('video/')) return true;
  if (mime.startsWith('image/')) return false;
  return _videoExtensions.contains(_extension(file.name.isNotEmpty ? file.name : file.path));
}

/// Picks photos and videos, prepares them for every platform, and uploads them.
class MediaService {
  MediaService(this.repo);

  final Repository repo;
  final ImagePicker _picker = ImagePicker();

  Future<List<XFile>> pickFromGallery() => _picker.pickMultipleMedia();
  Future<XFile?> takePhoto() => _picker.pickImage(source: ImageSource.camera);
  Future<XFile?> recordVideo() => _picker.pickVideo(source: ImageSource.camera);

  /// Converts/uploads one picked file and describes it as [PostMedia].
  /// [onStage] reports what is happening ("Shrinking video", "Uploading").
  Future<PostMedia> prepare(XFile file, {required bool shrinkVideos, void Function(String stage)? onStage}) async {
    return isVideoFile(file)
        ? _prepareVideo(file, shrinkVideos: shrinkVideos, onStage: onStage)
        : _prepareImage(file, onStage: onStage);
  }

  Future<PostMedia> _prepareImage(XFile file, {void Function(String stage)? onStage}) async {
    onStage?.call('Preparing photo');
    final ProcessedImage processed;
    try {
      processed = await processImage(await file.readAsBytes());
    } catch (_) {
      throw AppException('That photo could not be read. Try a different one.');
    }
    final path = repo.newMediaPath('jpg');
    final thumbPath = path.replaceFirst(RegExp(r'\.jpg$'), '_thumb.jpg');
    onStage?.call('Uploading photo');
    await repo.uploadBytes(path, processed.jpeg, 'image/jpeg');
    await repo.uploadBytes(thumbPath, processed.thumb, 'image/jpeg');
    return PostMedia(
      kind: 'image',
      storagePath: path,
      thumbPath: thumbPath,
      mimeType: 'image/jpeg',
      sizeBytes: processed.jpeg.length,
      width: processed.width,
      height: processed.height,
      preview: processed.thumb,
    );
  }

  Future<PostMedia> _prepareVideo(XFile file, {required bool shrinkVideos, void Function(String stage)? onStage}) async {
    var upload = file;
    var size = await file.length();
    int? width, height, durationMs;
    Uint8List? thumb;

    // Native video tools exist on phones only.
    if (!kIsWeb) {
      onStage?.call('Reading video');
      try {
        if (shrinkVideos && size > kShrinkVideosAbove) {
          onStage?.call('Shrinking video');
          final smaller = await VideoCompress.compressVideo(
            file.path,
            quality: VideoQuality.Res1920x1080Quality,
            deleteOrigin: false,
            includeAudio: true,
          );
          final out = smaller?.file;
          if (out != null) {
            final newSize = await out.length();
            if (newSize > 0 && newSize < size) {
              upload = XFile(out.path, name: 'video.mp4', mimeType: 'video/mp4');
              size = newSize;
            }
          }
        }
        final info = await VideoCompress.getMediaInfo(upload.path);
        final rotated = info.orientation == 90 || info.orientation == 270;
        width = rotated ? info.height : info.width;
        height = rotated ? info.width : info.height;
        durationMs = info.duration?.round();
        final frame = await VideoCompress.getByteThumbnail(upload.path, quality: 60);
        if (frame != null && frame.isNotEmpty) thumb = await shrinkToThumb(frame);
      } catch (_) {
        // Details and thumbnail are nice to have; the upload can still go ahead.
      }
    }

    final ext = _extension(upload.name.isNotEmpty ? upload.name : upload.path);
    final isMov = ext == 'mov';
    final path = repo.newMediaPath(isMov ? 'mov' : (ext == 'webm' ? 'webm' : 'mp4'));
    final mime = isMov ? 'video/quicktime' : (ext == 'webm' ? 'video/webm' : 'video/mp4');
    String? thumbPath;

    onStage?.call('Uploading video');
    await repo.uploadFile(path, upload, mime);
    if (thumb != null) {
      thumbPath = '${path.substring(0, path.lastIndexOf('.'))}_thumb.jpg';
      try {
        await repo.uploadBytes(thumbPath, thumb, 'image/jpeg');
      } catch (_) {
        thumbPath = null;
      }
    }
    return PostMedia(
      kind: 'video',
      storagePath: path,
      thumbPath: thumbPath,
      mimeType: mime,
      sizeBytes: size,
      width: width,
      height: height,
      durationMs: durationMs,
      preview: thumb,
    );
  }
}
