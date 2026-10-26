package io.github.arka01.cardash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.arka01.cardash.obd.ObdSession
import io.github.arka01.cardash.obd.Pids
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Car screen, using Google's standard list layout.
 *
 * Android Auto allows only a few "new" screens per task, but updates count as free refreshes
 * if the title, the number of rows and every row title stay the same. So the rows are fixed:
 * each row title names 3 sensors, and only the row text (their values) changes.
 */
class SensorListScreen(carContext: CarContext) : Screen(carContext) {
    private val ui = MainScope()
    private var refresh: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refresh = ui.launch {
                    while (isActive) {
                        delay(1000)
                        invalidate()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                refresh?.cancel()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                ui.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = ObdSession.state.value
        val values = state.readings.associate { it.pid to it.value }

        val items = ItemList.Builder()
        items.addItem(
            Row.Builder()
                .setTitle("Status")
                .addText("${state.status} · port ${state.adapterVoltage} · ${values.size} values")
                .build()
        )
        for (group in CAR_ORDER.chunked(PER_ROW).take(rowLimit() - 1)) {
            items.addItem(
                Row.Builder()
                    .setTitle(group.joinToString(" · ") { Pids.name(it) })
                    .addText(group.joinToString(" · ") { values[it] ?: "--" })
                    .build()
            )
        }

        val connect = Action.Builder()
            .setTitle(if (ObdSession.isRunning) "Stop" else "Connect")
            .setOnClickListener { toggleConnection(carContext); invalidate() }
            .build()
        return ListTemplate.Builder()
            .setTitle("Car Dash")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(ActionStrip.Builder().addAction(connect).build())
            .setSingleList(items.build())
            .build()
    }

    private fun rowLimit(): Int =
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else {
            6
        }

    private companion object {
        const val PER_ROW = 3

        /** Most useful values first; the car shows as many rows as Android Auto allows. */
        val CAR_ORDER = listOf(
            0x0C, 0x0D, 0x05,   // RPM, speed, coolant
            0x5C, 0x42, 0x2F,   // oil, battery, fuel level
            0x04, 0x11, 0x0B,   // load, throttle, manifold pressure
            0x0F, 0x46, 0x5E,   // intake air, outside air, fuel rate
            0x0E, 0x06, 0x07,   // timing, short and long fuel trim
            0x3C, 0x62, 0x49,   // catalyst, torque, pedal
            0x1F, 0x33, 0x44,   // run time, barometric pressure, commanded lambda
        )
    }
}
