package io.github.arka01.cardash.logic

import io.github.arka01.cardash.cars.CarProfile

data class Alert(val text: String, val level: Level)

/** Everything calculated from one update, for the screens and the recording. */
class Derived(
    val values: Values,                // the readings by name plus every calculated value
    val peaks: Map<String, Double>,    // highest value of each this run
    val trends: Map<String, Double?>,  // temperatures: change per minute
    val fuel: FuelReport?,             // null if the car's tank size isn't set
    val battery: BatteryReport?,
    val engine: EngineState?,          // hot beats warming up
    val warmUp: EngineState?,
    val alerts: List<Alert>,           // everything amber or red right now, most important first
)

/**
 * One update's calculations, in the same order as the Python dashboard's `dashboard()` step
 * (and its web server's Pipeline): trip computer, torque/power, pedal check, heat soak, boost,
 * then trends, fuel, battery, warm-up advisor and peaks. One per run (LIVE, DEMO, REPLAY);
 * only a LIVE run saves the fuel and battery state files ([live]).
 */
class Pipeline(val car: CarProfile, live: Boolean, now: Double) {
    private val trip = TripComputer(car, now)
    private val pedals = PedalCheck()
    private val trends = Trends(HEAT)
    private val advisor = WarmUpAdvisor()
    private val peaks = HashMap<String, Double>()
    private val battery = BatteryMonitor(if (live) car.file("battery_state.json") else null, car.smartAlternator)

    // The backup estimate from the air the engine takes in assumes petrol, so it's off for other fuels.
    private val fuel: FuelTracker? = car.tankLitres?.let { tank ->
        FuelTracker(tank, if (car.fuel == "petrol") car.engineLitres else null, car.volumetricEfficiency,
            if (live) car.file("fuel_state.json") else null)
    }

    /** A fill-up from the REFUEL button; false if the car's tank size isn't set. */
    fun refuel(litres: Double?, full: Boolean): Boolean {
        fuel?.refuel(litres, full) ?: return false
        return true
    }

    /** True while the engine is known to be off: then voltage and RPM are read fast, for the start dip. */
    fun engineOff() = battery.engineOff()

    /** At the end of a run. */
    fun close() {
        fuel?.save()
    }

    /** One update: the calculated values, and a sentence to say (warm-up stage change) or null. */
    @Synchronized
    fun process(input: Values, now: Double): Pair<Derived, String?> {
        val out = input.copy()
        trip.update(input, now, out)
        Calculations.engineOutput(car, input, out)
        pedals.update(input, now, out)
        Calculations.heatSoak(input, out)
        Calculations.boost(input, out)

        trends.update(out, now)
        fuel?.update(out, now, trip.distance, trip.accel)
        battery.update(out, now)
        advisor.update(out, now)
        val sentence = advisor.announcement()
        for (label in out.labels) {
            val value = out[label] ?: continue
            peaks[label] = maxOf(peaks[label] ?: value, value)
        }

        val warm = advisor.report()
        val fuelReport = fuel?.report()
        val batteryReport = battery.report()
        val derived = Derived(
            values = out,
            peaks = HashMap(peaks),
            trends = trends.perMinute(),
            fuel = fuelReport,
            battery = batteryReport,
            engine = Calculations.hotState(out) ?: warm,
            warmUp = warm,
            alerts = alerts(out, warm, batteryReport, fuelReport),
        )
        return derived to sentence
    }

    /** All active warnings, so nothing amber or red goes unnoticed (the Python critical_alerts and tile rules). */
    private fun alerts(v: Values, warm: EngineState?, battery: BatteryReport?, fuel: FuelReport?) = buildList {
        val rpm = v["RPM"]
        val limit = warm?.rpmLimit
        when (Rules.rpm(rpm, car, limit)) {
            Level.ALARM -> add(Alert(if (rpm != null && rpm <= car.redlineRpm && limit != null) "KEEP <$limit: COLD" else "REDLINE", Level.ALARM))
            Level.WARN -> add(Alert("SHIFT", Level.WARN))
            Level.OK -> {}
        }
        val coolant = v["Coolant"]
        val warming = warm != null && warm.state != "READY TO PUSH"
        when (Rules.coolant(coolant)) {
            Level.ALARM -> add(Alert("OVERHEATING", Level.ALARM))
            Level.WARN -> if ((coolant ?: 0.0) >= 60) add(Alert("COOLANT HOT", Level.WARN))
                else if (!warming) add(Alert("WARMING UP", Level.WARN))
            Level.OK -> {}
        }
        val oil = v["Oil"]
        when (Rules.oil(oil)) {
            Level.ALARM -> add(Alert("OIL OVERHEATING", Level.ALARM))
            Level.WARN -> if ((oil ?: 0.0) >= 60) add(Alert("OIL HOT", Level.WARN))
            Level.OK -> {}
        }
        if (warming && warm != null) {
            add(Alert(warm.state + (warm.rpmLimit?.let { " <$it" } ?: ""), if (warm.level == Level.OK) Level.WARN else warm.level))
        }
        if (battery != null && battery.level != Level.OK) add(Alert("BATTERY ${battery.state}", battery.level))
        if (battery?.crank?.level == "critical") add(Alert("BATTERY WEAK", Level.ALARM))
        val litres = fuel?.litres
        if (litres != null && litres < FUEL_RESERVE) add(Alert("FUEL RESERVE", Level.ALARM))
        else if (litres != null && litres < FUEL_LOW) add(Alert("FUEL LOW", Level.WARN))
        Rules.pedalMismatch(v["Pedal sensor mismatch"]).let { if (it != Level.OK) add(Alert("PEDAL SENSORS DISAGREE", it)) }
        if (Rules.intake(v["Intake air"]) != Level.OK) add(Alert("HOT AIR", Level.WARN))
        if (Rules.heatSoak(v["Heat soak"]) != Level.OK) add(Alert("HOT INTAKE", Level.WARN))
        if (Rules.catalyst(v["Catalyst"]) == Level.ALARM) add(Alert("CAT TOO HOT", Level.ALARM))
        if (Rules.fuelTrim(v["Long fuel trim"]) == Level.ALARM) add(Alert("FUEL TRIM CHECK", Level.ALARM))
    }

    companion object {
        /** Temperatures whose trend (change per minute) is shown. */
        val HEAT = listOf("Coolant", "Oil", "Intake air", "Outside", "Heat soak", "Catalyst")
        const val FUEL_LOW = 8.0       // litres: warning
        const val FUEL_RESERVE = 4.0   // litres: critical
    }
}
