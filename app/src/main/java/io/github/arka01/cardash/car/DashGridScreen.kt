package io.github.arka01.cardash.car

import android.text.SpannableString
import android.text.Spanned
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.arka01.cardash.CarState
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.R
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.logic.Calculations
import io.github.arka01.cardash.logic.Level
import io.github.arka01.cardash.logic.Rules
import io.github.arka01.cardash.logic.Values
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * The car screen: a grid of tiles (Android Auto's GridTemplate, drawn in its own style), one
 * value per tile with a small green icon. It scrolls; Android Auto decides how many columns fit
 * and the most tiles allowed (the list is cut from the bottom).
 *
 * What build 9 showed in the Tiago (2026-10-29): standard green, a custom colour (the phone's
 * terminal green) and icons all work. Display only: no buttons and nothing tappable, because the
 * Tiago's touch screen makes random ghost taps. Reading starts by itself (CarControls).
 *
 * Android Auto allows only a few "new" screens per task, but an update counts as a free refresh
 * if the number of tiles and every title stay the same. So the tiles are fixed; only values change.
 */

private val TERMINAL_GREEN: CarColor = CarColor.createCustom(0xFF33FF66.toInt(), 0xFF33FF66.toInt())
private const val REFRESH_MS = 250L   // asked for 4 times a second; Android Auto may deliver fewer
private const val OLD_MS = 10_000     // a reading older than this shows as "--"

/** One tile: what it shows now ([value] null = "--") and its warning level. */
private class TileValue(val value: String?, val level: Level = Level.OK)

private class Tile(val title: String, @DrawableRes val icon: Int, val read: (Snapshot) -> TileValue)

private fun Snapshot.fresh(pid: Int): Double? {
    val r = readings[pid] ?: return null
    return r.number?.takeIf { System.currentTimeMillis() - r.time < OLD_MS }
}

/** A reading formatted, coloured by [level]; "--" when missing or old. */
private fun pid(pid: Int, format: String, level: (Double) -> Level = { Level.OK }): (Snapshot) -> TileValue = { s ->
    val v = s.fresh(pid)
    if (v == null) TileValue(null) else TileValue(String.format(Locale.US, format, v), level(v))
}

/** A value read from whichever PID this car uses for it (e.g. throttle 45, else 11). */
private fun named(label: String, format: String): (Snapshot) -> TileValue = { s ->
    val p = Values.pidFor(label, s.supported)
    if (p == null) TileValue(null) else pid(p, format)(s)
}

/** A calculated value (logic/Pipeline); "n/a" if this car can't give it. */
private fun calc(label: String, format: String): (Snapshot) -> TileValue = { s ->
    val v = s.derived?.values
    when {
        v == null -> TileValue(null)
        v.isUnsupported(label) -> TileValue("n/a")
        else -> v[label]?.let { TileValue(String.format(Locale.US, format, it)) } ?: TileValue(null)
    }
}

/** The tiles, in order (2026-10-30 plan; to be trimmed later). */
private val TILES = listOf(
    Tile("Status", R.drawable.ic_tile_status) { s -> status(s) },
    Tile("RPM", R.drawable.ic_tile_gauge) { s ->
        pid(0x0C, "%.0f") { v -> s.car?.let { Rules.rpm(v, it, s.derived?.warmUp?.rpmLimit) } ?: Level.OK }.invoke(s)
    },
    Tile("Coolant", R.drawable.ic_tile_temp, pid(0x05, "%.0f °C") { Rules.coolant(it) }),
    Tile("Oil", R.drawable.ic_tile_temp, pid(0x5C, "%.0f °C") { Rules.oil(it) }),
    Tile("Intake air", R.drawable.ic_tile_temp, pid(0x0F, "%.0f °C") { Rules.intake(it) }),
    Tile("Catalyst", R.drawable.ic_tile_temp, pid(0x3C, "%.0f °C") { Rules.catalyst(it) }),
    Tile("Manifold", R.drawable.ic_tile_gauge, pid(0x0B, "%.0f kPa")),
    Tile("Baro", R.drawable.ic_tile_gauge, pid(0x33, "%.0f kPa")),
    Tile("Battery", R.drawable.ic_tile_battery) { s ->
        val b = s.derived?.battery
        if (b == null) TileValue(null) else TileValue(String.format(Locale.US, "%.1f V", b.volts), b.level)
    },
    Tile("Speed", R.drawable.ic_tile_gauge, pid(0x0D, "%.0f km/h")),
    Tile("Gear", R.drawable.ic_tile_gear) { s ->
        val car = s.car
        val speed = s.fresh(0x0D)
        if (car == null || speed == null) TileValue(null)
        else TileValue(Calculations.gearName(Calculations.gear(car, speed, s.fresh(0x0C))))
    },
    Tile("Load", R.drawable.ic_tile_bar, pid(0x04, "%.0f %%")),
    Tile("Throttle", R.drawable.ic_tile_bar, named("Throttle", "%.0f %%")),
    Tile("Pedal", R.drawable.ic_tile_bar, named("Accelerator pedal", "%.0f %%")),
    Tile("Fuel rate", R.drawable.ic_tile_fuel, pid(0x5E, "%.1f L/h")),
    Tile("Torque", R.drawable.ic_tile_bolt, calc("Torque", "%.0f Nm")),
    Tile("Power", R.drawable.ic_tile_bolt, calc("Power", "%.0f bhp")),
    Tile("Timing", R.drawable.ic_tile_gauge, pid(0x0E, "%.0f°")),
    Tile("Fuel trim", R.drawable.ic_tile_trim, pid(0x07, "%+.1f %%") { Rules.fuelTrim(it) }),
    Tile("Fuel left", R.drawable.ic_tile_fuel) { s ->
        val l = s.derived?.fuel?.litres
        if (l == null) TileValue(null)
        else TileValue(String.format(Locale.US, "%.1f L", l), if (l < 4) Level.ALARM else if (l < 8) Level.WARN else Level.OK)
    },
    Tile("Range", R.drawable.ic_tile_fuel) { s ->
        s.derived?.fuel?.rangeKm?.let { TileValue(String.format(Locale.US, "%.0f km", it)) } ?: TileValue(null)
    },
)

/** ENGINE ON / IGNITION ON / … while LIVE; the mode or status otherwise. */
private fun status(s: Snapshot): TileValue = when {
    s.mode == Mode.LIVE && s.carState != CarState.UNKNOWN -> TileValue(
        s.carState.label,
        if (s.carState == CarState.ENGINE_ON || s.carState == CarState.IGNITION_OFF) Level.OK else Level.WARN,
    )
    s.mode == Mode.OFF -> TileValue("OFF", Level.WARN)
    else -> TileValue(s.status.take(16))
}

private fun colored(text: String, color: CarColor): CharSequence = SpannableString(text).apply {
    setSpan(ForegroundCarColorSpan.create(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun Level.carColor(): CarColor = when (this) {
    Level.OK -> TERMINAL_GREEN
    Level.WARN -> CarColor.YELLOW
    Level.ALARM -> CarColor.RED
}

/** The car screen: a scrolling grid of tiles. Nothing on it can be tapped. */
class DashGridScreen(carContext: CarContext) : Screen(carContext) {
    private var setupNeeded: String? = null
    private val icons = HashMap<Int, CarIcon>()

    init {
        val ui = MainScope()
        var refresh: Job? = null
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refresh = ui.launch {
                    while (isActive) {
                        delay(REFRESH_MS)
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
        setupNeeded = CarControls.autoStart(carContext)
    }

    override fun onGetTemplate(): Template {
        if (setupNeeded != null) setupNeeded = CarControls.autoStart(carContext)   // done on the phone meanwhile?
        val s = Dash.state.value
        val items = ItemList.Builder()
        for (tile in TILES.take(tileLimit())) {
            // The one-time phone setup, if missing, goes in the Status tile (there are no buttons).
            val v = if (tile.title == "Status" && setupNeeded != null) TileValue("Set up on phone", Level.WARN) else tile.read(s)
            items.addItem(
                GridItem.Builder()
                    .setTitle(tile.title)
                    .setText(v.value?.let { colored(it, v.level.carColor()) } ?: "--")
                    .setImage(icon(tile.icon), GridItem.IMAGE_TYPE_ICON)
                    .build(),
            )
        }
        return GridTemplate.Builder()
            .setTitle("Car Dash")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .build()
    }

    private fun icon(@DrawableRes id: Int): CarIcon =
        icons.getOrPut(id) { CarIcon.Builder(IconCompat.createWithResource(carContext, id)).build() }

    /** The most tiles this car allows. */
    private fun tileLimit(): Int =
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        } else {
            6
        }
}
