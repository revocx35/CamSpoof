# ARCHITECTURE.md

## Purpose

Replace the camera frames a **chosen app** receives with a user-composited image/video on a rooted
device, **without** Zygisk/LSPosed. CamSpoof is both a small video compositor (live lens preview +
movable overlays + audio mixer) and a per-app camera injector, built for the owner's Galaxy Z Fold7
(One UI 8 / Android 16, KernelSU) where Xposed-family modules are unavailable.

## Components

1. **Compositor UI** (`ui/`, `camera/`)
   - `CameraController` enumerates the device's physical lenses via CameraX
     (`availableCameraInfos` + `Camera2CameraInfo.cameraId`) and binds a `Preview` of the selected
     one to a `PreviewView`.
   - Overlays (`media/OverlayItem`, `ui/OverlayLayer`) are Compose widgets drawn over the preview.
     Images render from a decoded `Bitmap`; videos play in a `TextureView` driven by a looping
     `MediaPlayer` whose volume follows the mixer. `detectTransformGestures` maps one-finger drag →
     move, two-finger pinch → resize, twist → rotate. Transforms are stored **normalized**
     (centre as a fraction of the surface, width as a fraction of surface width, degrees), so the
     same numbers place the overlay identically on screen and in the output frame.
   - `VolumeMixer` exposes a microphone channel and one channel per video overlay.

2. **Frame feed** (`camera/FrameCompositor`, `camera/YuvConvert`, `feed/`)
   - `FrameCompositor` runs a worker loop at ~30 fps. Each tick it pulls a `Scene` on the **main
     thread** (`PreviewView.bitmap` for the camera, each video's `TextureView.bitmap`), composites
     camera + overlays onto a 1280x720 `ARGB_8888` bitmap with `Canvas` (centre-crop camera, then
     each overlay with translate/rotate/scale), converts to **NV21** (`YuvConvert`, BT.601), and
     writes it to a shared memory-mapped file.
   - `FeedWriter`/`FeedFormat` define that file: a 64-byte header (magic, w, h, format, stride,
     size, **seq**, flags) + one NV21 frame, at `/data/local/tmp/camspoof/feed`. A **seqlock**
     (odd seq = write in progress) lets the reader copy a tear-free frame with no cross-process
     lock.

3. **Injector** (`inject/`)
   - `Injector.prepareFeed()` (root): `setenforce 0`, create `/data/local/tmp/camspoof` and the
     `feed`/`audio` files mode 0666 so both the CamSpoof uid and the target app uid can mmap them.
   - `Injector.inject(target)` (root): launch the target app if needed, resolve its pid, then
     `libfridainject.so -p <pid> -s agent.js -e` (eternalize). `InjectionParser` turns the agent's
     `csf:` output into an `InjectionResult` shown in the UI.
   - `TargetApp`/`AppRepository` list launchable installed apps for the picker.

4. **Frida agent** (`agent/index.js` → `app/src/main/assets/agent.js`)
   Injected into the target. Maps the feed (seqlock reader), runs a **reconnaissance** pass, and
   installs best-effort delivery hooks:
   - **Recon/diagnostics:** logs how the app uses the camera — legacy vs Camera2, `openCamera` id,
     `createCaptureSession` output Surfaces, `ImageReader` format/size — to
     `/data/local/tmp/camspoof/agent.log` and as `csf:log` lines. The app's **Diag** button surfaces
     it. This is how we learn which frame path a specific app uses so we can target the right swap.
   - **Legacy `android.hardware.Camera`** delivery: stores the app's `PreviewCallback` and drives it
     from a timer with our NV21 frames.
   - **`android.media.ImageReader`** delivery (Camera2/CameraX apps that read `YUV_420_888`):
     overwrites the Y/U/V planes of each acquired `Image`.
   All hooks are wrapped so a failure can never crash the host app. Emits
   `csf:loaded/feed/hook/log/ready/error`.

## Data flow

```
 lens preview ─┐                       ┌─ image overlay (Bitmap)
               ├─ FrameCompositor ◄────┤
 user gestures ┘   (Canvas @30fps)     └─ video overlay (TextureView.bitmap, MediaPlayer audio)
        │
        ▼  ARGB8888 → NV21
 FeedWriter.writeFrame  ──mmap seqlock──►  /data/local/tmp/camspoof/feed
                                                      │
 Injector.inject(target):                             │ (read, tear-free)
   su → frida-inject -p <pid> -s agent.js -e          ▼
        └──────────────► agent in TARGET app: hooks Camera / ImageReader → app sees our frames
```

## Key design decisions

- **Per-app Frida injection, not an LSPosed module or a global HAL swap.** Zygisk is unavailable on
  this device, and `system_server`/`cameraserver` injection is blocked by Knox/seccomp here (seen
  with flagsecure). A normal app process is far less hardened, so attaching to the chosen target is
  the most reliable path without Zygisk.
- **NV21 over the wire.** Camera callbacks and most `ImageReader` consumers want YUV 4:2:0; doing
  the ARGB→NV21 conversion on the app side (fast, native-ish) keeps the Frida JS agent free of
  per-pixel work it could never do at frame rate.
- **Shared mmap + seqlock.** A single-slot memory-mapped file is the simplest zero-copy-ish,
  lock-free transport between two unrelated processes; the seqlock avoids torn frames.
- **Normalized transforms** keep the on-screen composition and the injected frame identical without
  threading pixel geometry through both paths.
- **Agent precompiled in assets, binary in jniLibs (legacy packaging).** Same rationale as
  flagsecure: the device needs no Frida/Node toolchain, and `frida-inject` must be extracted to
  `nativeLibraryDir` to be executable (Android W^X).
- **One APK per ABI; binaries/agent not committed** (downloaded/generated at build time).

## Coverage & roadmap

- **Covered now:** lens selection, image + video overlays with full gesture editing, audio mixer
  (live for video playback), feed production, root setup, injection, legacy-Camera frame delivery,
  Camera2 `ImageReader` (YUV) delivery.
- **Not yet (next iterations):**
  - **Camera2 preview-only apps** that render to a `SurfaceTexture`/`SurfaceView` and never read
    pixels back need a GL producer bound onto the app's output `Surface` (hook
    `CameraDevice.createCaptureSession`, swap the producer). This is the main gap for modern apps.
  - **Audio injection** into the target (hook `AudioRecord`) so the mixer's mic/video audio reaches
    the app, not just local playback. The `audio` feed file and `micGain` are already plumbed for
    this.
  - **GL compositor** to replace the CPU Canvas path for full-rate HD.
  - Resolution/orientation negotiation with the target's requested sizes.

