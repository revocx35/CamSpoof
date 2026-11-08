'use strict';

import Java from 'frida-java-bridge';

/*
 * CamSpoof Frida agent (v0.1.1 — diagnostics/recon build).
 *
 * Runs inside the TARGET app (injected by `frida-inject -p <pid> -s agent.js -e`). Two jobs:
 *
 *  1. RECON/DIAGNOSTICS: log exactly how the app uses the camera — legacy Camera vs Camera2,
 *     openCamera ids, capture-session output Surfaces, ImageReader formats/sizes — to
 *     /data/local/tmp/camspoof/agent.log (world-writable, created by the app's root setup) and to
 *     stdout as `csf:` lines. This is what tells us which frame path to replace for apps like
 *     WhatsApp / CamScanner whose live preview renders to a GPU Surface.
 *
 *  2. BEST-EFFORT DELIVERY (works today): drive a legacy Camera PreviewCallback with our NV21
 *     frames, and overwrite YUV_420_888 ImageReader frames. Camera2 preview-Surface replacement is
 *     the next step and is informed by the recon log.
 *
 * Everything is wrapped so a hook failure can never crash the host app. Feed header offsets mirror
 * feed/FeedFormat.kt; `csf:` lines are parsed by InjectionParser.kt.
 */

var FEED_PATH = '/data/local/tmp/camspoof/feed';
var LOG_PATH = '/data/local/tmp/camspoof/agent.log';
var HDR = 64;
var OFF_W = 8, OFF_H = 12, OFF_SIZE = 24, OFF_SEQ = 28, OFF_FLAGS = 32;
var TARGET_FPS = 30;

function emit(s) { try { console.log('csf:' + s); } catch (e) {} }

var logWriter = null;
function logf(s) {
  try {
    if (logWriter === null) {
      var FW = Java.use('java.io.FileWriter');
      logWriter = FW.$new(LOG_PATH, true);
    }
    logWriter.write('[' + Date.now() + '] ' + s + '\n');
    logWriter.flush();
  } catch (e) {}
  emit('log ' + s);
}

function fmtName(f) {
  switch (f) {
    case 0x20203859: return 'Y8';
    case 35: return 'YUV_420_888';
    case 34: return 'PRIVATE';
    case 256: return 'JPEG';
    case 37: return 'RAW10';
    case 32: return 'RAW_SENSOR';
    case 0x7f420888: return 'YUV_420_888(alt)';
    default: return 'fmt#' + f;
  }
}

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
        if (seq1 & 1) continue;
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
  return { read: read, w: function () { return width; }, h: function () { return height; } };
}

function safe(name, fn) {
  try { fn(); return true; } catch (e) { logf('hook ' + name + ' FAILED: ' + e); return false; }
}

/* ---- Reconnaissance: log how the app consumes the camera (no behaviour change) ---- */
function reconCamera2() {
  safe('camera2.recon', function () {
    var CM = Java.use('android.hardware.camera2.CameraManager');
    CM.openCamera.overload('java.lang.String',
      'android.hardware.camera2.CameraDevice$StateCallback', 'android.os.Handler')
      .implementation = function (id, cb, h) {
        logf('Camera2 openCamera id=' + id);
        return this.openCamera(id, cb, h);
      };
  });
  safe('camera2.openCamera(executor)', function () {
    var CM = Java.use('android.hardware.camera2.CameraManager');
    CM.openCamera.overload('java.lang.String', 'java.util.concurrent.Executor',
      'android.hardware.camera2.CameraDevice$StateCallback')
      .implementation = function (id, ex, cb) {
        logf('Camera2 openCamera(executor) id=' + id);
        return this.openCamera(id, ex, cb);
      };
  });

  function logSurfaces(tag, list) {
    try {
      var n = list.size();
      logf(tag + ': ' + n + ' output surface(s)');
      for (var i = 0; i < n; i++) {
        var s = list.get(i);
        var valid = '?';
        try { valid = '' + s.isValid(); } catch (e) {}
        logf('  surface[' + i + '] ' + s.getClass().getName() + ' valid=' + valid + ' str=' + s.toString());
      }
    } catch (e) { logf(tag + ' surface-log err: ' + e); }
  }

  safe('camera2.createCaptureSession(list)', function () {
    var CD = Java.use('android.hardware.camera2.CameraDevice');
    CD.createCaptureSession.overload('java.util.List',
      'android.hardware.camera2.CameraCaptureSession$StateCallback', 'android.os.Handler')
      .implementation = function (outputs, cb, h) {
        logSurfaces('createCaptureSession(list)', outputs);
        return this.createCaptureSession(outputs, cb, h);
      };
  });

  safe('camera2.createCaptureSession(config)', function () {
    var CD = Java.use('android.hardware.camera2.CameraDevice');
    CD.createCaptureSession.overload('android.hardware.camera2.params.SessionConfiguration')
      .implementation = function (config) {
        try {
          var ocs = config.getOutputConfigurations();
          logf('createCaptureSession(SessionConfiguration): ' + ocs.size() + ' output config(s)');
          for (var i = 0; i < ocs.size(); i++) {
            var sfc = ocs.get(i).getSurface();
            logf('  outConfig[' + i + '] surface=' + (sfc ? sfc.toString() : 'null'));
          }
        } catch (e) { logf('sessionConfig log err: ' + e); }
        return this.createCaptureSession(config);
      };
  });

  safe('camera2.ImageReader.newInstance', function () {
    var IR = Java.use('android.media.ImageReader');
    IR.newInstance.overload('int', 'int', 'int', 'int').implementation = function (w, h, f, m) {
      logf('ImageReader.newInstance ' + w + 'x' + h + ' ' + fmtName(f) + ' max=' + m);
      return this.newInstance(w, h, f, m);
    };
  });
  safe('camera2.ImageReader.newInstance(usage)', function () {
    var IR = Java.use('android.media.ImageReader');
    IR.newInstance.overload('int', 'int', 'int', 'int', 'long').implementation = function (w, h, f, m, u) {
      logf('ImageReader.newInstance(usage) ' + w + 'x' + h + ' ' + fmtName(f) + ' max=' + m);
      return this.newInstance(w, h, f, m, u);
    };
  });
}

