package io.github.arka01.cardash.logic

import io.github.arka01.cardash.cars.CarProfile
import kotlin.math.abs

enum class Level { OK, WARN, ALARM }

/**
 * Warning levels for single values: the alert rules of the Python dashboard's STATS
 * (typical petrol-car values, the same for every car), plus the car's own RPM limits.
 */
object Rules {
    /** SHIFT / REDLINE from the car's limits; [limit] = a lower cold-engine limit (warm-up advisor). */
    fun rpm(rpm: Double?, car: CarProfile, limit: Int? = null): Level {
        if (rpm == null) return Level.OK
        val level = when {
            rpm > car.redlineRpm -> Level.ALARM
            rpm > car.shiftRpm -> Level.WARN
            else -> Level.OK
        }
        if (limit != null && rpm > limit) return Level.ALARM
        if (limit != null && rpm > limit - 500 && level == Level.OK) return Level.WARN
        return level
    }

    /** The RPM bar ends 1000 above the redline. */
    fun rpmScale(car: CarProfile) = car.redlineRpm + 1000.0

    fun coolant(c: Double?) = when {
        c == null -> Level.OK
        c > 115 -> Level.ALARM
        c > 105 || c < 60 -> Level.WARN   // hot, or still warming up
        else -> Level.OK
    }

    fun oil(c: Double?) = when {
        c == null -> Level.OK
        c > 135 -> Level.ALARM
        c > 120 || c < 60 -> Level.WARN   // hot, or cold: go easy until it's warm
        else -> Level.OK
    }

    fun intake(c: Double?) = if (c != null && c > 60) Level.WARN else Level.OK   // heat soak: less power

    /** Intake air minus outside air: hot air in = less power. */
    fun heatSoak(d: Double?) = if (d != null && d > 25) Level.WARN else Level.OK

    fun catalyst(c: Double?) = when {
        c == null -> Level.OK
        c > 900 -> Level.ALARM
        c < 300 -> Level.WARN   // only cleans the exhaust once hot
        else -> Level.OK
    }

    /** Long-term fuel trim: the engine constantly correcting the mixture. */
    fun fuelTrim(t: Double?) = when {
        t == null -> Level.OK
        abs(t) > 20 -> Level.ALARM
        abs(t) > 10 -> Level.WARN
        else -> Level.OK
    }

    /** Kilometres driven with the check-engine light on: any means it was on at some point. */
    fun drivenWithMil(km: Double?) = if (km != null && km > 0) Level.WARN else Level.OK

    fun pedalMismatch(points: Double?) = when {
        points == null -> Level.OK
        points > 20 -> Level.ALARM
        points > 10 -> Level.WARN
        else -> Level.OK
    }

    fun of(name: String) = when (name) {
        "good" -> Level.OK
        "warning" -> Level.WARN
        "critical" -> Level.ALARM
        else -> Level.OK
    }
}
