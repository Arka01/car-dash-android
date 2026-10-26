package io.github.arka01.cardash

import kotlin.math.abs
import kotlin.math.ln

enum class Level { OK, WARN, ALARM }

/**
 * The car's settings and rules, from the Python dashboard's cars/tata_tiago_2023/car.json
 * and its alert rules (car_dashboard.py), so both apps judge values the same way.
 */
object Tiago {
    const val SHIFT_RPM = 3500.0      // owner's choice: shift lights from here
    const val REDLINE_RPM = 4000.0    // owner's chosen limit, not the engine's maximum
    const val RPM_SCALE = REDLINE_RPM + 1000   // the RPM bar ends 1000 above the redline

    /** RPM per km/h in each gear, measured with --calibrate-gears on 2026-10-24. */
    private val RPM_PER_KMH = doubleArrayOf(149.2, 78.8, 51.1, 37.8, 29.1)

    /** Gear from the RPM-to-speed ratio: 0 when stopped, null while shifting or unknown. */
    fun gear(speed: Double?, rpm: Double?): Int? {
        if (speed == null || rpm == null) return null
        if (speed < 5) return 0
        if (rpm <= 0) return null
        val ratio = rpm / speed
        val best = RPM_PER_KMH.indices.minByOrNull { abs(ln(ratio / RPM_PER_KMH[it])) } ?: return null
        return if (abs(ln(ratio / RPM_PER_KMH[best])) < 0.15) best + 1 else null
    }

    fun rpmLevel(rpm: Double?) = when {
        rpm == null -> Level.OK
        rpm > REDLINE_RPM -> Level.ALARM
        rpm > SHIFT_RPM -> Level.WARN
        else -> Level.OK
    }

    fun coolantLevel(c: Double?) = when {
        c == null -> Level.OK
        c > 115 -> Level.ALARM
        c > 105 || c < 60 -> Level.WARN   // hot, or still warming up
        else -> Level.OK
    }

    fun oilLevel(c: Double?) = when {
        c == null -> Level.OK
        c > 135 -> Level.ALARM
        c > 120 || c < 60 -> Level.WARN   // hot, or cold: go easy until it's warm
        else -> Level.OK
    }

    fun intakeLevel(c: Double?) = if (c != null && c > 60) Level.WARN else Level.OK   // heat soak

    fun catalystLevel(c: Double?) = when {
        c == null -> Level.OK
        c > 900 -> Level.ALARM
        c < 300 -> Level.WARN   // only cleans the exhaust once hot
        else -> Level.OK
    }

    fun fuelTrimLevel(t: Double?) = when {
        t == null -> Level.OK
        abs(t) > 20 -> Level.ALARM
        abs(t) > 10 -> Level.WARN
        else -> Level.OK
    }

    /**
     * Battery as the engine computer sees it. This car has a smart alternator (car.json), which
     * drops to ~12.2 V while cruising, so only clearly abnormal values are flagged here.
     */
    fun batteryLevel(v: Double?) = when {
        v == null -> Level.OK
        v > 15.0 || v < 11.5 -> Level.ALARM
        v < 12.0 -> Level.WARN
        else -> Level.OK
    }

    /** "STOP" when standing, "~" between gears (clutch open or shifting), else the number. */
    fun gearName(gear: Int?) = when (gear) {
        null -> "~"
        0 -> "STOP"
        else -> gear.toString()
    }
}
