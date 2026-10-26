package io.github.arka01.cardash

import android.content.Context
import io.github.arka01.cardash.demo.DemoPlayer
import io.github.arka01.cardash.obd.ObdSession
import io.github.arka01.cardash.obd.SavedAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Mode { OFF, LIVE, DEMO, TEST }

/**
 * What the car is doing, worked out from the OBD port (KEY TEST of 2026-10-26):
 * ENGINE ON = RPM above 500; STARTING = RPM above 0 (cranking); IGNITION ON = RPM 0 and the
 * AMT computer (7E9) answers; IGNITION OFF = the AMT and the network are silent. The engine
 * computer keeps answering ~30 s after the key is turned off, so it can't be used for that.
 * LOCK and ACC look the same from the OBD port (both IGNITION OFF).
 */
enum class CarState(val label: String) {
    UNKNOWN("Connected"),
    IGNITION_OFF("IGNITION OFF"),
    IGNITION_ON("IGNITION ON"),
    STARTING("STARTING"),
    ENGINE_ON("ENGINE ON"),
}

/** One engine value: the number (for bars, warnings, gear) and the text to show. */
data class Reading(val pid: Int, val name: String, val number: Double?, val text: String, val time: Long)

/** The KEY TEST in the DEV tab: which file it writes, the key position tapped, the latest rows. */
data class TestInfo(val file: String, val label: String, val rows: Int = 0, val recent: List<String> = emptyList())

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
    val test: TestInfo? = null,
    val recording: String? = null,   // file name of the drive being recorded (LIVE)
    val carState: CarState = CarState.UNKNOWN,
    val recordedRows: Int = 0,
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

    /** Every LIVE run is recorded (files/drives/drive_<date>_<time>.csv + _profile.json). */
    fun startLive(context: Context, adapter: SavedAdapter) {
        stopSources()
        val gen = ++generation
        val rec = DriveRecorder(File(context.filesDir, DriveRecorder.DIR))
        recorder = rec
        store.value = Snapshot(mode = Mode.LIVE, status = "Connecting…", adapterName = adapter.name, recording = rec.csv.name)
        ObdSession.start(context, adapter, gen, recorder = rec)
    }

    private var recorder: DriveRecorder? = null

    fun startDemo(context: Context) {
        stopSources()
        val gen = ++generation
        store.value = Snapshot(mode = Mode.DEMO, status = "Starting demo…", adapterName = "recorded drive")
        DemoPlayer.start(context, gen)
    }

    /**
     * KEY TEST: records battery voltage, which car computers answer and network traffic about
     * once a second into files/dev/keytest_<date>_<time>.csv, labelled with the key position
     * tapped in the DEV tab. Used to work out how to tell LOCK / ACC / ON / START / RUN apart.
     */
    fun startKeyTest(context: Context, adapter: SavedAdapter) {
        stopSources()
        val gen = ++generation
        val dir = File(context.filesDir, DEV_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "keytest_$stamp.csv")
        store.value = Snapshot(
            mode = Mode.TEST, status = "Key test: connecting…", adapterName = adapter.name,
            test = TestInfo(file.name, KeyMarks.label),
        )
        ObdSession.start(context, adapter, gen, keyTest = file)
    }

    /** The key position the user just tapped (DEV tab); goes into the next KEY TEST row. */
    fun markKey(label: String) {
        KeyMarks.mark(label)
        synchronized(this) {
            val s = store.value
            store.value = s.copy(test = s.test?.copy(label = label))
        }
    }

    /** The newest drive recording (CSV, and its profile if saved), for sharing. */
    fun latestDrive(context: Context): List<File> {
        val csv = File(context.filesDir, DriveRecorder.DIR)
            .listFiles { f -> f.name.startsWith("drive_") && f.name.endsWith(".csv") }
            ?.maxByOrNull { it.name } ?: return emptyList()
        val profile = File(csv.parentFile, csv.name.removeSuffix(".csv") + "_profile.json")
        return listOfNotNull(csv, profile.takeIf { it.exists() })
    }

    /** The newest KEY TEST file, if any, for sharing. */
    fun latestKeyTest(context: Context): File? =
        File(context.filesDir, DEV_DIR).listFiles { f -> f.name.startsWith("keytest_") }?.maxByOrNull { it.name }

    const val DEV_DIR = "dev"

    fun stop() {
        stopSources()
        generation++
        store.value = store.value.copy(mode = Mode.OFF, status = "Stopped")
    }

    private fun stopSources() {
        recorder?.close()
        recorder = null
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

/** Key position labels from the DEV tab, read by the KEY TEST loop on another thread. */
object KeyMarks {
    val POSITIONS = listOf("LOCK", "ACC", "ON", "START", "RUN")

    @Volatile var label = "LOCK"
        private set
    @Volatile private var tapped: String? = null

    fun mark(position: String) {
        label = position
        tapped = position
    }

    /** The position tapped since the last row, if any (marks the exact moment of the change). */
    fun takeTap(): String? = tapped.also { tapped = null }
}
