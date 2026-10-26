package io.github.arka01.cardash.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import io.github.arka01.cardash.obd.Adapters
import io.github.arka01.cardash.obd.ObdSession

/** Connect/Stop from the car screen. Permission and adapter choice must be done on the phone once. */
fun toggleConnection(carContext: CarContext) {
    when {
        ObdSession.isRunning -> ObdSession.stop()
        !Adapters.hasPermission(carContext) ->
            CarToast.makeText(carContext, "Open Car Dash on the phone and allow Bluetooth", CarToast.LENGTH_LONG).show()
        else -> {
            val adapter = Adapters.chosen(carContext)
            if (adapter == null) {
                CarToast.makeText(carContext, "Choose the adapter on the phone first", CarToast.LENGTH_LONG).show()
            } else {
                ObdSession.start(carContext, adapter)
            }
        }
    }
}
