package dev.justask.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import dev.justask.sdk.JustAskContract
import dev.justask.sdk.JustAskTarget

enum class ComponentKind {
    ACTIVITY,
    SERVICE,
    RECEIVER,
}

data class CatalogComponent(
    val packageName: String,
    val className: String,
    val label: String,
    val kind: ComponentKind,
    val exported: Boolean,
    val enabled: Boolean,
    val actions: List<String>,
    val isTrampolineProvider: Boolean,
    val isBootService: Boolean,
) {
    val id: String get() = "$packageName/$className"

    val canLaunch: Boolean get() = kind == ComponentKind.ACTIVITY && exported

    val kindLabel: String
        get() = when (kind) {
            ComponentKind.ACTIVITY -> "Activity"
            ComponentKind.SERVICE -> "Service"
            ComponentKind.RECEIVER -> "Receiver"
        }

    fun toTarget(enabled: Boolean = true): JustAskTarget = JustAskTarget.component(
        id = id,
        packageName = packageName,
        activityClass = className,
        label = label,
        enabled = enabled,
    )
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSdkHost: Boolean,
    val bootServiceClass: String?,
    val components: List<CatalogComponent>,
) {
    val launchableCount: Int get() = components.count { it.canLaunch }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return label.contains(q, ignoreCase = true) ||
            packageName.contains(q, ignoreCase = true) ||
            (isSdkHost && (
                q.contains("sdk", ignoreCase = true) ||
                    q.contains("just ask", ignoreCase = true) ||
                    q.contains("justask", ignoreCase = true)
                )) ||
            components.any { component ->
                component.label.contains(q, ignoreCase = true) ||
                    component.className.contains(q, ignoreCase = true) ||
                    component.kindLabel.contains(q, ignoreCase = true) ||
                    component.actions.any { it.contains(q, ignoreCase = true) }
            }
    }
}

/**
 * Installed-app catalog: activities, services, and receivers, plus Just Ask SDK hosts
 * declared via [JustAskContract.META_BOOT_SERVICE].
 */
object AppCatalog {

    private val ACTIVITY_PROBE_ACTIONS = listOf(
        JustAskContract.ACTION_TRAMPOLINE_PROVIDER,
        Intent.ACTION_MAIN,
        Intent.ACTION_VIEW,
        Intent.ACTION_EDIT,
        Intent.ACTION_SEND,
        Intent.ACTION_SENDTO,
    )

    private val SERVICE_PROBE_ACTIONS = listOf(
        JustAskContract.ACTION_BOOT,
        JustAskContract.ACTION_SHOW_IDLE,
        JustAskContract.ACTION_ENABLE,
        JustAskContract.ACTION_TRAMPOLINE_PROVIDER,
    )

    private val RECEIVER_PROBE_ACTIONS = listOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_LOCKED_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
    )

    fun load(pm: PackageManager): List<InstalledApp> {
        val actionsByComponent = mutableMapOf<String, MutableSet<String>>()

        fun record(packageName: String, className: String, action: String) {
            actionsByComponent.getOrPut("$packageName/$className") { linkedSetOf() }.add(action)
        }

        for (action in ACTIVITY_PROBE_ACTIONS) {
            val probe = Intent(action)
            if (action == Intent.ACTION_MAIN) probe.addCategory(Intent.CATEGORY_LAUNCHER)
            for (resolve in queryActivities(pm, probe)) {
                val info = resolve.activityInfo ?: continue
                record(info.packageName, info.name, action)
            }
        }
        for (action in SERVICE_PROBE_ACTIONS) {
            for (resolve in queryServices(pm, Intent(action))) {
                val info = resolve.serviceInfo ?: continue
                record(info.packageName, info.name, action)
            }
        }
        for (action in RECEIVER_PROBE_ACTIONS) {
            for (resolve in queryReceivers(pm, Intent(action))) {
                val info = resolve.activityInfo ?: continue
                record(info.packageName, info.name, action)
            }
        }

        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_META_DATA
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        return packages.mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val bootService = resolveMetaClass(appInfo, pkg.packageName)
            val isSdkHost = bootService != null

            val components = buildList {
                pkg.activities.orEmpty().forEach { info ->
                    add(toComponent(pm, info, ComponentKind.ACTIVITY, actionsByComponent, bootService))
                }
                pkg.services.orEmpty().forEach { info ->
                    add(toComponent(pm, info, ComponentKind.SERVICE, actionsByComponent, bootService))
                }
                pkg.receivers.orEmpty().forEach { info ->
                    add(toComponent(pm, info, ComponentKind.RECEIVER, actionsByComponent, bootService))
                }
            }.sortedWith(componentComparator())

            if (components.isEmpty() && !isSdkHost) return@mapNotNull null

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
                isSdkHost = isSdkHost,
                bootServiceClass = bootService,
                components = components,
            )
        }.sortedWith(
            compareByDescending<InstalledApp> { it.isSdkHost }
                .thenByDescending { app -> app.components.any { it.isTrampolineProvider } }
                .thenBy { it.label.lowercase() },
        )
    }

    private fun resolveMetaClass(appInfo: ApplicationInfo, packageName: String): String? {
        val declared = appInfo.metaData?.getString(JustAskContract.META_BOOT_SERVICE) ?: return null
        return if (declared.startsWith(".")) packageName + declared else declared
    }

    private fun toComponent(
        pm: PackageManager,
        info: ComponentInfo,
        kind: ComponentKind,
        actionsByComponent: Map<String, Set<String>>,
        bootService: String?,
    ): CatalogComponent {
        val actions = actionsByComponent["${info.packageName}/${info.name}"]?.toList().orEmpty()
        val label = try {
            info.loadLabel(pm).toString()
        } catch (_: Exception) {
            info.name.substringAfterLast('.')
        }
        return CatalogComponent(
            packageName = info.packageName,
            className = info.name,
            label = label,
            kind = kind,
            exported = info.exported,
            enabled = info.enabled,
            actions = actions,
            isTrampolineProvider = JustAskContract.ACTION_TRAMPOLINE_PROVIDER in actions,
            isBootService = bootService != null && info.name == bootService,
        )
    }

    private fun componentComparator() = compareByDescending<CatalogComponent> { it.isTrampolineProvider }
        .thenByDescending { it.isBootService }
        .thenBy { it.kind.ordinal }
        .thenByDescending { it.exported }
        .thenBy { it.label.lowercase() }

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

    private fun queryServices(pm: PackageManager, probe: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(
                probe,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(probe, PackageManager.MATCH_ALL)
        }

    private fun queryReceivers(pm: PackageManager, probe: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryBroadcastReceivers(
                probe,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryBroadcastReceivers(probe, PackageManager.MATCH_ALL)
        }
}
