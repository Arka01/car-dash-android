package io.github.arka01.cardash

import io.github.arka01.cardash.cars.CarProfile
import io.github.arka01.cardash.logic.Csv
import io.github.arka01.cardash.logic.DriveBuffer
import io.github.arka01.cardash.logic.DriveReport
import io.github.arka01.cardash.logic.Pipeline
import io.github.arka01.cardash.logic.Values
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Replay test (plan: docs/PLAN_vehicle_methods.md, rule 1 "nothing old breaks").
 *
 * Plays short, anonymised pieces of real drives (src/test/resources/replay/<name>.csv.gz, made with
 * tools/make_replay_snippet.py) through the same steps as the app's REPLAY: readings carried
 * forward row by row -> Values -> Pipeline -> DriveBuffer -> DriveReport. What comes out (every 10th
 * row's calculated values, every alert / warm-up sentence change, and the drive report) must match
 * replay/expected/<name>.txt exactly.
 *
 * No expected file yet ("record mode"): the output is only written to build/replay-out/ and the
 * test passes; CI attaches that folder to the GitHub release so it can be committed as expected.
 */
class ReplayTest {
    private val resources = File("src/test/resources/replay")
    private val out = File("build/replay-out")

    @Before
    fun fixedClock() {
        // The car's time zone and number format, so times in the report read like on the phone.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        Locale.setDefault(Locale.US)
    }

    @Test
    fun tiagoDrivesGiveTheSameResults() {
        val car = tiago()
        val drives = resources.listFiles { f -> f.name.endsWith(".csv.gz") }.orEmpty().sortedBy { it.name }
        if (drives.isEmpty()) fail("no replay drives in $resources")
        out.mkdirs()
        val problems = mutableListOf<String>()
        for (drive in drives) {
            val name = drive.name.removeSuffix(".csv.gz")
            val actual = replay(drive, car)
            File(out, "$name.txt").writeText(actual)
            val expectedFile = File(resources, "expected/$name.txt")
            if (!expectedFile.exists()) {
                println("REPLAY $name: no expected file yet (record mode), output in ${File(out, "$name.txt")}")
                continue
            }
            val diff = firstDifferences(expectedFile.readText(), actual)
            if (diff.isNotEmpty()) problems += "$name:\n" + diff.joinToString("\n")
        }
        if (problems.isNotEmpty()) fail("Replay results changed:\n" + problems.joinToString("\n\n"))
    }

    private fun tiago(): CarProfile {
        val json = JSONObject(File("src/main/assets/cars/tata_tiago_2023/car.json").readText())
        return CarProfile.fromJson("tata_tiago_2023", json, folder = null)   // folder null: nothing is saved
    }

    /** One drive through the app's steps; the lines to compare. */
    private fun replay(file: File, car: CarProfile): String {
        val lines = GZIPInputStream(file.inputStream()).bufferedReader().readLines().filter { it.isNotBlank() }
        val header = Csv.split(lines.first())
        val timeColumn = header.indexOf("time")
        val columns = header.map { if (it == "t" || it == "time") null else DriveRecorder.pidOfColumn(it) }
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        class Row(val ms: Long, val values: Map<Int, Double>)
        val rows = lines.drop(1).mapNotNull { line ->
            val cells = Csv.split(line)
            val ms = runCatching { clock.parse(cells[timeColumn])!!.time }.getOrNull() ?: return@mapNotNull null
            val values = HashMap<Int, Double>()
            for (i in cells.indices) {
                val pid = columns.getOrNull(i) ?: continue
                cells[i].toDoubleOrNull()?.let { values.putIfAbsent(pid, it) }
            }
            Row(ms, values)
        }
        // As DemoPlayer: every PID with a number somewhere counts as supported.
        val supported = rows.flatMap { it.values.keys }.filter { it >= 0 }.toSet()

        val pipeline = Pipeline(car, live = false, now = rows.first().ms / 1000.0)
        val buffer = DriveBuffer()
        val readings = HashMap<Int, Reading>()
        var volts: Double? = null
        var lastEvents = ""
        val text = StringBuilder()
        rows.forEachIndexed { i, row ->
            for ((pid, number) in row.values) {
                if (pid == DriveRecorder.PORT_COLUMN) volts = number
                else readings[pid] = Reading(pid, "", number, "", row.ms)
            }
            val input = Values.from(readings, supported, volts, row.ms)
            input.readAt.clear()   // recordings: a changed value counts as a new reading (as REPLAY)
            val (derived, sentence) = pipeline.process(input, row.ms / 1000.0)
            buffer.add(row.ms) { name -> if (name in Values.PIDS || name == Values.PORT) input[name] else derived.values[name] }

            val events = buildString {
                append(derived.alerts.joinToString(" | ") { "${it.text}:${it.level}" })
                derived.battery?.let { append(" ; battery ${it.state}:${it.level}") }
                derived.engine?.let { append(" ; engine ${it.state}:${it.level}") }
            }
            val t = "%.2f".format((row.ms - rows.first().ms) / 1000.0)
            if (events != lastEvents) text.append("$t EVENTS $events\n").also { lastEvents = events }
            if (sentence != null) text.append("$t SAY $sentence\n")
            if (i % 10 == 0) {
                val v = derived.values
                text.append(t).append(" VALUES ")
                text.append(v.labels.sorted().joinToString(" ") { "$it=${round(v[it])}" })
                derived.fuel?.let { text.append(" ; fuel=${round(it.litres)}") }
                text.append('\n')
            }
        }
        val report = DriveReport.calculate(buffer.snapshot(), file.name.removeSuffix(".gz"), car, "none", null)
        text.append("REPORT\n").append(report?.toJson()?.toString(1) ?: "none").append('\n')
        return text.toString()
    }

    private fun round(x: Double?) = x?.let { "%.4f".format(it) } ?: "-"

    /** Up to 15 lines that differ, with their line numbers. */
    private fun firstDifferences(expected: String, actual: String): List<String> {
        val e = expected.lines()
        val a = actual.lines()
        val diffs = mutableListOf<String>()
        for (i in 0 until maxOf(e.size, a.size)) {
            if (e.getOrNull(i) != a.getOrNull(i)) {
                diffs += "line ${i + 1}\n  expected: ${e.getOrNull(i)?.take(300)}\n  actual:   ${a.getOrNull(i)?.take(300)}"
                if (diffs.size == 15) break
            }
        }
        return diffs
    }
}
