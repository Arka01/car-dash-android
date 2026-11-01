package io.github.arka01.cardash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arka01.cardash.BuildConfig
import io.github.arka01.cardash.CrashReport
import io.github.arka01.cardash.GearLearner
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.ai.AiSettings
import io.github.arka01.cardash.ai.LocalLlm
import io.github.arka01.cardash.cars.Cars
import io.github.arka01.cardash.logic.Rules
import org.json.JSONObject
import java.util.Locale

/*
 * Live details, Car, Settings and Support (build 22, 2026-11-01), arranged for a new customer:
 * what the car does now, this car's details (gears learned by themselves, fuel, limits), how the
 * app works (connection, voice, AI model, demo) and help when something goes wrong (logs).
 */

// ---------------------------------------------------------------- shared pieces

/** A list of cards with the usual spacing. */
@Composable
private fun CardList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().background(BG),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A card with a small title. */
@Composable
private fun Titled(title: String, border: Color = CARD_BORDER, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), border = border) {
        Txt(title, 13.sp, MUTED, FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/** "label ........ value": label left, value right (monospace numbers). */
@Composable
private fun KV(label: String, value: String?, color: Color = NEON, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 14.sp, modifier = Modifier.weight(1f))
        Txt(value ?: "--", 15.sp, if (value == null) FAINT else color, mono = mono)
    }
}

/** Buttons side by side, sharing the width. */
@Composable
private fun Buttons(vararg buttons: Triple<String, Color, () -> Unit>, active: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, color, action) in buttons) {
            Button(label, color, Modifier.weight(1f), filled = label == active, onClick = action)
        }
    }
}

private fun b(label: String, color: Color = NEON, action: () -> Unit) = Triple(label, color, action)

// ---------------------------------------------------------------- LIVE DETAILS

/**
 * The details under the Dash (scroll down; Live details was its own screen until build 23):
 * engine, temperature trends, fuel, battery, trip, then the raw values from the engine computer,
 * folded away. Dim while nothing runs. Facts about the car itself are in My car.
 */
