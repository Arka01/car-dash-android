package io.github.arka01.cardash.cars

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI

/** The engine computer's torque % mapped to real Nm through two known points (see engineOutput). */
class TorqueCalibration(val zeroPct: Double, val fullPct: Double, val fullNm: Double)

/**
 * One car's settings, from its car.json (same file format as the Python dashboard's
 * cars/<car>/car.json). A setting left out (or null) switches off what needs it: no gears ->
 * no gear display, no tank size -> no fuel-left estimate. Everything the car reports still shows.
 */
class CarProfile(
    val key: String,                   // folder name, e.g. "tata_tiago_2023"
    val name: String,
    val shortName: String,
    val brand: String?,
    val fuel: String,
    val protocol: String?,             // ELM327 protocol id, e.g. "6"; null = auto-detect
    val shiftRpm: Int,
    val redlineRpm: Int,
    val tankLitres: Double?,
    val engineLitres: Double?,
    val volumetricEfficiency: Double,
    val gearRpmPerKmh: List<Double>?,  // measured with GEARS (calibration)
    val gearRatios: List<Double>?,     // maker's specs, if known: a first guess before calibrating
    val finalDrive: Double?,
    val tyre: String?,
    val torqueCalibration: TorqueCalibration?,
    val smartAlternator: Boolean,
    val folder: File?,                 // null: nothing is saved (demo)
    val settings: JSONObject,          // car.json as loaded, to save it back unchanged
) {
    val recordings: File? get() = folder?.let { File(it, "recordings") }

    /** A file in this car's folder, or null for a car that saves nothing (demo). */
    fun file(name: String): File? = folder?.let { File(it, name) }

    /** RPM per km/h for each gear: measured if calibrated, else from the specs, else null. */
    val gears: List<Double>? get() = gearRpmPerKmh ?: theoreticalRpmPerKmh

    /** Engine rpm per 1 km/h in each gear in theory: wheel turns per minute at 1 km/h x gear ratio x final drive. */
    val theoreticalRpmPerKmh: List<Double>?
        get() {
            val circumference = tyreCircumferenceM(tyre)
            val ratios = gearRatios
            val drive = finalDrive
            if (ratios == null || drive == null || circumference == null) return null
            return ratios.map { it * drive * (1000.0 / 60) / circumference }
        }

    companion object {
        fun fromJson(key: String, json: JSONObject, folder: File?): CarProfile {
            val gears = json.optJSONObject("gears") ?: JSONObject()
            val torque = json.optJSONObject("torque_calibration")
            return CarProfile(
                key = key,
                name = json.str("name") ?: "My car",
                shortName = json.str("short_name") ?: "CAR",
                brand = json.str("brand"),
                fuel = json.str("fuel") ?: "petrol",
                protocol = json.str("protocol"),
                shiftRpm = json.num("shift_rpm")?.toInt() ?: 3500,
                redlineRpm = json.num("redline_rpm")?.toInt() ?: 4000,
                tankLitres = json.num("tank_litres"),
                engineLitres = json.num("engine_litres"),
                volumetricEfficiency = json.num("volumetric_efficiency") ?: 0.85,
                gearRpmPerKmh = gears.numbers("rpm_per_kmh"),
                gearRatios = gears.numbers("gear_ratios"),
                finalDrive = gears.num("final_drive"),
                tyre = gears.str("tyre"),
                torqueCalibration = torque?.let {
                    val zero = it.num("zero_pct")
                    val full = it.num("full_pct")
                    val nm = it.num("full_nm")
                    if (zero != null && full != null && nm != null && full != zero) TorqueCalibration(zero, full, nm) else null
                },
                smartAlternator = json.optBoolean("smart_alternator", false),
                folder = folder,
                settings = json,
            )
        }
    }
}

/** Rolling circumference from a tyre size like "175/60 R15": rim + 2 sidewalls. */
fun tyreCircumferenceM(tyre: String?): Double? {
    val match = Regex("""^\s*(\d+)\s*/\s*(\d+)\s*Z?R?\s*(\d+)""", RegexOption.IGNORE_CASE).find(tyre ?: "") ?: return null
    val (width, profile, rim) = match.destructured
    return PI * (rim.toInt() * 25.4 + 2 * width.toInt() * profile.toInt() / 100.0) / 1000
}

// JSON helpers: null for missing or JSON null (org.json's optString turns null into "null").
internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else opt(key)?.toString()
internal fun JSONObject.num(key: String): Double? = if (isNull(key)) null else (opt(key) as? Number)?.toDouble()
internal fun JSONObject.numbers(key: String): List<Double>? {
    if (isNull(key)) return null
    val array = optJSONArray(key) ?: return null
    return (0 until array.length()).mapNotNull { (array.opt(it) as? Number)?.toDouble() }.takeIf { it.isNotEmpty() }
}
internal fun List<Double>.toJson() = JSONArray().also { a -> forEach { a.put(it) } }
