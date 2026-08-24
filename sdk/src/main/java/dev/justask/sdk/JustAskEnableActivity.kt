package dev.justask.sdk

import android.app.Activity
import android.os.Bundle

/**
 * Transparent trampoline launched from the idle boot notification.
 *
 * Subclasses supply targets via [loadTargets]. The activity launches every enabled target
 * from an eligible Activity context, then notifies [JustAskBootForegroundService].
 */
abstract class JustAskEnableActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JustAsk.enableFromActivity(this, loadTargets(), notifyService = true)
        onTargetsLaunched()
        finish()
    }

    /** Targets to launch on this user action. */
    protected abstract fun loadTargets(): List<JustAskTarget>

    /** Optional hook after launches are dispatched. Default no-op. */
    protected open fun onTargetsLaunched() = Unit
}
