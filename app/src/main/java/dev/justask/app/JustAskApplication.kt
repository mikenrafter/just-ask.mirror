package dev.justask.app

import android.app.Application
import dev.justask.sdk.JustAsk
import dev.justask.sdk.JustAskBootPreferences

/** Ensures boot-receiver state matches the stored preference on every process start. */
class JustAskApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = JustAskBootPreferences(this)
        val prefEnabled = prefs.startOnBoot
        val receiverEnabled = JustAsk.isBootReceiverEnabled(this)
        when {
            prefEnabled && !receiverEnabled -> JustAsk.setBootReceiverEnabled(this, true)
            !prefEnabled && receiverEnabled -> JustAsk.setBootReceiverEnabled(this, false)
        }
    }
}
