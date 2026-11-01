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
)

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
    private val volumetricEfficiency: Double,
    private val stateFile: File?,        // null: don't load or save anything (demo, replay)
) {
    private val state: JSONObject = load()

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
    @Volatile private var pendingRefuel: Pair<Double?, Boolean>? = null
    private val notes = mutableListOf<String>()
    private var started = false
    private var consumptionNow: Double? = null

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
        if (lastSave == 0.0) lastSave = now
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
        if (rate != null) {
            if (air != null && air != 0.0) {   // keep the backup scaled to agree with the engine's own figure
                val ratio = if (air > 0.3) rate / air else null
                if (ratio != null && ratio > 0.3 && ratio < 3) {
                    state.put("air_scale", 0.99 * (state.num("air_scale") ?: 1.0) + 0.01 * ratio)
                }
            }
            return rate * (state.num("rate_scale") ?: 1.0) to RATE
        }
        if (air != null) return air * (state.num("air_scale") ?: 1.0) to "estimated from air intake"
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
        val measured = floatLitres(pct)
        var noise = floatSigma() * floatSigma()
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

    private fun floatSigma() = if (curve().size >= 2) FLOAT_SIGMA_CALIBRATED else FLOAT_SIGMA

    private fun smoothedPct(minimum: Int? = null): Double? {
        val values = readings.map { it.second }.sorted()
        if (values.size < (minimum ?: MIN_FLOAT_READINGS)) return null
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2
    }

    // ------------------------------------------------------------ range

    /** L/100 km for the range: this trip once it's long enough, blended with the long-term average. */
    private fun consumption(): Double? {
        val totalKm = state.num("total_km") ?: 0.0
        val totalL = state.num("total_litres") ?: 0.0
        val longTerm = if (totalKm > 20) totalL / totalKm * 100 else null
        val trip = if (tripKm > 2) usedTrip / tripKm * 100 else null
        if (longTerm != null && longTerm != 0.0 && trip != null && trip != 0.0) {
            val weight = minOf(tripKm / 50, 0.7)   // longer trips count more, but history always keeps a say
            return weight * trip + (1 - weight) * longTerm
        }
        return trip?.takeIf { it != 0.0 } ?: longTerm
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

    private companion object {
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
    }
}
