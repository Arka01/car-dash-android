package io.github.arka01.cardash

import android.content.Context
import io.github.arka01.cardash.ai.DriveSummary
import io.github.arka01.cardash.ai.LocalLlm
import io.github.arka01.cardash.cars.CarProfile
import io.github.arka01.cardash.cars.Cars
import io.github.arka01.cardash.demo.DemoPlayer
import io.github.arka01.cardash.logic.Derived
import io.github.arka01.cardash.logic.Pipeline
import io.github.arka01.cardash.logic.Values
import io.github.arka01.cardash.obd.ObdSession
import io.github.arka01.cardash.obd.SavedAdapter
import io.github.arka01.cardash.obd.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Mode { OFF, LIVE, DEMO, REPLAY, TEST, PROBE }

/**
 * What the car is doing, worked out from the OBD port (KEY TEST of 2026-10-26):
 * ENGINE ON = RPM above 500; STARTING = RPM above 0 coming from standstill (cranking); STOPPING =
 * RPM below 500 after the engine ran (switched off, spinning down); IGNITION ON = RPM 0 and the
 * AMT computer (7E9) answers; IGNITION OFF = the AMT and the network are silent. The engine
 * computer keeps answering ~30 s after the key is turned off, so it can't be used for that.
 * LOCK and ACC look the same from the OBD port (both IGNITION OFF).
 */
enum class CarState(val label: String) {
    UNKNOWN("Connected"),
    IGNITION_OFF("IGNITION OFF"),
    IGNITION_ON("IGNITION ON"),
    STARTING("STARTING"),
    STOPPING("STOPPING"),
    ENGINE_ON("ENGINE ON"),
}

/** One engine value: the number (for bars, warnings, gear) and the text to show. */
data class Reading(val pid: Int, val name: String, val number: Double?, val text: String, val time: Long)

/** The KEY TEST in the DEV tab: which file it writes, the key position tapped, the latest rows. */
data class TestInfo(val file: String, val label: String, val rows: Int = 0, val recent: List<String> = emptyList())

/**
 * What a PROBE / NEW CAR scan found. For a new car whose VIN matches no car set up ([knownCar]
 * null), the phone asks a few questions and then calls [Dash.createCar].
 */
data class ProbeResult(
    val newCar: Boolean,
    val report: JSONObject,
    val vin: String?,
    val maker: String?,
    val fuel: String?,
    val protocol: String,
    val knownCar: String?,
)

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
    val car: CarProfile? = null,     // the car whose settings are in use
    val supported: Set<Int>? = null, // the engine computer's PIDs (null: not known yet)
    val derived: Derived? = null,    // calculated values (logic/Pipeline)
    val vin: String? = null,
    val troubleCodes: String? = null,
    val protocol: String? = null,
    val probe: ProbeResult? = null,  // a finished NEW CAR scan waiting for the phone's questions
) {
    fun number(pid: Int): Double? = readings[pid]?.number
}

/**
 * The hub between data and screens. Three sources can fill it: the live OBD connection, the
 * demo (a recorded Tiago drive) or a replay of a drive recorded by the app. Each run has a
 * [Run]: its car, the calculations ([Pipeline]) and, when LIVE, the recording. The phone and
 * the car screen only read [state].
 */
object Dash {
    private val store = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = store

    // Each start gets a new number; a source that was stopped can't overwrite the next one.
    @Volatile private var generation = 0

    /** One run's car, calculations and recording. Recording starts once the car is confirmed (VIN). */
    class Run(val context: Context, val car: CarProfile, val live: Boolean) {
        val pipeline = Pipeline(car, live, System.currentTimeMillis() / 1000.0)
        val recorder: DriveRecorder? = if (live) car.recordings?.let { DriveRecorder(it, car.key) } else null
        @Volatile var carConfirmed = !live
        var engineRan = false   // since the last engine-off summary
        var offSince = 0L       // when the ignition went off (0 = it isn't off)

        fun close() {
            recorder?.close()
            pipeline.close()
            // The drive report (DRIVES screen), worked out in the background.
            recorder?.takeIf { it.rows > 0 }?.let { DriveReports.driveEnded(context, car, it.csv) }
        }
    }

    @Volatile var run: Run? = null
        private set

    val isRunning: Boolean get() = ObdSession.isRunning || DemoPlayer.isRunning

    private fun begin(context: Context, mode: Mode, car: CarProfile?, live: Boolean, status: String, adapter: String?): Int {
        stopSources()
        val gen = ++generation
        run = car?.let { Run(context.applicationContext, it, live) }
        if (live && car != null) takePendingRefuel(context, car)
        store.value = Snapshot(
            mode = mode, status = status, adapterName = adapter, car = car,
            recording = run?.recorder?.csv?.name, log = store.value.log.takeLast(20),
        )
        return gen
    }

    /** Every LIVE run is recorded (files/cars/<car>/recordings/drive_<date>_<time>.csv + _profile.json). */
    fun startLive(context: Context, adapter: SavedAdapter) {
        LocalLlm.release()   // the on-phone AI's memory and processor go to the recording
        Cars.init(context)
        val gen = begin(context, Mode.LIVE, Cars.active(context), live = true, "Connecting…", adapter.name)
        ObdSession.start(context, adapter, gen, Task.Live)
    }

