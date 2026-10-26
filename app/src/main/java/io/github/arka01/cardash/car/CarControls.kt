package io.github.arka01.cardash.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.obd.Adapters

/** Connect/Stop from the car screen. Permission and adapter choice must be done on the phone once. */
fun toggleConnection(carContext: CarContext) {
    when {
        Dash.isRunning -> Dash.stop()
        !Adapters.hasPermission(carContext) ->
            CarToast.makeText(carContext, "Open Car Dash on the phone and allow Bluetooth", CarToast.LENGTH_LONG).show()
        else -> {
            val adapter = Adapters.chosen(carContext)
            if (adapter == null) {
                CarToast.makeText(carContext, "Choose the adapter on the phone first", CarToast.LENGTH_LONG).show()
            } else {
                Dash.startLive(carContext, adapter)
            }
        }
    }
}