## Platform constraints

- **Root required** (KernelSU on the Fold7). No root → the Start button is disabled and the UI says
  so.
- **SELinux** is set permissive during a session to share the 0666 feed file; firmware that
  re-enforces can break the feed/injection. Reported, not silent.
- **Hardened apps** (banking, some messengers, DRM video) block Frida/`ptrace`; those targets will
  fail to inject by design.
- **Android-version coupling:** camera class/method names are AOSP/vendor internals; the agent skips
  unknown targets and reports how many hooks applied.

## Security / threat model

Local power-user tool for the device owner; not a networked service.

- **No network.** No INTERNET permission, no servers, no telemetry. The usual auth/transport/token
  surface does not exist.
- **Trust boundary is root.** The app already runs with the user's granted root. The only inputs to
  the root commands are the bundled agent/binary and the chosen target package name (shell-quoted);
  no network- or attacker-controlled data reaches `su`.
- **Shared-feed exposure.** While a session is active, SELinux is permissive and the feed file is
  mode 0666 under `/data/local/tmp`, so any local process could read the composited frames (not a
  secret: it is media the user chose) or write to it. The window is the session; stopping restores
  nothing by itself (reboot restores SELinux enforcing). This is the accepted tradeoff, same shape
  as flagsecure's.
- **Integrity of shipped artifacts.** The agent is compiled from `agent/index.js` in this repo; the
  binary is a pinned `frida-inject` release fetched by `scripts/fetch-frida.sh` (verify checksums
  when bumping `FRIDA_VERSION`).
- **Abuse consideration.** Swapping a camera's frame source on the owner's own device is a local
  content/testing tool. It does **not** defeat hardware attestation, Widevine, or server-side
  liveness that relies on signed hardware; and it affects only the single app the owner targets on
  their own phone.
- **Secrets.** None in the repo or APK. Release signing keys live only in CI secrets /
  `keystore.properties` (never committed).
