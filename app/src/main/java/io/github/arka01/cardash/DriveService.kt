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
        when (intent?.action) {
            MIC_ON, MIC_OFF -> {   // Talk listening (or done): add or drop the microphone type
                val mic = intent.action == MIC_ON
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
                    runCatching { startForeground(ID, notification, types) }
                        .onSuccess { if (mic) io.github.arka01.cardash.ai.Talk.note(this, "drive service: microphone on") }
                        .onFailure { io.github.arka01.cardash.ai.Talk.note(this, "drive service: microphone refused: ${it.javaClass.simpleName}: ${it.message}") }
                }
                return START_NOT_STICKY
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID, notification)
            }
            running = true
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
        running = false
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

        private const val MIC_ON = "mic_on"
        private const val MIC_OFF = "mic_off"
        @Volatile private var running = false

        /**
         * Talk listens: while LIVE records, the service also declares the microphone (Android 14 lets an
         * app hear with the phone locked only so). True if the service runs (the result goes into Talk's log).
         */
        fun micOn(context: Context): Boolean {
            if (!running) return false
            runCatching { context.startService(Intent(context, DriveService::class.java).setAction(MIC_ON)) }
                .onFailure { io.github.arka01.cardash.ai.Talk.note(context, "drive service: microphone not asked: ${it.message}") }
            return true
        }

        fun micOff(context: Context) {
            if (running) runCatching { context.startService(Intent(context, DriveService::class.java).setAction(MIC_OFF)) }
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
