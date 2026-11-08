# CamSpoof

A one-screen Android **virtual camera** tool for **rooted** devices. It shows a live preview of a
chosen lens, lets you drop **image** and **video** overlays on top — each **draggable, resizable and
rotatable** — with a **volume mixer** (microphone + per-video audio), and then uses root to feed the
composited result into a **chosen app's** camera via a **Frida** hook. No Zygisk, no LSPosed.

Built for the owner's Galaxy Z Fold7 (One UI 8 / Android 16, KernelSU), where Zygisk — and so the
usual LSPosed virtual-camera modules — is unavailable. It injects a per-app agent from a root shell
instead, the same approach as the sibling `flagsecure-off` app.

> **Root required. Use only on a device and apps you own.** This is a local tool: no network, no
> telemetry. It does **not** defeat hardware attestation or DRM (Widevine), and some hardened apps
> block injection. This is an early build — see **Status** below for what works today.

## What it does

- **Pick a lens** — chips list the phone's physical cameras (front/back/ultrawide/tele).
- **Add image / Add video** — overlays appear on the preview; drag to move, pinch to resize, twist
  to rotate, tap to select, Delete to remove.
- **Volumes** — a microphone channel and one slider per video overlay (video sliders drive live
  playback volume).
- **Choose target app → Start spoofing** — CamSpoof sets up the shared feed (root), injects the
  agent into that app, and the app starts receiving your composited frames. Open the camera in the
  target app **after** Start (if it was already open, close and reopen it).
- **Diag** — shows how the target app actually used the camera (legacy vs Camera2, formats, session
  surfaces) and lets you share the log. Use it when an app doesn't pick up the feed.

## How it works

1. A compositor blends the lens preview + overlays into one NV21 frame stream (1280×720) written to
   a shared memory-mapped file.
2. As root, CamSpoof runs `frida-inject -p <target_pid> -s agent.js -e`.
3. The agent, inside the target app, reads the feed and hooks the camera APIs so the app gets your
   frames instead of the sensor.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the design and [AGENTS.md](AGENTS.md) for build/test.

## Status

Working: lens selection, image + video overlays with gesture editing, the mixer, feed production,
root setup, injection, delivery to **legacy `Camera`** apps and **Camera2/CameraX apps that read
`YUV_420_888` via `ImageReader`**.

Not yet: Camera2 **preview-only** apps (render to a Surface, never read pixels) — these need a GL
producer swap, the next iteration; **audio injection** into the target (mixer is local-playback for
now); a GL compositor for full-rate HD. Details in ARCHITECTURE.md "Coverage & roadmap".

## Build from source

```bash
cd agent && npm install && npm run build && cd ..   # compile the Frida agent
scripts/fetch-frida.sh                               # download frida-inject into jniLibs
./gradlew :app:assembleRelease                       # APKs in app/build/outputs/apk/release/
```

Install the **arm64-v8a** APK on the phone and grant CamSpoof root in KernelSU.

## Credits

Injection uses [Frida](https://frida.re). Licensed under GPL-3.0 — see [LICENSE](LICENSE).
