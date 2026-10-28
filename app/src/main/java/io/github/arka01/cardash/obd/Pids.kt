package io.github.arka01.cardash.obd

import java.util.Locale

/**
 * Names and formulas for the standard live-data values (OBD-II service 01, SAE J1979).
 * Covers the common standard PIDs (everything the Tiago and the XUV700 reported, and more);
 * anything else is shown as raw hex bytes.
 *
 * Most values have a number (used for bars, warnings and the gear) and a text made from it.
 * The demo feeds numbers straight into [format], so live and demo values look the same.
 */
object Pids {
    private class Pid(
        val name: String,
        val unit: String = "",
        val decimals: Int = 0,
        val number: ((IntArray) -> Double)? = null,    // null: show the raw bytes
        val label: ((Double) -> String)? = null,       // words instead of number + unit
    )

    class Value(val number: Double?, val text: String)

    private fun word(d: IntArray) = d[0] * 256.0 + d[1]
    private fun pct(d: IntArray) = d[0] * 100.0 / 255
    private fun trim(d: IntArray) = (d[0] - 128) * 100.0 / 128
    private fun temp(d: IntArray) = d[0] - 40.0
    private fun hex(d: IntArray) = d.joinToString(" ") { "%02X".format(it) }

    private val fuelSystem = mapOf(
        1 to "open loop (cold)", 2 to "closed loop", 4 to "open loop (load/fuel cut)",
        8 to "open loop (fault)", 16 to "closed loop (fault)",
    )
    /** Fuel type codes (PID 51) -> the fuel names car.json uses. */
    val FUEL_TYPES = mapOf(
        1 to "petrol", 2 to "methanol", 3 to "ethanol", 4 to "diesel", 5 to "lpg", 6 to "cng", 7 to "lpg",
        8 to "electric", 9 to "petrol", 10 to "methanol", 11 to "ethanol", 12 to "lpg", 13 to "cng", 14 to "lpg",
        15 to "electric", 16 to "electric", 17 to "hybrid", 18 to "hybrid", 19 to "hybrid", 20 to "hybrid",
        21 to "hybrid", 22 to "hybrid", 23 to "diesel",
    )

