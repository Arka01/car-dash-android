package io.github.arka01.cardash.logic

import java.util.Locale
import kotlin.math.abs

/**
 * Several drives for one AI question (Drives → Ask AI, 2026-11-04, up to [MAX_DRIVES]). The totals
 * and trends are calculated here, in code, because the small on-phone model calculates badly: it
 * only explains them. Drives are tagged D1 (oldest) … Dn, as on the Ask screen.
 */
object DriveTotals {
    const val MAX_DRIVES = 5

    /** Oldest first: the order of the D1 … Dn tags. */
    fun ordered(reports: List<DriveReport>) = reports.sortedBy { it.start }

    fun tag(i: Int) = "D${i + 1}"

    /** TOTALS AND TRENDS: totals, fuel, battery, warm-up, highest values and how often each finding came up. */
    fun totals(reports: List<DriveReport>): String = buildString {
        val rs = ordered(reports)
        if (rs.isEmpty()) return@buildString
        append("${rs.size} drive${if (rs.size == 1) "" else "s"}, ${TimeFormat.forAi(rs.first().start)} to ${TimeFormat.forAi(rs.last().start)}\n")

        val withFuel = rs.filter { it.litres != null }
        val litres = withFuel.sumOf { it.litres!! }
        val fuelKm = withFuel.sumOf { it.km }
        append(String.format(Locale.US, "Total: %.0f min, %.1f km", rs.sumOf { it.minutes }, rs.sumOf { it.km }))
        if (withFuel.isNotEmpty()) append(String.format(Locale.US, ", %.2f L of fuel", litres))
        if (fuelKm >= 1) append(String.format(Locale.US, ", overall %.1f L/100 km", litres / fuelKm * 100))
        append('\n')

        // Fuel use per drive: only drives of 2 km or more (shorter ones say little).
        val fuel = rs.withIndex().filter { it.value.km >= 2 && it.value.lPer100 != null }
        if (fuel.size >= 2) {
            val best = fuel.minBy { it.value.lPer100!! }
            val worst = fuel.maxBy { it.value.lPer100!! }
            append(String.format(Locale.US, "Fuel use: best %s %.1f L/100 km (%s), worst %s %.1f L/100 km (%s)\n",
                tag(best.index), best.value.lPer100, best.value.driveType, tag(worst.index), worst.value.lPer100, worst.value.driveType))
        }
        trend(fuel.map { it.value.lPer100!! }, 0.10, relative = true, higherIsBetter = false)?.let { (word, earlier, later) ->
            val kinds = fuel.groupingBy { it.value.driveType.substringBefore(",") }.eachCount()
            val kindText = if (kinds.size == 1) "all ${fuel.size} were ${kinds.keys.first()} drives"
                else kinds.entries.joinToString(", ") { "${it.value} ${it.key}" }
            append(String.format(Locale.US, "Fuel use trend: %s, %.1f → %.1f L/100 km (earlier vs later drives); %s",
                word, earlier, later, kindText))
            // Few or short drives: cold starts and traffic weigh more than any real change.
            if (fuel.size < 4 || fuel.sumOf { it.value.km } < 30) append("; few short drives, so only a rough sign")
            append('\n')
        }

        series(rs, "BATTERY", RESTING)?.let { (text, values) ->
            append("Battery resting voltage: $text")
            trend(values, 0.15, relative = false, higherIsBetter = true)?.let { append("; trend: ${it.first}") }
            append('\n')
        }
        series(rs, "BATTERY", START_DIP)?.let { (text, values) ->
            append("Battery start dip (lowest of the drive): $text")
            trend(values, 0.3, relative = false, higherIsBetter = true)?.let { append("; trend: ${it.first}") }
            append('\n')
        }

        val warm = rs.mapNotNull { r -> line(r, "WARM-UP", "coolant 70")?.value?.let { seconds(it) } }
        if (warm.isNotEmpty()) {
            val avg = warm.average().toInt()
            append(String.format(Locale.US, "Warm-up to 70 °C: average %d:%02d over %d cold start%s\n",
                avg / 60, avg % 60, warm.size, if (warm.size == 1) "" else "s"))
        }

        highest(rs, "DRIVE", "top speed", "km/h")?.let { append("Highest speed: $it\n") }
        highest(rs, "DRIVE", "max rpm", "rpm")?.let { append("Highest engine speed: $it\n") }
        highest(rs, "ENGINE", "coolant max", "°C")?.let { append("Hottest coolant: $it\n") }

        // Each WATCH / CHECK finding and on which drives it came up (once per drive, the worse verdict;
        // "start 1 dip" and "start 2 dip" of a drive with two engine starts count as "start dip").
        val findings = LinkedHashMap<String, LinkedHashMap<String, Verdict>>()
        rs.forEachIndexed { i, r ->
            for (s in r.sections) for (l in s.lines) {
                if (l.verdict != Verdict.WATCH && l.verdict != Verdict.CHECK) continue
                val name = s.title.substringBefore(" (").lowercase() + " " + l.label.replace(Regex("^start \\d+ dip"), "start dip")
                val drives = findings.getOrPut(name) { LinkedHashMap() }
                if (drives[tag(i)] != Verdict.CHECK) drives[tag(i)] = l.verdict
            }
        }
        if (findings.isEmpty()) {
            append("Findings: none, every drive OK\n")
        } else {
            append("Findings (WATCH / CHECK) and on how many drives:\n")
            for ((name, on) in findings) {
                append("  $name: ${on.size} of ${rs.size} (${on.entries.joinToString(", ") { "${it.key} ${it.value}" }})\n")
            }
        }
    }

