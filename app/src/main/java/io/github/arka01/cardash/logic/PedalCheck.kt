package io.github.arka01.cardash.logic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Cross-checks the two accelerator pedal sensors (PIDs 49 / 4A), like the engine computer does.
 *
 * The sensors deliberately don't read the same numbers (different ranges, so one wiring fault
 * can't fool both). Each is rescaled by its range (pedal released → pressed fully), and the two rescaled
 * positions must agree. A mismatch has to last [HOLD] seconds, because the two are read a few
 * ms apart and can briefly differ while the pedal is moving. Also stands in for the relative
 * pedal reading (PID 5A) if that ever stops answering.
 */
 *
 * The ranges are remembered per car in [stateFile] (pedal_ranges.json in the car's folder), so the
 * owner presses the pedal fully once ever, not once per drive (2026-11-01: it kept asking). They only
 * grow, if a later press goes further. Null file (demo, replay): learned for this run only.
 */
class PedalCheck(private val stateFile: File? = null) {
    private val ranges = mutableMapOf("D" to doubleArrayOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY),
        "E" to doubleArrayOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
    private val saved = HashMap<String, DoubleArray>()   // what's in the file, to save only real changes

    init {
        runCatching {
            val j = JSONObject(stateFile?.takeIf { it.exists() }?.readText() ?: return@runCatching)
            for (key in listOf("D", "E")) {
                val a = j.optJSONArray(key) ?: continue
                ranges[key] = doubleArrayOf(a.getDouble(0), a.getDouble(1))
                saved[key] = doubleArrayOf(a.getDouble(0), a.getDouble(1))
            }
        }
    }
    private var mismatchSince: Double? = null
    private var reported: Double? = null   // mismatch currently shown (points)
    private var lastPair: Pair<Double, Double>? = null

    fun update(v: Values, now: Double, out: Values) {
        if (v.isUnsupported("Pedal sensor D") || v.isUnsupported("Pedal sensor E")) {
            return out.markUnsupported("Pedal sensor mismatch")
        }
        val d = v["Pedal sensor D"]
        val e = v["Pedal sensor E"]
        out["Pedal sensor mismatch"] = null
        if (d == null || e == null) return
        for ((key, value) in listOf("D" to d, "E" to e)) {
            val range = ranges.getValue(key)
            range[0] = minOf(range[0], value)
            range[1] = maxOf(range[1], value)
        }
        saveIfGrown()
        val posD = position("D", d)
        val posE = position("E", e)
        if (v["Accelerator pedal"] == null && posD != null) out["Accelerator pedal"] = posD   // 5A not answering
        if (posD == null || posE == null || (d to e) == lastPair) {
            out["Pedal sensor mismatch"] = held(null, now)
            return
        }
        lastPair = d to e
        out["Pedal sensor mismatch"] = held(abs(posD - posE), now)
    }

    /** True once both sensors' ranges are known (one full press), so nothing extra needs reading. */
    val rangesKnown: Boolean
        get() = listOf("D", "E").all { ranges.getValue(it).let { r -> r[1] - r[0] >= MIN_TRAVEL } }

    /** Saves the ranges when both are usable and one grew by more than 0.3 points. */
    private fun saveIfGrown() {
        val file = stateFile ?: return
        if (listOf("D", "E").any { ranges.getValue(it).let { r -> r[1] - r[0] < MIN_TRAVEL } }) return
        val grown = listOf("D", "E").any { key ->
            val r = ranges.getValue(key)
            val old = saved[key]
            old == null || old[0] - r[0] > 0.3 || r[1] - old[1] > 0.3
        }
        if (!grown) return
        runCatching {
            val j = JSONObject()
            for (key in listOf("D", "E")) {
                val r = ranges.getValue(key)
                j.put(key, JSONArray().put(r[0]).put(r[1]))
                saved[key] = doubleArrayOf(r[0], r[1])
            }
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(j.toString())
            temp.renameTo(file)
        }
    }

    private fun position(key: String, value: Double): Double? {
        val (low, high) = ranges.getValue(key).let { it[0] to it[1] }
        return if (high - low >= MIN_TRAVEL) (value - low) / (high - low) * 100 else null
    }

    /** Reports the mismatch only once it has lasted HOLD seconds; until then shows it as agreeing. */
    private fun held(mismatch: Double?, now: Double): Double? {
        if (mismatch == null) return reported
        if (mismatch > 10) {
            val since = mismatchSince ?: now.also { mismatchSince = it }
            reported = if (now - since >= HOLD) mismatch else minOf(mismatch, 10.0)
        } else {
            mismatchSince = null
            reported = mismatch
        }
        return reported
    }

    private companion object {
        const val MIN_TRAVEL = 20.0   // percent a sensor must have moved before its range means anything
        const val HOLD = 1.5          // seconds a mismatch must last to count
    }
}
