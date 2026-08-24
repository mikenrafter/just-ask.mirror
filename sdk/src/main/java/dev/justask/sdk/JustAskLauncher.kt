package dev.justask.sdk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Launches configured trampoline activities from an eligible Activity context.
 *
 * Must not be called from a [android.app.Service] or [android.content.BroadcastReceiver]
 * on API 35+: Android's while-in-use check requires a user-action Activity token.
 */
object JustAskLauncher {

    private const val TAG = "JustAskLauncher"

    data class LaunchResult(
        val target: JustAskTarget,
        val launched: Boolean,
        val error: String? = null,
    )

    fun launchAll(
        activity: Activity,
        targets: Iterable<JustAskTarget>,
        sessionToken: String? = null,
    ): List<LaunchResult> {
        val callerPackage = activity.packageName
        return targets
            .filter { it.enabled }
            .filter { target ->
                val pkg = target.componentPackage
                // Never launch the orchestrator's own components from the enable trampoline.
                pkg == null || pkg != callerPackage
            }
            .map { target ->
                launchOne(activity, target, callerPackage, sessionToken)
            }
    }

    fun launchOne(
        activity: Activity,
        target: JustAskTarget,
        callerPackage: String = activity.packageName,
        sessionToken: String? = null,
    ): LaunchResult {
        if (!target.enabled) {
            return LaunchResult(target, launched = false, error = "disabled")
        }
        return try {
            val intent = target.toLaunchIntent(callerPackage, sessionToken)
            activity.startActivity(intent)
            LaunchResult(target, launched = true)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Trampoline missing for ${target.displayLabel}", e)
            LaunchResult(target, launched = false, error = e.message)
        } catch (e: SecurityException) {
            Log.w(TAG, "Denied launching ${target.displayLabel}", e)
            LaunchResult(target, launched = false, error = e.message)
        }
    }

    /**
     * Finds exported activities advertising [JustAskContract.ACTION_TRAMPOLINE_PROVIDER].
     */
    fun discoverProviders(context: Context): List<JustAskTarget> {
        val pm = context.packageManager
        val probe = Intent(JustAskContract.ACTION_TRAMPOLINE_PROVIDER)
        val matches = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        return matches.mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            JustAskTarget.component(
                id = "${activityInfo.packageName}/${activityInfo.name}",
                packageName = activityInfo.packageName,
                activityClass = activityInfo.name,
                label = activityInfo.loadLabel(pm).toString(),
            )
        }.distinctBy { it.id }
    }
}
