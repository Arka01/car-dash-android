package io.github.arka01.cardash

import io.github.arka01.cardash.obd.Snapshot
import java.util.Locale

/** The text lines shown on both the phone and the car screen. */
object Report {
    private const val OLD_MS = 10_000

    fun lines(s: Snapshot): List<String> {
        val out = mutableListOf<String>()
        out += "CAR DASH ${BuildConfig.VERSION_NAME}  ${s.status}"
        out += "Adapter ${s.adapterName ?: "-"}  port ${s.adapterVoltage}"
        if (s.sweeps > 0) {
            out += String.format(Locale.US, "Sweep %d: %.1f s for %d values", s.sweeps, s.sweepMs / 1000.0, s.readings.size)
        }
        val now = System.currentTimeMillis()
        var module: String? = null
        for (r in s.readings) {
            if (r.module != module) {
                module = r.module
                out += ""
                out += "== ${r.module} =="
            }
            val old = if (now - r.time > OLD_MS) " (old)" else ""
            out += String.format(Locale.US, "%02X %-20s %s%s", r.pid, r.name.take(20), r.value, old)
        }
        return out
    }

    /** Lines plus the connection log, for the phone screen and the Share button. */
    fun full(s: Snapshot): String =
        (lines(s) + listOf("", "== LOG ==") + s.log).joinToString("\n")
}
