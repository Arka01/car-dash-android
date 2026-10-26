package io.github.arka01.cardash

import android.content.Context
import io.github.arka01.cardash.demo.DemoPlayer
import io.github.arka01.cardash.obd.ObdSession
import io.github.arka01.cardash.obd.SavedAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Mode { OFF, LIVE, DEMO }

/** One engine value: the number (for bars, warnings, gear) and the text to show. */
data class Reading(val pid: Int, val name: String, val number: Double?, val text: String, val time: Long)

/** Everything the screens show; replaced as a whole on every change. */
data class Snapshot(
    val mode: Mode = Mode.OFF,
    val status: String = "Not connected",
    val adapterName: String? = null,
    val adapterVoltage: Double? = null,
    val readings: Map<Int, Reading> = emptyMap(),   // engine computer (7E8), by PID
    val rounds: Int = 0,
    val roundsPerSec: Double = 0.0,                 // how often RPM and speed refresh
    val fastRead: String = "",
    val log: List<String> = emptyList(),
) {
    fun number(pid: Int): Double? = readings[pid]?.number
}

/**
 * The hub between data and screens. Two sources can fill it: the live OBD connection
 * or the demo (a recorded drive). The phone and the car screen only read [state],
 * so they can't tell the difference, except through [Snapshot.mode].
 */
object Dash {
    private val store = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = store

    // Each start gets a new number; a source that was stopped can't overwrite the next one.
    @Volatile private var generation = 0

    val isRunning: Boolean get() = ObdSession.isRunning || DemoPlayer.isRunning

    fun startLive(context: Context, adapter: SavedAdapter) {
        stopSources()
        val gen = ++generation
        store.value = Snapshot(mode = Mode.LIVE, status = "Connecting…", adapterName = adapter.name)
        ObdSession.start(context, adapter, gen)
    }

    fun startDemo(context: Context) {
        stopSources()
        val gen = ++generation
        store.value = Snapshot(mode = Mode.DEMO, status = "Starting demo…", adapterName = "recorded drive")
        DemoPlayer.start(context, gen)
    }

    fun stop() {
        stopSources()
        generation++
        store.value = store.value.copy(mode = Mode.OFF, status = "Stopped")
    }

    private fun stopSources() {
        ObdSession.stop()
        DemoPlayer.stop()
    }

    /** Changes the snapshot, unless the source asking has been stopped meanwhile. */
    fun update(gen: Int, change: (Snapshot) -> Snapshot) {
        synchronized(this) {
            if (gen == generation) store.value = change(store.value)
        }
    }

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(gen: Int, line: String) = update(gen) {
        it.copy(log = (it.log + "${clock.format(Date())} $line").takeLast(60))
    }
}
