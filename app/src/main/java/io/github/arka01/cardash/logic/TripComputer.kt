package io.github.arka01.cardash.logic

import io.github.arka01.cardash.cars.CarProfile

/** Values calculated from what the car reports: gear, acceleration, trip distance, sprint times. */
class TripComputer(private val car: CarProfile, now: Double) {
    private val start = now
    private var lastTime = now
    var distance = 0.0   // km
        private set
    var accel = 0.0      // smoothed, in g
        private set
    private var lastSpeed: Double? = null
    private var sprintStart: Double? = null   // time the car last stood still, while a sprint is possible
    private val sprintDone = HashSet<Int>()
    private val best = HashMap<Int, Double>()   // target km/h -> best seconds

    fun update(v: Values, now: Double, out: Values) {
        val dt = now - lastTime
        lastTime = now
        val speed = v["Speed"]
        val rpm = v["RPM"]

        if (speed != null) {
            distance += speed * dt / 3600
            val last = lastSpeed
            if (last != null && dt > 0) {
                val g = (speed - last) / 3.6 / dt / 9.81
                accel = 0.6 * accel + 0.4 * g   // smoothed: speed only comes in whole km/h
            }
            trackSprint(speed, now)
            lastSpeed = speed
        }

        val hours = (now - start) / 3600
        val gear = Calculations.gear(car, speed, rpm)
        if (gear == Calculations.NO_GEARS) out.markUnsupported("Gear (estimated)") else out["Gear (estimated)"] = gear?.toDouble()
        out["Acceleration"] = if (speed != null) accel else null
        out["Trip distance"] = distance
        out["Trip avg speed"] = if (hours > 0) distance / hours else null
        out["0-60 km/h best"] = best[60]
        out["0-100 km/h best"] = best[100]
        out["RPM per km/h"] = if (rpm != null && speed != null && speed > 5) rpm / speed else null
    }

    /** Standing-start timers: armed while stopped, timed until each target speed is reached. */
    private fun trackSprint(speed: Double, now: Double) {
        if (speed < 1) {
            sprintStart = now
            sprintDone.clear()
            return
        }
        val started = sprintStart ?: return
        val last = lastSpeed
        if (last != null && speed < last - 3) {   // slowed down: run aborted
            sprintStart = null
            return
        }
        for (target in SPRINTS) {
            if (target !in sprintDone && speed >= target) {
                val seconds = now - started
                best[target] = minOf(best[target] ?: seconds, seconds)
                sprintDone += target
            }
        }
    }

    private companion object {
        val SPRINTS = listOf(60, 100)   // km/h targets for the standing-start timers
    }
}
