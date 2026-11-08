package io.github.revocx35.camspoof.inject

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** An installed, launchable app that can be chosen as the injection target. */
data class TargetApp(
    val packageName: String,
    val label: String,
)

object AppRepository {
    /** Launchable third-party + system apps, sorted by label, excluding CamSpoof itself. */
    fun installedTargets(context: Context): List<TargetApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val self = context.packageName
        val selfBase = self.removeSuffix(".debug")
        val flags = PackageManager.ResolveInfoFlags.of(0L)
        return pm.queryIntentActivities(intent, flags)
            .asSequence()
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == self || pkg == selfBase) return@mapNotNull null
                TargetApp(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
