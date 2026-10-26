package io.github.arka01.cardash

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records every LIVE drive to files/drives/drive_<date>_<time>.csv (+ _profile.json),
 * in the same format as the Python dashboard's recordings: one row per reading round,
 * "t" = seconds since the start, "time" = clock time, then one column per value with the
 * Python dashboard's column names, so its analysis and --replay work on these files too.
 * Columns the Tiago doesn't report (MAF, EGR, rail pressure) or that the app doesn't compute
 * yet (Torque, Power) stay empty. App-only values are added at the end.
 */
class DriveRecorder(dir: File) {
    val csv: File
    val profile: File
    private var writer: FileWriter? = null
    private var closed = false
    private val start = System.currentTimeMillis()
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

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
    fun write(readings: Map<Int, Reading>, adapterVoltage: Double?, state: CarState) {
        if (closed) return   // stopped while the reading loop was finishing a round
        val w = writer ?: FileWriter(csv, true).also { writer = it }
        if (csv.length() == 0L) w.write((listOf("t", "time") + COLUMNS.map { it.first }).joinToString(",") + "\n")
        val gear = Tiago.gear(readings[0x0D]?.number, readings[0x0C]?.number)
        val cells = COLUMNS.map { (_, source) ->
            when (source) {
                ADAPTER_VOLTAGE -> adapterVoltage?.let(::number) ?: ""
                GEAR -> gear?.toString() ?: ""
                STATE -> state.label
                NOTHING -> ""
                else -> readings[source]?.let { r ->
                    if (source in TEXT_PIDS || r.number == null) text(r.text) else number(r.number)
                } ?: ""
            }
        }
        val t = String.format(Locale.US, "%.2f", (System.currentTimeMillis() - start) / 1000.0)
        w.write((listOf(t, clock.format(Date())) + cells).joinToString(",") + "\n")
        w.flush()   // keep the file complete even if the app is killed mid-drive
        rows++
    }

    /** What the car supports and how reading went, saved next to the CSV (like the Python profile). */
    @Synchronized
    fun saveProfile(adapter: String?, modules: Map<String, List<Int>>, fastRead: String, readings: Map<Int, Reading>) {
        if (closed) return
        val json = JSONObject()
        json.put("car", "tata_tiago_2023")
        json.put("saved", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        json.put("app", "Car Dash ${BuildConfig.VERSION_NAME}")
        json.put("adapter", adapter ?: "")
        json.put("protocol", "ISO 15765-4 (CAN 11/500)")
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
        const val DIR = "drives"
        private const val ADAPTER_VOLTAGE = -1
        private const val GEAR = -2
        private const val NOTHING = -3
        private const val STATE = -4

        /** Values written as words (as the Python recordings do), not numbers. */
        private val TEXT_PIDS = setOf(0x01, 0x02, 0x03, 0x13, 0x1C, 0x41, 0x4F, 0x51)

        /** Column name (Python dashboard's) -> PID it comes from. */
        private val COLUMNS: List<Pair<String, Int>> = listOf(
            "Speed" to 0x0D, "RPM" to 0x0C, "Coolant" to 0x05, "Oil" to 0x5C, "Intake air" to 0x0F,
            "Outside" to 0x46, "Catalyst" to 0x3C, "Throttle" to 0x45, "Accelerator pedal" to 0x5A,
            "Engine load" to 0x04, "Manifold pressure" to 0x0B, "Air flow (MAF)" to 0x10,
            "Timing advance" to 0x0E, "Lambda (commanded)" to 0x44, "Short fuel trim" to 0x06,
            "Long fuel trim" to 0x07, "EGR (commanded)" to 0x2C, "Fuel rail pressure" to 0x23,
            "Fuel level" to 0x2F, "Fuel rate" to 0x5E, "Pedal sensor D" to 0x49, "Pedal sensor E" to 0x4A,
            "Engine torque %" to 0x62, "Reference torque" to 0x63, "Engine run time" to 0x1F,
            "Battery" to 0x42, "Battery (port)" to ADAPTER_VOLTAGE, "Barometric pressure" to 0x33,
            "Since codes cleared" to 0x31, "Driven w/ check-engine" to 0x21, "Torque" to NOTHING, "Power" to NOTHING,
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
            "Car state" to STATE, "Gear (estimated)" to GEAR, "O2 S1 lambda" to 0x34, "Torque demanded %" to 0x61,
            "Coolant (sensors)" to 0x67, "Manifold pressure fine" to 0x87, "Exhaust flow (?)" to 0x9E,
        )
    }
}
