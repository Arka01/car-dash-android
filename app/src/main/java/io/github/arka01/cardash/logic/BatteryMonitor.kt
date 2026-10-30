package io.github.arka01.cardash.logic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

/** One engine start: the lowest voltage while cranking, and how many readings saw it. */
data class Crank(val time: String, val minVolts: Double, val samples: Int, val level: String, val text: String)

data class BatteryReport(
    val volts: Double,
    val source: String,            // "adapter port" or "engine computer"
    val ecuVolts: Double?,
    val portVolts: Double?,
    val state: String,             // CHARGING, LOW CHARGE, NOT CHARGING, OVERCHARGING, SETTLING, RESTING or ""
    val level: Level,
    val detail: String,
    val crank: Crank?,
    val sourcesNote: String?,
)

/**
 * Battery and charging health from two independent voltage readings (the Python dashboard's battery_monitor.py):
 * the adapter's own port voltage ("AT RV", quick) and the engine computer's supply voltage (PID 42).
 *
 * - Engine running: CHARGING, LOW CHARGE, NOT CHARGING (alternator problem) or OVERCHARGING.
 *   Cars with a "smart" alternator (car.json "smart_alternator": true, e.g. the Tiago) charge mostly
 *   when slowing down and drop to ~12.2 V while accelerating, by design; they're judged by the
 *   highest voltage over the last 5 minutes instead: a working alternator still lifts it above ~13 V
 *   every so often, a failed one never does.
 * - Engine off: SETTLING right after stopping (surface charge), then RESTING with a state of charge.
 * - Each engine start: the lowest voltage while cranking, the classic battery test. Kept in
 *   battery_state.json so a battery that is slowly weakening shows up over weeks.
 *
 * Thresholds are typical 12 V lead-acid values (general knowledge), not maker specifications.
 */
class BatteryMonitor(private val stateFile: File?, private val smart: Boolean) {
    private val state: JSONObject = load()
    private val samples = ArrayDeque<Pair<Double, Double>>()        // (time, volts), the last few seconds
    private val runningVolts = ArrayDeque<Pair<Double, Double>>()   // while running, the last SMART_WINDOW s
    private var runningSince: Double? = null
    private var volts: Double? = null
    private var port: Double? = null
    private var ecu: Double? = null
    private var running: Boolean? = null
    private var offSince: Double? = null
    private var problemSince: Double? = null
    private var overSince: Double? = null
    private var disagreeSince: Double? = null
    private var now = 0.0
    private var lastReading: Double? = null
    private var lastCrank: JSONObject? = state.optJSONArray("cranks")?.let { if (it.length() > 0) it.getJSONObject(it.length() - 1) else null }

    /** Feed one set of readings; call once per update. */
    fun update(v: Values, now: Double) {
        this.now = now
        port = v[Values.PORT]
        ecu = v["Battery"]
        volts = port ?: ecu
        val current = volts
        if (current != null && isNewReading(v, current)) {
            samples.addLast(now to current)
            while (now - samples.first().first > CRANK_WINDOW + 1) samples.removeFirst()
        }

        val rpm = v["RPM"]
        if (rpm != null) {
            val isRunning = rpm >= RUNNING_RPM
            val off = offSince
            val offLongEnough = off != null && now - off >= MIN_OFF_SECONDS
            if (running == false && isRunning && offLongEnough) recordCrank(now)   // the engine just started
            if (isRunning) {
                offSince = null
                runningSince = runningSince ?: now
            } else {
                runningSince = null
                runningVolts.clear()
                if (rpm < STOPPED_RPM && offSince == null && running != null) offSince = now
            }
            running = isRunning
        }
        if (running == true && current != null) {
            runningVolts.addLast(now to current)
            while (now - runningVolts.first().first > SMART_WINDOW) runningVolts.removeFirst()
        }
        trackTimers(now)
    }

    /**
     * Only real new readings count towards the cranking dip, not the last value shown again:
     * the reading's time when known (live), else a changed value (replays).
     */
    private fun isNewReading(v: Values, current: Double): Boolean {
        val key = v.readAt[if (port != null) Values.PORT else "Battery"] ?: current
        if (key == lastReading) return false
        lastReading = key
        return true
    }

