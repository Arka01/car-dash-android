package io.github.arka01.cardash

import io.github.arka01.cardash.logic.Values
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records every LIVE drive to files/cars/<car>/recordings/drive_<date>_<time>.csv (+ _profile.json),
 * in the same format as the Python dashboard's recordings: one row per reading round,
 * "t" = seconds since the start, "time" = clock time, then one column per value with the
 * Python dashboard's column names, so its analysis and --replay work on these files too.
 * Raw readings as read (like Python), plus the calculated Torque and Power; columns a car doesn't
 * report stay empty. App-only values are added at the end, then "PID xx" for anything else
 * the car reports.
 */
class DriveRecorder(dir: File, private val carKey: String) {
    val csv: File
    val profile: File
    private var writer: FileWriter? = null
    private var closed = false
    private val start = System.currentTimeMillis()
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var extras: List<Int> = emptyList()   // PIDs not in COLUMNS, fixed at the first row

    @Volatile var rows = 0
        private set

    init {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        csv = File(dir, "drive_$stamp.csv")
        profile = File(dir, "drive_${stamp}_profile.json")
    }

    /** Appends one row with the latest value of everything. */
    @Synchronized
    fun write(readings: Map<Int, Reading>, raw: Values, calculated: Values, state: CarState) {
        if (closed) return   // stopped while the reading loop was finishing a round
        val w = writer ?: FileWriter(csv, true).also { writer = it }
        if (csv.length() == 0L) {
            val known = COLUMNS.map { it.second }.toSet() + Values.PIDS.values + Values.FALLBACK.values
            extras = readings.keys.filter { it !in known }.sorted()
            w.write((listOf("t", "time") + COLUMNS.map { it.first } + extras.map { "PID %02X".format(it) }).joinToString(",") + "\n")
        }
        val cells = COLUMNS.map { (name, source) ->
            when (source) {
                LABEL -> (if (name in Values.PIDS || name == Values.PORT) raw[name] else calculated[name])?.let { number(it) } ?: ""
                STATE -> state.label
                NOTHING -> ""
                else -> cell(readings[source], source)
            }
        } + extras.map { cell(readings[it], it) }
        val t = String.format(Locale.US, "%.2f", (System.currentTimeMillis() - start) / 1000.0)
        w.write((listOf(t, clock.format(Date())) + cells).joinToString(",") + "\n")
        w.flush()   // keep the file complete even if the app is killed mid-drive
        rows++
    }

    private fun cell(r: Reading?, pid: Int): String {
        if (r == null) return ""
        return if (pid in TEXT_PIDS || r.number == null) text(r.text) else number(r.number)
    }

    /** What the car supports and how reading went, saved next to the CSV (like the Python profile). */
    @Synchronized
    fun saveProfile(
        adapter: String?,
        protocol: String,
        troubleCodes: String,
        modules: Map<String, List<Int>>,
        fastRead: String,
        readings: Map<Int, Reading>,
    ) {
        if (closed) return
        val json = JSONObject()
        json.put("car", carKey)
        json.put("saved", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        json.put("app", "Car Dash ${BuildConfig.VERSION_NAME}")
        json.put("adapter", adapter ?: "")
        json.put("protocol", protocol)
        json.put("trouble_codes", troubleCodes)
        val mods = JSONObject()
        for ((header, pids) in modules) mods.put(header, JSONArray(pids.map { "%02X".format(it) }))
        json.put("supported_pids", mods)
        json.put("fast_read", fastRead)
        val snapshot = JSONObject()
        for (r in readings.values.sortedBy { it.pid }) snapshot.put("%02X %s".format(r.pid, r.name), r.text)
        json.put("snapshot", snapshot)
        profile.writeText(json.toString(2))
    }

    /** Closes the file; a drive without any rows (never connected) leaves no files behind. */
    @Synchronized
    fun close() {
        closed = true
        writer?.close()
        writer = null
        if (rows == 0) {
            csv.delete()
            profile.delete()
        }
    }

    private fun number(v: Double) = String.format(Locale.US, "%.6g", v).let { s ->
        if ('.' in s && 'e' !in s) s.trimEnd('0').trimEnd('.') else s
    }

    /** CSV-safe text: quoted if it contains a comma or a quote. */
    private fun text(s: String) = if (',' in s || '"' in s) "\"" + s.replace("\"", "\"\"") + "\"" else s

    companion object {
        private const val LABEL = -1     // from the pipeline's values, by the column's name
        private const val NOTHING = -3
        private const val STATE = -4
        const val PORT_COLUMN = -100

        /** Values written as words (as the Python recordings do), not numbers. */
        private val TEXT_PIDS = setOf(0x01, 0x02, 0x03, 0x13, 0x1C, 0x41, 0x4F, 0x51)

        /** Column name (Python dashboard's) -> where the value comes from. */
        private val COLUMNS: List<Pair<String, Int>> =
            Values.PIDS.keys.map { it to LABEL } +
                listOf(Values.PORT to LABEL, "Torque" to LABEL, "Power" to LABEL) +
                listOf(
                    "Status since DTCs cleared" to 0x01, "DTC that triggered the freeze frame" to 0x02,
                    "Fuel System Status" to 0x03, "Throttle Position" to 0x11, "O2 Sensors Present" to 0x13,
                    "O2: Bank 1 - Sensor 2 Voltage" to 0x15, "OBD Standards Compliance" to 0x1C,
                    "Commanded Evaporative Purge" to 0x2E, "Number of warm-ups since codes cleared" to 0x30,
                    "02 Sensor 1 WR Lambda Current" to NOTHING,   // Python stored the current; the app decodes the lambda (below)
                    "Monitor status this drive cycle" to 0x41, "Absolute load value" to 0x43,
                    "Absolute throttle position B" to 0x47, "Commanded throttle actuator" to 0x4C,
                    "Time run with MIL on" to 0x4D, "Time since trouble codes cleared" to 0x4E,
                    "Various Max values" to 0x4F, "Maximum value for mass air flow sensor" to 0x50,
                    "Fuel Type" to 0x51, "Long term secondary O2 trim - Bank 1" to 0x56,
                    // App-only columns
                    "Car state" to STATE, "Gear (estimated)" to LABEL, "O2 S1 lambda" to 0x34,
                    "Torque demanded %" to 0x61, "Coolant (sensors)" to 0x67, "Manifold pressure fine" to 0x87,
                    "Exhaust flow (?)" to 0x9E,
                )

        /**
         * For REPLAY: the PID a recorded column holds ([PORT_COLUMN] for the adapter's voltage),
         * or null for calculated or empty columns. Throttle / pedal columns map to their usual PID.
         */
        fun pidOfColumn(name: String): Int? {
            if (name == Values.PORT) return PORT_COLUMN
            Values.PIDS[name]?.let { return it }
            Regex("PID ([0-9A-F]{2})").matchEntire(name)?.let { return it.groupValues[1].toInt(16) }
            return COLUMNS.firstOrNull { it.first == name }?.second?.takeIf { it >= 0 }
        }
    }
}
