package io.github.arka01.cardash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arka01.cardash.BuildConfig
import io.github.arka01.cardash.CarState
import io.github.arka01.cardash.DriveItem
import io.github.arka01.cardash.DriveReports
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.ai.AiState
import io.github.arka01.cardash.ai.DriveSummary
import io.github.arka01.cardash.ai.LocalLlm
import io.github.arka01.cardash.driveLabel
import io.github.arka01.cardash.logic.Calculations
import io.github.arka01.cardash.logic.DriveReport
import io.github.arka01.cardash.logic.DriveTotals
import io.github.arka01.cardash.logic.TimeFormat
import io.github.arka01.cardash.logic.Level
import io.github.arka01.cardash.logic.Rules
import io.github.arka01.cardash.logic.Values
import io.github.arka01.cardash.logic.Verdict
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The modern look (since build 19, 2026-10-31): cards, readable sans-serif labels, real buttons,
 * in the terminal colours (green on black, amber / red for warnings). Big numbers stay monospace
 * with a subtle green glow. Dash and Drives first; the other screens keep the terminal look for now.
 */

internal val BG = Color.Black
internal val CARD = Color(0xFF0A130C)          // just lighter than black, so cards stand out
internal val CARD_BORDER = Color(0x5533FF66)   // terminal green, faint
internal val NEON = Color(0xFF33FF66)          // the terminal green
internal val MUTED = Color(0xFF8CC79D)         // labels
internal val FAINT = Color(0xFF4C7A58)         // less important text, old values
internal val WARN = Color(0xFFFFB000)
internal val ALARM = Color(0xFFFF4D4D)
internal const val STALE_MS = 10_000

internal fun Level.tone() = when (this) {
    Level.OK -> NEON
    Level.WARN -> WARN
    Level.ALARM -> ALARM
}

internal fun Verdict.tone() = when (this) {
    Verdict.OK, Verdict.INFO -> NEON
    Verdict.WATCH -> WARN
    Verdict.CHECK -> ALARM
}

// ---------------------------------------------------------------- building blocks

/** Text: sans-serif labels, or monospace numbers ([mono]), optionally with the green [glow]. */
@Composable
internal fun Txt(
    text: String,
    size: TextUnit,
    color: Color = MUTED,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    glow: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
            shadow = if (glow) Shadow(color.copy(alpha = 0.6f), Offset.Zero, blurRadius = 16f) else null,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A card: rounded, slightly lighter than the background, thin green (or warning-coloured) border. */
@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    border: Color = CARD_BORDER,
    fill: Color = CARD,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(fill).border(1.dp, border, shape).padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

/** A button: green outline, rounded; [filled] = selected / active. */
@Composable
internal fun Button(label: String, color: Color = NEON, modifier: Modifier = Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(if (filled) color.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, color, shape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Txt(label, 15.sp, color, FontWeight.Medium, maxLines = 1)
    }
}

/** A small pill with a coloured outline, e.g. "● LIVE" or "▲ 2 watch". */
@Composable
internal fun Chip(text: String, color: Color) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).border(1.dp, color.copy(alpha = 0.7f), shape).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Txt(text, 13.sp, color, FontWeight.Medium)
    }
}

internal fun f(x: Double?, format: String) = x?.let { String.format(Locale.US, format, it) }

/** A reading if it's fresh (newer than [STALE_MS]), else null. */
internal fun Snapshot.fresh(pid: Int, now: Long): Double? {
    val r = readings[pid] ?: return null
    return r.number?.takeIf { now - r.time < STALE_MS }
}

// ---------------------------------------------------------------- top bar

