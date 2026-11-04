package io.github.arka01.cardash.logic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

data class FuelReport(
    val litres: Double?,             // null until there's enough data
    val sigma: Double?,              // ± one standard deviation, litres
    val tank: Double,
    val sensorPct: Double?,          // smoothed float reading
    val sensorLitres: Double?,
    val countedLitres: Double?,
    val flowLph: Double?,
    val flowSource: String?,
    val usedTrip: Double,
    val nowLPer100km: Double?,
    val tripLPer100km: Double?,
    val consumptionLPer100km: Double?,
    val rangeKm: Double?,
    val fillUpsLearned: Int,
    val notes: List<String>,
    val fillTip: Boolean,            // the tank isn't learned yet: ask for a fill to the click at the next refuel
)

/** What the app has learned about this car's fuel readings, in plain words for My car (from fuel_state.json). */
data class FuelLearning(
    val fullFills: Int,
    val calibrated: Boolean,         // two full fills have corrected the counting
    val sigma: Double?,              // litres, ± one standard deviation
    val gauge: Check,                // does the tank gauge agree with the fuel counted?
    val gaugeReadsLow: Boolean,      // when off: the gauge drops faster than the fuel used
    val fuelRate: Check,             // is the engine's fuel-rate reading believable?
    val lastFillError: Double?,      // litres the app was off at the last full fill
    val tankLearned: Double?,
) {
    enum class Check { CHECKING, OK, OFF }
}

/**
 * Fuel left in the tank, in litres (the Python dashboard's fuel_tracker.py). Four sources:
 *
 * 1. Counting from the engine's fuel rate (PID 5E, L/h): precise over short spans, but small scale
 *    errors add up over a tank.
 * 2. Counting from the air going into the engine (manifold pressure, RPM, intake temperature,
 *    lambda): a backup when the fuel rate isn't reported, scaled to agree with source 1.
 * 3. The tank's float sensor (PID 2F, %): noisy, non-linear and stuck at 100 % for the first litres
 *    after a fill-up, but absolute, so it stops 1 and 2 from drifting. Only a one-minute median,
 *    taken while stopped or driving steadily, is used.
 * 4. Fill-up receipts (REFUEL): ground truth. They reset the estimate, teach the float sensor's
 *    real curve and correct the fuel-rate scale.
 *
 * A one-dimensional Kalman filter combines them: counting moves the estimate down and grows its
 * uncertainty; each float reading pulls it towards the sensor by as much as the two are trusted.
 * State is kept between drives in fuel_state.json. Error sizes are assumptions, not measured.
 */
