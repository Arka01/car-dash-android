package io.github.arka01.cardash.logic

import io.github.arka01.cardash.cars.CarProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Calculated values from the Python dashboard's car_dashboard.py, one function each. */
object Calculations {
    /**
     * Gear from the RPM-to-speed ratio: 0 when standing, null while shifting or unknown,
     * [NO_GEARS] if this car's gears aren't known yet (run GEARS after a drive).
     */
    fun gear(car: CarProfile, speed: Double?, rpm: Double?): Int? {
        val gears = car.gears ?: return NO_GEARS
        if (speed == null || rpm == null) return null
        if (speed < 1) return 0
        if (rpm <= 0) return null   // engine off / stalled while rolling
        val ratio = rpm / speed
        val best = gears.indices.minByOrNull { abs(ln(ratio / gears[it])) } ?: return null
        if (abs(ln(ratio / gears[best])) < 0.15) return best + 1
        // Creeping or pulling away: an automatic clutch is still closing, so the engine turns faster
        // than 1st gear explains (Tiago drive 2026-10-24: ~210 rpm per km/h at 5 km/h vs 149 in 1st).
        // Reverse looks the same to OBD (speed is never negative), so it shows as 1 too.
        if (speed < 10 && ratio > gears[0] * exp(-0.15)) return 1
        return null
    }

    const val NO_GEARS = -1

    /** "STOP" when standing, "~" between gears, "-" if the gears aren't known, else the number. */
    fun gearName(gear: Int?) = when (gear) {
        null -> "~"
        NO_GEARS -> "-"
        0 -> "STOP"
        else -> gear.toString()
    }

    /**
     * Torque (Nm) and power (bhp) from the engine computer's own torque estimate (% of its
     * reference torque). Power = torque x rpm / 9549 (kW) x 1.341 (bhp); negative = engine braking.
     * Some ECUs' percentages don't add up to real Nm (the Tiago's gives ~60 Nm at full throttle
     * where ~100 are expected, and 12 % at idle): car.json "torque_calibration" then maps the
     * percentage through two known points instead.
     */
    fun engineOutput(car: CarProfile, v: Values, out: Values) {
        val calibration = car.torqueCalibration
        if (v.isUnsupported("Engine torque %") || (v.isUnsupported("Reference torque") && calibration == null)) {
            out.markUnsupported("Torque")
            out.markUnsupported("Power")
            return
        }
        val pct = v["Engine torque %"]
        val reference = v["Reference torque"]
        val rpm = v["RPM"]
        if (pct == null || (calibration == null && (reference == null || reference == 0.0))) {
            out["Torque"] = null
            out["Power"] = null
            return
        }
        val torque = if (calibration != null) {
            // an engine that isn't turning has no torque (the % reads 0 then, which would map to engine braking)
            if (rpm != null && rpm < 300) 0.0
            else (pct - calibration.zeroPct) / (calibration.fullPct - calibration.zeroPct) * calibration.fullNm
        } else {
            pct / 100 * reference!!
        }
        out["Torque"] = torque
        out["Power"] = rpm?.let { torque * it / 9549 * 1.341 }
    }

    /** Heat soak: how much hotter the intake air is than outside (engine-bay heat getting sucked in). */
    fun heatSoak(v: Values, out: Values) {
        if (v.isUnsupported("Intake air") || v.isUnsupported("Outside")) return out.markUnsupported("Heat soak")
        val intake = v["Intake air"]
        val outside = v["Outside"]
        out["Heat soak"] = if (intake != null && outside != null) intake - outside else null
    }

    /** Turbo boost in bar: manifold pressure minus barometric (outside air); negative = vacuum. */
    fun boost(v: Values, out: Values) {
        if (v.isUnsupported("Manifold pressure") || v.isUnsupported("Barometric pressure")) return out.markUnsupported("Boost")
        val manifold = v["Manifold pressure"]
        val outside = v["Barometric pressure"]
        out["Boost"] = if (manifold != null && outside != null) (manifold - outside) / 100 else null
    }

    /** TOO HOT / HOT from the coolant and oil rules, or null while the engine isn't hot. */
    fun hotState(v: Values): EngineState? {
        val coolant = v["Coolant"]
        val oil = v["Oil"]
        if ((coolant != null && coolant > 115) || (oil != null && oil > 135)) {
            return EngineState("TOO HOT", Level.ALARM, "ease off / stop safely")
        }
        if ((coolant != null && coolant > 105) || (oil != null && oil > 120)) {
            return EngineState("HOT", Level.WARN, "ease off")
        }
        return null
    }
}

/** The engine's state for the dashboard: hot, or a warm-up stage. [rpmLimit] = cold-engine limit. */
data class EngineState(val state: String, val level: Level, val detail: String, val rpmLimit: Int? = null)
