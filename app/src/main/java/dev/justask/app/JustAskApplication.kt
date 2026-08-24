package dev.justask.app

import android.app.Application
import dev.justask.sdk.JustAsk
import dev.justask.sdk.JustAskBootPreferences

/** Keeps boot-receiver state aligned with the start-on-boot preference. */
class JustAskApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = JustAskBootPreferences(this)
        // Default is on for the orchestrator app; force the component to match.
        JustAsk.setBootReceiverEnabled(this, prefs.startOnBoot)
    }
}