class FuelTracker(
    private val nominalTank: Double,     // the size entered in My car: a starting guess
    private val engineLitres: Double?,   // null: no backup estimate from the air (not petrol)
    private val engineSize: Double?,     // litres, any fuel: for the idle fuel-rate check
    private val volumetricEfficiency: Double,
    private val stateFile: File?,        // null: don't load or save anything (demo, replay)
) {
    private val state: JSONObject = load().also { seedRecent(it) }

    /** The tank's real volume to the first click: learned from near-empty full fills, else the entered size. */
    private val tank: Double get() = state.num("tank_learned") ?: nominalTank
    private var odoChecked = false
    private var lastOdo: Double? = null   // the car's km counter ("distance since codes cleared")
    private var litres: Double? = state.num("litres")
    private var variance: Double = state.num("variance") ?: 0.0
    private var counted: Double? = litres   // same start, but only ever reduced by counting
    private var sinceAnchor = 0.0          // litres counted since the last fill-up or reset
    private val readings = ArrayDeque<Pair<Double, Double>>()   // (time, float %)
    private var lastTime: Double? = null
    private var lastFloatUpdate = 0.0
    private var lastSave = 0.0
    private var flow: Double? = null
    private var flowSource: String? = null
    private var usedTrip = 0.0
    private var tripKm = 0.0
    private var tripKmSaved = 0.0
    private var firstSeen: Double? = null
    private var usedSaved = 0.0
    private var runKm = 0.0                // this run's km and litres, kept in state "recent"
    private var runLitres = 0.0
    private var runIndex: Int? = null
    @Volatile private var pendingRefuel: Pair<Double?, Boolean>? = null
    private val notes = mutableListOf<String>()
    private var started = false
    private var consumptionNow: Double? = null
    private var engineOffAt = 0.0          // last time the engine was seen not running (gauge reads differently then)

    // ------------------------------------------------------------ public

    /**
     * Records a fill-up: [litresAdded] from the pump receipt, [full] = filled to the automatic cut-off.
     * Applied at the next update that has float readings (at the start of a run, or straight away).
     */
    fun refuel(litresAdded: Double?, full: Boolean) {
        pendingRefuel = litresAdded to full
    }

    /** Feed one set of readings; call once per update. */
    fun update(v: Values, now: Double, tripKm: Double, accelG: Double) {
        if (lastSave == 0.0) {
            lastSave = now
            engineOffAt = now   // the gauge check waits for a full minute of readings from this run
        }
        val rpm = v["RPM"]
        if (rpm == null || rpm < 400) engineOffAt = now
        val pct = v["Fuel level"]
        if (pct != null) {
            readings.addLast(now to pct)
            while (now - readings.first().first > SMOOTH_SECONDS) readings.removeFirst()
        }

        if (!started && !start(now)) return   // waiting for the first float readings
        v["Since codes cleared"]?.let { odo ->
            if (!odoChecked) missedDrives(odo)
            lastOdo = odo
        }
        if (pendingRefuel != null) applyRefuel(smoothedPct())   // a fill-up entered during the run
        val dt = lastTime?.let { now - it } ?: 0.0
        lastTime = now

        checkRate(v)
        val (f0, source) = currentFlow(v)
        flow = f0
        flowSource = source
        val speed = v["Speed"]
        val f = flow
        consumptionNow = if (f != null && speed != null && speed > 5) f / speed * 100 else null
        if (f != null && dt > 0) count(f * dt / 3600)

        if (now - lastFloatUpdate >= FLOAT_EVERY && abs(accelG) < STEADY_G) floatUpdate(now)

        this.tripKm = tripKm
        if (stateFile != null && now - lastSave >= SAVE_EVERY) save(now)
    }

    /** Everything the dashboard needs; litres is null until there's enough data. */
    fun report(): FuelReport {
        val pct = smoothedPct()
        val consumption = consumption()
        val left = litres?.coerceIn(0.0, tank)
        return FuelReport(
            litres = left,
            sigma = if (litres == null) null else sqrt(variance),
            tank = tank,
            sensorPct = pct,
            sensorLitres = pct?.let { floatLitres(it) },
            countedLitres = counted,
            flowLph = flow,
            flowSource = flowSource,
            usedTrip = usedTrip,
            nowLPer100km = consumptionNow,
            tripLPer100km = if (tripKm > 1) usedTrip / tripKm * 100 else null,
            consumptionLPer100km = consumption,
            rangeKm = if (left != null && consumption != null && consumption > 0) left / consumption * 100 else null,
            fillUpsLearned = state.optJSONArray("float_curve")?.length() ?: 0,
            notes = notes.toList(),
            fillTip = state.optInt("tank_samples", 0) == 0 || state.optInt("rate_scale_learned", 0) == 0,
        )
    }

    /** Litres for a float reading, from the sensor curve learned at fill-ups (straight line until then). */
    fun floatLitres(pct: Double): Double {
        val points = java.util.TreeMap<Double, Double>()
        points[0.0] = 0.0
        points[100.0] = tank
        curve().forEach { (p, l) -> points[p] = l }
        val list = points.entries.map { it.key to it.value }
        for ((a, b) in list.zipWithNext()) {
            if (a.first <= pct && pct <= b.first) {
                return if (b.first > a.first) a.second + (b.second - a.second) * (pct - a.first) / (b.first - a.first) else b.second
            }
        }
        return if (pct > 100) list.last().second else 0.0
    }

    /** Writes the state file (atomically, so a crash can't leave half a file). */
    fun save(now: Double = System.currentTimeMillis() / 1000.0) {
        val file = stateFile ?: return
        val l = litres ?: return
        val km = tripKm - tripKmSaved
        val used = usedTrip - usedSaved
        tripKmSaved = tripKm
        usedSaved = usedTrip
        state.put("saved", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        state.put("litres", round3(l))
        state.put("variance", round(variance * 10000) / 10000)
        state.put("float_pct_at_save", smoothedPct() ?: JSONObject.NULL)
        state.put("total_km", round3((state.num("total_km") ?: 0.0) + km))
        state.put("total_litres", round3((state.num("total_litres") ?: 0.0) + used))
        recordRun(km, used)
        lastOdo?.let { state.put("odo_km", it) }
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(state.toString(2))
        temp.renameTo(file)
        lastSave = now
    }

    // ------------------------------------------------------------ starting a run

    /** First estimate of this run: saved state, a fill-up, a detected refuel, or the float alone. */
    private fun start(now: Double): Boolean {
        val first = firstSeen ?: now.also { firstSeen = it }
        val pct = smoothedPct(minimum = 3)
        if (pct == null && now - first < 10) return false   // give the float sensor a few seconds

        val saved = litres
        if (pendingRefuel != null) {
            applyRefuel(pct)
        } else if (saved == null && pct != null) {   // first run ever: the float is all we have
            reset(floatLitres(pct), floatSigma() * floatSigma())
            notes += "First run: starting from the float sensor. After filling up, [REFUEL] with the litres and FULL for the best accuracy."
        } else if (saved != null && pct != null) {
            val sensor = floatLitres(pct)
            val noise = 3 * sqrt(variance + floatSigma() * floatSigma())
            if ((pct < PLATEAU_PCT && sensor - saved > maxOf(REFUEL_JUMP, noise)) ||
                (pct >= PLATEAU_PCT && saved < floatLitres(PLATEAU_PCT) - noise)
            ) {
                reset(maxOf(sensor, saved), floatSigma() * floatSigma())
                state.put("counting_since_full_valid", false)
                state.remove("gauge_window")
                notes += String.format(Locale.US, "Refuel detected (~%.0f L added). Next time use [REFUEL] for the best accuracy.", sensor - saved)
            }
        }
        if (litres == null) return false   // no saved state and no float sensor: nothing to start from yet
        started = true
        lastTime = now
        lastFloatUpdate = now
        counted = litres
        return true
    }

    private fun applyRefuel(pctNow: Double?) {
        val (added, full) = pendingRefuel ?: return
        pendingRefuel = null
        state.remove("gauge_window")
        val before = litres
        val beforeSigma = if (before != null) sqrt(variance) else null
        val capacity = tank   // before this fill teaches it anything
        val receipts = state.num("receipts_since_full") ?: 0.0
        logFill(added, full, before, beforeSigma, capacity, receipts)
        if (full) {
            if (added != null && added > 0) {
                learnFromFullFill(added)
                val pctAtSave = state.num("float_pct_at_save")
                // Tank was (full - added) litres just before this fill: a real point on the float curve.
                if (before != null && pctAtSave != null) addCurvePoint(pctAtSave, capacity - added)
                learnTank(before, beforeSigma, added)
            }
            reset(tank, 0.5 * 0.5)   // brimmed tanks still vary a little
            state.put("counting_since_full", 0.0)
            state.put("receipts_since_full", 0.0)
            state.put("counting_since_full_valid", true)
            notes += "Fill-up to full recorded" + (if (added != null && added > 0) String.format(Locale.US, " (%.1f L added).", added) else ".")
        } else if (added != null && added > 0) {
            val base = before ?: pctNow?.let { floatLitres(it) } ?: 0.0
            val v = (if (before != null) variance else floatSigma() * floatSigma()) + 0.3 * 0.3
            reset(minOf(tank, base + added), v)
            state.put("receipts_since_full", (state.num("receipts_since_full") ?: 0.0) + added)
            notes += String.format(Locale.US, "Fill-up of %.1f L recorded.", added)
        }
    }

    /**
     * The real tank volume to the first click: fuel left just before a full fill + the litres added.
     * Only from fills made fairly empty with a fairly certain estimate (then the estimate's error is
     * small next to the tank). Averaged with the entered size as a first guess (2026-11-01).
     */
    private fun learnTank(before: Double?, sigma: Double?, added: Double) {
        if (before == null || sigma == null || sigma > 2.0 || before > 0.5 * nominalTank) return
        val estimate = before + added
        if (estimate < nominalTank * 0.8 || estimate > nominalTank * 1.3) return   // implausible: skip
        val n = state.optInt("tank_samples", 0)
        val old = state.num("tank_learned") ?: nominalTank
        val learned = (old * (n + 1) + estimate) / (n + 2)   // the entered size counts as one sample
        state.put("tank_learned", round(learned * 10) / 10)
        state.put("tank_samples", n + 1)
        notes += String.format(Locale.US, "Real tank size learned: %.1f L (from %d fill%s).", learned, n + 1, if (n == 0) "" else "s")
    }

    /**
     * One line per refuel in fuel_state.json ("fills"): date, litres, full, the car's km counter, what
     * the app thought was left, and for full fills an accuracy check and the true consumption since
     * the previous full fill (litres added / km driven: the most reliable figure there is).
     */
    private fun logFill(added: Double?, full: Boolean, before: Double?, sigma: Double?, capacity: Double, receipts: Double) {
        val odo = lastOdo ?: state.num("odo_km")   // refuels happen between drives: the last drive's end
        val fill = JSONObject()
            .put("date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            .put("litres", added ?: JSONObject.NULL)
            .put("full", full)
            .put("odo_km", odo ?: JSONObject.NULL)
            .put("app_before", before?.let { round(it * 10) / 10 } ?: JSONObject.NULL)
        if (full && added != null && added > 0) {
            val actual = capacity - added
            fill.put("actual_before", round(actual * 10) / 10)
            if (before != null) {
                fill.put("error", round((before - actual) * 10) / 10)
                notes += String.format(Locale.US, "Accuracy check: the app said %.1f L were left; this fill showed about %.1f L (off by %.1f L).",
                    before, actual, abs(before - actual))
            }
            val fullOdo = state.num("full_odo")
            if (odo != null && fullOdo != null && odo - fullOdo >= 20) {
                val l100 = (receipts + added) / (odo - fullOdo) * 100
                fill.put("l_per_100km", round(l100 * 10) / 10)
                notes += String.format(Locale.US, "True consumption since the last full fill: %.1f L/100 km.", l100)
            }
        }
        if (full && odo != null) state.put("full_odo", odo)
        val fills = state.optJSONArray("fills") ?: JSONArray()
        fills.put(fill)
        val kept = JSONArray()
        for (i in maxOf(0, fills.length() - 50) until fills.length()) kept.put(fills.get(i))
        state.put("fills", kept)
        save()
    }

    /**
     * Drives without Car Dash: the car's km counter moved since the last saved drive, so fuel was used
     * that wasn't counted: km x this car's consumption is taken off, with a larger uncertainty.
     */
    private fun missedDrives(odo: Double) {
        odoChecked = true
        val saved = state.num("odo_km") ?: return
        val km = odo - saved
        if (km < 1 || km > 3000) return   // nothing missed, or the counter was reset (codes cleared)
        val l = km * (consumption() ?: DEFAULT_L_PER_100) / 100
        litres = litres?.let { maxOf(0.0, it - l) }
        counted = counted?.let { it - l }
        variance += (0.3 * l) * (0.3 * l)
        state.put("counting_since_full_valid", false)   // this tank can't calibrate the counting any more
        state.optJSONObject("gauge_window")?.let {        // the gauge check goes on, with this estimate marked
            it.put("litres", it.optDouble("litres", 0.0) + l)
            it.put("missed", it.optDouble("missed", 0.0) + l)
        }
        notes += String.format(Locale.US, "Driven about %.0f km without Car Dash: about %.1f L taken off.", km, l)
    }

    /** Between two fills to full, the litres added = the fuel really burned: correct the counting scale. */
    private fun learnFromFullFill(added: Double) {
        val countedSinceFull = state.num("counting_since_full") ?: 0.0
        val reallyBurned = (state.num("receipts_since_full") ?: 0.0) + added
        if (state.optBoolean("counting_since_full_valid", false) && countedSinceFull > 5) {
            val newScale = (state.num("rate_scale") ?: 1.0) * reallyBurned / countedSinceFull
            val old = state.num("rate_scale")
            state.put("rate_scale", if (old == null) newScale else (old + newScale) / 2)
            state.put("rate_scale_learned", state.optInt("rate_scale_learned", 0) + 1)
            notes += String.format(Locale.US, "Fuel-rate scale corrected to %.3f.", state.getDouble("rate_scale"))
        }
    }

    private fun addCurvePoint(pct: Double, litres: Double) {
        val points = curve().filter { abs(it.first - pct) > 3 }.toMutableList()   // replace close ones
        points += round(pct * 10) / 10 to round(litres * 100) / 100
        val array = JSONArray()
        points.sortedWith(compareBy({ it.first }, { it.second })).takeLast(20).forEach { (p, l) -> array.put(JSONArray().put(p).put(l)) }
        state.put("float_curve", array)
    }

    private fun curve(): List<Pair<Double, Double>> {
        val array = state.optJSONArray("float_curve") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val point = array.optJSONArray(i) ?: return@mapNotNull null
            point.optDouble(0) to point.optDouble(1)
        }
    }

    // ------------------------------------------------------------ counting

    /** Fuel flowing now, litres/hour, and where the number came from. */
    private fun currentFlow(v: Values): Pair<Double?, String?> {
        val air = airFlowLph(v)
        val rate = v["Fuel rate"]
        val ratio = if (rate != null && air != null && air > 0.3) rate / air else null
        if (ratio != null) {   // for the fuel-rate check: unclipped, so a wrong reading shows (and a fixed one recovers)
            state.put("rate_vs_air", 0.99 * (state.num("rate_vs_air") ?: ratio) + 0.01 * ratio)
            state.put("rate_vs_air_n", state.optInt("rate_vs_air_n", 0) + 1)
        }
        val believable = rateCheck() != FuelLearning.Check.OFF
        if (rate != null && believable) {
            // keep the backup scaled to agree with the engine's own figure
            if (ratio != null && ratio > 0.3 && ratio < 3) {
                state.put("air_scale", 0.99 * (state.num("air_scale") ?: 1.0) + 0.01 * ratio)
            }
            return rate * (state.num("rate_scale") ?: 1.0) to RATE
        }
        // The car's figure missing, or not believable: the air estimate (unscaled if the scale came from a wrong rate).
        if (air != null) return air * (if (believable) state.num("air_scale") ?: 1.0 else 1.0) to "estimated from air intake"
        return null to null
    }

    /** Fuel needed for the air the engine takes in (speed-density: pressure x volume / temperature). */
    private fun airFlowLph(v: Values): Double? {
        val pressure = v["Manifold pressure"]
        val rpm = v["RPM"]
        val engine = engineLitres
        if (pressure == null || rpm == null || engine == null || engine == 0.0) return null
        val kelvin = (v["Intake air"] ?: 25.0) + 273.15
        val airGPerS = pressure * 1000 * engine / 1000 * volumetricEfficiency * rpm / 120 / (287.05 * kelvin) * 1000
        val lambda = v["Lambda (commanded)"]?.takeIf { it != 0.0 } ?: 1.0
        return airGPerS / (STOICHIOMETRIC_AFR * lambda) / PETROL_G_PER_LITRE * 3600
    }

    /**
     * Predict step: fuel burned lowers the estimate; the uncertainty grows with what was counted.
     * Counting errors are mostly a scale error (systematic), so they grow with the total counted
     * since the last anchor, not like independent noise.
     */
    private fun count(l: Double) {
        val error = countingError()
        sinceAnchor += l
        variance += (error * l) * (error * l) + 2 * error * error * (sinceAnchor - l) * l
        litres = litres?.minus(l)
        counted = counted?.minus(l)
        usedTrip += l
        state.optJSONObject("gauge_window")?.let { it.put("litres", it.optDouble("litres", 0.0) + l) }
        val scale = state.num("rate_scale") ?: 1.0
        if (flowSource == RATE) {
            state.put("counting_since_full", (state.num("counting_since_full") ?: 0.0) + l / scale)
        } else {
            state.put("counting_since_full_valid", false)   // backup counting can't calibrate the scale
        }
    }

    private fun countingError(): Double {
        if (flowSource != RATE) return AIR_ERROR
        return if (state.optInt("rate_scale_learned", 0) > 0) RATE_ERROR_CALIBRATED else RATE_ERROR
    }

    // ------------------------------------------------------------ float corrections

    /** Update step: pull the estimate towards the smoothed float reading, weighted by trust. */
    private fun floatUpdate(now: Double) {
        val pct = smoothedPct() ?: return
        val current = litres ?: return
        lastFloatUpdate = now
        learnGauge(pct)
        if (now - engineOffAt >= SMOOTH_SECONDS) checkGauge(pct)   // only readings taken with the engine running
        val measured = floatLitres(pct)
        var noise = floatSigma() * floatSigma()
        if (gaugeCheck() == FuelLearning.Check.OFF && rateCheck() == FuelLearning.Check.OK) noise *= GAUGE_OFF_NOISE
        if (pct >= PLATEAU_PCT && current >= measured) return   // up on the plateau the float only says "at least"
        if (pct <= 3) noise *= 4   // near empty the sender is least reliable
        val gain = variance / (variance + noise)
        litres = current + gain * (measured - current)
        val floor = if (curve().size >= 2) MIN_SIGMA_CALIBRATED else MIN_SIGMA
        variance = maxOf((1 - gain) * variance, floor * floor)
    }

    /**
     * The tank gauge's curve from counting (2026-11-01): once the counting is calibrated by two full
     * fills, litres left since the last full fill are known without the gauge (tank - counted +
     * receipts), so each steady moment is a point "gauge % = litres". Not near full (the gauge sticks
     * there) or empty (least reliable), and only on a tank counted all the way since full.
     */
    private fun learnGauge(pct: Double) {
        if (state.optInt("rate_scale_learned", 0) == 0 || !state.optBoolean("counting_since_full_valid", false)) return
        if (pct >= PLATEAU_PCT || pct <= 5) return
        val burned = (state.num("counting_since_full") ?: return) * (state.num("rate_scale") ?: 1.0)
        val left = tank - burned + (state.num("receipts_since_full") ?: 0.0)
        if (left <= 0 || left > tank) return
        addCurvePoint(pct, left)
    }

    /**
     * Gauge check (2026-11-04): over every [GAUGE_CHECK_PCT] the gauge drops, compare the litres it
     * shows gone with the litres counted from the engine. Several checks far from 1 = the gauge (or
     * its learned curve) is off at these levels; it is then trusted less, see [floatUpdate]. Fill-ups
     * teach the gauge's curve, after which new checks come out right and the flag clears.
     */
    private fun checkGauge(pct: Double) {
        if (pct >= PLATEAU_PCT) return   // stuck near full: says nothing about the fuel used
        val window = state.optJSONObject("gauge_window")
        if (window == null || pct > window.optDouble("pct") + 5) {   // none yet, or fuel was added
            state.put("gauge_window", JSONObject().put("pct", pct).put("litres", 0.0))
            return
        }
        val start = window.optDouble("pct")
        if (start - pct < GAUGE_CHECK_PCT) return
        val counted = window.optDouble("litres", 0.0)
        // Skipped if too much of it is only estimated (drives without Car Dash).
        if (counted > 0.5 && window.optDouble("missed", 0.0) <= MAX_MISSED_SHARE * counted) {
            val ratio = (floatLitres(start) - floatLitres(pct)) / counted
            val checks = state.optJSONArray("gauge_checks") ?: JSONArray()
            checks.put(JSONArray().put(round((start + pct) / 2 * 10) / 10).put(round(ratio * 100) / 100))
            while (checks.length() > 10) checks.remove(0)
            state.put("gauge_checks", checks)
        }
        state.put("gauge_window", JSONObject().put("pct", pct).put("litres", 0.0))
    }

    private fun gaugeCheck() = gaugeVerdict(state).first

    /** Idle fuel use (engine running, standing still), for the fuel-rate check. */
    private fun checkRate(v: Values) {
        val rate = v["Fuel rate"] ?: return
        val rpm = v["RPM"] ?: return
        if (rpm < 500 || rpm > 1100 || (v["Speed"] ?: 0.0) > 0) return
        val n = state.optInt("idle_n", 0)
        state.put("idle_lph", if (n == 0) rate else 0.99 * (state.num("idle_lph") ?: rate) + 0.01 * rate)
        state.put("idle_n", n + 1)
    }

    private fun rateCheck() = rateVerdict(state, engineSize)

    private fun floatSigma() = if (curve().size >= 2) FLOAT_SIGMA_CALIBRATED else FLOAT_SIGMA

    private fun smoothedPct(minimum: Int? = null): Double? {
        val values = readings.map { it.second }.sorted()
        if (values.size < (minimum ?: MIN_FLOAT_READINGS)) return null
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2
    }

    // ------------------------------------------------------------ range

    /**
     * L/100 km for the range: this trip once it's long enough, blended with the recent average
     * (the last ~100 km of driving; the all-time average only until there's 20 km of it).
     */
    private fun consumption(): Double? {
        val totalKm = state.num("total_km") ?: 0.0
        val totalL = state.num("total_litres") ?: 0.0
        val longTerm = recentConsumption() ?: if (totalKm > 20) totalL / totalKm * 100 else null
        val trip = if (tripKm > 2) usedTrip / tripKm * 100 else null
        if (longTerm != null && longTerm != 0.0 && trip != null && trip != 0.0) {
            val weight = minOf(tripKm / 50, 0.7)   // longer trips count more, but history always keeps a say
            return weight * trip + (1 - weight) * longTerm
        }
        return trip?.takeIf { it != 0.0 } ?: longTerm
    }

    /**
     * L/100 km over the newest runs, back to ~100 km of driving (2026-11-04). Runs that didn't move
     * (parked with the engine idling, e.g. testing the app) are left out: they made the range far too
     * low. This run is left out too: it's blended in separately as the trip.
     */
    private fun recentConsumption(): Double? {
        val runs = state.optJSONArray("recent") ?: return null
        var km = 0.0
        var litres = 0.0
        for (i in runs.length() - 1 downTo 0) {
            if (i == runIndex) continue
            val run = runs.optJSONArray(i) ?: continue
            val k = run.optDouble(0, 0.0)
            if (k < MIN_RUN_KM) continue
            km += k
            litres += run.optDouble(1, 0.0)
            if (km >= RECENT_KM) break
        }
        return if (km > 20 && litres > 0) litres / km * 100 else null
    }

    /** Adds what was driven since the last save to this run's entry in "recent" (one entry per run). */
    private fun recordRun(km: Double, litres: Double) {
        runKm += km
        runLitres += litres
        if (runKm <= 0 && runLitres <= 0) return
        val runs = state.optJSONArray("recent") ?: JSONArray()
        val entry = JSONArray().put(round3(runKm)).put(round3(runLitres))
        val index = runIndex
        if (index != null && index < runs.length()) {
            runs.put(index, entry)
        } else {
            runs.put(entry)
            while (runs.length() > MAX_RUNS) runs.remove(0)
            runIndex = runs.length() - 1
        }
        state.put("recent", runs)
    }

    /**
     * First time with "recent": fill it from this car's drive reports (km and litres per recorded
     * drive), so the range uses real driving straight away instead of the all-time average.
     */
    private fun seedRecent(state: JSONObject) {
        val file = stateFile ?: return
        if (state.has("recent")) return
        val reports = File(file.parentFile, "recordings")
            .listFiles { f -> f.name.startsWith("drive_") && f.name.endsWith("_report.json") }.orEmpty().sortedBy { it.name }
        val runs = JSONArray()
        for (f in reports.takeLast(MAX_RUNS)) {
            val r = runCatching { JSONObject(f.readText()) }.getOrNull() ?: continue
            val km = r.optDouble("km", Double.NaN)
            val litres = r.optDouble("litres", Double.NaN)
            if (km.isNaN() || litres.isNaN()) continue
            runs.put(JSONArray().put(round3(km)).put(round3(litres)))
        }
        state.put("recent", runs)
    }

    // ------------------------------------------------------------ helpers

    private fun reset(l: Double, v: Double) {
        litres = l
        variance = v
        counted = l
        sinceAnchor = 0.0
    }

    private fun load(): JSONObject = try {
        stateFile?.takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
    } catch (e: Exception) {
        JSONObject()   // a damaged file shouldn't stop the dashboard
    }

    private fun JSONObject.num(key: String): Double? = if (isNull(key)) null else (opt(key) as? Number)?.toDouble()

    private fun round3(x: Double) = round(x * 1000) / 1000

    companion object {
        /** For My car: what has been learned so far, from the saved state (null file: nothing yet). */
        fun learning(state: JSONObject?, engineSize: Double?): FuelLearning {
            val s = state ?: JSONObject()
            val fills = s.optJSONArray("fills")
            val list = (0 until (fills?.length() ?: 0)).mapNotNull { fills!!.optJSONObject(it) }
            val (gauge, low) = gaugeVerdict(s)
            return FuelLearning(
                fullFills = list.count { it.optBoolean("full") },
                calibrated = s.optInt("rate_scale_learned", 0) > 0,
                sigma = s.optDouble("variance", Double.NaN).takeIf { !it.isNaN() }?.let { sqrt(it) },
                gauge = gauge,
                gaugeReadsLow = low,
                fuelRate = rateVerdict(s, engineSize),
                lastFillError = list.lastOrNull { it.has("error") }?.optDouble("error"),
                tankLearned = s.optDouble("tank_learned", Double.NaN).takeIf { !it.isNaN() },
            )
        }

        /** The last 3 gauge checks: OK when their median ratio is near 1; second = gauge reads low. */
        private fun gaugeVerdict(s: JSONObject): Pair<FuelLearning.Check, Boolean> {
            val checks = s.optJSONArray("gauge_checks") ?: return FuelLearning.Check.CHECKING to false
            val ratios = (maxOf(0, checks.length() - 3) until checks.length())
                .mapNotNull { checks.optJSONArray(it)?.optDouble(1)?.takeIf { r -> !r.isNaN() } }.sorted()
            if (ratios.size < 2) return FuelLearning.Check.CHECKING to false
            val median = if (ratios.size % 2 == 1) ratios[ratios.size / 2] else (ratios[0] + ratios[1]) / 2
            return if (median in GAUGE_OK) FuelLearning.Check.OK to false else FuelLearning.Check.OFF to (median > 1)
        }

        /**
         * Is the engine's fuel rate believable? Idle use per litre of engine and, for petrol, agreement
         * with the air-intake estimate (rough: the engine's breathing is assumed). Wide limits: only a
         * clearly wrong reading (e.g. a different unit or always zero) fails.
         */
        private fun rateVerdict(s: JSONObject, engineSize: Double?): FuelLearning.Check {
            val idleN = s.optInt("idle_n", 0)
            val airN = s.optInt("rate_vs_air_n", 0)
            val idle = s.optDouble("idle_lph", Double.NaN)
            val vsAir = s.optDouble("rate_vs_air", Double.NaN)
            val idleKnown = idleN >= RATE_SAMPLES && engineSize != null && engineSize > 0 && !idle.isNaN()
            val airKnown = airN >= RATE_SAMPLES && !vsAir.isNaN()
            if (idleKnown && (idle / engineSize!!) !in IDLE_LPH_PER_LITRE) return FuelLearning.Check.OFF
            if (airKnown && vsAir !in RATE_VS_AIR) return FuelLearning.Check.OFF
            return if (idleKnown || airKnown) FuelLearning.Check.OK else FuelLearning.Check.CHECKING
        }

        private val GAUGE_OK = 0.75..1.33          // gauge litres / counted litres that count as agreeing
        private val IDLE_LPH_PER_LITRE = 0.2..2.0  // idle fuel use, L/h per litre of engine
        private val RATE_VS_AIR = 0.5..2.0         // engine's fuel rate / air-intake estimate
        private const val RATE_SAMPLES = 300       // readings before the fuel-rate check decides
        private const val GAUGE_CHECK_PCT = 10.0   // gauge drop (%) per gauge check
        private const val MAX_MISSED_SHARE = 0.3   // most of a gauge check's litres must be counted, not estimated
        private const val GAUGE_OFF_NOISE = 4.0    // a gauge that disagrees: its variance x this (sigma x2)

        const val PETROL_G_PER_LITRE = 745.0   // density of petrol
        const val STOICHIOMETRIC_AFR = 14.7    // grams of air per gram of petrol for complete burning
        const val RATE = "engine fuel rate"
        const val FLOAT_SIGMA = 2.0            // litres (1σ): smoothed float sensor before any fill-up calibration
        const val FLOAT_SIGMA_CALIBRATED = 1.2 // once two or more fill-ups have taught the sensor curve
        const val RATE_ERROR = 0.05            // counting from fuel rate: error as a fraction of litres counted
        const val RATE_ERROR_CALIBRATED = 0.02 // after a fill-to-full pair has corrected the scale
        const val AIR_ERROR = 0.15             // counting from the air estimate
        const val MIN_SIGMA = 1.0              // litres: never surer than this while the float curve is unlearned...
        const val MIN_SIGMA_CALIBRATED = 0.4   // ...or than this once fill-ups have taught it
        const val SMOOTH_SECONDS = 60.0        // float readings are reduced to their median over this window...
        const val FLOAT_EVERY = 60.0           // ...and used at most this often (its errors are correlated)
        const val MIN_FLOAT_READINGS = 5
        const val STEADY_G = 0.05              // only trust the float when acceleration/braking is below this
        const val PLATEAU_PCT = 97.0           // float reads ~100 % for the first litres after filling
        const val REFUEL_JUMP = 4.0            // litres: a bigger rise since last drive counts as a refuel
        const val SAVE_EVERY = 30.0            // seconds between saves of the state file
        const val DEFAULT_L_PER_100 = 7.0      // for missed drives before the car's own average is known
        const val RECENT_KM = 100.0            // the range's average covers about this much recent driving...
        const val MIN_RUN_KM = 0.5             // ...from runs that moved at least this far
        const val MAX_RUNS = 60                // runs kept in "recent"
    }
}
