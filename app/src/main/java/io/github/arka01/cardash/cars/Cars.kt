package io.github.arka01.cardash.cars

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The cars set up on this phone, one folder each (like the Python dashboard's cars/):
 *
 *   files/cars/
 *     tata_tiago_2023/
 *       car.json            settings (bundled with the app, then edited by GEARS / NEW CAR)
 *       vin.txt             the car's chassis number, learned at the first LIVE connect
 *       battery_state.json  start-dip history (battery monitor)
 *       fuel_state.json     fuel left, fill-ups, learned float curve (fuel tracker)
 *       probe.json          what the car supports (NEW CAR)
 *       recordings/         every LIVE drive, and PROBE scans
 *
 * Bundled cars (assets/cars) are copied once; a car.json on the phone is never overwritten.
 */
object Cars {
    const val DEFAULT = "tata_tiago_2023"
    private const val ASSETS = "cars"
    private const val TEMPLATE = "_template"
    private const val PREFS = "car"

    @Volatile private var rootDir: File? = null
    val root: File get() = rootDir ?: throw IllegalStateException("Cars.init() not called")

    /** Sets up the folders; safe to call many times. */
    @Synchronized
    fun init(context: Context) {
        if (rootDir != null) return
        val app = context.applicationContext
        val root = File(app.filesDir, "cars").apply { mkdirs() }
        for (key in app.assets.list(ASSETS).orEmpty()) {
            if (key.startsWith("_") || key.startsWith(".")) continue
            val target = File(root, "$key/car.json")
            if (target.exists()) continue
            target.parentFile?.mkdirs()
            app.assets.open("$ASSETS/$key/car.json").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        // Builds 5-6 recorded every drive into files/drives; those were all the Tiago's.
        val old = File(app.filesDir, "drives")
        val moved = File(root, "$DEFAULT/recordings").apply { mkdirs() }
        old.listFiles()?.forEach { it.renameTo(File(moved, it.name)) }
        old.delete()
        rootDir = root
    }

    /** Folder names of the cars set up so far (folders starting with _ are templates). */
    fun all(): List<String> = root.listFiles()
        .orEmpty()
        .filter { !it.name.startsWith("_") && !it.name.startsWith(".") && File(it, "car.json").exists() }
        .map { it.name }
        .sorted()

    fun load(key: String): CarProfile {
        val folder = File(root, key)
        return CarProfile.fromJson(key, JSONObject(File(folder, "car.json").readText()), folder)
    }

    /** A bundled car's settings that save nothing (the demo replays a Tiago drive). */
    fun bundled(context: Context, key: String): CarProfile =
        CarProfile.fromJson(key, JSONObject(readAsset(context, "$ASSETS/$key/car.json")), null)

    /** The car used last; else the Tiago; else the first one set up. */
    fun active(context: Context): CarProfile {
        val cars = all()
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("key", null)
        val key = saved?.takeIf { it in cars } ?: DEFAULT.takeIf { it in cars } ?: cars.first()
        return load(key)
    }

    fun setActive(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key", key).apply()
    }

    fun vin(car: CarProfile): String? = car.file("vin.txt")?.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    fun saveVin(car: CarProfile, vin: String) {
        car.file("vin.txt")?.writeText(vin + "\n")
    }

    /** The car folder whose saved VIN matches, or null. */
    fun matchVin(vin: String?): String? {
        if (vin == null) return null
        return all().firstOrNull { vin(load(it)) == vin }
    }

    /** Writes car.json back with new gear measurements, keeping everything else in it. */
    fun saveGears(car: CarProfile, rpmPerKmh: List<Double>) {
        val settings = JSONObject(car.settings.toString())
        val gears = settings.optJSONObject("gears") ?: JSONObject().also { settings.put("gears", it) }
        gears.put("rpm_per_kmh", rpmPerKmh.toJson())
        write(car.key, settings)
    }

    /**
     * A new car from the template (NEW CAR), like new_car_probe.py: returns its folder name.
     * [probe] is saved as probe.json, [vin] as vin.txt.
     */
    fun create(
        context: Context,
        title: String,
        maker: String?,
        fuel: String,
        protocol: String?,
        tank: Double?,
        engine: Double?,
        redline: Int,
        vin: String?,
        probe: JSONObject,
    ): String {
        val name = withMaker(title, maker)
        val key = freeFolder(folderName(name))
        val settings = JSONObject(readAsset(context, "$ASSETS/$TEMPLATE/car.json"))
        settings.put("name", name)
        settings.put("short_name", shortName(name, maker))
        settings.put("brand", maker ?: JSONObject.NULL)
        settings.put("fuel", fuel)
        settings.put("protocol", protocol ?: JSONObject.NULL)
        settings.put("tank_litres", tank ?: JSONObject.NULL)
        settings.put("engine_litres", engine ?: JSONObject.NULL)
        settings.put("redline_rpm", redline)
        settings.put("shift_rpm", redline - 500)
        write(key, settings)
        File(root, "$key/recordings").mkdirs()
        File(root, "$key/probe.json").writeText(probe.toString(2))
        if (vin != null) File(root, "$key/vin.txt").writeText(vin + "\n")
        return key
    }

    private fun write(key: String, settings: JSONObject) {
        val folder = File(root, key).apply { mkdirs() }
        val temp = File(folder, "car.json.tmp")
        temp.writeText(settings.toString(2) + "\n")
        temp.renameTo(File(folder, "car.json"))
    }

    private fun readAsset(context: Context, path: String) =
        context.assets.open(path).bufferedReader().use { it.readText() }

    // ---------------------------------------------------------------- names (new_car_probe.py)

    /** 'Tata Tiago 2023' -> 'tata_tiago_2023' (safe on every system). */
    fun folderName(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "my_car" }

    private fun freeFolder(name: String): String {
        var key = name
        var n = 2
        while (File(root, key).exists()) key = "${name}_${n++}"
        return key
    }

    private val GENERIC_WORDS = setOf(
        "petrol", "diesel", "cng", "lpg", "electric", "hybrid", "car", "my", "turbo", "amt", "manual", "automatic",
    )

    /** The model for the top bar: "Tata Tiago 1.2 petrol" -> "TIAGO", "Mahindra XUV700 2.2 diesel" -> "XUV700". */
    fun shortName(title: String, maker: String?): String {
        val skip = GENERIC_WORDS + (maker ?: "").replace("/", " ").split(" ").map { it.lowercase() }.filter { it.isNotEmpty() }
        val words = title.split(" ").map { it.trim('(', ')', '[', ']', ',') }.filter { w -> w.any { it.isLetter() } }
        val model = words.firstOrNull { it.lowercase() !in skip } ?: "CAR"
        return model.uppercase().take(10)
    }

    /** Puts the maker in front of the car's name if it isn't there yet ("XUV700 ..." -> "Mahindra XUV700 ..."). */
    fun withMaker(title: String, maker: String?): String {
        val first = maker?.split(" ")?.firstOrNull().orEmpty()
        return if (first.isNotEmpty() && !title.lowercase().contains(first.lowercase())) "$first $title" else title
    }

    /** The maker from the VIN's first 3 characters (World Manufacturer Identifier), or null. */
    fun makerFromVin(vin: String?): String? = MAKERS[(vin ?: "").take(3).uppercase()]

    // Common car makers only; from Wikibooks' WMI list (same table as the Python car_profile.py).
    private val MAKERS = mapOf(
        // India
        "MAT" to "Tata Motors", "MA3" to "Maruti Suzuki", "MAL" to "Hyundai", "MB2" to "Hyundai", "MA1" to "Mahindra",
        "MAB" to "Mahindra", "MAC" to "Mahindra", "MAK" to "Honda", "MBJ" to "Toyota", "MAJ" to "Ford", "MZB" to "Kia",
        "MZ7" to "MG Motor", "MEE" to "Renault", "MDH" to "Nissan", "MEX" to "Skoda / Volkswagen", "MCA" to "Fiat / Jeep",
        "MAH" to "Fiat", "MZZ" to "Citroen", "MBR" to "Mercedes-Benz", "MBH" to "Suzuki", "MC1" to "Force Motors",
        // Elsewhere
        "WVW" to "Volkswagen", "WVG" to "Volkswagen", "WBA" to "BMW", "WBS" to "BMW M", "WBX" to "BMW",
        "WDD" to "Mercedes-Benz", "WDB" to "Mercedes-Benz", "WDC" to "Mercedes-Benz", "W1K" to "Mercedes-Benz",
        "W1N" to "Mercedes-Benz", "WAU" to "Audi", "WA1" to "Audi", "WP0" to "Porsche", "WP1" to "Porsche",
        "TMB" to "Skoda", "VF1" to "Renault", "WF0" to "Ford", "JTD" to "Toyota", "JT2" to "Toyota", "JT3" to "Toyota",
        "JHM" to "Honda", "JS2" to "Suzuki", "JS3" to "Suzuki", "JN1" to "Nissan", "JN8" to "Nissan", "KMH" to "Hyundai",
        "KM8" to "Hyundai", "KNA" to "Kia", "KND" to "Kia", "LSJ" to "MG / SAIC",
    )
}