    /**
     * THE DRIVES: each drive's report with its tag (the DATA part left out). [compact]: one line
     * each instead, if the full reports would be too long for the model.
     */
    fun drives(reports: List<DriveReport>, compact: Boolean = false): String = buildString {
        val rs = ordered(reports)
        rs.forEachIndexed { i, r ->
            val mark = when {
                rs.size < 2 -> ""
                i == 0 -> "oldest"
                i == rs.lastIndex -> "newest"
                else -> ""
            }
            if (compact) {
                append(String.format(Locale.US, "%s%s %s | %.0f min, %.1f km, %s | %s | findings: %s\n",
                    tag(i), if (mark.isEmpty()) "" else " ($mark)", TimeFormat.forAi(r.start), r.minutes, r.km, r.driveType,
                    r.lPer100?.let { String.format(Locale.US, "%.1f L/100 km", it) } ?: "fuel --",
                    r.verdicts.filter { it.verdict == Verdict.WATCH || it.verdict == Verdict.CHECK }
                        .joinToString("; ") { "${it.verdict} ${it.label}" }.ifEmpty { "none" }))
            } else {
                append(r.text(tag(i), mark, withData = false).trimEnd()).append("\n\n")
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun line(r: DriveReport, section: String, label: String): ReportLine? =
        r.sections.firstOrNull { it.title.startsWith(section) }?.lines?.firstOrNull { it.label.startsWith(label) }

    private val RESTING = Regex("^resting")
    private val START_DIP = Regex("^start( \\d+)? dip")   // "start dip", or "start 1 dip", "start 2 dip" with several starts

    private fun number(text: String): Double? = Regex("-?\\d+(\\.\\d+)?").find(text)?.value?.toDoubleOrNull()

    /** "4:31" -> 271 */
    private fun seconds(text: String): Int? = Regex("(\\d+):(\\d{2})").find(text)?.let {
        it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
    }

    /**
     * "12.3 V (D1) → 12.0 V (D3)" and the values, oldest first; null if no drive has it. A drive with
     * several matching lines (several engine starts) gives its lowest value.
     */
    private fun series(rs: List<DriveReport>, section: String, label: Regex): Pair<String, List<Double>>? {
        val points = rs.withIndex().mapNotNull { (i, r) ->
            r.sections.firstOrNull { it.title.startsWith(section) }?.lines
                ?.filter { label.containsMatchIn(it.label) }?.mapNotNull { number(it.value) }?.minOrNull()?.let { i to it }
        }
        if (points.isEmpty()) return null
        return points.joinToString(" → ") { (i, v) -> String.format(Locale.US, "%.1f V (%s)", v, tag(i)) } to points.map { it.second }
    }

    /** "51 km/h (D3)" */
    private fun highest(rs: List<DriveReport>, section: String, label: String, unit: String): String? =
        rs.withIndex().mapNotNull { (i, r) -> line(r, section, label)?.value?.let(::number)?.let { i to it } }
            .maxByOrNull { it.second }?.let { (i, v) -> String.format(Locale.US, "%.0f %s (%s)", v, unit, tag(i)) }

    /**
     * Earlier half against later half: "getting better", "getting worse" or "steady" (decided by
     * [threshold], a fraction if [relative], else in the values' unit) with the two averages.
     * Null with fewer than 3 values.
     */
    private fun trend(values: List<Double>, threshold: Double, relative: Boolean, higherIsBetter: Boolean): Triple<String, Double, Double>? {
        if (values.size < 3) return null
        val half = values.size / 2
        val earlier = values.take(half).average()
        val later = values.takeLast(half).average()
        val change = if (relative) (later - earlier) / earlier else later - earlier
        val word = when {
            abs(change) < threshold -> "steady"
            (change > 0) == higherIsBetter -> "getting better"
            else -> "getting worse"
        }
        return Triple(word, earlier, later)
    }
}
