package io.github.arka01.cardash.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.R
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.logic.Level
import io.github.arka01.cardash.logic.Rules
import io.github.arka01.cardash.obd.Pids
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Car screens, using Google's standard templates (Android Auto draws them in its own style).
 *
 * Build 9 tests what Android Auto allows beyond plain text (first Play build: 2026-10-29):
 *  1. standard green text (Status row)     2. a custom colour: the phone's terminal green
 *  3. standard amber/red for warnings      4. the app's icon in a row
 *  5. tapping a row opens a detail page (PaneTemplate) with a Back button
 *
 * Android Auto allows only a few "new" screens per task, but updates count as free refreshes
 * if the title, the number of rows and every row title stay the same. So the rows are fixed:
 * each row title names 3 sensors, and only the row text (their values) changes.
 */

private val TERMINAL_GREEN: CarColor = CarColor.createCustom(0xFF33FF66.toInt(), 0xFF33FF66.toInt())

/** Most useful values first, 3 per row; the car shows as many rows as Android Auto allows. */
private val CAR_ORDER = listOf(
    0x0C, 0x0D, 0x05,   // RPM, speed, coolant
    0x5C, 0x42, 0x2F,   // oil, battery, fuel level
    0x04, 0x11, 0x0B,   // load, throttle, manifold pressure
    0x0F, 0x46, 0x5E,   // intake air, outside air, fuel rate
    0x0E, 0x06, 0x07,   // timing, short and long fuel trim
    0x3C, 0x62, 0x49,   // catalyst, torque, pedal
    0x1F, 0x33, 0x44,   // run time, barometric pressure, commanded lambda
)
private const val PER_ROW = 3

/** Standard car colour for a warning level, or null when fine (then the value stays terminal green). */
private fun warnColor(pid: Int, s: Snapshot): CarColor? {
    val level = when (pid) {
        0x05 -> Rules.coolant(s.number(pid))
        0x5C -> Rules.oil(s.number(pid))
        0x42 -> s.derived?.battery?.level ?: Level.OK
        else -> Level.OK
    }
    return when (level) {
        Level.OK -> null
        Level.WARN -> CarColor.YELLOW
        Level.ALARM -> CarColor.RED
    }
}

/** "1093 rpm · 0 km/h · 56 °C", each value coloured: terminal green, or amber/red when it warns. */
private fun valuesText(pids: List<Int>, s: Snapshot): CharSequence {
    val parts = pids.map { s.readings[it]?.text ?: "--" }
    val text = SpannableString(parts.joinToString(" · "))
    var start = 0
    for ((i, pid) in pids.withIndex()) {
        val end = start + parts[i].length
        if (s.readings[pid] != null && end > start) {
            val color = warnColor(pid, s) ?: TERMINAL_GREEN
            text.setSpan(ForegroundCarColorSpan.create(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        start = end + 3   // " · "
    }
    return text
}

/** Text in one colour. */
private fun colored(text: String, color: CarColor): CharSequence = SpannableString(text).apply {
    setSpan(ForegroundCarColorSpan.create(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** Redraws the screen once a second while it's visible. */
private fun Screen.refreshEverySecond() {
    val ui = MainScope()
    var refresh: Job? = null
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

/** The main car screen: a status row, then rows of 3 values; tap a row for its detail page. */
class SensorListScreen(carContext: CarContext) : Screen(carContext) {
    init {
        refreshEverySecond()
    }

    override fun onGetTemplate(): Template {
        val s = Dash.state.value
        val items = ItemList.Builder()
        items.addItem(
            Row.Builder()
                .setTitle("Status")
                // The app's own icon (green prompt on black), not tinted.
                .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_launcher)).build())
                .addText(colored("${s.mode} · ${s.status} · ${s.readings.size} values", CarColor.GREEN))
                .build(),
        )
        CAR_ORDER.chunked(PER_ROW).take(rowLimit() - 1).forEachIndexed { index, group ->
            items.addItem(
                Row.Builder()
                    .setTitle(group.joinToString(" · ") { Pids.name(it) })
                    .addText(valuesText(group, s))
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(DetailScreen(carContext, index)) }
                    .build(),
            )
        }

        val connect = Action.Builder()
            .setTitle(if (Dash.isRunning) "Stop" else "Connect")
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
}

/** One row's values on their own page, one per line, with Back (a PaneTemplate). */
class DetailScreen(carContext: CarContext, group: Int) : Screen(carContext) {
    private val pids = CAR_ORDER.chunked(PER_ROW)[group]
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        refreshEverySecond()
    }

    override fun onGetTemplate(): Template {
        val s = Dash.state.value
        val pane = Pane.Builder()
        for (pid in pids) {
            pane.addRow(Row.Builder().setTitle(Pids.name(pid)).addText(valuesText(listOf(pid), s)).build())
        }
        pane.addRow(Row.Builder().setTitle("Updated").addText(clock.format(Date())).build())
        return PaneTemplate.Builder(pane.build())
            .setTitle(pids.joinToString(" · ") { Pids.name(it) })
            .setHeaderAction(Action.BACK)
            .build()
    }
}