/** ☰, the screen's name, the mode (LIVE / DEMO / …) and the clock; below: car state, car, reading rate. */
@Composable
internal fun TopBar(s: Snapshot, now: Long, title: String, openMenu: () -> Unit) {
    val (mode, modeColor) = when (s.mode) {
        Mode.LIVE -> "● LIVE" to NEON
        Mode.DEMO -> "◆ DEMO" to WARN
        Mode.REPLAY -> "◆ REPLAY" to WARN
        Mode.TEST -> "◇ TEST" to WARN
        Mode.PROBE -> "◇ SCAN" to WARN
        Mode.OFF -> "○ OFF" to FAINT
    }
    Column(Modifier.fillMaxWidth().background(BG).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = openMenu), verticalAlignment = Alignment.CenterVertically) {
                Txt("  ☰", 22.sp, NEON)
            }
            Txt(title, 20.sp, NEON, FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Chip(mode, modeColor)
            Spacer(Modifier.width(10.dp))
            Txt(TimeFormat.clock(now), 15.sp, MUTED, mono = true)
        }
        val stateColor = when (s.carState) {
            CarState.ENGINE_ON -> NEON
            CarState.STARTING, CarState.STOPPING, CarState.IGNITION_ON -> WARN
            CarState.IGNITION_OFF, CarState.UNKNOWN -> FAINT
        }
        val details = buildList {
            s.car?.let { add(it.shortName) }
            if (s.mode == Mode.LIVE && s.recording != null) add("REC")
            if (s.rounds > 0) add(String.format(Locale.US, "%.1f/s", s.roundsPerSec))
        }.joinToString(" · ")
        Row(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp)) {
            Txt(s.status, 13.sp, if (s.mode == Mode.LIVE) stateColor else FAINT, maxLines = 1)
            if (details.isNotEmpty()) Txt("  ·  $details", 13.sp, FAINT, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- DASH

/** One value card on the Dash. */
private class ValueSpec(
    val label: String, val value: String?, val unit: String, val sub: String?, val color: Color,
    val onClick: (() -> Unit)? = null,   // e.g. Fuel left: log a fill-up
)

/**
 * The Dash: a main card (speed, gear, RPM with its bar), one alert card, value cards two per row,
 * then (scroll down) the details (Screens.kt). Landscape: main + alerts left, the rest right.
 */
@Composable
internal fun DashScreen(s: Snapshot, now: Long, actions: Actions) {
    BoxWithConstraints(Modifier.fillMaxSize().background(BG)) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MainCard(s, now)
                    AlertCard(s)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ValueGrid(s, now, actions)
                    DashDetails(s, now, actions)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MainCard(s, now)
                AlertCard(s)
                ValueGrid(s, now, actions)
                DashDetails(s, now, actions)   // scroll down: engine, fuel, battery, trip, all sensors
            }
        }
    }
}

