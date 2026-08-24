package dev.justask.sdk

import android.content.pm.ServiceInfo

/**
 * Notification copy and foreground-service typing for [JustAskBootForegroundService].
 */
data class JustAskBootConfig(
    val notificationChannelId: String = "just_ask_boot",
    val notificationChannelName: String,
    val notificationChannelDescription: String = "",
    val idleNotificationTitle: String,
    val idleNotificationBody: String,
    val activeNotificationTitle: String = idleNotificationTitle,
    val activeNotificationBody: String = idleNotificationBody,
    val notificationId: Int = 4242,
    /**
     * Foreground service type for the idle orchestrator. Use a non-while-in-use type so boot
     * start from [JustAskBootReceiver] is legal (connectedDevice is a non-WIU type).
     */
    val foregroundServiceType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
)
