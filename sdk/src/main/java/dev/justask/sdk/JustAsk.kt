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
     * Prefer toggling the boot receiver directly ([setBootReceiverEnabled]); this
     * helper exists for callers that still mirror the legacy preference.
     */
    @JvmStatic
    fun startBootOrchestratorIfEnabled(context: Context) {
        if (!isBootReceiverEnabled(context)) {
            Log.d(TAG, "boot receiver disabled — skipping boot orchestrator")
            return
        }
        showIntentNotification(context)
    }

    /**
     * Starts the idle foreground service and posts the tap-to-launch notification.
     *
     * SDK host apps step down when the standalone Just Ask app is installed: that app
     * discovers trampolines via [JustAskContract.ACTION_TRAMPOLINE_PROVIDER] and posts
     * one consolidated notification. The Just Ask app itself never steps down.
     *
     * @return true if the host service was started, false if this call stepped down
     *   or could not resolve a boot service.
     */
    @JvmStatic
    fun showIntentNotification(context: Context): Boolean {
        if (shouldDeferToJustAskApp(context)) {
            Log.i(TAG, "Just Ask app present — deferring boot notification to it")
            stopHostService(context)
            return false
        }
        return startHostService(context, JustAskContract.ACTION_SHOW_IDLE)
    }

    /**
     * True when another package should own the boot notification: the Just Ask app is
     * installed and this process is not that app.
     */
    @JvmStatic
    fun shouldDeferToJustAskApp(context: Context): Boolean {
        if (context.packageName == JustAskContract.JUST_ASK_APP_PACKAGE) return false
        return isJustAskAppInstalled(context)
    }

    /** Returns true if the standalone Just Ask orchestrator app is installed. */
    @JvmStatic
    fun isJustAskAppInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(JustAskContract.JUST_ASK_APP_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
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
        val fqcn = appInfo.metaData?.getString(JustAskContract.META_BOOT_SERVICE) ?: return null
        @Suppress("UNCHECKED_CAST")
        return Class.forName(fqcn) as Class<out Service>
    }

    @JvmStatic
    fun startHostService(context: Context, action: String): Boolean {
        val serviceClass = resolveBootServiceClass(context)
        if (serviceClass == null) {
            Log.e(TAG, "Missing ${JustAskContract.META_BOOT_SERVICE} application meta-data")
            return false
        }
        val intent = Intent(context, serviceClass).apply { this.action = action }
        ContextCompat.startForegroundService(context, intent)
        return true
    }

    /** Stops the host idle orchestrator if it is declared and running. */
    @JvmStatic
    fun stopHostService(context: Context) {
        val serviceClass = resolveBootServiceClass(context) ?: return
        context.stopService(Intent(context, serviceClass))
    }

    /**
     * If the Just Ask app is present, stop this host's idle notification service so
     * only the orchestrator keeps a boot notification up.
     */
    @JvmStatic
    fun relinquishIfOrchestratorPresent(context: Context) {
        if (!shouldDeferToJustAskApp(context)) return
        Log.i(TAG, "Just Ask app present — stopping host boot service")
        stopHostService(context)
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
        // DEFAULT means the manifest value applies; treat it as enabled unless the manifest
        // declares android:enabled="false" (which sets DISABLED at install time).
        return state != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}
