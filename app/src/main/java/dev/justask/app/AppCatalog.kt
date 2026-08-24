package dev.justask.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import dev.justask.sdk.JustAskContract
import dev.justask.sdk.JustAskTarget

data class LaunchableIntent(
    val packageName: String,
    val activityClass: String,
    val activityLabel: String,
    val actions: List<String>,
    val isTrampolineProvider: Boolean,
) {
    val id: String get() = "$packageName/$activityClass"

    val subtitle: String
        get() = when {
            actions.isNotEmpty() -> actions.joinToString(" · ")
            else -> activityClass
        }

    fun toTarget(enabled: Boolean = true): JustAskTarget = JustAskTarget.component(
        id = id,
        packageName = packageName,
        activityClass = activityClass,
        label = activityLabel,
        enabled = enabled,
    )
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val intents: List<LaunchableIntent>,
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return label.contains(q, ignoreCase = true) ||
            packageName.contains(q, ignoreCase = true) ||
            intents.any {
                it.activityLabel.contains(q, ignoreCase = true) ||
                    it.activityClass.contains(q, ignoreCase = true) ||
                    it.actions.any { action -> action.contains(q, ignoreCase = true) }
            }
    }
}

/**
 * Builds an installed-app catalog with each package's exported, launchable activities.
 *
 * Actions are discovered by probing common / Just Ask intent filters and attaching
 * matching actions to the corresponding activity components.
 */
object AppCatalog {

    private val PROBE_ACTIONS = listOf(
        JustAskContract.ACTION_TRAMPOLINE_PROVIDER,
        Intent.ACTION_MAIN,
        Intent.ACTION_VIEW,
        Intent.ACTION_EDIT,
        Intent.ACTION_SEND,
        Intent.ACTION_SENDTO,
        Intent.ACTION_DIAL,
        Intent.ACTION_CALL,
        "android.settings.APPLICATION_DETAILS_SETTINGS",
        "android.intent.action.CREATE_SHORTCUT",
    )

    fun load(pm: PackageManager): List<InstalledApp> {
        val actionsByComponent = mutableMapOf<String, MutableSet<String>>()

        for (action in PROBE_ACTIONS) {
            val probe = Intent(action)
            if (action == Intent.ACTION_MAIN) {
                probe.addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val matches = queryActivities(pm, probe)
            for (resolve in matches) {
                val info = resolve.activityInfo ?: continue
                val key = "${info.packageName}/${info.name}"
                actionsByComponent.getOrPut(key) { linkedSetOf() }.add(action)
            }
        }

        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        }

        return packages.mapNotNull { pkg ->
            val activities = pkg.activities ?: return@mapNotNull null
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val intents = activities
                .asSequence()
                .filter { it.exported }
                .map { activity ->
                    val key = "${activity.packageName}/${activity.name}"
                    val actions = actionsByComponent[key]?.toList().orEmpty()
                    val label = try {
                        activity.loadLabel(pm).toString()
                    } catch (_: Exception) {
                        activity.name.substringAfterLast('.')
                    }
                    LaunchableIntent(
                        packageName = activity.packageName,
                        activityClass = activity.name,
                        activityLabel = label,
                        actions = actions,
                        isTrampolineProvider = JustAskContract.ACTION_TRAMPOLINE_PROVIDER in actions,
                    )
                }
                .sortedWith(
                    compareByDescending<LaunchableIntent> { it.isTrampolineProvider }
                        .thenByDescending { Intent.ACTION_MAIN in it.actions }
                        .thenBy { it.activityLabel.lowercase() },
                )
                .toList()

            if (intents.isEmpty()) return@mapNotNull null

            val label = try {
                appInfo.loadLabel(pm).toString()
            } catch (_: Exception) {
                pkg.packageName
            }
            val icon = try {
                appInfo.loadIcon(pm)
            } catch (_: Exception) {
                null
            }

            InstalledApp(
                packageName = pkg.packageName,
                label = label,
                icon = icon,
                intents = intents,
            )
        }.sortedWith(
            compareByDescending<InstalledApp> { app ->
                app.intents.any { it.isTrampolineProvider }
            }.thenBy { it.label.lowercase() },
        )
    }

    private fun queryActivities(pm: PackageManager, probe: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                probe,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        }
}
