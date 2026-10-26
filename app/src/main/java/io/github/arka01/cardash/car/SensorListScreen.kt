package io.github.arka01.cardash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.arka01.cardash.obd.ObdSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fallback car screen using Google's standard list layout (the way most apps show data).
 * Android Auto limits how many rows a list may have, so 4 values share each row.
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
        val items = ItemList.Builder()
        val groups = state.readings.chunked(4).take(rowLimit())
        for (group in groups) {
            val pairs = group.map { "${it.name}: ${it.value}" }
            val row = Row.Builder().setTitle(pairs.take(2).joinToString("   "))
            if (pairs.size > 2) row.addText(pairs.drop(2).joinToString("   "))
            items.addItem(row.build())
        }
        if (groups.isEmpty()) items.setNoItemsMessage(state.status)
        return ListTemplate.Builder()
            .setTitle("${state.status} · ${state.readings.size} values")
            .setHeaderAction(Action.BACK)
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
}
