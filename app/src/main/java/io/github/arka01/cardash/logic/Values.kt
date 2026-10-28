package io.github.arka01.cardash.logic

import io.github.arka01.cardash.Reading

/**
 * One update's values by the Python dashboard's names ("Speed", "RPM", "Torque"...), like its
 * `values` dict: a number, missing (no answer yet), or unsupported (this car doesn't report it,
 * the Python NOT_SUPPORTED). [readAt] = when each reading arrived, in seconds.
 */
class Values(
    private val numbers: MutableMap<String, Double> = HashMap(),
    private val unsupported: MutableSet<String> = HashSet(),
    val readAt: MutableMap<String, Double> = HashMap(),
) {
    operator fun get(label: String): Double? = numbers[label]

    fun isUnsupported(label: String) = label in unsupported

    operator fun set(label: String, value: Double?) {
        if (value == null || value.isNaN()) numbers.remove(label) else numbers[label] = value
        unsupported.remove(label)
    }

    fun markUnsupported(label: String) {
        numbers.remove(label)
        unsupported += label
    }

    val labels: Set<String> get() = numbers.keys

    fun copy() = Values(HashMap(numbers), HashSet(unsupported), HashMap(readAt))

    companion object {
        /** The adapter's own voltage reading (ELM327 "AT RV"), not an engine computer value. */
        const val PORT = "Battery (port)"

        /** Name -> standard PID, the Python dashboard's STATS (their column names in recordings). */
        val PIDS: Map<String, Int> = linkedMapOf(
            "Speed" to 0x0D, "RPM" to 0x0C, "Coolant" to 0x05, "Oil" to 0x5C, "Intake air" to 0x0F,
            "Outside" to 0x46, "Catalyst" to 0x3C, "Throttle" to 0x45, "Accelerator pedal" to 0x5A,
            "Engine load" to 0x04, "Manifold pressure" to 0x0B, "Air flow (MAF)" to 0x10,
            "Timing advance" to 0x0E, "Lambda (commanded)" to 0x44, "Short fuel trim" to 0x06,
            "Long fuel trim" to 0x07, "EGR (commanded)" to 0x2C, "Fuel rail pressure" to 0x23,
            "Fuel level" to 0x2F, "Fuel rate" to 0x5E, "Pedal sensor D" to 0x49, "Pedal sensor E" to 0x4A,
            "Engine torque %" to 0x62, "Reference torque" to 0x63, "Engine run time" to 0x1F,
            "Battery" to 0x42, "Barometric pressure" to 0x33, "Since codes cleared" to 0x31,
            "Driven w/ check-engine" to 0x21,
        )

        /**
         * Read instead if the car doesn't support the first choice. The relative throttle / pedal
         * (45 / 5A) run a true 0-100 %; cars without them (e.g. the XUV700) get the plain ones.
         */
        val FALLBACK: Map<String, Int> = mapOf("Throttle" to 0x11, "Accelerator pedal" to 0x49)

        /** The PID a value comes from on this car: its own, or the fallback if only that is supported. */
        fun pidFor(label: String, supported: Set<Int>?): Int? {
            val pid = PIDS[label] ?: return null
            if (supported == null || pid in supported) return pid
            val fallback = FALLBACK[label]
            return if (fallback != null && fallback in supported) fallback else pid
        }

        /**
         * The values by name from the readings by PID. [supported] = the engine computer's PIDs
         * (null = not known yet: nothing counts as unsupported).
         */
        fun from(readings: Map<Int, Reading>, supported: Set<Int>?, port: Double?, portAtMs: Long?): Values {
            val v = Values()
            for (label in PIDS.keys) {
                val pid = pidFor(label, supported)!!
                if (supported != null && pid !in supported) {
                    v.markUnsupported(label)
                    continue
                }
                val r = readings[pid] ?: continue
                v[label] = r.number
                v.readAt[label] = r.time / 1000.0
            }
            v[PORT] = port
            if (portAtMs != null) v.readAt[PORT] = portAtMs / 1000.0
            return v
        }
    }
}
