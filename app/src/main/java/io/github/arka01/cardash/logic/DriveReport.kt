package io.github.arka01.cardash.logic

import io.github.arka01.cardash.cars.CarProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** OK / WATCH / CHECK for one finding; INFO = no verdict. */
enum class Verdict { OK, WATCH, CHECK, INFO }

/** One report line: "label  value", with a verdict colour. */
class ReportLine(val label: String, val value: String, val verdict: Verdict = Verdict.INFO)

class ReportSection(val title: String, val lines: List<ReportLine>)

/**
 * What one drive was like, calculated from its recording (no AI): the checks done by hand on the
 * Tiago's drives (2026-10-24…30), each with a verdict from the app's own rules. Saved next to the
 * drive as drive_<date>_<time>_report.json; the input for the on-phone AI's summary later.
 */
class DriveReport(
    val drive: String,           // CSV file name
    val start: Long,             // epoch ms
    val minutes: Double,
    val km: Double,
    val litres: Double?,
    val lPer100: Double?,
    val verdicts: List<ReportLine>,   // the WATCH / CHECK findings first, then key OKs
    val sections: List<ReportSection>,
) {
    /** The worst verdict, for the drive list. */
    val worst: Verdict
        get() = when {
            verdicts.any { it.verdict == Verdict.CHECK } -> Verdict.CHECK
            verdicts.any { it.verdict == Verdict.WATCH } -> Verdict.WATCH
            else -> Verdict.OK
        }

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("drive", drive)
        .put("start", start)
        .put("minutes", minutes)
        .put("km", km)
        .put("litres", litres ?: JSONObject.NULL)
        .put("l_per_100km", lPer100 ?: JSONObject.NULL)
        .put("verdicts", JSONArray(verdicts.map { it.toJson() }))
        .put("sections", JSONArray(sections.map { s ->
            JSONObject().put("title", s.title).put("lines", JSONArray(s.lines.map { it.toJson() }))
        }))

    /** Plain text (for sharing, and later for the on-phone AI). */
    fun text(): String = buildString {
        append(SimpleDateFormat("d MMM yyyy HH:mm", Locale.UK).format(start))
        append(String.format(Locale.US, ", %.0f min, %.1f km\n", minutes, km))
        append("VERDICT\n")
        verdicts.forEach { append("  ${it.verdict} ${it.label}${if (it.value.isNotEmpty()) " ${it.value}" else ""}\n") }
        sections.forEach { s ->
            append(s.title).append('\n')
            s.lines.forEach { append("  ${it.label}: ${it.value}${if (it.verdict != Verdict.INFO) " (${it.verdict})" else ""}\n") }
        }
    }

    companion object {
        /** Raise when the calculation changes: older saved reports are then calculated again. */
        const val VERSION = 1

        fun fromJson(j: JSONObject): DriveReport? {
            if (j.optInt("version") != VERSION) return null
            fun lines(a: JSONArray) = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                ReportLine(o.getString("label"), o.getString("value"), Verdict.valueOf(o.getString("verdict")))
            }
            val sections = j.getJSONArray("sections")
            return DriveReport(
                drive = j.getString("drive"),
                start = j.getLong("start"),
                minutes = j.getDouble("minutes"),
                km = j.getDouble("km"),
                litres = if (j.isNull("litres")) null else j.getDouble("litres"),
                lPer100 = if (j.isNull("l_per_100km")) null else j.getDouble("l_per_100km"),
                verdicts = lines(j.getJSONArray("verdicts")),
                sections = (0 until sections.length()).map { i ->
                    val s = sections.getJSONObject(i)
                    ReportSection(s.getString("title"), lines(s.getJSONArray("lines")))
                },
            )
        }

        private fun ReportLine.toJson() = JSONObject().put("label", label).put("value", value).put("verdict", verdict.name)

        // ------------------------------------------------------------ thresholds (sources in the README)

        private const val RUNNING_RPM = 500.0
        private const val COLD = 70.0             // °C coolant at start: below = cold start
        private const val COLD_RPM_LIMIT = 2500.0 // warm-up advisor's "gently" limit
        private const val GAP_S = 2.0

        /**
         * The report for [csv] (a recording in the Python dashboard's column format).
         * [profile] = its _profile.json (trouble codes), [averageLPer100] = this car's average over
         * earlier drives (null below 3 drives).
         */
        fun calculate(csv: File, car: CarProfile, profile: JSONObject?, averageLPer100: Double?): DriveReport? {
            val rows = read(csv) ?: return null
            if (rows.size < 10) return null
            val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val t = rows.map { r -> r.time?.let { runCatching { clock.parse(it)?.time }.getOrNull() } ?: 0L }
            if (t.first() == 0L) return null
            val n = rows.size
            val secs = DoubleArray(n) { (t[it] - t[0]) / 1000.0 }
            val dt = DoubleArray(n) { if (it == 0) 0.0 else (secs[it] - secs[it - 1]).coerceIn(0.0, GAP_S) }
            val duration = secs.last()
            fun col(name: String) = DoubleArray(n) { rows[it].values[name] ?: Double.NaN }
            val speed = col("Speed")
            val rpm = col("RPM")
            val coolant = col("Coolant")
            val oil = col("Oil")
            val port = col("Battery (port)")
            val ecu = col("Battery")
            val running = BooleanArray(n) { !rpm[it].isNaN() && rpm[it] >= RUNNING_RPM }
            fun DoubleArray.maxOk() = filter { !it.isNaN() }.maxOrNull()
            fun DoubleArray.minOk() = filter { !it.isNaN() }.minOrNull()

            val verdicts = mutableListOf<ReportLine>()
            val oks = mutableListOf<ReportLine>()
            val sections = mutableListOf<ReportSection>()
            fun finding(line: ReportLine) {
                if (line.verdict == Verdict.WATCH || line.verdict == Verdict.CHECK) verdicts += line
                else if (line.verdict == Verdict.OK) oks += line
            }

            // ---------------------------------------------------- drive
            var km = 0.0
            for (i in 0 until n) if (!speed[i].isNaN()) km += speed[i] * dt[i] / 3600
            val runningSecs = (0 until n).filter { running[it] }.sumOf { dt[it] }
            val idleSecs = (0 until n).filter { running[it] && speed[it] < 0.5 }.sumOf { dt[it] }
            val overRedline = (0 until n).filter { !rpm[it].isNaN() && rpm[it] > car.redlineRpm }.sumOf { dt[it] }
            val redlineVerdict = when {
                overRedline <= 0.0 -> Verdict.OK
                overRedline <= 5.0 -> Verdict.WATCH
                else -> Verdict.CHECK
            }
            val drive = listOf(
                ReportLine("distance", String.format(Locale.US, "%.1f km", km)),
                ReportLine("average", String.format(Locale.US, "%.0f km/h", if (duration > 0) km / (duration / 3600) else 0.0)),
                ReportLine("top speed", speed.maxOk()?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "--"),
                ReportLine("idling", if (runningSecs > 0) String.format(Locale.US, "%.0f %%", idleSecs / runningSecs * 100) else "--"),
                ReportLine("max rpm", rpm.maxOk()?.let { String.format(Locale.US, "%.0f", it) } ?: "--"),
                ReportLine("over ${car.redlineRpm} rpm", String.format(Locale.US, "%.0f s", overRedline), redlineVerdict),
            )
            if (redlineVerdict != Verdict.OK) finding(ReportLine("over your ${car.redlineRpm} rpm limit", String.format(Locale.US, "for %.0f s", overRedline), redlineVerdict))
            sections += ReportSection("DRIVE", drive)

            // ---------------------------------------------------- fuel
            val rate = col("Fuel rate")
            var litres: Double? = null
            if (rate.any { !it.isNaN() }) litres = (0 until n).filter { !rate[it].isNaN() }.sumOf { rate[it] * dt[it] / 3600 }
            val lPer100 = litres?.takeIf { km >= 0.5 }?.let { it / km * 100 }
            val fuel = mutableListOf(
                ReportLine("used", litres?.let { String.format(Locale.US, "%.2f L", it) } ?: "--"),
                ReportLine("consumption", lPer100?.let { String.format(Locale.US, "%.1f L/100 km", it) } ?: "--"),
            )
            if (lPer100 != null && averageLPer100 != null && km >= 2) {
                val ratio = lPer100 / averageLPer100
                val v = if (ratio <= 1.15) Verdict.OK else Verdict.WATCH
                fuel += ReportLine("vs your average", String.format(Locale.US, "%.1f (%+.0f %%)", averageLPer100, (ratio - 1) * 100), v)
                if (v == Verdict.WATCH) finding(ReportLine("fuel use above your average", String.format(Locale.US, "%+.0f %%", (ratio - 1) * 100), v))
            } else {
                fuel += ReportLine("vs your average", "needs 3+ drives")
            }
            sections += ReportSection("FUEL", fuel)

            // ---------------------------------------------------- engine starts (cranking from standstill)
            val starts = mutableListOf<Int>()   // row where the engine caught (rpm >= 500 after < 100)
            var stopped = false
            for (i in 0 until n) {
                if (rpm[i].isNaN()) continue
                if (rpm[i] < 100) stopped = true
                else if (stopped && rpm[i] >= RUNNING_RPM) {
                    starts += i
                    stopped = false
                }
            }

            // ---------------------------------------------------- warm-up (the first start, if cold)
            val firstStart = starts.firstOrNull()
            val startCoolant = firstStart?.let { i -> (i downTo 0).map { coolant[it] }.firstOrNull { !it.isNaN() } }
            if (firstStart != null && startCoolant != null && startCoolant < COLD) {
                fun after(cond: (Int) -> Boolean): String {
                    val i = (firstStart until n).firstOrNull(cond) ?: return "not reached"
                    val s = (secs[i] - secs[firstStart]).toInt()
                    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
                }
                val cold = (firstStart until n).filter { running[it] && !coolant[it].isNaN() && coolant[it] < COLD }
                val coldMax = cold.maxOfOrNull { rpm[it] }
                val above = cold.filter { rpm[it] > COLD_RPM_LIMIT }.sumOf { dt[it] }
                val v = when {
                    above <= 0.0 -> Verdict.OK
                    above <= 10.0 -> Verdict.WATCH
                    else -> Verdict.CHECK
                }
                sections += ReportSection("WARM-UP (cold start)", listOf(
                    ReportLine("start coolant", String.format(Locale.US, "%.0f °C", startCoolant)),
                    ReportLine("coolant 70 °C after", after { !coolant[it].isNaN() && coolant[it] >= 70 }),
                    ReportLine("coolant 80 °C after", after { !coolant[it].isNaN() && coolant[it] >= 80 }),
                    ReportLine("oil 85 °C after", after { !oil[it].isNaN() && oil[it] >= 85 }),
                    ReportLine("max rpm while cold", coldMax?.let { String.format(Locale.US, "%.0f", it) } ?: "--", v),
                ))
                finding(when (v) {
                    Verdict.OK -> ReportLine("cold start driven gently", "")
                    else -> ReportLine("revved while cold", String.format(Locale.US, "above %.0f rpm for %.0f s", COLD_RPM_LIMIT, above), v)
                })
            }

            // ---------------------------------------------------- battery
            val volts = DoubleArray(n) { if (!port[it].isNaN()) port[it] else ecu[it] }
            val battery = mutableListOf<ReportLine>()
            if (firstStart != null) {
                val crankFrom = (firstStart downTo 0).firstOrNull { !rpm[it].isNaN() && rpm[it] < 100 } ?: firstStart
                val before = (0 until crankFrom).filter { secs[crankFrom] - secs[it] <= 5 && !volts[it].isNaN() }.map { volts[it] }
                if (before.size >= 3) {
                    val rest = before.sorted()[before.size / 2]
                    val pct = BatteryMonitor.chargePercent(rest)
                    val v = when {
                        pct >= 75 -> Verdict.OK
                        pct >= 25 -> Verdict.WATCH
                        else -> Verdict.CHECK
                    }
                    battery += ReportLine("resting", String.format(Locale.US, "%.1f V (~%.0f %%)", rest, pct), v)
                    if (v != Verdict.OK) finding(ReportLine("battery only ~${pct.toInt()} % charged", String.format(Locale.US, "(%.1f V at rest)", rest), v))
                }
            }
            for ((k, s) in starts.withIndex()) {
                val window = (0..s).filter { secs[s] - secs[it] <= 5 && !port[it].isNaN() }
                val dip = window.minOfOrNull { port[it] } ?: continue
                val dense = window.count { secs[s] - secs[it] <= 2 }
                val v = when {
                    dense < 5 -> Verdict.INFO
                    dip >= BatteryMonitor.CRANK_GOOD -> Verdict.OK
                    dip >= BatteryMonitor.CRANK_WEAK -> Verdict.WATCH
                    else -> Verdict.CHECK
                }
                val label = if (starts.size > 1) "start ${k + 1} dip" else "start dip"
                battery += ReportLine(label, String.format(Locale.US, "%.1f V (%d readings)", dip, dense), v)
                if (k == 0) finding(when (v) {
                    Verdict.OK -> ReportLine("start dip", String.format(Locale.US, "%.1f V (good)", dip), v)
                    Verdict.INFO -> ReportLine("", "", Verdict.INFO)
                    else -> ReportLine("weak start dip", String.format(Locale.US, "%.1f V", dip), v)
                })
            }
            // Charging. Smart alternator: the lowest 5-minute peak while running (after 5 minutes of running).
            val runIdx = (0 until n).filter { running[it] && !volts[it].isNaN() }
            if (runIdx.isNotEmpty()) {
                val firstRun = secs[runIdx.first()]
                if (car.smartAlternator) {
                    val window = ArrayDeque<Int>()   // indexes with decreasing voltage: front = window max
                    var lowestPeak = Double.NaN
                    for (i in runIdx) {
                        while (window.isNotEmpty() && volts[window.last()] <= volts[i]) window.removeLast()
                        window.addLast(i)
                        while (secs[i] - secs[window.first()] > 300) window.removeFirst()
                        if (secs[i] - firstRun >= 300) lowestPeak = if (lowestPeak.isNaN()) volts[window.first()] else minOf(lowestPeak, volts[window.first()])
                    }
                    if (!lowestPeak.isNaN()) {
                        val v = when {
                            lowestPeak >= BatteryMonitor.SMART_LOW_PEAK -> Verdict.OK
                            lowestPeak >= BatteryMonitor.SMART_NO_PEAK -> Verdict.WATCH
                            else -> Verdict.CHECK
                        }
                        battery += ReportLine("charging (5-min peak)", String.format(Locale.US, "≥ %.1f V", lowestPeak), v)
                        if (v != Verdict.OK) finding(ReportLine("alternator charging low", String.format(Locale.US, "peak only %.1f V", lowestPeak), v))
                    }
                } else {
                    val sorted = runIdx.map { volts[it] }.sorted()
                    val median = sorted[sorted.size / 2]
                    val v = when {
                        median >= BatteryMonitor.CHARGING_MIN -> Verdict.OK
                        median >= BatteryMonitor.NOT_CHARGING -> Verdict.WATCH
                        else -> Verdict.CHECK
                    }
                    battery += ReportLine("charging (typical)", String.format(Locale.US, "%.1f V", median), v)
                    if (v != Verdict.OK) finding(ReportLine("alternator charging low", String.format(Locale.US, "%.1f V", median), v))
                }
                // Below 12 V while running (30 s after a start, so the start itself doesn't count)
                var below = 0.0
                var longest = 0.0
                var stretch = 0.0
                var stretchStart = 0
                var longestStart = 0
                for (i in runIdx) {
                    val sinceStart = starts.filter { it <= i }.maxOrNull()?.let { secs[i] - secs[it] } ?: Double.MAX_VALUE
                    if (volts[i] < 12.0 && sinceStart > 30) {
                        if (stretch == 0.0) stretchStart = i
                        below += dt[i]
                        stretch += dt[i]
                        if (stretch > longest) {
                            longest = stretch
                            longestStart = stretchStart
                        }
                    } else {
                        stretch = 0.0
                    }
                }
                val v = when {
                    below < 30 -> Verdict.OK
                    below < 300 -> Verdict.WATCH
                    else -> Verdict.CHECK
                }
                val at = if (longest > 0) " (longest at ${rows[longestStart].time?.substring(11, 16)})" else ""
                battery += ReportLine("below 12 V running", String.format(Locale.US, "%.0f s%s", below, at), v)
                if (v != Verdict.OK) finding(ReportLine("below 12 V while driving", String.format(Locale.US, "%.0f s total%s", below, at), v))
                // Overcharge: above 15.0 V for 5 s (brief spikes while braking are normal)
                var over = 0.0
                var overMax = 0.0
                var overHigh = false
                for (i in runIdx) {
                    if (volts[i] > BatteryMonitor.OVERCHARGE_WARNING) {
                        over += dt[i]
                        if (over > overMax) overMax = over
                        if (volts[i] > BatteryMonitor.OVERCHARGE_CRITICAL && over >= BatteryMonitor.OVERCHARGE_AFTER) overHigh = true
                    } else {
                        over = 0.0
                    }
                }
                val high = runIdx.maxOf { volts[it] }
                val ov = when {
                    overHigh -> Verdict.CHECK
                    overMax >= BatteryMonitor.OVERCHARGE_AFTER -> Verdict.WATCH
                    else -> Verdict.OK
                }
                battery += ReportLine("highest", String.format(Locale.US, "%.1f V", high), ov)
                if (ov != Verdict.OK) finding(ReportLine("overcharging", String.format(Locale.US, "up to %.1f V", high), ov))
                battery += ReportLine("at end", String.format(Locale.US, "%.1f V", volts[runIdx.last()]))
            }
            if (battery.isNotEmpty()) sections += ReportSection("BATTERY", battery)

            // ---------------------------------------------------- engine
            val engine = mutableListOf<ReportLine>()
            val torque = col("Torque").maxOk()
            val power = col("Power").maxOk()
            if (torque != null || power != null) {
                engine += ReportLine("peak", String.format(Locale.US, "%s Nm · %s bhp",
                    torque?.let { String.format(Locale.US, "%.0f", it) } ?: "--", power?.let { String.format(Locale.US, "%.0f", it) } ?: "--"))
            }
            coolant.maxOk()?.let { c ->
                val v = when (Rules.coolant(c)) { Level.ALARM -> Verdict.CHECK; Level.WARN -> if (c > 100) Verdict.WATCH else Verdict.OK; Level.OK -> Verdict.OK }
                engine += ReportLine("coolant max", String.format(Locale.US, "%.0f °C", c), v)
                if (v != Verdict.OK) finding(ReportLine("engine got hot", String.format(Locale.US, "coolant %.0f °C", c), v))
            }
            oil.maxOk()?.let { c ->
                val v = when (Rules.oil(c)) { Level.ALARM -> Verdict.CHECK; Level.WARN -> if (c > 100) Verdict.WATCH else Verdict.OK; Level.OK -> Verdict.OK }
                engine += ReportLine("oil max", String.format(Locale.US, "%.0f °C", c), v)
                if (v != Verdict.OK) finding(ReportLine("oil got hot", String.format(Locale.US, "%.0f °C", c), v))
            }
            val intake = col("Intake air")
            intake.minOk()?.let { lo -> engine += ReportLine("intake air", String.format(Locale.US, "%.0f–%.0f °C", lo, intake.maxOk() ?: lo)) }
            val trim = col("Long fuel trim").filter { !it.isNaN() }.maxByOrNull { abs(it) }
            if (trim != null) {
                val v = when (Rules.fuelTrim(trim)) { Level.ALARM -> Verdict.CHECK; Level.WARN -> Verdict.WATCH; Level.OK -> Verdict.OK }
                engine += ReportLine("long fuel trim", String.format(Locale.US, "%+.1f %%", trim), v)
                if (v != Verdict.OK) finding(ReportLine("fuel mixture correction", String.format(Locale.US, "%+.1f %%", trim), v))
            }
            col("Catalyst").maxOk()?.let { c ->
                val v = if (c > 900) Verdict.CHECK else Verdict.OK
                engine += ReportLine("catalyst max", String.format(Locale.US, "%.0f °C", c), v)
                if (v != Verdict.OK) finding(ReportLine("catalytic converter very hot", String.format(Locale.US, "%.0f °C", c), v))
            }
            val codes = profile?.optString("trouble_codes")?.takeIf { it.isNotEmpty() }
            if (codes != null) {
                val v = if (codes.startsWith("none")) Verdict.OK else Verdict.CHECK
                engine += ReportLine("trouble codes", codes, v)
                if (v == Verdict.CHECK) finding(ReportLine("trouble codes stored", codes, v))
            }
            if (engine.isNotEmpty()) sections += ReportSection("ENGINE", engine)

            // ---------------------------------------------------- data
            val gaps = (1 until n).count { secs[it] - secs[it - 1] > GAP_S }
            sections += ReportSection("DATA", listOf(
                ReportLine("rows", String.format(Locale.US, "%d · %.1f/s", n, if (duration > 0) n / duration else 0.0)),
                ReportLine("gaps over 2 s", gaps.toString(), if (gaps == 0) Verdict.OK else Verdict.WATCH),
            ))

            val all = verdicts.sortedBy { if (it.verdict == Verdict.CHECK) 0 else 1 } + oks.filter { it.label.isNotEmpty() }
            return DriveReport(csv.name, t[0], duration / 60, km, litres, lPer100, all, sections)
        }

        private class Row(val time: String?, val values: Map<String, Double>)

        private val WANTED = setOf(
            "Speed", "RPM", "Coolant", "Oil", "Intake air", "Catalyst", "Long fuel trim", "Fuel rate",
            "Battery", "Battery (port)", "Torque", "Power",
        )

        /** The columns the report needs, row by row. */
        private fun read(csv: File): List<Row>? {
            val out = ArrayList<Row>()
            csv.bufferedReader().useLines { lines ->
                var index: Map<String, Int> = emptyMap()
                var timeCol = -1
                for ((k, line) in lines.withIndex()) {
                    if (line.isBlank()) continue
                    val cells = Csv.split(line)
                    if (k == 0) {
                        index = cells.withIndex().filter { it.value in WANTED }.associate { it.value to it.index }
                        timeCol = cells.indexOf("time")
                        if (timeCol < 0) return null
                        continue
                    }
                    val values = HashMap<String, Double>()
                    for ((name, i) in index) cells.getOrNull(i)?.toDoubleOrNull()?.let { values[name] = it }
                    out += Row(cells.getOrNull(timeCol), values)
                }
            }
            return out
        }
    }
}