@Composable
internal fun DashDetails(s: Snapshot, now: Long, actions: Actions) {
    var allOpen by rememberSaveable { mutableStateOf(false) }
    val d = s.derived
    val car = s.car
    val live = s.mode != Mode.OFF && d != null
    val on = if (live) NEON else FAINT
    fun calc(label: String, format: String): String? {
        val v = d?.values ?: return null
        if (v.isUnsupported(label)) return "n/a"
        return f(v[label], format)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Details", 13.sp, MUTED, FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        run {
            Titled("Engine") {
                val codes = s.troubleCodes
                KV("Fault codes", codes, if (codes == null || codes.startsWith("none")) on else WARN, mono = false)
                val e = d?.engine
                if (e != null) {
                    Txt(e.state, 17.sp, e.level.tone(), FontWeight.SemiBold)
                    Txt(e.detail, 13.sp)
                    Spacer(Modifier.height(4.dp))
                }
                KV("Torque", calc("Torque", "%.0f Nm"), on)
                KV("Power", calc("Power", "%.0f bhp"), on)
                KV("Peak torque", f(d?.peaks?.get("Torque"), "%.0f Nm"), on)
                KV("Peak power", f(d?.peaks?.get("Power"), "%.0f bhp"), on)
                val soak = d?.values?.get("Heat soak")
                KV("Heat soak", if (d?.values?.isUnsupported("Heat soak") == true) "n/a" else f(soak, "%+.0f °C"),
                    if (live) Rules.heatSoak(soak).tone() else FAINT)
                KV("Boost", calc("Boost", "%+.2f bar"), on)
            }
        }
        run {
            Titled("Temperature trends (per minute)") {
                val t = d?.trends.orEmpty()
                KV("Coolant", f(t["Coolant"], "%+.1f °C"), on)
                KV("Oil", f(t["Oil"], "%+.1f °C"), on)
                KV("Intake air", f(t["Intake air"], "%+.1f °C"), on)
                KV("Catalyst", f(t["Catalyst"], "%+.0f °C"), on)
            }
        }
        run {
            Titled("Fuel") {
                val fuel = d?.fuel
                if (fuel == null) {
                    Txt(if (car?.tankLitres == null) "Tank size not set for this car" else "Waiting for data", 14.sp, FAINT)
                } else {
                    val left = fuel.litres
                    KV("Left", left?.let { String.format(Locale.US, "%.1f L", it) + (fuel.sigma?.let { sigma -> String.format(Locale.US, " ±%.1f", sigma) } ?: "") },
                        if (left != null && left < 8) WARN else on)
                    KV("Range", f(fuel.rangeKm, "%.0f km"), on)
                    KV("Now", f(fuel.nowLPer100km, "%.1f L/100 km"), on)
                    KV("This trip", f(fuel.tripLPer100km, "%.1f L/100 km"), on)
                    KV("Average", f(fuel.consumptionLPer100km, "%.1f L/100 km"), on)
                    KV("Used this trip", f(fuel.usedTrip, "%.2f L"), on)
                    KV("Flow", f(fuel.flowLph, "%.2f L/h"), on)
                    fuel.flowSource?.let { Txt(it, 12.sp, FAINT) }
                    KV("Float sensor", f(fuel.sensorPct, "%.0f %%")?.let { it + " = " + (f(fuel.sensorLitres, "%.1f L") ?: "--") }, on)
                    KV("Fill-ups learned", fuel.fillUpsLearned.toString(), on)
                    for (note in fuel.notes) Txt(note, 13.sp, WARN)
                }
                Buttons(b("Log a fill-up", action = actions.refuel))
            }
        }
        run {
            Titled("Battery") {
                val bat = d?.battery
                if (bat == null) {
                    Txt("Waiting for data", 14.sp, FAINT)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Txt(bat.state.ifEmpty { "Battery" }, 17.sp, bat.level.tone(), FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Txt(String.format(Locale.US, "%.2f V", bat.volts), 17.sp, on, mono = true)
                    }
                    Txt("${bat.source}${if (bat.detail.isNotEmpty()) " · ${bat.detail}" else ""}", 13.sp)
                    Spacer(Modifier.height(4.dp))
                    KV("Engine computer", f(bat.ecuVolts, "%.2f V"), on)
                    KV("OBD port", f(bat.portVolts, "%.2f V"), on)
                    bat.crank?.let { c ->
                        KV("Start dip", String.format(Locale.US, "%.2f V", c.minVolts), Rules.of(c.level).tone())
                        Txt("${c.text} · ${c.samples} readings · ${c.time.replace('T', ' ')}", 12.sp, FAINT)
                    }
                    bat.sourcesNote?.let { Txt(it, 13.sp, WARN) }
                }
            }
        }
        run {
            Titled("Trip") {
                KV("Distance", calc("Trip distance", "%.1f km"), on)
                KV("Average speed", calc("Trip avg speed", "%.0f km/h"), on)
                KV("Acceleration", calc("Acceleration", "%+.2f g"), on)
                KV("RPM per km/h", calc("RPM per km/h", "%.1f"), on)
                KV("Best 0-60 km/h", calc("0-60 km/h best", "%.1f s"), on)
                KV("Best 0-100 km/h", calc("0-100 km/h best", "%.1f s"), on)
                KV("Top speed", f(d?.peaks?.get("Speed"), "%.0f km/h"), on)
                KV("Top RPM", f(d?.peaks?.get("RPM"), "%.0f"), on)
                val mismatch = d?.values?.get("Pedal sensor mismatch")
                KV("Pedal sensor check", when {
                    d?.values?.isUnsupported("Pedal sensor mismatch") == true -> "n/a"
                    mismatch == null -> "move the pedal fully once"
                    else -> String.format(Locale.US, "%.0f pts apart", mismatch)
                }, if (live) Rules.pedalMismatch(mismatch).tone() else FAINT, mono = mismatch != null)
                val mil = s.number(0x21)
                KV("Driven with check-engine", f(mil, "%.0f km"), if (live) Rules.drivenWithMil(mil).tone() else FAINT)
                KV("Since codes cleared", f(s.number(0x31), "%.0f km"), on)
            }
        }
        run {
            // The raw values as the engine computer sends them (by PID), folded away by default.
            val readings = s.readings.values.sortedBy { it.pid }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable { allOpen = !allOpen }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("All sensors (${readings.size})", 13.sp, MUTED, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Txt(if (allOpen) "▴" else "▾", 16.sp, MUTED)
                }
                if (allOpen) {
                    if (readings.isEmpty()) Txt("No values yet: start LIVE or the demo.", 14.sp, FAINT)
                    for (r in readings) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(r.name, 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                            Txt(r.text, 14.sp, if (now - r.time < STALE_MS) NEON else FAINT, mono = true, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- CAR

/**
 * This car: its details, the gears (learned by themselves from the drives, see GearLearner), fuel
 * learning and fill-ups, engine limits (editable) and a read-only scan of its computers.
 */
@Composable
internal fun CarScreen(s: Snapshot, actions: Actions) {
    val context = LocalContext.current
    val car = s.car
    val suggestion by GearLearner.suggestion.collectAsState()
    LaunchedEffect(car?.key) { GearLearner.check(context) }
    if (car == null) {
        CardList { item { Card(Modifier.fillMaxWidth()) { Txt("No car chosen: tap the car at the top of the sidebar.", 15.sp) } } }
        return
    }
    val vin = remember(car.key) { Cars.vin(car) }
    val measured = remember(car.key, car.gearRpmPerKmh) { Cars.measuredGears(car) }
    val fuel = remember(car.key, s.mode) { runCatching { car.file("fuel_state.json")?.takeIf { it.exists() }?.let { JSONObject(it.readText()) } }.getOrNull() }
    CardList {
        item {
            Titled("This car") {
                Txt(car.name, 18.sp, NEON, FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                KV("Fuel", car.fuel, NEON, mono = false)
                KV("VIN", vin ?: "learned at the first connection", if (vin == null) FAINT else NEON, mono = vin != null)
                if (car.smartAlternator) Txt("Smart alternator: low voltage while accelerating is normal for this car.", 12.sp, FAINT)
            }
        }
        suggestion?.takeIf { it.carKey == car.key }?.let { sg ->
            item {
                Titled("New gears learned", border = NEON) {
                    Txt("From your ${sg.drives} recorded drives. Save them so the gear display uses them:", 14.sp)
                    Spacer(Modifier.height(4.dp))
                    for (line in sg.lines) Txt(line, 14.sp, NEON, mono = true)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button("Save", NEON, Modifier.weight(1f), filled = true) { GearLearner.accept(context) }
                        Button("Not now", MUTED, Modifier.weight(1f)) { GearLearner.dismiss(context) }
                    }
                }
            }
        }
        item {
            Titled("Gears") {
                val gears = car.gearRpmPerKmh
                if (gears == null) {
                    Txt("Not known yet. Just drive: the app learns your gears by itself (about 20 seconds steady in each gear).", 14.sp)
                } else {
                    Txt("Learned from your drives. Engine rpm per km/h in each gear:", 13.sp)
                    Spacer(Modifier.height(4.dp))
                    gears.forEachIndexed { g, v ->
                        val m = measured?.getOrNull(g)
                        KV("${GearLearner.ordinal(g + 1)} gear", String.format(Locale.US, "%.1f", v) + when (m) {
                            true -> ""
                            false -> "  (estimated)"
                            null -> ""
                        }, if (m == false) MUTED else NEON)
                    }
                    Txt("Estimated gears become measured after some driving in them, e.g. 5th on a highway.", 12.sp, FAINT)
                }
            }
        }
        item {
            Titled("Fuel") {
                // Learned by itself from your fill-ups and drives (logic/FuelTracker), like the gears.
                val fills = fuel?.optJSONArray("fills")
                val fullFills = (0 until (fills?.length() ?: 0)).count { fills!!.optJSONObject(it)?.optBoolean("full") == true }
                val calibrated = (fuel?.optInt("rate_scale_learned", 0) ?: 0) > 0
                val sigma = fuel?.optDouble("variance", Double.NaN)?.takeIf { !it.isNaN() }?.let { kotlin.math.sqrt(it) }
                val status = when {
                    fuel == null || fills == null -> "learning: log your next fill-up"
                    !calibrated -> "learning: $fullFills of 2 full fills"
                    else -> "calibrated"
                }
                KV("Fuel accuracy", status + (sigma?.let { String.format(Locale.US, " · ±%.1f L", it) } ?: ""), if (calibrated) NEON else WARN, mono = false)
                Setting("Tank size (entered)", car.tankLitres?.let { String.format(Locale.US, "%.0f L", it) } ?: "not set") { actions.editCar("tank_litres") }
                val learnedTank = fuel?.optDouble("tank_learned", Double.NaN)?.takeIf { !it.isNaN() }
                val tankFills = fuel?.optInt("tank_samples", 0) ?: 0
                KV("Real tank size (learned)", learnedTank?.let { String.format(Locale.US, "%.1f L · %d fill%s", it, tankFills, if (tankFills == 1) "" else "s") } ?: "after a near-empty full fill")
                val km = fuel?.optDouble("total_km", 0.0) ?: 0.0
                val litres = fuel?.optDouble("total_litres", 0.0) ?: 0.0
                KV("Average consumption", if (km > 20) String.format(Locale.US, "%.1f L/100 km", litres / km * 100) else "after 20 km")
                KV("Gauge points learned", (fuel?.optJSONArray("float_curve")?.length() ?: 0).toString())
                Txt("Log every refuel (litres from the pump). Fill to the first automatic click when you fill up; don't top up after it. The rest is learned by itself.", 12.sp, FAINT)
                Buttons(b("Log a fill-up", action = actions.refuel))
                if (fills != null && fills.length() > 0) {
                    Spacer(Modifier.height(8.dp))
                    Txt("Fill-up log", 13.sp, MUTED, FontWeight.SemiBold)
                    for (i in fills.length() - 1 downTo maxOf(0, fills.length() - 10)) {
                        val fl = fills.optJSONObject(i) ?: continue
                        val line = buildString {
                            append(fl.optString("date").take(10))
                            if (!fl.isNull("litres")) append(String.format(Locale.US, " · %.1f L", fl.optDouble("litres")))
                            append(if (fl.optBoolean("full")) " full" else " partial")
                            if (fl.has("l_per_100km")) append(String.format(Locale.US, " · %.1f L/100", fl.optDouble("l_per_100km")))
                            if (fl.has("error")) append(String.format(Locale.US, " · app off by %.1f L", kotlin.math.abs(fl.optDouble("error"))))
                        }
                        Txt(line, 13.sp, NEON, mono = true)
                    }
                }
            }
        }
        item {
            Titled("Engine and limits") {
                Setting("Engine size", car.engineLitres?.let { String.format(Locale.US, "%.1f L", it) } ?: "not set") { actions.editCar("engine_litres") }
                Setting("Shift light from", "${car.shiftRpm} rpm") { actions.editCar("shift_rpm") }
                Setting("Red line (your limit)", "${car.redlineRpm} rpm") { actions.editCar("redline_rpm") }
            }
        }
        item {
            Titled("Car computers") {
                Txt("Reads (never changes) what each computer in the car supports and any stored fault codes. Needs the adapter and the ignition on.", 13.sp)
                Buttons(b("Scan car computers", if (s.mode == Mode.OFF) NEON else FAINT, actions.scanCar))
            }
        }
    }
}

// ---------------------------------------------------------------- SETTINGS

/** How the app works: connection, voice, the on-phone AI, demo, app version and updates. */
@Composable
internal fun SettingsScreen(s: Snapshot, adapterName: String?, voiceOn: Boolean, settings: AiSettings, actions: Actions) {
    val ai by LocalLlm.state.collectAsState()
    var advanced by rememberSaveable { mutableStateOf(false) }
    val hasModel = ai.modelName != null
    CardList {
        item {
            Titled("Connection") {
                KV("OBD adapter", adapterName ?: "not chosen", if (adapterName == null) WARN else NEON, mono = false)
                Txt("Works with Bluetooth OBD-II adapters (ELM327 type). Pair it in Android's Bluetooth settings first, then choose it here. With Android Auto, reading starts by itself when you open Car Dash on the car screen.", 12.sp, FAINT)
                Buttons(b("Choose adapter", action = actions.pickAdapter))
            }
        }
        item {
            Titled("Voice") {
                // Re-read when a voice setting changes (Speech.changes).
                val context = LocalContext.current
                val version by io.github.arka01.cardash.Speech.changes.collectAsState()
                val speech = io.github.arka01.cardash.Speech
                val voice = remember(version) { speech.label(speech.voiceName(context)) }
                val rate = remember(version) { speech.rate(context) }
                val pitch = remember(version) { speech.pitch(context) }
                Txt("Spoken drive summary after you switch off the engine, and warm-up tips.", 13.sp)
                Buttons(b(if (voiceOn) "Voice: on" else "Voice: off", if (voiceOn) NEON else FAINT, actions.voice), active = if (voiceOn) "Voice: on" else null)
                Spacer(Modifier.height(4.dp))
                Setting("Voice", voice) { actions.voiceSetting("voice") }
                Setting("Speed", String.format(Locale.US, "%.2g×", rate)) { actions.voiceSetting("speed") }
                Setting("Pitch", String.format(Locale.US, "%.2g", pitch)) { actions.voiceSetting("pitch") }
                Buttons(b("Play sample", action = { actions.voiceSetting("sample") }))
                Txt("Only voices that work offline are listed. More can be added in Android's settings: Text-to-speech → Install voice data.", 12.sp, FAINT)
            }
        }
        item {
            Titled("AI (on this phone)") {
                Txt("Writes your drive summaries and answers questions about a drive (in Drives). Runs on the phone; nothing is sent anywhere.", 13.sp)
                Spacer(Modifier.height(6.dp))
                Txt(ai.modelName ?: "No model yet", 16.sp, if (hasModel) NEON else WARN, FontWeight.SemiBold)
                if (hasModel) Txt(String.format(Locale.US, "%.2f GB", ai.modelBytes / 1e9), 13.sp, FAINT, mono = true)
                Txt(ai.status, 13.sp, if (ai.status.startsWith("failed") || ai.status.startsWith("import failed")) ALARM else MUTED)
                Buttons(
                    // With a model there, importing another one replaces it.
                    b(if (hasModel) "Replace model" else "Import model", if (ai.busy) FAINT else NEON, actions.aiImport),
                    b("Remove", if (hasModel && !ai.busy) WARN else FAINT, actions.aiRemove),
                )
                if (ai.busy) Buttons(b("Stop AI", WARN, actions.aiStop))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Advanced settings", 15.sp, MUTED, modifier = Modifier.weight(1f))
                    Txt(if (advanced) "▴" else "▾", 16.sp, MUTED)
                }
                if (advanced) {
                    Setting("Temperature", String.format(Locale.US, "%.2f", settings.temperature)) { actions.aiSetting("temperature") }
                    Setting("Top-K", settings.topK.toString()) { actions.aiSetting("topK") }
                    Setting("Top-P", String.format(Locale.US, "%.2f", settings.topP)) { actions.aiSetting("topP") }
                    Setting("Max answer", "${settings.maxTokens} tokens") { actions.aiSetting("maxTokens") }
                    Setting("Runs on", if (settings.gpu) "GPU" else "CPU") { actions.aiSetting("backend") }
                    Setting("System prompt", "Edit") { actions.aiSetting("system") }
                    Txt(settings.systemPrompt, 13.sp, FAINT)
                }
            }
        }
        item {
            Titled("Demo") {
                Txt("Plays a recorded drive so you can try every screen without a car. Nothing is saved.", 13.sp)
                if (s.mode == Mode.DEMO) Buttons(b("Stop demo", WARN, actions.stop))
                else Buttons(b("Try a demo drive", WARN, actions.demo))
            }
        }
        item {
            Titled("App") {
                KV("Version", BuildConfig.VERSION_NAME)
                Txt("Car Dash only reads data from the car; it never changes anything. Your data stays on this phone.", 12.sp, FAINT)
                Buttons(b("Check for updates", action = actions.update))
            }
        }
    }
}

// ---------------------------------------------------------------- SUPPORT

/** Help when something goes wrong: the connection's state, a crash report, and the log to share. */
@Composable
internal fun SupportScreen(s: Snapshot, adapterName: String?, actions: Actions) {
    val crash by CrashReport.report.collectAsState()
    CardList {
        item {
            Titled("Status") {
                KV("Connection", s.status, if (s.mode == Mode.LIVE) NEON else MUTED, mono = false)
                KV("Adapter", adapterName ?: "not chosen", NEON, mono = false)
                KV("Car protocol", s.protocol, NEON, mono = false)
                KV("Reading speed", if (s.rounds > 0) String.format(Locale.US, "%.1f per second", s.roundsPerSec) else null)
                KV("Fast reading", s.fastRead.ifEmpty { null }, NEON, mono = false)
            }
        }
        crash?.let { report ->
            item {
                Titled("The app closed unexpectedly", border = ALARM) {
                    Txt("Please share this report so it can be fixed.", 13.sp)
                    Spacer(Modifier.height(4.dp))
                    for (line in report.lines().take(10)) Txt(line, 12.sp, WARN, mono = true)
                    Buttons(b("Share report", ALARM, actions.shareCrash), b("Clear", MUTED, actions.clearCrash))
                }
            }
        }
        item {
            Titled("Connection log") {
                Txt("If something doesn't work, tap Share log and send it to the developer.", 13.sp)
                Buttons(b("Share log", action = actions.shareLog))
                Spacer(Modifier.height(6.dp))
                if (s.log.isEmpty()) Txt("Empty: the log fills when the app connects.", 13.sp, FAINT)
                for (line in s.log.reversed()) Txt(line, 12.sp, MUTED, mono = true)
            }
        }
    }
}

/** One setting: label left, value right in green, the whole row tappable. */
@Composable
private fun Setting(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 15.sp, modifier = Modifier.weight(1f))
        Txt(value, 15.sp, NEON, mono = true)
        Txt("  ›", 15.sp, FAINT)
    }
}
