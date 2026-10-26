package io.github.arka01.cardash.demo

import android.content.Context
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.Reading
import io.github.arka01.cardash.obd.Pids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Demo mode: replays a real Tiago drive (assets/demo_drive.csv, 8 minutes from 2026-10-24)
 * in real time, on loop, so the screens can be tested without the car.
 * It fills the same snapshot as the live connection, with mode = DEMO.
 */
object DemoPlayer {
    private const val FILE = "demo_drive.csv"
    private const val ADAPTER_VOLTAGE = "RV"

    private class Row(val t: Double, val values: Map<String, Double>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(context: Context, gen: Int) {
        val appContext = context.applicationContext
        job?.cancel()
        job = scope.launch {
            val rows = load(appContext)
            Dash.log(gen, "Demo: ${rows.size} rows from $FILE (Tiago, 24 Oct 2026, 08:05-08:13)")
            Dash.update(gen) { it.copy(status = "Playing recorded drive", fastRead = "demo") }
            var rounds = 0
            while (isActive) {
                var started = System.currentTimeMillis()
                var previous = System.currentTimeMillis()
                var rate = 0.0
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
                    val r = rounds
                    val perSec = rate
                    Dash.update(gen) { snap ->
                        val readings = snap.readings.toMutableMap()
                        for ((column, number) in row.values) {
                            if (column == ADAPTER_VOLTAGE) continue
                            val pid = column.toInt(16)
                            readings[pid] = Reading(pid, Pids.name(pid), number, Pids.format(pid, number), now)
                        }
                        snap.copy(
                            readings = readings,
                            adapterVoltage = row.values[ADAPTER_VOLTAGE] ?: snap.adapterVoltage,
                            rounds = r,
                            roundsPerSec = perSec,
                        )
                    }
                }
                Dash.log(gen, "Demo: end of drive, starting again")
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Reads the CSV: "t,0C,0D,…" header, then one row per moment; empty cells are skipped. */
    private fun load(context: Context): List<Row> {
        val lines = context.assets.open(FILE).bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val columns = lines.first().split(",")
        return lines.drop(1).map { line ->
            val cells = line.split(",")
            val values = HashMap<String, Double>()
            for (i in 1 until minOf(cells.size, columns.size)) {
                cells[i].toDoubleOrNull()?.let { values[columns[i]] = it }
            }
            Row(cells[0].toDouble(), values)
        }
    }
}
