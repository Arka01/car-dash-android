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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arka01.cardash.CrashReport
import io.github.arka01.cardash.KeyMarks
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.ai.AiSettings
import io.github.arka01.cardash.ai.DriveSummary
import io.github.arka01.cardash.ai.LocalLlm
import io.github.arka01.cardash.logic.Rules
import java.util.Locale

/*
 * Info, All sensors, AI and Dev tools in the modern look (build 20): same contents and buttons as
 * their terminal versions, as cards. The grouping of the screens is to be reconsidered later.
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

// ---------------------------------------------------------------- INFO

/**
 * Everything the app calculates (the Python dashboard's values): car, engine, temperature trends,
 * fuel, battery and trip. Values are dim while nothing is running.
 */
@Composable
internal fun InfoScreen(s: Snapshot) {
    val d = s.derived
    val car = s.car
    val live = s.mode != Mode.OFF && d != null
    val on = if (live) NEON else FAINT
    fun calc(label: String, format: String): String? {
        val v = d?.values ?: return null
        if (v.isUnsupported(label)) return "n/a"
        return f(v[label], format)
    }
    CardList {
        item {
            Titled("Car") {
                Txt(car?.name ?: "No car chosen", 17.sp, NEON, FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                KV("VIN", s.vin, on)
                val codes = s.troubleCodes
                KV("Trouble codes", codes, if (codes == null || codes.startsWith("none")) on else WARN, mono = false)
                KV("Gears (rpm per km/h)", car?.gears?.joinToString(" ") { String.format(Locale.US, "%.0f", it) } ?: "not known yet", NEON)
                s.protocol?.let { Txt(it, 12.sp, FAINT) }
            }
        }
        item {
            Titled("Engine") {
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
        item {
            Titled("Temperature trends (per minute)") {
                val t = d?.trends.orEmpty()
                KV("Coolant", f(t["Coolant"], "%+.1f °C"), on)
                KV("Oil", f(t["Oil"], "%+.1f °C"), on)
                KV("Intake air", f(t["Intake air"], "%+.1f °C"), on)
                KV("Catalyst", f(t["Catalyst"], "%+.0f °C"), on)
            }
        }
        item {
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
            }
        }
        item {
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
        item {
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
    }
}

// ---------------------------------------------------------------- ALL SENSORS

/** Every value the engine computer reports, by PID; dim when older than 10 s. */
@Composable
internal fun AllScreen(s: Snapshot, now: Long) {
    val readings = s.readings.values.sortedBy { it.pid }
    CardList {
        item { Txt("Engine computer · ${readings.size} values", 14.sp, modifier = Modifier.padding(start = 6.dp)) }
        if (readings.isEmpty()) item { Card(Modifier.fillMaxWidth()) { Txt("No values yet: start LIVE or DEMO.", 15.sp) } }
        items(readings) { r ->
            val fresh = now - r.time < STALE_MS
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("%02X".format(r.pid), 13.sp, FAINT, mono = true, modifier = Modifier.width(30.dp))
                    Txt(r.name, 14.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    Txt(r.text, 15.sp, if (fresh) NEON else FAINT, mono = true, maxLines = 2)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- AI

/** The on-phone language model: last crash (if any), the model file, settings, and asking. */
@Composable
internal fun AiScreen(settings: AiSettings, actions: Actions) {
    val ai by LocalLlm.state.collectAsState()
    val crash by CrashReport.report.collectAsState()
    val hasModel = ai.modelName != null
    CardList {
        crash?.let { report ->
            item {
                Titled("Last crash", border = ALARM) {
                    for (line in report.lines().take(14)) Txt(line, 12.sp, WARN, mono = true)
                    Txt("Full report: Share crash", 12.sp, FAINT)
                    Buttons(b("Share crash", ALARM, actions.shareCrash), b("Clear", MUTED, actions.clearCrash))
                }
            }
        }
        item {
            Titled("Model") {
                Txt(ai.modelName ?: "None imported", 16.sp, if (hasModel) NEON else WARN, FontWeight.SemiBold)
                if (hasModel) Txt(String.format(Locale.US, "%.2f GB", ai.modelBytes / 1e9), 13.sp, FAINT, mono = true)
                Txt(ai.status, 13.sp, if (ai.status.startsWith("failed") || ai.status.startsWith("import failed")) ALARM else MUTED)
                Buttons(
                    b("Import model", if (ai.busy) FAINT else NEON, actions.aiImport),
                    b("Remove", if (hasModel && !ai.busy) WARN else FAINT, actions.aiRemove),
                )
            }
        }
        item {
            Titled("Settings (tap to change)") {
                Setting("Temperature", String.format(Locale.US, "%.2f", settings.temperature)) { actions.aiSetting("temperature") }
                Setting("Top-K", settings.topK.toString()) { actions.aiSetting("topK") }
                Setting("Top-P", String.format(Locale.US, "%.2f", settings.topP)) { actions.aiSetting("topP") }
                Setting("Max answer", "${settings.maxTokens} tokens") { actions.aiSetting("maxTokens") }
                Setting("Runs on", if (settings.gpu) "GPU" else "CPU") { actions.aiSetting("backend") }
                Setting("System prompt", "Edit") { actions.aiSetting("system") }
                Txt(settings.systemPrompt, 13.sp, FAINT)
            }
        }
        item {
            Titled("Ask") {
                if (ai.busy) {
                    Buttons(b("Stop", WARN, actions.aiStop))
                } else {
                    Buttons(b("Ask", if (hasModel) NEON else FAINT, actions.aiAsk), b("Test drive", if (hasModel) NEON else FAINT, actions.aiTest))
                }
                if (ai.question.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Txt("> " + ai.question.lineSequence().first().take(120), 13.sp, FAINT)
                }
                if (ai.answer.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Txt(DriveSummary.plain(ai.answer), 15.sp, NEON)
                }
                if (ai.stats.isNotEmpty()) Txt(ai.stats, 12.sp, FAINT)
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

// ---------------------------------------------------------------- DEV TOOLS

/** Car tools, everyday tools, adapter, demo / replay, the KEY TEST and the connection log. */
@Composable
internal fun DevScreen(s: Snapshot, adapterName: String?, voiceOn: Boolean, actions: Actions) {
    val test = s.test
    val testing = s.mode == Mode.TEST
    val idle = s.mode == Mode.OFF
    CardList {
        item {
            Titled("Car") {
                Txt(s.car?.name ?: "No car chosen", 16.sp, NEON, FontWeight.SemiBold)
                s.car?.let { Txt(it.key, 12.sp, FAINT, mono = true) }
                Buttons(b("Choose car", action = actions.pickCar), b("Gears", action = actions.gears))
                Buttons(b("New car", if (idle) NEON else FAINT, actions.newCar), b("Probe", if (idle) NEON else FAINT, actions.probe))
            }
        }
        item {
            Titled("Everyday") {
                Buttons(b("Refuel", action = actions.refuel), b(if (voiceOn) "Voice: on" else "Voice: off", if (voiceOn) NEON else FAINT, actions.voice))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(if (s.mode == Mode.LIVE) "● Recording" else "Every LIVE run is recorded", 14.sp, if (s.mode == Mode.LIVE) ALARM else MUTED, modifier = Modifier.weight(1f))
                }
                Txt(s.recording?.let { "$it · ${s.recordedRows} rows" } ?: "Share drive sends this car's newest drive", 12.sp, FAINT)
                Buttons(b("Share drive", action = actions.shareDrive))
            }
        }
        item {
            Titled("Adapter") {
                KV("Bluetooth adapter", adapterName ?: "not chosen", if (adapterName == null) WARN else NEON, mono = false)
                Buttons(b("Choose adapter", action = actions.pickAdapter))
            }
        }
        item {
            Titled("Demo and replay") {
                Txt("Demo plays a recorded Tiago drive; Replay plays one of this car's drives. Nothing is saved.", 13.sp)
                Buttons(b("Demo", WARN, actions.demo), b("Replay", WARN, actions.replay),
                    active = when (s.mode) { Mode.DEMO -> "Demo"; Mode.REPLAY -> "Replay"; else -> null })
            }
        }
        item {
            Titled("Key test") {
                Txt(test?.let { "${it.file} · ${it.rows} rows" } ?: "With LIVE / DEMO off: Start, then tap the key position at every turn.", 13.sp)
                if (testing) Buttons(b("Stop test", WARN, actions.stop), b("Share test", action = actions.shareTest))
                else Buttons(b("Start test", action = actions.keyTest), b("Share test", action = actions.shareTest))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (position in KeyMarks.POSITIONS) {
                        Button(position, WARN, Modifier.weight(1f), filled = testing && test?.label == position) { actions.markKey(position) }
                    }
                }
                if (test != null) {
                    Spacer(Modifier.height(6.dp))
                    Txt("   t label     volt ecu  net", 12.sp, FAINT, mono = true)
                    for (line in test.recent.reversed()) Txt(line, 12.sp, NEON, mono = true)
                }
            }
        }
        item {
            Titled("Connection log") {
                Txt("Fast read: ${s.fastRead.ifEmpty { "-" }}", 13.sp)
                Buttons(b("Share log", action = actions.share))
                Spacer(Modifier.height(6.dp))
                for (line in s.log.reversed()) Txt(line, 12.sp, MUTED, mono = true)
            }
        }
    }
}
