package dev.justask.sdk

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Entry point for orchestrator apps and libraries.
 */
object JustAsk {

    private const val TAG = "JustAsk"

    /**
     * Launch every enabled target from an Activity context (notification tap, in-app button, etc.).
     */
    @JvmStatic
    fun launchTargets(
        activity: android.app.Activity,
        targets: Iterable<JustAskTarget>,
        sessionToken: String? = null,
    ): List<JustAskLauncher.LaunchResult> {
        return JustAskLauncher.launchAll(activity, targets, sessionToken)
    }

    /**
     * Launch every enabled target, then optionally notify [JustAskBootForegroundService].
     */
    @JvmStatic
    fun enableFromActivity(
        activity: android.app.Activity,
        targets: Iterable<JustAskTarget>,
        notifyService: Boolean = true,
    ): List<JustAskLauncher.LaunchResult> {
        val results = launchTargets(activity, targets)
        if (notifyService) {
            startHostService(activity, JustAskContract.ACTION_ENABLE)
        }
        return results
    }

    /**
     * Starts the idle boot orchestrator if [startOnBoot] is enabled.
     */
    @JvmStatic
    fun startBootOrchestratorIfEnabled(context: Context) {
        if (!JustAskBootPreferences(context).startOnBoot) {
            Log.d(TAG, "startOnBoot disabled — skipping boot orchestrator")
            return
        }
        startHostService(context, JustAskContract.ACTION_BOOT)
    }

    /**
     * Resolves the host app's [JustAskBootForegroundService] subclass from
     * application meta-data [JustAskContract.META_BOOT_SERVICE].
     */
    @JvmStatic
    fun resolveBootServiceClass(context: Context): Class<out Service>? {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        val declared = appInfo.metaData?.getString(JustAskContract.META_BOOT_SERVICE) ?: return null
        val fqcn = if (declared.startsWith(".")) context.packageName + declared else declared
        @Suppress("UNCHECKED_CAST")
        return Class.forName(fqcn) as Class<out Service>
    }

    @JvmStatic
    fun startHostService(context: Context, action: String) {
        val serviceClass = resolveBootServiceClass(context)
        if (serviceClass == null) {
            Log.e(TAG, "Missing ${JustAskContract.META_BOOT_SERVICE} application meta-data")
            return
        }
        val intent = Intent(context, serviceClass).apply { this.action = action }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Enables or disables the boot [android.content.BroadcastReceiver] component.
     */
    @JvmStatic
    fun setBootReceiverEnabled(context: Context, enabled: Boolean) {
        val receiver = android.content.ComponentName(context, JustAskBootReceiver::class.java)
        context.packageManager.setComponentEnabledSetting(
            receiver,
            if (enabled) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
    }

    @JvmStatic
    fun isBootReceiverEnabled(context: Context): Boolean {
        val receiver = android.content.ComponentName(context, JustAskBootReceiver::class.java)
        val state = context.packageManager.getComponentEnabledSetting(receiver)
        return state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
