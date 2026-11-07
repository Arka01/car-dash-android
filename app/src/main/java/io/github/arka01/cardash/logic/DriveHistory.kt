package io.github.arka01.cardash.logic

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The car's recent history for the AI (Talk, 2026-11-07: "how was yesterday's drive?"), worked out
 * in code: the newest drives in one line each, the last 7 days' totals and the last engine starts'
 * dips. From the reports already saved per drive and the car's battery_state.json, so nothing new
 * is stored.
 */
object DriveHistory {
    private const val DRIVES = 5
    private const val STARTS = 5
    private const val WEEK_MS = 7 * 24 * 3600 * 1000L

    /** One engine start from battery_state.json: when (epoch ms), its dip and how many readings saw it. */
    class Start(val at: Long, val volts: Double, val samples: Int)

    /**
     * EARLIER DRIVES: [reports] (any order; the drive going on now left out by the caller), the totals
     * of those in the 7 days before [now], and [starts] (the newest engine starts).
     */
    fun text(reports: List<DriveReport>, starts: List<Start>, now: Long): String = buildString {
        val newest = reports.sortedByDescending { it.start }
        append("EARLIER DRIVES (newest first; findings are the WATCH / CHECK of that drive)\n")
        if (newest.isEmpty()) append("  none saved yet\n")
        newest.take(DRIVES).forEach { r ->
            val issues = r.verdicts.filter { it.verdict == Verdict.WATCH || it.verdict == Verdict.CHECK }
                .joinToString("; ") { "${it.verdict} ${it.label}${if (it.value.isNotEmpty()) " ${it.value}" else ""}" }
                .ifEmpty { "all OK" }
            append(String.format(Locale.US, "  %s: %.0f min, %.1f km, %s, %s; %s\n", TimeFormat.forAi(r.start), r.minutes, r.km,
                r.driveType, r.lPer100?.let { String.format(Locale.US, "%.1f L/100 km", it) } ?: "fuel --", issues))
        }
        val week = newest.filter { now - it.start in 0..WEEK_MS }
        if (week.isNotEmpty()) {
            val litres = week.mapNotNull { it.litres }.sum()
            append(String.format(Locale.US, "Last 7 days: %d drive%s, %.0f min, %.1f km, %.1f L of fuel\n",
                week.size, if (week.size == 1) "" else "s", week.sumOf { it.minutes }, week.sumOf { it.km }, litres))
        }
        if (starts.isNotEmpty()) {
            append("LAST ENGINE STARTS (battery start dip, newest first)\n")
            starts.sortedByDescending { it.at }.take(STARTS).forEach { s ->
                append(String.format(Locale.US, "  %s: %.1f V (%s)\n", TimeFormat.forAi(s.at), s.volts, crankWord(s)))
            }
        }
    }

    /** The app's verdict on a start dip, with the same limits as the drive report and the Dash. */
    fun crankWord(s: Start): String = when {
        s.samples < BatteryMonitor.CRANK_MIN_SAMPLES -> "too few readings to judge"
        s.volts >= BatteryMonitor.CRANK_GOOD -> "OK"
        s.volts >= BatteryMonitor.CRANK_WEAK -> "WATCH"
        else -> "CHECK"
    }

    /** The engine starts saved in a car's battery_state.json (oldest first); empty if none. */
    fun starts(batteryState: File?): List<Start> {
        val file = batteryState?.takeIf { it.exists() } ?: return emptyList()
        return runCatching { readStarts(file) }.getOrDefault(emptyList())
    }

    private fun readStarts(file: File): List<Start> {
        val cranks = JSONObject(file.readText()).optJSONArray("cranks") ?: return emptyList()
        val parse = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        return (0 until cranks.length()).mapNotNull { i ->
            val c = cranks.getJSONObject(i)
            val at = runCatching { parse.parse(c.getString("when"))?.time }.getOrNull() ?: return@mapNotNull null
            Start(at, c.getDouble("min_volts"), c.optInt("samples"))
        }
    }
}