    /** DEMO: a real Tiago drive (assets), with the Tiago's settings; nothing is saved. */
    fun startDemo(context: Context) {
        Cars.init(context)
        val gen = begin(context, Mode.DEMO, Cars.bundled(context, Cars.DEFAULT), live = false, "Starting demo…", "recorded drive")
        DemoPlayer.start(context, gen, null)
    }

    /** REPLAY: a drive the app recorded, with its car's settings; nothing is saved. */
    fun startReplay(context: Context, file: File) {
        Cars.init(context)
        val gen = begin(context, Mode.REPLAY, Cars.active(context), live = false, "Replaying ${file.name}", "recorded drive")
        DemoPlayer.start(context, gen, file)
    }

    /**
     * PROBE (newCar = false): read-only scan of every computer, saved as the active car's
     * recordings/probe_<date>_<time>.json. NEW CAR: also finds the protocol by itself, reads the
     * VIN and fuel type, then asks a few questions (see [ProbeResult]).
     */
    fun startProbe(context: Context, adapter: SavedAdapter, newCar: Boolean) {
        Cars.init(context)
        val car = if (newCar) null else Cars.active(context)
        val gen = begin(context, Mode.PROBE, car, live = false, if (newCar) "New car: connecting…" else "Probe: connecting…", adapter.name)
        ObdSession.start(context, adapter, gen, Task.Scan(newCar, car))
    }

    /**
     * KEY TEST: records battery voltage, which car computers answer and network traffic about
     * once a second into files/dev/keytest_<date>_<time>.csv, labelled with the key position
     * tapped in the DEV tab. Used to work out how to tell LOCK / ACC / ON / START / RUN apart.
     */
    fun startKeyTest(context: Context, adapter: SavedAdapter) {
        Cars.init(context)
        val dir = File(context.filesDir, DEV_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "keytest_$stamp.csv")
        val gen = begin(context, Mode.TEST, Cars.active(context), live = false, "Key test: connecting…", adapter.name)
        update(gen) { it.copy(test = TestInfo(file.name, KeyMarks.label)) }
        ObdSession.start(context, adapter, gen, Task.KeyTest(file))
    }

    /** The key position the user just tapped (DEV tab); goes into the next KEY TEST row. */
    fun markKey(label: String) {
        KeyMarks.mark(label)
        synchronized(this) {
            val s = store.value
            store.value = s.copy(test = s.test?.copy(label = label))
        }
    }

    // ---------------------------------------------------------------- during a run

    /**
     * One round of readings from any source: runs the calculations, records the round (LIVE, once
     * the car is confirmed), says a warm-up message if there is one, and updates the snapshot.
     */
    fun publishRound(
        gen: Int,
        readings: Map<Int, Reading>,
        portVolts: Double?,
        portAtMs: Long?,
        carState: CarState,
        change: (Snapshot) -> Snapshot = { it },
    ) {
        if (gen != generation) return
        val r = run ?: return
        val input = Values.from(readings, store.value.supported, portVolts, portAtMs)
        // Recordings have no read times (as in Python replays): a changed value counts as a new reading.
        if (!r.live) input.readAt.clear()
        val (derived, sentence) = r.pipeline.process(input, System.currentTimeMillis() / 1000.0)
        val recorder = r.recorder?.takeIf { r.carConfirmed }
        recorder?.write(readings, input, derived.values, carState)
        if (sentence != null) Speech.say(r.context, sentence)
        if (recorder != null) engineOffCheck(r, recorder, carState)
        val rows = recorder?.rows ?: 0
        update(gen) {
            change(it.copy(readings = readings, derived = derived, adapterVoltage = portVolts ?: it.adapterVoltage, recordedRows = rows))
        }
    }

    /**
     * After the engine has run, ignition off for [ENGINE_OFF_MS] (LIVE keeps listening while Android
     * Auto is connected): the drive report and the AI's spoken summary (owner waits ~1 min in the
     * car before getting out). Starting the engine again stops a summary being written.
     */
    private fun engineOffCheck(r: Run, recorder: DriveRecorder, state: CarState) {
        val now = System.currentTimeMillis()
        when (state) {
            CarState.ENGINE_ON -> {
                r.engineRan = true
                r.offSince = 0L
                DriveSummary.cancel()
            }
            CarState.IGNITION_OFF -> {
                if (r.offSince == 0L) r.offSince = now
                if (r.engineRan && now - r.offSince >= ENGINE_OFF_MS) {
                    r.engineRan = false
                    DriveReports.engineOff(r.context, r.car, recorder.csv)
                }
            }
            else -> r.offSince = 0L
        }
    }

    private const val ENGINE_OFF_MS = 20_000L

