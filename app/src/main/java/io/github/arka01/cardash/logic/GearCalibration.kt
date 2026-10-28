package io.github.arka01.cardash.logic

import io.github.arka01.cardash.cars.CarProfile
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

/** Result of a gear calibration: what to show, and the values to save (null: nothing to save). */
class GearResult(val lines: List<String>, val suggested: List<Double>?)

/**
 * Measures RPM per km/h for each gear from the car's recorded drives (the Python --calibrate-gears).
 * While in gear, RPM / speed is fixed by the gearbox, so the readings cluster around one value per
 * gear. Shifting, clutch slip and idling fall between the clusters and are filtered out.
 */
object GearCalibration {
    fun run(car: CarProfile): GearResult {
        val folder = car.recordings ?: return GearResult(listOf("This car saves no recordings."), null)
        val ratios = mutableListOf<Double>()
        val files = folder.listFiles { f -> f.name.startsWith("drive_") && f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }
        for (file in files) ratios += steadyRatios(file)
        if (ratios.isEmpty()) return GearResult(listOf("No usable readings in ${files.size} recorded drives (record a drive first)."), null)

        // Peaks of a histogram on a log scale (gears are spread evenly in log), 2 % wide bins.
        val bins = HashMap<Long, Int>()
        for (r in ratios) bins.merge(round(ln(r) / 0.02).toLong(), 1) { a, b -> a + b }
        val floor = maxOf(15.0, 0.01 * ratios.size)
        val peaks = bins.filter { (b, n) -> n >= floor && listOf(-2L, -1L, 1L, 2L).all { n >= (bins[b + it] ?: 0) } }
            .keys.map { exp(it * 0.02) }
        val measured = peaks.map { p -> median(ratios.filter { abs(ln(it / p)) < 0.08 }) }.sortedDescending()

        val lines = mutableListOf("${ratios.size} in-gear readings from ${files.size} drives")
        if (measured.isEmpty()) return GearResult(lines + "No gear found clearly enough (drive longer in each gear).", null)
        val theory = car.theoreticalRpmPerKmh
        val suggested: List<Double>
        if (theory != null) {
            // Match each measured cluster to the theoretical gear it's closest to; fill unseen gears from theory.
            val byGear = HashMap<Int, Double>()
            for (value in measured) {
                val gear = theory.indices.minBy { abs(ln(value / theory[it])) }
                byGear[gear] = round1(value)
            }
            val scale = median(byGear.map { (g, v) -> v / theory[g] })
            suggested = theory.indices.map { g -> byGear[g] ?: round1(theory[g] * scale) }
            for (g in theory.indices) {
                val source = if (g in byGear) "measured" else String.format(Locale.US, "not driven: theory x %.3f", scale)
                lines += String.format(Locale.US, "gear %d: %6.1f  (%s)", g + 1, suggested[g], source)
            }
        } else {
            // No gear ratios known: number the clusters in order. Only right if the drives used
            // every gear from 1st up; a skipped gear shifts the numbers.
            suggested = measured.map { round1(it) }
            suggested.forEachIndexed { g, v -> lines += String.format(Locale.US, "gear %d: %6.1f  (measured)", g + 1, v) }
            lines += "(numbered in order: check the drives used every gear from 1st, none skipped)"
        }
        if (suggested.isEmpty()) return GearResult(lines + "No gear found clearly enough.", null)
        return GearResult(lines, suggested)
    }

    /** RPM / speed from readings that are steady (in gear, not shifting) and fast enough. */
    private fun steadyRatios(file: File): List<Double> {
        val out = mutableListOf<Double>()
        file.bufferedReader().useLines { lines ->
            var speedCol = -1
            var rpmCol = -1
            var previous: Pair<Double, Double>? = null
            for ((i, line) in lines.withIndex()) {
                val cells = Csv.split(line)
                if (i == 0) {
                    speedCol = cells.indexOf("Speed")
                    rpmCol = cells.indexOf("RPM")
                    if (speedCol < 0 || rpmCol < 0) return out
                    continue
                }
                val speed = cells.getOrNull(speedCol)?.toDoubleOrNull()
                val rpm = cells.getOrNull(rpmCol)?.toDoubleOrNull()
                if (speed == null || rpm == null) {
                    previous = null
                    continue
                }
                val p = previous
                val steady = p != null && abs(p.first - speed) <= 2 && abs(p.second - rpm) < 250
                if (speed >= 10 && rpm > 900 && steady) out += rpm / speed
                previous = speed to rpm
            }
        }
        return out
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    private fun round1(x: Double) = round(x * 10) / 10
}

/** Splits one CSV line, keeping quoted cells (which may contain commas) together. */
object Csv {
    fun split(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { cells += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        cells += cell.toString()
        return cells
    }
}
