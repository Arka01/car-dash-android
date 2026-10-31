package io.github.arka01.cardash

import android.content.Context
import io.github.arka01.cardash.cars.CarProfile
import io.github.arka01.cardash.cars.Cars
import io.github.arka01.cardash.logic.DriveReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** One drive in the DRIVES list: its recording and its report (null = being calculated / not possible). */
data class DriveItem(val csv: File, val report: DriveReport?, val failed: Boolean = false)

/**
 * The drive reports (logic/DriveReport) of the chosen car: calculated in the background at the
 * end of every LIVE drive, and for older recordings when the DRIVES screen opens. Each is saved
 * next to its drive as drive_<date>_<time>_report.json, so it's calculated only once.
 */
object DriveReports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val store = MutableStateFlow<List<DriveItem>>(emptyList())
    val items: StateFlow<List<DriveItem>> = store

    fun reportFile(csv: File) = File(csv.parentFile, csv.name.removeSuffix(".csv") + "_report.json")
    private fun profileFile(csv: File) = File(csv.parentFile, csv.name.removeSuffix(".csv") + "_profile.json")

    /** The chosen car's drives with their reports; calculates any that are missing (oldest first). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                Cars.init(app)
                val car = Cars.active(app)
                val drives = Dash.drives(app)   // newest first
                val recording = Dash.run?.takeIf { Dash.state.value.mode == Mode.LIVE }?.recorder?.csv
                val reports = HashMap<File, DriveReport?>()
                val failed = HashSet<File>()
                for (csv in drives) reports[csv] = load(csv)
                store.value = drives.map { DriveItem(it, reports[it]) }
                // Missing ones, oldest first, so each can be compared with the average of the drives before it.
                for (csv in drives.reversed()) {
                    if (reports[csv] != null || csv == recording) continue
                    val r = calculate(csv, car, drives.filter { it.name < csv.name }.mapNotNull { reports[it] })
                    if (r == null) failed += csv else reports[csv] = r
                    store.value = drives.map { DriveItem(it, reports[it], it in failed) }
                }
            }
        }
    }

    /** At the end of a LIVE drive: its report, then the list again. */
    fun driveEnded(context: Context, car: CarProfile, csv: File) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                if (!csv.exists()) return@withLock
                reportFile(csv).delete()   // a report of a part of this drive, if one was made meanwhile
                val earlier = Dash.drives(app).filter { it.name < csv.name }.mapNotNull { load(it) }
                calculate(csv, car, earlier)
            }
            if (Cars.active(app).key == car.key) refresh(app)
        }
    }

    private fun load(csv: File): DriveReport? = runCatching {
        reportFile(csv).takeIf { it.exists() }?.let { DriveReport.fromJson(JSONObject(it.readText())) }
    }.getOrNull()

    /** Calculates and saves one report; null if the recording has too little data. */
    private fun calculate(csv: File, car: CarProfile, earlier: List<DriveReport>): DriveReport? = runCatching {
        // This car's average consumption over earlier drives of 2 km or more (3 needed).
        val usable = earlier.filter { it.km >= 2 && it.litres != null }
        val average = if (usable.size >= 3) usable.sumOf { it.litres!! } / usable.sumOf { it.km } * 100 else null
        val profile = runCatching { JSONObject(profileFile(csv).readText()) }.getOrNull()
        DriveReport.calculate(csv, car, profile, average)?.also { reportFile(csv).writeText(it.toJson().toString(2)) }
    }.getOrNull()
}