    private val table = mapOf(
        0x01 to Pid("Monitor status", number = { it[0].toDouble() }, label = { a ->
            val v = a.toInt()
            "check-engine ${if (v and 0x80 != 0) "ON" else "off"}, ${v and 0x7F} codes"
        }),
        0x02 to Pid("Freeze-frame code", number = ::word, label = { if (it == 0.0) "none" else "%04X".format(it.toInt()) }),
        0x03 to Pid("Fuel system", number = { it[0].toDouble() }, label = { fuelSystem[it.toInt()] ?: "off" }),
        0x04 to Pid("Engine load", "%", 1, ::pct),
        0x05 to Pid("Coolant", "°C", 0, ::temp),
        0x06 to Pid("Short fuel trim", "%", 1, ::trim),
        0x07 to Pid("Long fuel trim", "%", 1, ::trim),
        0x0A to Pid("Fuel pressure", "kPa", 0, { it[0] * 3.0 }),
        0x0B to Pid("Manifold pressure", "kPa", 0, { it[0].toDouble() }),
        0x0C to Pid("RPM", "rpm", 0, { word(it) / 4 }),
        0x0D to Pid("Speed", "km/h", 0, { it[0].toDouble() }),
        0x0E to Pid("Timing advance", "°", 1, { it[0] / 2.0 - 64 }),
        0x0F to Pid("Intake air", "°C", 0, ::temp),
        0x10 to Pid("Air flow (MAF)", "g/s", 2, { word(it) / 100 }),
        0x11 to Pid("Throttle (absolute)", "%", 1, ::pct),
        0x13 to Pid("O2 sensors present"),
        0x14 to Pid("O2 B1S1 voltage", "V", 3, { it[0] / 200.0 }),
        0x15 to Pid("O2 B1S2 voltage", "V", 3, { it[0] / 200.0 }),
        0x16 to Pid("O2 B1S3 voltage", "V", 3, { it[0] / 200.0 }),
        0x17 to Pid("O2 B1S4 voltage", "V", 3, { it[0] / 200.0 }),
        0x18 to Pid("O2 B2S1 voltage", "V", 3, { it[0] / 200.0 }),
        0x19 to Pid("O2 B2S2 voltage", "V", 3, { it[0] / 200.0 }),
        0x1C to Pid("OBD standard", number = { it[0].toDouble() }),
        0x1F to Pid("Engine run time", "s", 0, ::word),
        0x21 to Pid("Driven w/ check-eng", "km", 0, ::word),
        0x22 to Pid("Fuel rail (vacuum)", "kPa", 1, { word(it) * 0.079 }),
        // Python records it in bar, so the same here (standard: 10 kPa steps).
        0x23 to Pid("Fuel rail pressure", "bar", 0, { word(it) * 10 / 100 }),
        0x2C to Pid("EGR (commanded)", "%", 1, ::pct),
        0x2D to Pid("EGR error", "%", 1, ::trim),
        0x2E to Pid("Evap purge", "%", 1, ::pct),
        0x2F to Pid("Fuel level", "%", 1, ::pct),
        0x30 to Pid("Warm-ups since clear", number = { it[0].toDouble() }),
        0x31 to Pid("Since codes cleared", "km", 0, ::word),
        0x33 to Pid("Barometric pressure", "kPa", 0, { it[0].toDouble() }),
        0x34 to Pid("O2 S1 lambda", "", 3, { word(it) * 2 / 65536 }),
        0x3C to Pid("Catalyst", "°C", 0, { word(it) / 10 - 40 }),
        0x3D to Pid("Catalyst B2S1", "°C", 0, { word(it) / 10 - 40 }),
        0x3E to Pid("Catalyst B1S2", "°C", 0, { word(it) / 10 - 40 }),
        0x3F to Pid("Catalyst B2S2", "°C", 0, { word(it) / 10 - 40 }),
        0x41 to Pid("Monitors this drive"),
        0x42 to Pid("Battery (ECU)", "V", 2, { word(it) / 1000 }),
        0x43 to Pid("Absolute load", "%", 1, { word(it) * 100 / 255 }),
        0x44 to Pid("Lambda (commanded)", "", 3, { word(it) * 2 / 65536 }),
        0x45 to Pid("Throttle", "%", 1, ::pct),
        0x46 to Pid("Outside air", "°C", 0, ::temp),
        0x47 to Pid("Throttle B", "%", 1, ::pct),
        0x48 to Pid("Throttle C", "%", 1, ::pct),
        0x49 to Pid("Pedal sensor D", "%", 1, ::pct),
        0x4A to Pid("Pedal sensor E", "%", 1, ::pct),
        0x4B to Pid("Pedal sensor F", "%", 1, ::pct),
        0x4C to Pid("Throttle commanded", "%", 1, ::pct),
        0x4D to Pid("Time w/ check-eng", "min", 0, ::word),
        0x4E to Pid("Since codes cleared", "min", 0, ::word),
        0x4F to Pid("Max values"),
        0x50 to Pid("Max air flow", "g/s", 0, { it[0] * 10.0 }),
        0x51 to Pid("Fuel type", number = { it[0].toDouble() }, label = { FUEL_TYPES[it.toInt()] ?: "%.0f".format(it) }),
        0x52 to Pid("Ethanol", "%", 1, ::pct),
        0x55 to Pid("Short O2 trim B1", "%", 1, ::trim),
        0x56 to Pid("Long O2 trim B1", "%", 1, ::trim),
        0x59 to Pid("Fuel rail absolute", "kPa", 0, { word(it) * 10 }),
        0x5B to Pid("Hybrid battery", "%", 1, ::pct),
        0x5A to Pid("Pedal", "%", 1, ::pct),
        0x5C to Pid("Oil", "°C", 0, ::temp),
        0x5D to Pid("Injection timing", "°", 1, { (word(it) - 26880) / 128 }),
        0x5E to Pid("Fuel rate", "L/h", 2, { word(it) / 20 }),
        0x61 to Pid("Torque demanded", "%", 0, { it[0] - 125.0 }),
        0x62 to Pid("Torque actual", "%", 0, { it[0] - 125.0 }),
        0x63 to Pid("Reference torque", "Nm", 0, ::word),
        0x67 to Pid("Coolant (sensors)", "°C", 0, { it[1] - 40.0 }),
        // Seen on the Tiago 2026-10-26: matched the normal manifold pressure (91.1 vs 91, 28.2 vs 29).
        0x87 to Pid("Manifold pressure fine", "kPa", 1, { (it[1] * 256 + it[2]) / 32.0 }),
        // Standard says exhaust flow (0.2 kg/h steps); 10 kg/h at idle is plausible but not checked yet.
        0x9E to Pid("Exhaust flow (?)", "kg/h", 1, { word(it) / 5 }),
    )

    /** Trouble code from its two bytes, e.g. 03 01 -> "P0301"; null for the 00 00 padding. */
    fun troubleCode(a: Int, b: Int): String? {
        if (a == 0 && b == 0) return null
        return "${"PCBU"[a shr 6]}" + "%d%X%02X".format((a shr 4) and 3, a and 0x0F, b)
    }

    /** Codes from a service 03 / 07 answer's data bytes after the service byte (CAN: count first). */
    fun troubleCodes(data: IntArray): List<String> {
        val codes = mutableListOf<String>()
        var i = 2   // 43 <count> then pairs (CAN)
        while (i + 1 < data.size) {
            troubleCode(data[i], data[i + 1])?.let { codes += it }
            i += 2
        }
        return codes
    }

    fun name(pid: Int): String = table[pid]?.name ?: "PID %02X".format(pid)

    /** Turns the data bytes after "41 <pid>" into a value. */
    fun decode(pid: Int, data: IntArray): Value {
        val known = table[pid]
        val toNumber = known?.number ?: return Value(null, hex(data))
        return try {
            val n = toNumber(data)
            Value(n, format(pid, n))
        } catch (e: IndexOutOfBoundsException) {
            Value(null, "? ${hex(data)}")   // shorter answer than the formula expects
        }
    }

    /** The text for a number, e.g. 812.0 -> "812 rpm". */
    fun format(pid: Int, number: Double): String {
        val known = table[pid] ?: return "%.0f".format(number)
        known.label?.let { return it(number) }
        val n = String.format(Locale.US, "%.${known.decimals}f", number)
        return if (known.unit.isEmpty()) n else "$n ${known.unit}"
    }
}
