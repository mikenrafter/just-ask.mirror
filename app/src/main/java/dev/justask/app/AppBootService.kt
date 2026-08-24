package dev.justask.app

import dev.justask.sdk.JustAskBootConfig
import dev.justask.sdk.JustAskBootForegroundService
import dev.justask.sdk.JustAskTarget

class AppBootForegroundService : JustAskBootForegroundService() {

    override fun bootConfig(): JustAskBootConfig {
        return JustAskBootConfig(
            notificationChannelName = getString(R.string.notification_channel_name),
            notificationChannelDescription = getString(R.string.notification_channel_description),
            idleNotificationTitle = getString(R.string.notification_idle_title),
            idleNotificationBody = getString(R.string.notification_idle_body),
        )
    }

    override fun enableActivityClass() = AppEnableActivity::class.java
}

class AppEnableActivity : dev.justask.sdk.JustAskEnableActivity() {

    override fun loadTargets(): List<JustAskTarget> {
        return TargetStore(this).enabledTargets()
    }
}
