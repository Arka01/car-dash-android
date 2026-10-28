package io.github.arka01.cardash.demo

import android.content.Context
import io.github.arka01.cardash.CarState
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.DriveRecorder
import io.github.arka01.cardash.Reading
import io.github.arka01.cardash.logic.Csv
import io.github.arka01.cardash.obd.Pids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Plays a recorded drive in real time, on loop, so the screens can be tested without the car:
 * - DEMO: a real Tiago drive (assets/demo_drive.csv, 8 minutes from 2026-10-24; columns are PIDs in hex)
 * - REPLAY (the Python --replay): a drive the app recorded (columns named like the Python recordings)
 * It feeds the same pipeline and snapshot as the live connection, with mode = DEMO / REPLAY.
 */
object DemoPlayer {
    private const val FILE = "demo_drive.csv"
    private const val ADAPTER_VOLTAGE = -1

    private class Row(val t: Double, val values: Map<Int, Double>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** [file] null: the demo drive; else a recording to replay. */
    fun start(context: Context, gen: Int, file: File?) {
        val appContext = context.applicationContext
        job?.cancel()
        job = scope.launch {
            val rows = try {
                if (file == null) loadDemo(appContext) else loadRecording(file)
            } catch (e: Exception) {
                Dash.finished(gen, "Can't play ${file?.name ?: FILE}: ${e.message}")
                return@launch
            }
            if (rows.isEmpty()) {
                Dash.finished(gen, "${file?.name ?: FILE} has no readings")
                return@launch
            }
            Dash.log(gen, "Playing ${rows.size} rows from ${file?.name ?: "$FILE (Tiago, 24 Oct 2026, 08:05-08:13)"}")
            val pids = rows.flatMap { it.values.keys }.filter { it >= 0 }.toSet()
            Dash.connected(gen, "recorded drive", pids, "none (recording)")
            Dash.update(gen) {
                it.copy(status = if (file == null) "Playing recorded drive" else "Replaying ${file.name}", fastRead = "recording")
            }
            var rounds = 0
            val readings = HashMap<Int, Reading>()
            var volts: Double? = null
            while (isActive) {
                var started = System.currentTimeMillis()
                var previous = System.currentTimeMillis()
                var rate = 0.0
                readings.clear()
                for (row in rows) {
                    var wait = started + (row.t * 1000).toLong() - System.currentTimeMillis()
                    if (wait < -1000) {
                        // Fell behind (phone asleep, e.g. during a call): carry on from here
                        // instead of rushing through the missed part.
                        started -= wait
                        wait = 0
                    }
                    if (wait > 0) delay(wait)
                    val now = System.currentTimeMillis()
                    val instant = 1000.0 / maxOf(now - previous, 1L)
                    rate = if (rate == 0.0) instant else rate * 0.9 + instant * 0.1
                    previous = now
                    rounds++
                    for ((pid, number) in row.values) {
                        if (pid == ADAPTER_VOLTAGE) volts = number
                        else readings[pid] = Reading(pid, Pids.name(pid), number, Pids.format(pid, number), now)
                    }
                    val r = rounds
                    val perSec = rate
                    Dash.publishRound(gen, readings.toMap(), volts, now, CarState.UNKNOWN) {
                        it.copy(rounds = r, roundsPerSec = perSec)
                    }
                }
                Dash.log(gen, "End of the drive, starting again")
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** The demo CSV: "t,0C,0D,…,RV" header (PIDs in hex, RV = adapter voltage), one row per moment. */
    private fun loadDemo(context: Context): List<Row> {
        val lines = context.assets.open(FILE).bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val columns = lines.first().split(",").map { if (it == "RV") ADAPTER_VOLTAGE else it.toIntOrNull(16) }
        return rows(lines.drop(1), columns, timeColumn = 0)
    }

    /** A recording: "t,time,Speed,RPM,…" (column names as the Python dashboard's). */
    private fun loadRecording(file: File): List<Row> {
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = Csv.split(lines.first())
        val columns = header.map { name ->
            when (val pid = DriveRecorder.pidOfColumn(name)) {
                DriveRecorder.PORT_COLUMN -> ADAPTER_VOLTAGE
                else -> pid
            }
        }
        return rows(lines.drop(1), columns, timeColumn = header.indexOf("t"))
    }

    /** Empty and text cells are skipped: the value shown keeps its last number. */
    private fun rows(lines: List<String>, columns: List<Int?>, timeColumn: Int): List<Row> = lines.mapNotNull { line ->
        val cells = Csv.split(line)
        val t = cells.getOrNull(timeColumn)?.toDoubleOrNull() ?: return@mapNotNull null
        val values = HashMap<Int, Double>()
        for (i in cells.indices) {
            if (i == timeColumn) continue
            val pid = columns.getOrNull(i) ?: continue
            cells[i].toDoubleOrNull()?.let { values.putIfAbsent(pid, it) }
        }
        Row(t, values)
    }
}
