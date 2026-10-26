package io.github.arka01.cardash.obd

import java.util.Locale

/**
 * Names and formulas for the standard live-data values (OBD-II service 01, SAE J1979).
 * Covers everything the Tiago reported in the probe of 2026-10-13; anything else is
 * shown as raw hex bytes.
 */
object Pids {
    private class Pid(val name: String, val decode: (IntArray) -> String)

    private fun f(format: String, vararg args: Any) = String.format(Locale.US, format, *args)
    private fun pct(a: Int) = f("%.1f %%", a * 100.0 / 255)
    private fun trim(a: Int) = f("%.1f %%", (a - 128) * 100.0 / 128)
    private fun temp(a: Int) = "${a - 40} °C"
    private fun word(d: IntArray) = d[0] * 256 + d[1]
    private fun hex(d: IntArray) = d.joinToString(" ") { "%02X".format(it) }

    private val fuelSystem = mapOf(
        1 to "open loop (cold)", 2 to "closed loop", 4 to "open loop (load)",
        8 to "open loop (fault)", 16 to "closed loop (fault)",
    )
    private val fuelType = mapOf(1 to "petrol", 4 to "diesel", 8 to "electric")

    private val table = mapOf(
        0x01 to Pid("Monitor status") { d ->
            "check-engine ${if (d[0] and 0x80 != 0) "ON" else "off"}, ${d[0] and 0x7F} codes"
        },
        0x02 to Pid("Freeze-frame code") { d -> if (word(d) == 0) "none" else hex(d) },
        0x03 to Pid("Fuel system") { d -> fuelSystem[d[0]] ?: hex(d) },
        0x04 to Pid("Engine load") { d -> pct(d[0]) },
        0x05 to Pid("Coolant") { d -> temp(d[0]) },
        0x06 to Pid("Short fuel trim") { d -> trim(d[0]) },
        0x07 to Pid("Long fuel trim") { d -> trim(d[0]) },
        0x0B to Pid("Manifold pressure") { d -> "${d[0]} kPa" },
        0x0C to Pid("RPM") { d -> f("%.0f rpm", word(d) / 4.0) },
        0x0D to Pid("Speed") { d -> "${d[0]} km/h" },
        0x0E to Pid("Timing advance") { d -> f("%.1f °", d[0] / 2.0 - 64) },
        0x0F to Pid("Intake air") { d -> temp(d[0]) },
        0x10 to Pid("Air flow (MAF)") { d -> f("%.2f g/s", word(d) / 100.0) },
        0x11 to Pid("Throttle") { d -> pct(d[0]) },
        0x13 to Pid("O2 sensors present") { d -> hex(d) },
        0x15 to Pid("O2 B1S2 voltage") { d -> f("%.3f V", d[0] / 200.0) },
        0x1C to Pid("OBD standard") { d -> "${d[0]}" },
        0x1F to Pid("Engine run time") { d -> "${word(d)} s" },
        0x21 to Pid("Driven w/ check-eng") { d -> "${word(d)} km" },
        0x2E to Pid("Evap purge") { d -> pct(d[0]) },
        0x2F to Pid("Fuel level") { d -> pct(d[0]) },
        0x30 to Pid("Warm-ups since clear") { d -> "${d[0]}" },
        0x31 to Pid("Since codes cleared") { d -> "${word(d)} km" },
        0x33 to Pid("Barometric pressure") { d -> "${d[0]} kPa" },
        0x34 to Pid("O2 S1 lambda/current") { d ->
            f("λ %.3f  %.2f mA", word(d) * 2 / 65536.0, (d[2] * 256 + d[3]) / 256.0 - 128)
        },
        0x3C to Pid("Catalyst") { d -> f("%.0f °C", word(d) / 10.0 - 40) },
        0x41 to Pid("Monitors this drive") { d -> hex(d) },
        0x42 to Pid("Battery (ECU)") { d -> f("%.2f V", word(d) / 1000.0) },
        0x43 to Pid("Absolute load") { d -> f("%.1f %%", word(d) * 100.0 / 255) },
        0x44 to Pid("Lambda (commanded)") { d -> f("%.3f", word(d) * 2 / 65536.0) },
        0x45 to Pid("Relative throttle") { d -> pct(d[0]) },
        0x46 to Pid("Outside air") { d -> temp(d[0]) },
        0x47 to Pid("Throttle B") { d -> pct(d[0]) },
        0x49 to Pid("Pedal sensor D") { d -> pct(d[0]) },
        0x4A to Pid("Pedal sensor E") { d -> pct(d[0]) },
        0x4C to Pid("Throttle commanded") { d -> pct(d[0]) },
        0x4D to Pid("Time w/ check-eng") { d -> "${word(d)} min" },
        0x4E to Pid("Since codes cleared") { d -> "${word(d)} min" },
        0x4F to Pid("Max values") { d -> hex(d) },
        0x50 to Pid("Max air flow") { d -> "${d[0] * 10} g/s" },
        0x51 to Pid("Fuel type") { d -> fuelType[d[0]] ?: "${d[0]}" },
        0x56 to Pid("Long O2 trim B1") { d -> trim(d[0]) },
        0x5A to Pid("Relative pedal") { d -> pct(d[0]) },
        0x5C to Pid("Oil") { d -> temp(d[0]) },
        0x5E to Pid("Fuel rate") { d -> f("%.2f L/h", word(d) / 20.0) },
        0x61 to Pid("Torque demanded") { d -> "${d[0] - 125} %" },
        0x62 to Pid("Torque actual") { d -> "${d[0] - 125} %" },
        0x63 to Pid("Reference torque") { d -> "${word(d)} Nm" },
        0x67 to Pid("Coolant (sensors)") { d -> "${d[1] - 40} °C" },
    )

    fun name(pid: Int): String = table[pid]?.name ?: "PID %02X".format(pid)

    /** Turns the data bytes after "41 <pid>" into a readable value. */
    fun decode(pid: Int, data: IntArray): String {
        val known = table[pid] ?: return hex(data)
        return try {
            known.decode(data)
        } catch (e: IndexOutOfBoundsException) {
            "? ${hex(data)}"   // shorter answer than the formula expects
        }
    }
}
