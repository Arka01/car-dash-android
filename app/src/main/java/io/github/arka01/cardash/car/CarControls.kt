package io.github.arka01.cardash.car

import androidx.car.app.CarContext
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.obd.Adapters

/**
 * The car screen has no buttons (the Tiago's touch screen makes ghost taps on its right side,
 * 2026-10-29): reading starts by itself when Car Dash opens in the car, and stops when the car
 * session ends (phone unplugged), but only if the car started it.
 */
object CarControls {
    @Volatile private var startedByCar = false

    /**
     * Starts LIVE unless something already runs. Returns what the driver must do on the phone first
     * (one-time setup), or null if reading runs.
     */
    fun autoStart(carContext: CarContext): String? {
        if (Dash.isRunning) return null
        if (!Adapters.hasPermission(carContext)) return "Open Car Dash on the phone once: allow Nearby devices"
        val adapter = Adapters.chosen(carContext) ?: return "Open Car Dash on the phone once: choose the adapter"
        Dash.startLive(carContext, adapter)
        startedByCar = true
        return null
    }

    /** The car session ended: stop a LIVE run the car started (a run started on the phone keeps going). */
    fun carGone() {
        if (startedByCar && Dash.state.value.mode == Mode.LIVE) Dash.stop()
        startedByCar = false
    }
}
