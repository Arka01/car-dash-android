package io.github.arka01.cardash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps a drive recording while Car Dash isn't on screen (owner: "very important", 2026-11-01):
 * a foreground service with a quiet "Recording your drive" notification, so Android doesn't pause
 * or close the app in the background (phone locked, music or maps in front), and a partial wake
 * lock so the processor keeps reading the car with the screen off. Runs exactly while LIVE runs.
 */
class DriveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID, notification)
            }
        } catch (e: Exception) {
            // e.g. Android refusing the "connected device" type: reading goes on, just without this.
            Dash.note("Background recording unavailable: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarDash:drive")
                .apply { acquire(12 * 60 * 60 * 1000L) }   // safety limit: 12 hours
        }
        return START_NOT_STICKY   // if Android stops it anyway, the drive can't go on by itself
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "drive"

        /** LIVE started. If Android refuses (e.g. started from the background), reading goes on without it. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DriveService::class.java)) }
                .onFailure { Dash.note("Background recording not allowed now: ${it.message}") }
        }

        /** LIVE stopped. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DriveService::class.java)) }
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Drive recording", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while Car Dash reads and records your drive"
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile_gauge)
                .setContentTitle("Recording your drive")
                .setContentText("Car Dash is reading your car. Stops when you unplug or tap Stop.")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }
    }
}
