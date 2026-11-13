import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;

class ProcessedImage {
  ProcessedImage({required this.jpeg, required this.thumb, required this.width, required this.height});
  final Uint8List jpeg;
  final Uint8List thumb;
  final int width;
  final int height;
}

class _Raw {
  _Raw(this.rgba, this.width, this.height, this.quality);
  final Uint8List rgba;
  final int width;
  final int height;
  final int quality;
}

Uint8List _encodeJpeg(_Raw raw) {
  final image = img.Image.fromBytes(
    width: raw.width,
    height: raw.height,
    bytes: raw.rgba.buffer,
    bytesOffset: raw.rgba.offsetInBytes,
    numChannels: 4,
    order: img.ChannelOrder.rgba,
  );
  return img.encodeJpg(image, quality: raw.quality);
}

/// Decodes with the platform decoder (handles HEIC, WebP, PNG and applies the
/// photo's rotation), scaled so the longest side is at most [maxSide].
Future<_Raw> _decode(Uint8List bytes, int maxSide, int quality) async {
  final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
  final codec = await ui.instantiateImageCodecWithSize(
    buffer,
    getTargetSize: (w, h) {
      if (math.max(w, h) <= maxSide) return ui.TargetImageSize(width: w, height: h);
      // Constrain one side only, so the aspect ratio is always preserved.
      return w >= h ? ui.TargetImageSize(width: maxSide) : ui.TargetImageSize(height: maxSide);
    },
  );
  final frame = await codec.getNextFrame();
  final image = frame.image;
  final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
  final raw = _Raw(data!.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes), image.width, image.height, quality);
  image.dispose();
  codec.dispose();
  return raw;
}

/// Every platform accepts JPEG (Instagram accepts nothing else), so photos
/// are converted once here: a full-size JPEG to post and a small one to show
/// in lists.
Future<ProcessedImage> processImage(Uint8List source) async {
  final full = await _decode(source, 2048, 88);
  final small = await _decode(source, 480, 72);
  final jpeg = await compute(_encodeJpeg, full);
  final thumb = await compute(_encodeJpeg, small);
  return ProcessedImage(jpeg: jpeg, thumb: thumb, width: full.width, height: full.height);
}

/// A small JPEG from an existing preview (used for video thumbnails).
Future<Uint8List> shrinkToThumb(Uint8List source) async => compute(_encodeJpeg, await _decode(source, 480, 72));