/* ---- Delivery: legacy Camera PreviewCallback pumped with our frames ---- */
function hookLegacyCamera(feed) {
  return safe('legacyCamera', function () {
    var Camera = Java.use('android.hardware.Camera');
    var state = { cb: null, cam: null, timer: null };

    function startPump() {
      if (state.timer) return;
      state.timer = setInterval(function () {
        if (!state.cb || !state.cam) return;
        var f = feed.read();
        if (!f) return;
        var copy = newByteArray(f.buf.length);
        for (var i = 0; i < f.buf.length; i++) copy[i] = f.buf[i];
        try { state.cb.onPreviewFrame(copy, state.cam); } catch (e) {}
      }, Math.floor(1000 / TARGET_FPS));
    }

    Camera.setPreviewCallback.implementation = function (cb) { state.cb = cb; state.cam = this; logf('legacy setPreviewCallback'); };
    Camera.setPreviewCallbackWithBuffer.implementation = function (cb) { state.cb = cb; state.cam = this; logf('legacy setPreviewCallbackWithBuffer'); };
    Camera.setOneShotPreviewCallback.implementation = function (cb) { state.cb = cb; state.cam = this; };
    Camera.startPreview.implementation = function () {
      this.startPreview.call(this); state.cam = this; logf('legacy startPreview'); startPump();
    };
    logf('legacy Camera hooks installed');
    emit('hook android.hardware.Camera ok');
  });
}

/* ---- Delivery: overwrite YUV_420_888 ImageReader frames ---- */
function hookImageReader(feed) {
  return safe('imageReaderDeliver', function () {
    var ImageReader = Java.use('android.media.ImageReader');
    function overwrite(image) {
      try {
        var f = feed.read();
        if (!f) return;
        var planes = image.getPlanes();
        if (!planes || planes.length === 0) return;
        var w = image.getWidth(), h = image.getHeight();
        var ySize = w * h;
        var yb = planes[0].getBuffer();
        var yStride = planes[0].getRowStride();
        yb.clear();
        for (var row = 0; row < h; row++) {
          yb.position(row * yStride);
          for (var col = 0; col < w; col++) yb.put(f.buf[row * w + col]);
        }
        if (planes.length >= 3) {
          var ub = planes[1].getBuffer(), vb = planes[2].getBuffer();
          var cStride = planes[1].getRowStride(), cPix = planes[1].getPixelStride();
          var cw = w / 2, ch = h / 2;
          ub.clear(); vb.clear();
          for (var r2 = 0; r2 < ch; r2++) {
            for (var c2 = 0; c2 < cw; c2++) {
              var nvIdx = ySize + (r2 * cw + c2) * 2;
              var dst = r2 * cStride + c2 * cPix;
              vb.position(dst); vb.put(f.buf[nvIdx]);
              ub.position(dst); ub.put(f.buf[nvIdx + 1]);
            }
          }
        }
      } catch (e) {}
    }
    ['acquireLatestImage', 'acquireNextImage'].forEach(function (m) {
      if (ImageReader[m]) {
        ImageReader[m].implementation = function () {
          var img = this[m].call(this);
          if (img) overwrite(img);
          return img;
        };
      }
    });
    logf('ImageReader delivery hooks installed');
    emit('hook android.media.ImageReader ok');
  });
}

Java.perform(function () {
  emit('loaded');
  logf('=== CamSpoof agent attached, pid ' + Process.id + ' ===');

  var feed = openFeed();
  if (!feed) {
    logf('FEED OPEN FAILED (' + FEED_PATH + ')');
    emit('error feed-open-failed (' + FEED_PATH + ')');
  } else {
    var first = feed.read();
    if (first) { logf('feed ' + first.w + 'x' + first.h); emit('feed ' + first.w + 'x' + first.h); }
    else { logf('feed present but no frame yet'); emit('feed unknown'); }
  }

  // Recon first (always), then delivery (needs the feed).
  reconCamera2();
  var any = false;
  if (feed) {
    any = hookLegacyCamera(feed) || any;
    any = hookImageReader(feed) || any;
  }

  logf('recon installed; delivery=' + any);
  if (!any) emit('error no-delivery-hook');
  emit('ready');
});