    /**
     * After connecting, makes sure readings go to the right car's folder, using the VIN when the car
     * shares it (the Python check_car). A car with a saved VIN is switched to automatically. The
     * active car learns a VIN if it has none yet. A VIN that belongs to no car set up here stops the
     * run, so another car's drive never lands in this car's recordings. Returns why to stop, or null.
     */
    fun checkCar(gen: Int, vin: String?): String? {
        if (gen != generation) return null
        val r = run ?: return null
        update(gen) { it.copy(vin = vin) }
        if (r.carConfirmed) return null   // checked at an earlier connection of this run
        if (vin == null) {   // this car doesn't share its VIN: trust the chosen car
            r.carConfirmed = true
            return null
        }
        val known = Cars.matchVin(vin)
        if (known != null && known != r.car.key) {
            r.close()
            val car = Cars.load(known)
            Cars.setActive(r.context, known)
            val next = Run(r.context, car, live = true).also { it.carConfirmed = true }
            run = next
            update(gen) { it.copy(car = car, recording = next.recorder?.csv?.name) }
            log(gen, "Recognised this car by its VIN: ${car.name}")
            takePendingRefuel(r.context, car)
            return null
        }
        if (known == null) {
            if (Cars.vin(r.car) != null) {
                return "VIN isn't the ${r.car.shortName}'s or any car set up: [NEW CAR] or choose with [CAR]"
            }
            Cars.saveVin(r.car, vin)
            log(gen, "Saved this car's VIN to ${r.car.key}, to recognise it next time")
        }
        r.carConfirmed = true
        return null
    }

    /** The car's protocol, supported PIDs and trouble codes, once known. */
    fun connected(gen: Int, protocol: String, supported: Set<Int>, codes: String) =
        update(gen) { it.copy(protocol = protocol, supported = supported, troubleCodes = codes) }

    /** A source finished by itself (probe done, wrong car): back to OFF with a message. */
    fun finished(gen: Int, status: String, probe: ProbeResult? = null) {
        if (gen != generation) return
        run?.close()
        run = null
        update(gen) { it.copy(mode = Mode.OFF, status = status, probe = probe ?: it.probe) }
    }

    // ---------------------------------------------------------------- cars, fuel

    /** NEW CAR answers: creates the car's folder from the template and makes it the active car. */
    fun createCar(context: Context, p: ProbeResult, title: String, fuel: String, tank: Double?, engine: Double?, redline: Int): String {
        Cars.init(context)
        val key = Cars.create(context, title, p.maker, fuel, p.protocol, tank, engine, redline, p.vin, p.report)
        Cars.setActive(context, key)
        val car = Cars.load(key)
        synchronized(this) { store.value = store.value.copy(probe = null, car = car, status = "New car set up: ${car.shortName}") }
        log(generation, "Set up cars/$key. Drive through every gear, then [GEARS].")
        return key
    }

    fun clearProbe() {
        synchronized(this) { store.value = store.value.copy(probe = null) }
    }

    fun chooseCar(context: Context, key: String) {
        Cars.setActive(context, key)
        if (!isRunning) synchronized(this) { store.value = store.value.copy(car = Cars.load(key)) }
    }

    /**
     * A fill-up from REFUEL (the Python --refuel L [--full]): straight into the running LIVE drive,
     * else kept for the next LIVE start. Returns what happened, for a message.
     */
    fun refuel(context: Context, litres: Double?, full: Boolean): String {
        Cars.init(context)
        val r = run
        val live = r != null && store.value.mode == Mode.LIVE
        val car = if (live) r!!.car else Cars.active(context)
        if (car.tankLitres == null) return "Not saved: the ${car.shortName} has no tank size set"
        if (live && r!!.pipeline.refuel(litres, full)) return "Fill-up recorded"
        context.getSharedPreferences(REFUEL_PREFS, Context.MODE_PRIVATE).edit()
            .putString("car", car.key).putFloat("litres", (litres ?: -1.0).toFloat()).putBoolean("full", full).apply()
        return "Fill-up saved: applied at the next LIVE start"
    }

    private fun takePendingRefuel(context: Context, car: CarProfile) {
        val prefs = context.getSharedPreferences(REFUEL_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("car", null) != car.key) return
        val litres = prefs.getFloat("litres", -1f).toDouble().takeIf { it > 0 }
        run?.pipeline?.refuel(litres, prefs.getBoolean("full", false))
        prefs.edit().clear().apply()
    }

    private const val REFUEL_PREFS = "refuel"

    // ---------------------------------------------------------------- files

    /** The newest drive recording of the active car (CSV, and its profile if saved), for sharing. */
    fun latestDrive(context: Context): List<File> {
        val csv = drives(context).firstOrNull() ?: return emptyList()
        val profile = File(csv.parentFile, csv.name.removeSuffix(".csv") + "_profile.json")
        return listOfNotNull(csv, profile.takeIf { it.exists() })
    }

    /** The active car's drive recordings, newest first. */
    fun drives(context: Context): List<File> {
        Cars.init(context)
        return Cars.active(context).recordings
            ?.listFiles { f -> f.name.startsWith("drive_") && f.name.endsWith(".csv") }
            .orEmpty()
            .sortedByDescending { it.name }
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
        ObdSession.stop()
        DemoPlayer.stop()
        run?.close()
        run = null
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
