package io.github.arka01.cardash

import android.content.Context
import io.github.arka01.cardash.cars.Cars
import io.github.arka01.cardash.logic.GearCalibration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** New gear values learned from the drives, waiting for the owner's OK (Car screen). */
class GearSuggestion(val carKey: String, val values: List<Double>, val measured: List<Boolean>, val lines: List<String>, val drives: Int)

/**
 * Learns the gearbox by itself (2026-11-01, instead of a "Gears" button): after each drive, and
 * when the Car screen opens, it measures RPM per km/h in each gear from all recorded drives
 * (logic/GearCalibration) and offers the result when it's new: no gears known yet, a gear measured
 * for the first time, or a measured gear that changed by more than 2 %. A gear counts as measured
 * from [MIN_READINGS] steady readings (about 20 s in that gear). "Not now" hides these values.
 */
object GearLearner {
    private const val MIN_READINGS = 150
    private const val CHANGE = 0.02
    private const val PREFS = "gears"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = MutableStateFlow<GearSuggestion?>(null)
    val suggestion: StateFlow<GearSuggestion?> = store

    /** Looks for new gear values of the car in use (in the background). */
    fun check(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                Cars.init(app)
                val car = Cars.active(app)
                val result = GearCalibration.run(car)
                val details = result.details ?: return@runCatching store.update(null)
                val ok = details.map { it.measured && it.readings >= MIN_READINGS }
                val values = details.map { it.value }
                val saved = car.gearRpmPerKmh
                val savedMeasured = Cars.measuredGears(car)
                val new = when {
                    saved == null -> ok.count { it } >= 2   // needs two gears to place the rest
                    else -> details.indices.any { g ->
                        ok[g] && (g >= saved.size || savedMeasured?.getOrNull(g) == false ||
                            abs(values[g] - saved[g]) / saved[g] > CHANGE)
                    }
                }
                val key = values.joinToString(",") { String.format(Locale.US, "%.1f", it) }
                val dismissed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dismissed_${car.key}", null)
                if (!new || key == dismissed) return@runCatching store.update(null)
                val lines = details.mapIndexed { g, d ->
                    val old = saved?.getOrNull(g)?.let { String.format(Locale.US, "%.1f → ", it) } ?: ""
                    String.format(Locale.US, "%s gear: %s%.1f  (%s)", ordinal(g + 1), old, d.value,
                        if (ok[g]) "measured" else "estimated")
                }
                store.value = GearSuggestion(car.key, values, ok, lines, result.drives)
            }
        }
    }

    /** Save: the gear display uses the new values from now on. */
    fun accept(context: Context) {
        val s = store.value ?: return
        val app = context.applicationContext
        val car = Cars.load(s.carKey)
        Cars.saveGears(car, s.values, s.measured)
        store.value = null
        Dash.chooseCar(app, s.carKey)   // reload the car's settings
    }

    /** Not now: these values aren't offered again (new ones from later drives are). */
    fun dismiss(context: Context) {
        val s = store.value ?: return
        val key = s.values.joinToString(",") { String.format(Locale.US, "%.1f", it) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("dismissed_${s.carKey}", key).apply()
        store.value = null
    }

    private fun MutableStateFlow<GearSuggestion?>.update(value: GearSuggestion?) {
        this.value = value
    }

    fun ordinal(n: Int) = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }
}
