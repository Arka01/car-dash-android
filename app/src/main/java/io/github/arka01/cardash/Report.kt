package io.github.arka01.cardash

import java.util.Locale

/** Plain text of everything on screen plus the log, for the Share button. */
object Report {
    fun full(s: Snapshot): String {
        val out = mutableListOf<String>()
        out += "CAR DASH ${BuildConfig.VERSION_NAME}  ${s.mode}  ${s.status}"
        out += "Adapter ${s.adapterName ?: "-"}  port ${s.adapterVoltage?.let { "%.1f V".format(Locale.US, it) } ?: "--"}"
        out += String.format(Locale.US, "Round %d, %.1f per second, fast read %s", s.rounds, s.roundsPerSec, s.fastRead.ifEmpty { "-" })
        out += ""
        out += "== ENGINE 7E8 =="
        for (r in s.readings.values.sortedBy { it.pid }) {
            out += String.format(Locale.US, "%02X %-22s %s", r.pid, r.name.take(22), r.text)
        }
        out += ""
        out += "== LOG =="
        out += s.log
        return out.joinToString("\n")
    }
}
