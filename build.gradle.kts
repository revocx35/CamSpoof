// Top-level build file. The single :app module is a Compose + CameraX "virtual camera" compositor
// that, on a rooted device, injects a Frida agent (app/src/main/assets/agent.js, built from
// agent/) into a chosen TARGET app and feeds it a composited image/video stream in place of the
// real camera frames. See ARCHITECTURE.md.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
