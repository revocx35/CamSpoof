'use strict';

import Java from 'frida-java-bridge';

/*
 * CamSpoof Frida agent.
 *
 * Runs inside the TARGET app (injected by `frida-inject -p <pid> -s agent.js -e`). It memory-maps
 * the shared feed that the CamSpoof app writes (NV21 frames, see feed/FeedFormat.kt and
 * ARCHITECTURE.md) and pushes those frames into the app's camera pipeline so the app receives the
 * composited image/video instead of the real sensor.
 *
 * Progress is printed as machine-readable `csf:` lines on stdout, parsed by InjectionParser.kt.
 * Keep the feed header offsets in step with feed/FeedFormat.kt.
 *
 * Coverage (first cut):
 *   - android.hardware.Camera (legacy API): full — a timer drives the app's PreviewCallback with
 *     our NV21 frames.
 *   - android.media.ImageReader (Camera2 / CameraX apps that READ YUV_420_888 frames): best-effort
 *     — acquired images' plane buffers are overwritten with our frame.
 *   - Camera2 apps that only render preview to a Surface/SurfaceTexture and never read pixels back
 *     need a GL-producer swap on the output Surface; that is the next iteration (see ARCHITECTURE).
 */

var FEED_PATH = '/data/local/tmp/camspoof/feed';
var HDR = 64;
var OFF_W = 8, OFF_H = 12, OFF_SIZE = 24, OFF_SEQ = 28, OFF_FLAGS = 32;
var TARGET_FPS = 30;

function emit(s) { console.log('csf:' + s); }

function le32(arr, off) {
  return ((arr[off] & 0xff)) | ((arr[off + 1] & 0xff) << 8) |
         ((arr[off + 2] & 0xff) << 16) | ((arr[off + 3] & 0xff) << 24);
}

function newByteArray(n) {
  var a = new Array(n);
  for (var i = 0; i < n; i++) a[i] = 0;
  return Java.array('byte', a);
}

/* Opens the feed and exposes read(): the latest NV21 frame as a Java byte[], tear-free. */
function openFeed() {
  var RAF = Java.use('java.io.RandomAccessFile');
  var raf;
  try {
    raf = RAF.$new(FEED_PATH, 'r');
  } catch (e) {
    return null;
  }
  var header = newByteArray(HDR);
  var seqTail = newByteArray(4);
  var frame = null, frameLen = 0, width = 0, height = 0;

  function read() {
    try {
      for (var attempt = 0; attempt < 3; attempt++) {
        raf.seek(0);
        raf.readFully(header);
        var seq1 = le32(header, OFF_SEQ);
        if (seq1 & 1) continue; // writer in progress
        var w = le32(header, OFF_W), h = le32(header, OFF_H), size = le32(header, OFF_SIZE);
        if (w <= 0 || h <= 0 || size <= 0) return null;
        if (frame === null || size !== frameLen) {
          frame = newByteArray(size);
          frameLen = size; width = w; height = h;
        }
        raf.seek(HDR);
        raf.readFully(frame);
        raf.seek(OFF_SEQ);
        raf.readFully(seqTail);
        if (le32(seqTail, 0) === seq1) {
          return { buf: frame, w: width, h: height, flags: le32(header, OFF_FLAGS) };
        }
      }
    } catch (e) {}
    return null;
  }
  return { read: read, width: function () { return width; }, height: function () { return height; } };
}

function hookLegacyCamera(feed) {
  var Camera;
  try { Camera = Java.use('android.hardware.Camera'); } catch (e) { return false; }

  var state = { cb: null, cam: null, timer: null };

  function startPump() {
    if (state.timer) return;
    state.timer = setInterval(function () {
      if (!state.cb || !state.cam) return;
      var f = feed.read();
      if (!f) return;
      // Deliver a fresh copy so the app may retain it without us overwriting.
      var copy = newByteArray(f.buf.length);
      for (var i = 0; i < f.buf.length; i++) copy[i] = f.buf[i];
      try { state.cb.onPreviewFrame(copy, state.cam); } catch (e) {}
    }, Math.floor(1000 / TARGET_FPS));
  }

  Camera.setPreviewCallback.implementation = function (cb) {
    state.cb = cb; state.cam = this;
    // Swallow the real callback registration; we drive frames ourselves.
  };
  Camera.setPreviewCallbackWithBuffer.implementation = function (cb) {
    state.cb = cb; state.cam = this;
  };
  Camera.setOneShotPreviewCallback.implementation = function (cb) {
    state.cb = cb; state.cam = this;
  };
  Camera.startPreview.implementation = function () {
    this.startPreview.call(this);
    state.cam = this;
    startPump();
  };

  emit('hook android.hardware.Camera ok');
  return true;
}

function hookImageReader(feed) {
  var Image;
  try { Image = Java.use('android.media.Image'); } catch (e) { return false; }

  function overwrite(image) {
    try {
      var f = feed.read();
      if (!f) return;
      var planes = image.getPlanes();
      if (!planes || planes.length === 0) return;
      var w = image.getWidth(), h = image.getHeight();
      var ySize = w * h;
      // Y plane.
      var yb = planes[0].getBuffer();
      var yStride = planes[0].getRowStride();
      yb.clear();
      for (var row = 0; row < h; row++) {
        yb.position(row * yStride);
        for (var col = 0; col < w; col++) yb.put(f.buf[row * w + col]);
      }
      // Chroma (NV21 is V,U interleaved). YUV_420_888 exposes U (plane 1) and V (plane 2).
      if (planes.length >= 3) {
        var ub = planes[1].getBuffer();
        var vb = planes[2].getBuffer();
        var cStride = planes[1].getRowStride();
        var cPix = planes[1].getPixelStride();
        var cw = w / 2, ch = h / 2;
        ub.clear(); vb.clear();
        for (var r2 = 0; r2 < ch; r2++) {
          for (var c2 = 0; c2 < cw; c2++) {
            var nvIdx = ySize + (r2 * cw + c2) * 2; // V,U
            var dst = r2 * cStride + c2 * cPix;
            vb.position(dst); vb.put(f.buf[nvIdx]);
            ub.position(dst); ub.put(f.buf[nvIdx + 1]);
          }
        }
      }
    } catch (e) {}
  }

  ['acquireLatestImage', 'acquireNextImage'].forEach(function (m) {
    var ImageReader = Java.use('android.media.ImageReader');
    if (ImageReader[m]) {
      ImageReader[m].implementation = function () {
        var img = this[m].call(this);
        if (img) overwrite(img);
        return img;
      };
    }
  });

  emit('hook android.media.ImageReader ok');
  return true;
}

Java.perform(function () {
  emit('loaded');
  var feed = openFeed();
  if (!feed) {
    emit('error feed-open-failed (' + FEED_PATH + ')');
    return;
  }
  // Force a first read to learn the dimensions for the status line.
  var first = feed.read();
  if (first) emit('feed ' + first.w + 'x' + first.h);
  else emit('feed unknown');

  var any = false;
  any = hookLegacyCamera(feed) || any;
  any = hookImageReader(feed) || any;

  if (!any) emit('error no-camera-api-found');
  emit('ready');
});