    /** How long a charging problem / source disagreement has lasted. */
    private fun trackTimers(now: Double) {
        val v = volts
        val chargingLow = running == true && v != null && v < CHARGING_MIN
        problemSince = if (chargingLow) problemSince ?: now else null
        val over = running == true && v != null && v > OVERCHARGE_WARNING
        overSince = if (over) overSince ?: now else null
        val p = port
        val e = ecu
        val apart = p != null && e != null && kotlin.math.abs(p - e) > SOURCES_DISAGREE
        disagreeSince = if (apart) disagreeSince ?: now else null
    }

    /** True while the engine is known to be off (then voltage and RPM are read fast for the start dip). */
    fun engineOff() = running == false

    /** Everything the dashboard needs, or null until there's a voltage reading. */
    fun report(): BatteryReport? {
        val v = volts ?: return null
        val (state, level, detail) = judge(v)
        return BatteryReport(
            volts = v,
            source = if (port != null) "adapter port" else "engine computer",
            ecuVolts = ecu,
            portVolts = port,
            state = state,
            level = level,
            detail = detail,
            crank = crankVerdict(),
            sourcesNote = sourcesCheck(),
        )
    }

    // ------------------------------------------------------------ states

    private fun judge(volts: Double): Triple<String, Level, String> {
        if (running == true) {
            // Must last OVERCHARGE_AFTER: a smart alternator briefly reaches ~15.1 V while braking
            // (Tiago 2026-10-29, ~1 s at 15:38:26). Unlike the Python dashboard, which reacts at once.
            val overFor = overSince?.let { now - it } ?: 0.0
            if (overFor >= OVERCHARGE_AFTER && volts > OVERCHARGE_CRITICAL) {
                return Triple("OVERCHARGING", Level.ALARM, "voltage regulator fault: can damage the battery")
            }
            if (overFor >= OVERCHARGE_AFTER && volts > OVERCHARGE_WARNING) {
                return Triple("OVERCHARGING", Level.WARN, "higher than normal charging voltage")
            }
            if (smart) return smartState()
            val lasted = problemSince?.let { now - it } ?: 0.0
            if (volts < NOT_CHARGING && lasted >= CRITICAL_AFTER) {
                return Triple("NOT CHARGING", Level.ALARM, "alternator not charging: running on battery")
            }
            if (lasted >= WARNING_AFTER) return Triple("LOW CHARGE", Level.WARN, "alternator output low")
            return Triple("CHARGING", Level.OK, "alternator OK")
        }
        if (running == null) return Triple("", Level.OK, "")
        val off = offSince
        if (off != null && now - off < SETTLE_SECONDS) {
            val minutes = (SETTLE_SECONDS - (now - off)) / 60
            return Triple("SETTLING", Level.OK, String.format(Locale.US, "true resting value in ~%.0f min", minutes))
        }
        val percent = chargePercent(volts)
        val level = if (percent < 25) Level.ALARM else if (percent < 50) Level.WARN else Level.OK
        val prefix = if (off != null) "" else "if rested: "
        return Triple("RESTING", level, String.format(Locale.US, "%s≈%.0f %% charged", prefix, percent))
    }

    /** Smart alternator: low voltage now is normal; never getting above ~13 V for minutes is not. */
    private fun smartState(): Triple<String, Level, String> {
        val since = runningSince
        if (since == null || now - since < SMART_WINDOW || runningVolts.isEmpty()) {
            return Triple("CHARGING", Level.OK, "smart alternator: varies by design")   // too early to judge
        }
        val peak = runningVolts.maxOf { it.second }
        val minutes = (SMART_WINDOW / 60).toInt()
        if (peak < SMART_NO_PEAK) {
            return Triple("NOT CHARGING", Level.ALARM, String.format(Locale.US, "never above %.1f V in %d min: running on battery", peak, minutes))
        }
        if (peak < SMART_LOW_PEAK) {
            return Triple("LOW CHARGE", Level.WARN, String.format(Locale.US, "only up to %.1f V in %d min", peak, minutes))
        }
        return Triple("CHARGING", Level.OK, String.format(Locale.US, "smart alternator: up to %.1f V lately", peak))
    }

