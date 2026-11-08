# AGENTS.md — notes for AI agents working on this repo

Self-contained orientation. Read this before touching anything; deeper rationale is in
[ARCHITECTURE.md](ARCHITECTURE.md).

## What this is

**CamSpoof** is a one-screen Android "virtual camera" tool for the owner's **rooted** Galaxy Z Fold7
(One UI 8 / Android 16, KernelSU — Zygisk/LSPosed unavailable). It:

1. Shows a live preview of a chosen physical lens and lets you drop **image** and **video**
   overlays on top that can be **dragged, pinched-resized and rotated**, with a **volume mixer**
   (microphone + per-video audio).
2. Composites camera + overlays into one frame stream and, using **root**, injects a **Frida**
   agent into a **chosen target app** so that app receives the composited feed in place of its real
   camera (per-app Camera2/legacy-Camera hook — no Zygisk).

It is the sibling of the owner's `flagsecure-off` app and reuses the same proven injection
scaffolding (`su` + `frida-inject -e`), but targets a third-party app instead of `system_server`.

> Stage note: this is an early build. The compositor + UI + injector + legacy-Camera hook are the
> validated core; the Camera2 **preview-only** path (apps that render to a Surface and never read
> pixels) is not yet covered — see ARCHITECTURE.md "Coverage & roadmap". Don't claim full coverage
> in UI copy or README.

## Layout

```
app/                     Android app module (Kotlin + Compose + CameraX)
  src/main/
    java/io/github/revocx35/camspoof/
      MainActivity.kt           Compose host
      ui/
        CameraScreen.kt         preview + lens chips + add/delete + target + Start/Stop + mixer
        OverlayLayer.kt         draggable/rotatable/resizable overlays; video via TextureView
        VolumeMixer.kt          mic + per-video volume sliders
        TargetPickerDialog.kt   searchable installed-app list
        CamSpoofViewModel.kt    ties camera/overlays/compositor/injector; implements SceneProvider
      camera/
        CameraController.kt     CameraX: enumerate physical lenses, bind preview
        FrameCompositor.kt      Canvas composite (worker thread) -> NV21 -> feed
        YuvConvert.kt           ARGB_8888 -> NV21 (BT.601)
      media/OverlayItem.kt      overlay model + transform + volume (Compose state) + OverlayStore
      feed/
        FeedFormat.kt           shared-memory header layout + sizes (mirrors agent/index.js)
        FeedWriter.kt           mmap seqlock writer
      inject/
        Injector.kt             the ONLY root code: prepareFeed() + inject(target)
        InjectionParser.kt      pure parser of the agent's csf: output (unit-tested)
        TargetApp.kt            installed launchable-app listing
    assets/agent.js             compiled Frida agent (GENERATED from agent/ — not committed)
    jniLibs/<abi>/libfridainject.so   frida-inject binary (DOWNLOADED — not committed)
  src/test/.../                 JVM unit tests (InjectionParser, FeedFormat)
agent/                   Frida agent source (frida-compile)
  index.js               feed reader + camera hooks; emits csf: lines
scripts/
  fetch-frida.sh         download frida-inject into jniLibs (run before building)
  emulator.sh            headless API-37 emulator for install/launch smoke tests
```

## Build & test commands

Prereqs: JDK 17/21, Android SDK at `/opt/android-sdk` (`local.properties` `sdk.dir`), Node for the
agent. On this host wrap Gradle/emulator in `/usr/local/bin/heavy` and build with
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (a JRE-only `java-25` is also installed and Gradle
may otherwise auto-pick it and fail with "does not provide JAVA_COMPILER").

```bash
# 1. Build the Frida agent (regenerates app/src/main/assets/agent.js)
cd agent && npm install && npm run build && cd ..

# 2. Fetch the native frida-inject binaries into app/src/main/jniLibs/
scripts/fetch-frida.sh            # or set FRIDA_VERSION=...

# 3. Build / test
heavy ./gradlew :app:testDebugUnitTest   # JVM unit tests
heavy ./gradlew :app:assembleDebug       # per-ABI debug APKs
heavy ./gradlew :app:assembleRelease     # per-ABI release APKs (signed if keystore.properties)
```

APKs: `app/build/outputs/apk/{debug,release}/app-<abi>-<type>.apk`. **One APK per ABI**
(arm64-v8a for phones, x86_64 for emulators); no universal APK (frida-inject is large).

## Conventions

- Package / applicationId `io.github.revocx35.camspoof`; compile/targetSdk 37, minSdk 26.
- Versions from `gradle/libs.versions.toml` (AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, CameraX 1.5.0).
- The agent prints machine lines prefixed `csf:` (`loaded`, `feed <w>x<h>`, `hook <api> ok|skip`,
  `ready`, `error <msg>`, `no-pid`). `InjectionParser.parse` is the single parser and is
  unit-tested — keep them in step.
- The feed header offsets in `feed/FeedFormat.kt` and `agent/index.js` are one wire format. Change
  both together; `FeedFormatTest` guards `OFF_SEQ`/`HEADER_SIZE`.
- The on-screen overlay transforms and the compositor use the **same normalized math** (centre as a
  fraction, width as a fraction of surface width) so screen == injected frame. Keep them aligned.

## Never do

- **Never commit** `app/src/main/assets/agent.js`, anything under `app/src/main/jniLibs/`,
  `third_party/`, `keystore.properties`, `*.jks`, `local.properties`, build output. They are in
  `.gitignore`; they are generated or downloaded.
- **Never set** `jniLibs.useLegacyPackaging = false`. frida-inject is *executed*, not linked, so it
  must be extracted to `nativeLibraryDir` (same hard rule as flagsecure).
- **Never add INTERNET permission.** CamSpoof is fully local; no network, no telemetry.
- **Never run live injection into a real target app as part of automated testing here.** The
  emulator has no `su`. Verify build + install + launch + that `libfridainject.so --help` runs;
  leave end-to-end injection to the owner on the device.
- Don't add a universal APK or Play dependency metadata (`dependenciesInfo` is off).

## Gotchas

- Shell `$` inside Kotlin strings must be written `${'$'}` (see `Injector.kt`) or Kotlin treats it
  as a template.
- `prepareFeed()` sets **SELinux permissive** (`setenforce 0`) so the unprivileged CamSpoof and
  target uids can both mmap the 0666 feed file. This is the same tradeoff flagsecure makes; it is
  documented in the threat model. If a firmware re-enforces, injection/feed sharing can fail — the
  UI surfaces it.
- Frida injecting into a **third-party app** is far more reliable than into `system_server` (which
  Knox/seccomp blocks on this Fold7 — see the flagsecure debug notes). Still, some hardened apps
  (banking/DRM) block `ptrace`/Frida; failure is reported, not silent.
- The compositor is **CPU Canvas** compositing at 1280x720 — fine to validate, not final. A GL path
  is the planned optimization (ARCHITECTURE.md "Performance").
- `TextureView.bitmap` / `PreviewView.bitmap` must be read on the main thread; the compositor pulls
  the scene on main and composites on a worker (see `FrameCompositor.fetchSceneOnMain`).
- DRM/attestation: this does not defeat hardware attestation or Widevine; it is a frame-source
  swap, out of scope for protected pipelines.
