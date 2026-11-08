package io.github.revocx35.camspoof.inject

/**
 * Pure parser for the combined stdout/stderr of the inject step. Kept free of Android types so it
 * can be unit-tested on the JVM (see InjectionParserTest). The agent and setup emit machine lines:
 *
 *   csf:setup-ok                 feed files created
 *   csf:selinux <mode>           SELinux mode after setenforce
 *   csf:pid <n>                  resolved target pid
 *   csf:no-pid                   target app has no running process
 *   csf:loaded                   agent script loaded into the target
 *   csf:feed <w>x<h>             agent mapped the shared feed at that size
 *   csf:hook <name> ok|skip      a camera API hook installed or skipped
 *   csf:ready                    agent is live and serving frames
 *   csf:error <message>          fatal error
 */
object InjectionParser {

    fun parse(raw: String, targetLabel: String): InjectionResult {
        var loaded = false
        var ready = false
        var feed: String? = null
        var noPid = false
        val hooks = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (line in raw.lineSequence()) {
            val t = line.trim()
            when {
                t == "csf:loaded" -> loaded = true
                t == "csf:ready" -> ready = true
                t == "csf:no-pid" -> noPid = true
                t.startsWith("csf:feed ") -> feed = t.removePrefix("csf:feed ").trim()
                t.startsWith("csf:hook ") -> {
                    val body = t.removePrefix("csf:hook ").trim()
                    if (body.endsWith(" ok")) hooks.add(body.removeSuffix(" ok").trim())
                }
                t.startsWith("csf:error ") -> errors.add(t.removePrefix("csf:error ").trim())
            }
        }

        val success = ready && hooks.isNotEmpty() && errors.isEmpty()
        val summary = when {
            noPid -> "Target app isn't running and couldn't be launched."
            errors.isNotEmpty() -> "Injection failed: ${errors.first()}"
            !loaded -> "Agent did not load into $targetLabel (frida-inject may be blocked)."
            !ready -> "Agent loaded but did not become ready."
            hooks.isEmpty() -> "Agent ready but installed no camera hooks (unknown camera API?)."
            else -> "Spoofing $targetLabel — ${hooks.size} camera hook(s) active${feed?.let { " @ $it" } ?: ""}."
        }
        return InjectionResult(success, summary, feed, hooks, raw)
    }
}