    /** Lowest voltage between the engine stopping (at most CRANK_WINDOW s back) and it catching. */
    private fun recordCrank(now: Double) {
        val since = maxOf(now - CRANK_WINDOW, offSince ?: 0.0)
        val window = samples.filter { it.first >= since }.map { it.second }
        if (window.isEmpty()) return
        val dense = samples.count { it.first >= now - CRANK_DENSE_SECONDS && it.first >= since }
        val crank = JSONObject()
            .put("when", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            .put("min_volts", round(window.min() * 100) / 100)
            .put("samples", dense)   // readings in the last 2 s: enough to have caught the dip?
        lastCrank = crank
        val cranks = state.optJSONArray("cranks") ?: JSONArray()
        cranks.put(crank)
        val kept = JSONArray()
        for (i in maxOf(0, cranks.length() - 100) until cranks.length()) kept.put(cranks.get(i))
        state.put("cranks", kept)
        save()
    }

    private fun crankVerdict(): Crank? {
        val crank = lastCrank ?: return null
        val volts = crank.optDouble("min_volts")
        val samples = crank.optInt("samples")
        val (level, text) = when {
            samples < CRANK_MIN_SAMPLES -> "unknown" to "too few readings to judge"
            volts >= CRANK_GOOD -> "good" to "battery strong"
            volts >= CRANK_WEAK -> "warning" to "battery getting weak"
            else -> "critical" to "battery weak: may not start next time"
        }
        return Crank(crank.optString("when"), volts, samples, level, text)
    }

    /** The two readings should agree; a lasting gap points at a terminal, ground or wiring issue. */
    private fun sourcesCheck(): String? {
        val since = disagreeSince ?: return null
        if (now - since < DISAGREE_AFTER) return null
        return String.format(Locale.US, "port %.1f V vs engine computer %.1f V: check battery terminals", port ?: 0.0, ecu ?: 0.0)
    }

    // ------------------------------------------------------------ state file

    private fun load(): JSONObject = try {
        stateFile?.takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
    } catch (e: Exception) {
        JSONObject()   // a damaged file shouldn't stop the dashboard
    }

    private fun save() {
        val file = stateFile ?: return
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(state.toString(2))
        temp.renameTo(file)
    }

    companion object {
        const val RUNNING_RPM = 400.0   // above this the engine counts as running
        const val STOPPED_RPM = 100.0
        const val CHARGING_MIN = 13.0   // volts while running: below this (sustained) is a warning...
        const val NOT_CHARGING = 12.6   // ...and below this the alternator isn't charging at all
        const val OVERCHARGE_WARNING = 15.0
        const val OVERCHARGE_CRITICAL = 15.5
        const val OVERCHARGE_AFTER = 5.0   // seconds above 15 V before it counts
        const val WARNING_AFTER = 10.0  // seconds a charging problem must last (ignores brief dips)
        const val CRITICAL_AFTER = 30.0
        // Smart alternator: judged by the highest voltage in the last SMART_WINDOW seconds of running. Healthy,
        // the Tiago's lowest such peak over its drives (2026-10-13..24) was 13.8 V, even in slow city traffic.
        const val SMART_WINDOW = 5 * 60.0
        const val SMART_LOW_PEAK = 13.2   // peak below this: LOW CHARGE
        const val SMART_NO_PEAK = 12.8    // ...below this: NOT CHARGING
        const val SETTLE_SECONDS = 20 * 60.0   // surface charge is mostly gone after ~20 minutes off (rough)
        const val CRANK_GOOD = 10.0       // lowest volts while starting: above GOOD fine, below WEAK bad
        const val CRANK_WEAK = 9.6
        const val CRANK_WINDOW = 5.0      // seconds before the engine catches that count as cranking
        const val CRANK_DENSE_SECONDS = 2.0
        const val CRANK_MIN_SAMPLES = 5   // need >= 5 readings in the last 2 s to see the real dip
        const val MIN_OFF_SECONDS = 2.0   // the engine must have been off this long for a restart to count
        const val SOURCES_DISAGREE = 0.6  // volts between the two readings that suggests a wiring issue
        const val DISAGREE_AFTER = 30.0   // seconds the difference must last

        // Resting (open-circuit) voltage -> state of charge, lead-acid at ~25 °C.
        private val RESTING_CHARGE = listOf(11.89 to 0.0, 12.06 to 25.0, 12.24 to 50.0, 12.45 to 75.0, 12.65 to 100.0)

        /** Estimated state of charge for a rested battery, by straight lines between the table points. */
        fun chargePercent(volts: Double): Double {
            if (volts <= RESTING_CHARGE[0].first) return 0.0
            for ((a, b) in RESTING_CHARGE.zipWithNext()) {
                if (volts <= b.first) return a.second + (b.second - a.second) * (volts - a.first) / (b.first - a.first)
            }
            return 100.0
        }
    }
}
