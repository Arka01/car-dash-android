package io.github.arka01.cardash.obd

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only questions to every computer on the car's network (the Python dashboard's
 * scan_modules / read_vin / trouble_codes). Only standard read requests are sent
 * (services 01, 03, 07, 09); nothing is written or cleared. Expects headers on (ATH1)
 * and CAN 11-bit (protocol 6 or 8).
 */
class Probe(private val elm: Elm327, private val log: (String) -> Unit) {

    /** Asks every computer at once (header 7DF): {reply address: reply bytes, service + 0x40 first}. */
    fun ask(request: String, timeoutMs: Long = 5000): Map<String, IntArray> {
        elm.command("ATSH7DF")
        val service = request.substring(0, 2).toInt(16)
        return assembleCan11(elm.command(request, timeoutMs)).filterValues { it.isNotEmpty() && it[0] == service + 0x40 }
    }

    /** Which live-data PIDs each computer supports: 01 00 lists 01-20, 01 20 lists 21-40, and so on. */
    fun supported(firstTimeoutMs: Long = 5000): Map<String, List<Int>> {
        val supported = sortedMapOf<String, MutableList<Int>>()
        var base = 0x00
        while (base < 0xE0) {
            if (base != 0x00 && supported.values.none { base in it }) break   // nobody has the next range
            val answer = ask("01%02X".format(base), if (base == 0) firstTimeoutMs else 5000)
            log("01%02X → %s".format(base, answer.entries.joinToString(" | ") { (m, d) -> m + " " + hex(d) }.ifEmpty { "no answer" }))
            for ((module, d) in answer) {
                if (d.size < 6 || d[1] != base) continue
                val pids = supported.getOrPut(module) { mutableListOf() }
                for (i in 0 until 32) {
                    if (d[2 + i / 8] and (0x80 shr (i % 8)) != 0) pids += base + i + 1
                }
            }
            base += 0x20
        }
        // 0x20, 0x40, … only say "there are more"; they are not sensors.
        return supported.mapValues { (_, pids) -> pids.filter { it % 0x20 != 0 } }
    }

    /**
     * The car's 17-character chassis number (VIN, service 09 PID 02), or null if it doesn't share it.
     * VINs never use I, O or Q; anything else (padding, the message counter) is dropped.
     */
    fun vin(): String? {
        val answer = ask("0902").entries.firstOrNull { (_, d) -> d.size > 3 && d[1] == 0x02 } ?: return null
        val text = answer.value.drop(3).map { it.toChar() }.joinToString("").uppercase()
        val vin = text.replace(Regex("[^A-HJ-NPR-Z0-9]"), "").takeLast(17)
        return vin.takeIf { it.length == 17 }
    }

    /** Stored trouble codes (the check-engine light's), from every computer: "P0301, P0171" or "none". */
    fun troubleCodes(): String {
        val codes = ask("03").values.flatMap { Pids.troubleCodes(it) }.distinct()
        return codes.joinToString(", ").ifEmpty { "none" }
    }

    /** The fuel the engine computer says it uses (PID 51), as car.json names it, or null. */
    fun fuelType(): String? {
        val d = ask("0151").values.firstOrNull { it.size >= 3 && it[1] == 0x51 } ?: return null
        return Pids.FUEL_TYPES[d[2]]
    }

    /**
     * Every computer that answers: what live data it supports, the standard gear PID (A4), stored +
     * pending trouble codes, and its name and software ID. Same structure as the Python probe JSON.
     */
    fun scan(protocol: String, supported: Map<String, List<Int>>): JSONObject {
        val gear = ask("01A4")
        val stored = ask("03")
        val pending = ask("07")
        val names = ask("090A")
        val calibration = ask("0904")
        val modules = (supported.keys + gear.keys + stored.keys + pending.keys + names.keys).toSortedSet()
        val report = JSONObject()
            .put("saved", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            .put("protocol", protocol)
        val all = JSONObject()
        for (module in modules) {
            val g = gear[module]
            all.put(module, JSONObject()
                .put("usually", mapOf("7E8" to "engine", "7E9" to "transmission")[module] ?: "other module")
                .put("name", names[module]?.let { text(it, 3) } ?: JSONObject.NULL)
                .put("calibration_id", calibration[module]?.let { text(it, 3) } ?: JSONObject.NULL)
                .put("supported_live_data", JSONArray(supported[module].orEmpty().map { "%02X %s".format(it, Pids.name(it)) }))
                .put("gear_pid_a4", if (g == null) JSONObject.NULL else JSONObject()
                    .put("raw", hex(g.drop(2).toIntArray()))
                    // Ratio per Wikipedia's PID table: (256*C + D) / 1000. Gear position encoding unconfirmed.
                    .put("gear_ratio", if (g.size >= 6) (g[4] * 256 + g[5]) / 1000.0 else JSONObject.NULL))
                .put("stored_codes", JSONArray(stored[module]?.let { Pids.troubleCodes(it) }.orEmpty()))
                .put("pending_codes", JSONArray(pending[module]?.let { Pids.troubleCodes(it) }.orEmpty())))
        }
        report.put("modules", all)
        return report
    }

    /** Lines for the log: one per computer. */
    fun summary(report: JSONObject): List<String> {
        val modules = report.optJSONObject("modules") ?: return emptyList()
        return modules.keys().asSequence().map { m ->
            val info = modules.getJSONObject(m)
            val name = if (info.isNull("name")) info.optString("usually") else info.optString("name")
            val codes = (0 until info.getJSONArray("stored_codes").length()).map { info.getJSONArray("stored_codes").getString(it) }
            "$m $name: ${info.getJSONArray("supported_live_data").length()} values, codes ${codes.joinToString(" ").ifEmpty { "none" }}"
        }.toList()
    }

    private fun text(data: IntArray, from: Int) =
        data.drop(from).map { it.toChar() }.joinToString("").trim('\u0000', ' ').trim().ifEmpty { null }

    private fun hex(d: IntArray) = d.joinToString(" ") { "%02X".format(it) }
}
