package io.github.revocx35.camspoof.inject

import android.content.Context
import java.io.File

/** Outcome of an injection attempt; [InjectionParser] builds it from the agent's stdout/stderr. */
data class InjectionResult(
    val success: Boolean,
    val summary: String,
    val feed: String?,        // e.g. "1280x720" if the agent mapped the feed
    val hooks: List<String>,  // hook targets the agent reported installing
    val raw: String,
)

/**
 * The only code that touches root. Mirrors the proven flagsecure flow (su + `frida-inject -e`) but
 * targets a chosen third-party app instead of system_server:
 *
 *  1. [prepareFeed] (as root): SELinux permissive + create the shared 0666 feed/audio files so the
 *     unprivileged CamSpoof and target uids can both mmap them.
 *  2. [inject] (as root): ensure the target app is running, then
 *     `frida-inject -p <target_pid> -s agent.js -e`, loading the agent that replaces the app's
 *     camera frames with CamSpoof's composited feed.
 *
 * Shell `$` inside Kotlin strings is written `${'$'}` so Kotlin does not treat it as a template
 * (same gotcha as flagsecure — see AGENTS.md).
 */
class Injector(private val context: Context) {

    fun hasRoot(): Boolean {
        val out = runSu("id -u").trim()
        return out.lineSequence().any { it.trim() == "0" }
    }

    /** Create the shared feed files and set SELinux permissive. Returns a status message. */
    fun prepareFeed(): String {
        val script = buildString {
            append("setenforce 0 2>/dev/null; ")
            append("mkdir -p /data/local/tmp/camspoof && chmod 777 /data/local/tmp/camspoof; ")
            append("for f in feed audio; do ")
            append("f=/data/local/tmp/camspoof/${'$'}f; ")
            append("[ -e \"${'$'}f\" ] || : > \"${'$'}f\"; chmod 666 \"${'$'}f\"; done; ")
            append("echo csf:setup-ok; echo csf:selinux ${'$'}(getenforce 2>/dev/null)")
        }
        return runSu(script)
    }

    /**
     * Inject the agent into [target]. Copies the compiled agent next to the native injector, makes
     * sure the app is running, then eternalizes the agent into its process.
     */
    fun inject(target: TargetApp): InjectionResult {
        val agent = stageAgent()
        val injector = locateInjector()
            ?: return InjectionResult(false, "frida-inject binary not found in nativeLibraryDir.", null, emptyList(), "")

        val pkg = shellQuote(target.packageName)
        val script = buildString {
            // Launch the app if it is not already running, then resolve its pid.
            append("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; ")
            append("for i in 1 2 3 4 5 6 7 8 9 10; do ")
            append("PID=${'$'}(pidof $pkg 2>/dev/null | tr ' ' '\\n' | head -n1); ")
            append("[ -n \"${'$'}PID\" ] && break; sleep 0.3; done; ")
            append("if [ -z \"${'$'}PID\" ]; then echo csf:no-pid; exit 1; fi; ")
            append("echo csf:pid ${'$'}PID; ")
            append("${shellQuote(injector.absolutePath)} -p \"${'$'}PID\" -s ${shellQuote(agent.absolutePath)} -e 2>&1")
        }
        val raw = runSu(script)
        return InjectionParser.parse(raw, target.label)
    }

    // --- internals ---

    /** Copy the compiled Frida agent from assets into filesDir where root can read it. */
    private fun stageAgent(): File {
        val dest = File(context.filesDir, "agent.js")
        context.assets.open("agent.js").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        dest.setReadable(true, false)
        return dest
    }

    /** frida-inject ships as libfridainject.so under nativeLibraryDir (executable there). */
    private fun locateInjector(): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, "libfridainject.so")
        return if (f.exists()) f else null
    }

    private fun runSu(script: String): String = try {
        val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Exception) {
        "csf:error ${e.message}"
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