@Composable
private fun MainCard(s: Snapshot, now: Long) {
    val car = s.car
    val speed = s.fresh(0x0D, now)
    val rpm = s.fresh(0x0C, now)
    val limit = s.derived?.warmUp?.rpmLimit   // lower limit while the engine is cold
    val rpmLevel = if (car == null || rpm == null) Level.OK else Rules.rpm(rpm, car, limit)
    val gear = if (car == null) null else Calculations.gear(car, speed, rpm)
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Txt(speed?.roundToInt()?.toString() ?: "--", 80.sp, if (speed == null) FAINT else NEON, FontWeight.Bold, mono = true, glow = speed != null, maxLines = 1)
                Txt("km/h", 14.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                // "--" without fresh speed and RPM: "~" would mean "between gears".
                Txt(if (speed == null || rpm == null) "--" else Calculations.gearName(gear), 56.sp, if (speed == null) FAINT else NEON, FontWeight.Bold, mono = true, glow = speed != null, maxLines = 1)
                Txt("gear", 14.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Txt(rpm?.roundToInt()?.toString() ?: "--", 40.sp, if (rpm == null) FAINT else rpmLevel.tone(), FontWeight.Bold, mono = true, glow = rpm != null, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Txt("rpm", 14.sp, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            if (limit != null) Txt("cold: max $limit", 13.sp, WARN, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
        RpmBar(rpm, car?.let { Rules.rpmScale(it) } ?: 5000.0, car?.shiftRpm?.toDouble(), car?.redlineRpm?.toDouble(), limit?.toDouble())
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Small("Pedal", f(Values.pidFor("Accelerator pedal", s.supported)?.let { s.fresh(it, now) }, "%.0f %%"))
            Small("Throttle", f(Values.pidFor("Throttle", s.supported)?.let { s.fresh(it, now) }, "%.0f %%"))
            Small("Load", f(s.fresh(0x04, now), "%.0f %%"))
        }
    }
}

@Composable
private fun Small(label: String, value: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Txt("$label ", 13.sp)
        Txt(value ?: "--", 14.sp, if (value == null) FAINT else NEON, mono = true)
    }
}

/** The RPM bar: segments, green up to the shift point, amber to the redline (or the cold limit), red above. */
@Composable
private fun RpmBar(rpm: Double?, scale: Double, shift: Double?, redline: Double?, coldLimit: Double?) {
    val segments = 40
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val w = size.width / segments
        for (i in 0 until segments) {
            val at = (i + 0.5) / segments * scale
            val color = when {
                redline != null && at > redline -> ALARM
                coldLimit != null && at > coldLimit -> ALARM
                shift != null && at > shift -> WARN
                coldLimit != null && at > coldLimit - 500 -> WARN
                else -> NEON
            }
            val lit = rpm != null && at <= rpm
            drawRoundRect(
                color = if (lit) color else color.copy(alpha = 0.12f),
                topLeft = Offset(i * w + w * 0.12f, 0f),
                size = Size(w * 0.76f, size.height),
                cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
}

/** The most important warnings (logic/Pipeline alerts), or "All normal". */
@Composable
private fun AlertCard(s: Snapshot) {
    val alerts = s.derived?.alerts.orEmpty()
    if (alerts.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Txt(if (s.derived == null) "Waiting for data" else "✓  All normal", 15.sp, if (s.derived == null) FAINT else NEON)
        }
        return
    }
    val worst = if (alerts.any { it.level == Level.ALARM }) Level.ALARM else Level.WARN
    Card(Modifier.fillMaxWidth(), border = worst.tone(), fill = worst.tone().copy(alpha = 0.08f)) {
        for (a in alerts.take(4)) Txt("▲  ${a.text}", 16.sp, a.level.tone(), FontWeight.SemiBold)
        if (alerts.size > 4) Txt("+${alerts.size - 4} more", 13.sp, MUTED)
        // Low fuel is the moment to ask for the fill-up that teaches the app this car's tank.
        if (s.derived?.fuel?.fillTip == true && alerts.any { it.text.startsWith("FUEL") }) {
            Txt("At the pump: fill until it clicks, then tap Fuel left and log the litres. That teaches Car Dash your tank.", 13.sp, MUTED)
        }
    }
}

@Composable
private fun ValueGrid(s: Snapshot, now: Long, actions: Actions) {
    val d = s.derived
    val v = d?.values
    val trend = { label: String -> d?.trends?.get(label)?.let { String.format(Locale.US, "%+.1f °C/min", it) } }
    fun temp(label: String, pid: Int, level: (Double) -> Level, sub: String?): ValueSpec {
        val x = s.fresh(pid, now)
        return ValueSpec(label, f(x, "%.0f"), "°C", sub, if (x == null) FAINT else level(x).tone())
    }
    val warm = d?.warmUp?.takeIf { it.state != "READY TO PUSH" }
    val battery = d?.battery
    val fuel = d?.fuel
    val litres = fuel?.litres
    val specs = listOf(
        temp("Coolant", 0x05, { Rules.coolant(it) }, warm?.let { it.state.lowercase() } ?: trend("Coolant")),
        temp("Oil", 0x5C, { Rules.oil(it) }, trend("Oil")),
        temp("Intake air", 0x0F, { Rules.intake(it) }, trend("Intake air")),
        if (battery != null) {
            ValueSpec("Battery", f(battery.volts, "%.1f"), "V", battery.state.lowercase().ifEmpty { null }, battery.level.tone())
        } else {
            ValueSpec("Battery", f(s.fresh(0x42, now), "%.1f"), "V", null, NEON)
        },
        ValueSpec("Fuel left", f(litres, "%.1f"), "L", fuel?.rangeKm?.let { String.format(Locale.US, "%.0f km range", it) } ?: "tap after filling up",
            when {
                litres == null -> FAINT
                litres < 4 -> ALARM
                litres < 8 -> WARN
                else -> NEON
            }, onClick = actions.refuel),
        ValueSpec("Consumption", f(fuel?.nowLPer100km, "%.1f"), "L/100", fuel?.tripLPer100km?.let { String.format(Locale.US, "trip %.1f", it) }, NEON),
        ValueSpec("Torque", f(v?.get("Torque"), "%.0f"), "Nm", f(v?.get("Power"), "%.0f bhp"), NEON),
        ValueSpec("Trip", f(v?.get("Trip distance"), "%.1f"), "km", f(v?.get("Trip avg speed"), "avg %.0f km/h"), NEON),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (pair in specs.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (spec in pair) ValueCard(spec, Modifier.weight(1f))
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ValueCard(spec: ValueSpec, modifier: Modifier) {
    Card(if (spec.onClick != null) modifier.clickable(onClick = spec.onClick) else modifier) {
        Txt(spec.label, 13.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Txt(spec.value ?: "--", 30.sp, if (spec.value == null) FAINT else spec.color, FontWeight.Bold, mono = true, glow = spec.value != null, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Txt(spec.unit, 13.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        Txt(spec.sub ?: " ", 12.sp, FAINT, maxLines = 1)
    }
}

// ---------------------------------------------------------------- DRIVES

/**
 * The chosen car's drives, newest first, each as a card with its worst verdict; tap for the report.
 * Select mode (Ask AI, or long-press a card; owner's design 2026-11-04): tap cards to choose up to
 * [DriveTotals.MAX_DRIVES], then Ask → asks the AI about them together.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)   // combinedClickable, on older Compose
@Composable
internal fun DrivesScreen(s: Snapshot, actions: Actions) {
    val context = LocalContext.current
    val drives by DriveReports.items.collectAsState()
    val changes by DriveSummary.changes.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }   // the drive shown, by file name
    var selecting by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf("") }            // chosen drives' file names, "|"-separated
    var asking by rememberSaveable { mutableStateOf(false) }         // the Ask screen for the chosen drives
    LaunchedEffect(s.car?.key) { DriveReports.refresh(context) }
    val chosen = picked.split("|").filter { it.isNotEmpty() }
    BackHandler(enabled = selecting && open == null) {
        if (asking) asking = false else { selecting = false; picked = "" }
    }
    BackHandler(enabled = open != null) { open = null }

    val shown = drives.firstOrNull { it.csv.name == open }
    if (shown != null) {
        DriveReportScreen(shown, actions) { open = null }
        return
    }
    if (asking) {
        SeveralDrivesScreen(drives.filter { it.csv.name in chosen && it.report != null }, actions) { asking = false }
        return
    }
    fun toggle(name: String) {
        picked = when {
            name in chosen -> chosen - name
            chosen.size >= DriveTotals.MAX_DRIVES -> {
                android.widget.Toast.makeText(context, "Up to ${DriveTotals.MAX_DRIVES} drives", android.widget.Toast.LENGTH_SHORT).show()
                chosen
            }
            else -> chosen + name
        }.joinToString("|")
    }
    Column(Modifier.fillMaxSize().background(BG)) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                if (!selecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(s.car?.name ?: "", 14.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        if (drives.any { it.report != null }) Button("Ask AI", NEON) { selecting = true }
                    }
                } else {
                    Column(Modifier.padding(horizontal = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt("${chosen.size} selected", 18.sp, NEON, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Button("✕", MUTED) { selecting = false; picked = "" }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button("Last ${DriveTotals.MAX_DRIVES}", NEON) {
                                picked = drives.filter { it.report != null }.take(DriveTotals.MAX_DRIVES).joinToString("|") { it.csv.name }
                            }
                            Button("Clear", MUTED) { picked = "" }
                        }
                        Spacer(Modifier.height(4.dp))
                        Txt("Tap drives to choose up to ${DriveTotals.MAX_DRIVES}, then Ask.", 12.sp, FAINT)
                    }
                }
            }
            if (drives.isEmpty()) item { Card(Modifier.fillMaxWidth()) { Txt("No drives recorded for this car yet.", 15.sp) } }
            items(drives) { item ->
                val r = item.report
                val name = item.csv.name
                val on = name in chosen
                val hasSummary = remember(item.csv, changes) { DriveSummary.file(item.csv).exists() }
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        enabled = r != null,
                        onClick = { if (selecting) toggle(name) else open = name },
                        onLongClick = {
                            if (!selecting) {
                                selecting = true
                                picked = ""
                            }
                            toggle(name)
                        },
                    ),
                    border = if (selecting && on) NEON else CARD_BORDER,
                    fill = if (selecting && on) NEON.copy(alpha = 0.08f) else CARD,
                ) {
                    if (r == null) {
                        Txt(driveLabel(name), 16.sp, FAINT)
                        Txt(if (item.failed) "too short for a report" else "calculating…", 13.sp, FAINT)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) {
                                Txt(if (on) "☑" else "☐", 22.sp, if (on) NEON else MUTED)
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Txt(TimeFormat.day(r.start), 17.sp, NEON, FontWeight.SemiBold)
                                Txt(driveLine(r), 13.sp)
                                if (hasSummary) Txt("AI summary", 12.sp, FAINT)
                            }
                            VerdictChip(r.verdicts.count { it.verdict == Verdict.CHECK }, r.verdicts.count { it.verdict == Verdict.WATCH })
                        }
                    }
                }
            }
        }
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().background(CARD).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("${chosen.size} drive${if (chosen.size == 1) "" else "s"} selected", 15.sp, modifier = Modifier.weight(1f))
                Button("Ask →", NEON, filled = true) {
                    if (chosen.isEmpty()) {
                        android.widget.Toast.makeText(context, "Choose at least one drive", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        asking = true
                    }
                }
            }
        }
    }
}

/** "36 min · 6.8 km · city · 11.8 L/100 km" */
private fun driveLine(r: DriveReport) =
    String.format(Locale.US, "%.0f min · %.1f km · %s", r.minutes, r.km, r.driveType) +
        (r.lPer100?.let { String.format(Locale.US, " · %.1f L/100 km", it) } ?: "")

/**
 * Ask about the chosen drives together: the drives with their tags (D1 = oldest, as the AI gets
 * them), the question and the answer (ai/DriveSummary.askSeveral).
 */
@Composable
private fun SeveralDrivesScreen(items: List<DriveItem>, actions: Actions, back: () -> Unit) {
    val ai by LocalLlm.state.collectAsState()
    val lastAsk by DriveSummary.lastAskDrive.collectAsState()
    val reports = DriveTotals.ordered(items.mapNotNull { it.report })
    val mine = lastAsk == DriveSummary.SEVERAL && ai.question.isNotEmpty()
    LazyColumn(
        Modifier.fillMaxSize().background(BG),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Row { Button("‹ Drives", onClick = back) } }
        item {
            Txt("Ask about ${reports.size} drive${if (reports.size == 1) "" else "s"}", 22.sp, NEON, FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                reports.forEachIndexed { i, r ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    Txt("${DriveTotals.tag(i)}  ${TimeFormat.day(r.start)}", 15.sp, NEON, FontWeight.SemiBold)
                    Txt(driveLine(r), 13.sp)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Txt("Your question", 13.sp, MUTED, FontWeight.SemiBold)
                Txt("e.g. \"Is my fuel use getting better?\" or \"Is my battery getting weaker?\"", 13.sp, FAINT)
                Spacer(Modifier.height(8.dp))
                if (mine && ai.busy) {
                    Button("Stop", WARN, onClick = actions.aiStop)
                } else {
                    Button("Ask a question", NEON) { actions.askDrives(items.map { it.csv.name }) }
                }
                if (mine) AnswerBlock(ai)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Txt("Share", 13.sp, MUTED, FontWeight.SemiBold)
                Txt(if (mine && !ai.busy) "One ZIP: these drives' files, plus the question, the answer and the prompt."
                    else "One ZIP with these drives' files. Ask a question first to include the answer.", 13.sp, FAINT)
                Spacer(Modifier.height(8.dp))
                Button("Share", NEON) { actions.shareDrives(items.map { it.csv.name }) }
            }
        }
    }
}

@Composable
private fun VerdictChip(check: Int, watch: Int) = when {
    check > 0 -> Chip("■ $check check", ALARM)
    watch > 0 -> Chip("▲ $watch watch", WARN)
    else -> Chip("✓ OK", NEON)
}

/** One drive's report: AI summary, verdict, then a card per section (logic/DriveReport). */
@Composable
private fun DriveReportScreen(item: DriveItem, actions: Actions, back: () -> Unit) {
    val r = item.report ?: return
    val context = LocalContext.current
    val changes by DriveSummary.changes.collectAsState()
    val busyWith by DriveSummary.busyWith.collectAsState()
    val ai by LocalLlm.state.collectAsState()
    val summary = remember(item.csv, changes) { DriveSummary.read(item.csv) }
    val writing = busyWith == item.csv.name
    LazyColumn(
        Modifier.fillMaxSize().background(BG),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button("‹ Drives", onClick = back)
                Spacer(Modifier.weight(1f))
                VerdictChip(r.verdicts.count { it.verdict == Verdict.CHECK }, r.verdicts.count { it.verdict == Verdict.WATCH })
            }
        }
        item {
            Column(Modifier.padding(horizontal = 6.dp)) {
                Txt(TimeFormat.day(r.start), 22.sp, NEON, FontWeight.SemiBold)
                Txt(driveLine(r), 14.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Share", NEON, Modifier.weight(1f)) { actions.shareDrive(item.csv) }
                Button("Replay", WARN, Modifier.weight(1f)) { actions.replayDrive(item.csv) }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Txt("AI summary", 13.sp, MUTED, FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                when {
                    writing -> Txt("Writing… ${ai.status}", 15.sp, WARN)
                    summary != null -> {
                        if (summary.witty.isNotEmpty()) Txt(summary.witty, 15.sp, MUTED)
                        if (summary.refuel.isNotEmpty()) Txt(summary.refuel, 15.sp, WARN)
                        if (summary.spoken.isNotEmpty()) Txt(summary.spoken, 17.sp, NEON)
                        Spacer(Modifier.height(8.dp))
                        Txt(summary.full, 14.sp, MUTED)
                    }
                    else -> Txt("None yet", 15.sp, FAINT)
                }
                if (!writing) {
                    Spacer(Modifier.height(10.dp))
                    Button(if (summary == null) "Write AI summary" else "Write again") {
                        DriveReports.summarize(context, item.csv)?.let {
                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        item { AskCard(item, actions) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Txt("Verdict", 13.sp, MUTED, FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (r.verdicts.isEmpty()) Txt("Nothing to report", 15.sp, NEON)
                for (v in r.verdicts) {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Txt(when (v.verdict) { Verdict.CHECK -> "■ "; Verdict.WATCH -> "▲ "; else -> "✓ " }, 15.sp, v.verdict.tone())
                        Txt(v.label + if (v.value.isNotEmpty()) "  ${v.value}" else "", 15.sp, v.verdict.tone())
                    }
                }
            }
        }
        items(r.sections) { section ->
            Card(Modifier.fillMaxWidth()) {
                Txt(section.title.lowercase().replaceFirstChar { it.uppercase() }, 13.sp, MUTED, FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                for (line in section.lines) {
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(line.label, 14.sp, modifier = Modifier.weight(1f))
                        Txt(line.value, 15.sp, line.verdict.tone(), mono = true)
                        if (line.verdict == Verdict.WATCH || line.verdict == Verdict.CHECK) {
                            Txt(if (line.verdict == Verdict.CHECK) "  ■" else "  ▲", 15.sp, line.verdict.tone())
                        }
                    }
                }
            }
        }
        item { Txt("Car Dash ${BuildConfig.VERSION_NAME}", 11.sp, FAINT, modifier = Modifier.padding(6.dp)) }
    }
}

/**
 * Ask about this drive: a question to the on-phone AI with this drive's report (grounded like the
 * summary); the answer appears here. Show prompt: exactly what the model got.
 */
@Composable
private fun AskCard(item: DriveItem, actions: Actions) {
    val ai by LocalLlm.state.collectAsState()
    val lastAsk by DriveSummary.lastAskDrive.collectAsState()
    val mine = lastAsk == item.csv.name && ai.question.isNotEmpty() && ai.question != "Summary of the drive"
    Card(Modifier.fillMaxWidth()) {
        Txt("Ask about this drive", 13.sp, MUTED, FontWeight.SemiBold)
        Txt("e.g. \"Why is the battery a watch?\" or \"Was my fuel use OK?\"", 13.sp, FAINT)
        Spacer(Modifier.height(8.dp))
        if (mine && ai.busy) {
            Button("Stop", WARN, onClick = actions.aiStop)
        } else {
            Button("Ask a question", NEON) { actions.askDrive(item.csv.name) }
        }
        if (mine) AnswerBlock(ai)
    }
}

/** The question asked, the answer as it streams in, the time it took, and Show prompt (exactly what the model got). */
@Composable
private fun AnswerBlock(ai: AiState) {
    var showPrompt by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Txt("> ${ai.question}", 14.sp, FAINT)
    if (ai.answer.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Txt(DriveSummary.plain(ai.answer), 15.sp, NEON)
    } else if (ai.busy) {
        Txt(ai.status, 13.sp, WARN)
    }
    if (ai.stats.isNotEmpty()) Txt(ai.stats, 12.sp, FAINT)
    if (!ai.busy && ai.sentPrompt.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Button(if (showPrompt) "Hide prompt" else "Show prompt", MUTED) { showPrompt = !showPrompt }
        if (showPrompt) {
            Spacer(Modifier.height(6.dp))
            Txt("SYSTEM INSTRUCTION", 12.sp, MUTED, FontWeight.SemiBold)
            Txt(ai.sentSystem, 12.sp, FAINT, mono = true)
            Txt("MESSAGE", 12.sp, MUTED, FontWeight.SemiBold)
            Txt(ai.sentPrompt, 12.sp, FAINT, mono = true)
        }
    }
}
