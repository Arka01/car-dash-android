package io.github.arka01.cardash.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.connection.CarConnection
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.obd.Adapters

/**
 * The car screen has no buttons (the Tiago's touch screen makes ghost taps on its right side,
 * 2026-10-29): reading starts by itself when Car Dash opens in the car, and stops when the phone
 * is really disconnected from Android Auto, but only if the car started it.
 *
 * Not when the car screen's session ends: Android Auto may close it in the background while
 * another app (music) is on the screen; that stopped a drive halfway on 2026-11-01 (21:27:50, at
 * 35 km/h) and started a spoken summary. Android's car connection state tells unplugging apart.
 */
object CarControls {
    @Volatile private var startedByCar = false
    @Volatile private var watching = false
    @Volatile private var wasConnected = false

    /**
     * Starts LIVE unless something already runs. Returns what the driver must do on the phone first
     * (one-time setup), or null if reading runs.
     */
    fun autoStart(carContext: CarContext): String? {
        watchConnection(carContext)
        if (Dash.isRunning) return null
        if (!Adapters.hasPermission(carContext)) return "Open Car Dash on the phone once: allow Nearby devices"
        val adapter = Adapters.chosen(carContext) ?: return "Open Car Dash on the phone once: choose the adapter"
        Dash.startLive(carContext, adapter)
        startedByCar = true
        return null
    }

    /** The car screen's session ended. Nothing to do: only a real disconnect stops reading. */
    fun carGone() {}

    /** Follows Android's car connection (connected / not connected) for the rest of the app's life. */
    private fun watchConnection(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            CarConnection(app).type.observeForever { type ->
                val connected = type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                if (wasConnected && !connected) disconnected()
                wasConnected = connected
            }
        }
    }

    /** Unplugged: stop a LIVE run the car started (a run started on the phone keeps going). */
    private fun disconnected() {
        if (startedByCar && Dash.state.value.mode == Mode.LIVE) Dash.stop()
        startedByCar = false
    }
}
