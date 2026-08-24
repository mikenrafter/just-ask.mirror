package dev.justask.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the idle orchestrator foreground service after boot.
 *
 * Declare exported with BOOT_COMPLETED in the app manifest. Disable by default and call
 * [JustAsk.setBootReceiverEnabled] when the user opts in.
 */
class JustAskBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (JustAsk.shouldDeferToJustAskApp(context)) {
            Log.i(TAG, "BOOT_COMPLETED — Just Ask app present, stepping down")
            JustAsk.relinquishIfOrchestratorPresent(context)
            return
        }
        Log.i(TAG, "BOOT_COMPLETED — posting idle launch notification")
        JustAsk.showIntentNotification(context)
    }

    companion object {
        private const val TAG = "JustAskBootReceiver"
    }
}
