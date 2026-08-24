package dev.justask.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the idle orchestrator foreground service after boot.
 *
 * Declare exported with BOOT_COMPLETED in the app manifest. Disable by default and call
 * [JustAsk.setBootReceiverEnabled] when the user opts in — same pattern as Reverb.
 */
class JustAskBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        JustAsk.startBootOrchestratorIfEnabled(context)
    }
}
