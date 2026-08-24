package dev.justask.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Idle orchestrator foreground service shown after boot.
 *
 * Boot start stays idle until the user taps the notification, which launches
 * [JustAskEnableActivity]. Subclasses provide notification copy via [bootConfig].
 */
abstract class JustAskBootForegroundService : Service() {

    protected abstract fun bootConfig(): JustAskBootConfig

    protected abstract fun enableActivityClass(): Class<out JustAskEnableActivity>

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startIdleForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            JustAskContract.ACTION_BOOT, JustAskContract.ACTION_SHOW_IDLE -> {
                Log.d(TAG, "Idle notification — waiting for user tap")
                startIdleForeground()
            }
            JustAskContract.ACTION_ENABLE -> {
                // Targets were launched from the enable activity. The idle
                // orchestrator has nothing left to do — tear down the FGS and
                // its notification rather than posting a "done" status.
                Log.d(TAG, "Enable requested — stopping idle orchestrator")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                Log.d(TAG, "onStartCommand action=${intent?.action}")
                startIdleForeground()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        val config = bootConfig()
        val channel = NotificationChannel(
            config.notificationChannelId,
            config.notificationChannelName,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = config.notificationChannelDescription
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun startIdleForeground() {
        val config = bootConfig()
        ServiceCompat.startForeground(
            this,
            config.notificationId,
            buildIdleNotification(config),
            config.foregroundServiceType,
        )
    }

    private fun buildIdleNotification(config: JustAskBootConfig): Notification {
        val enableIntent = Intent(this, enableActivityClass()).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            enableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, config.notificationChannelId)
            .setSmallIcon(config.notificationIconRes)
            .setContentTitle(config.idleNotificationTitle)
            .setContentText(config.idleNotificationBody)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "JustAskBootService"
    }
}
