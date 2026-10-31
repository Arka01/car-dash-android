package io.github.arka01.cardash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.arka01.cardash.BuildConfig
import io.github.arka01.cardash.CarState
import io.github.arka01.cardash.CrashReport
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.KeyMarks
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.ai.AiSettings
import io.github.arka01.cardash.ai.LocalLlm
import io.github.arka01.cardash.logic.Calculations
import io.github.arka01.cardash.logic.Level
import io.github.arka01.cardash.logic.Rules
import io.github.arka01.cardash.logic.Values
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The phone screen: a green-on-black terminal. Every line is fixed-width monospace text,
 * 30 characters per column. Portrait: one column. Landscape: two columns side by side.
 * The font size is the largest that fits both the width and the height, so the DASH screen
 * always fits on one screen without scrolling. The screens (DASH, INFO, ALL, DEV) and LIVE/STOP
 * are in a sidebar that slides in from the left (≡ in the top-left corner).
 */

private val GREEN = Color(0xFF33FF66)
private val DIM = Color(0xFF1F8A3F)
private val AMBER = Color(0xFFFFB000)
private val RED = Color(0xFFFF4D4D)
private val BLACK = Color.Black

private const val PANE = 30       // characters per column
private const val SIDEBAR = 16    // characters across the sidebar
private const val GAP = 2         // characters between the two landscape columns
private const val CHAR = 0.6f     // a monospace character is ~0.6 of the font size wide
private const val LINE = 1.2f     // line height, in font sizes
private const val OLD_MS = 10_000

// Height of each part in lines (bigger lines count by their size); used to fit the font.
private const val FRAME_LINES = 3f     // header (2), rule
private const val DRIVE_LINES = 9.5f   // speed (3), RPM (1.5), RPM bar, pedal, throttle, temps, warnings
private const val ENGINE_LINES = 5f
private const val FUEL_AIR_LINES = 5f
private const val CAR_LINES = 3f

private val LocalTerm = staticCompositionLocalOf { TextStyle() }

/** Size of one character cell in pixels, for drawing bars exactly as wide as text. */
private class Cell(val width: Float, val height: Float)

private val LocalCell = staticCompositionLocalOf { Cell(10f, 12f) }

private enum class Tab { DASH, INFO, ALL, AI, DEV }

/** What the screen can ask the activity to do. */
class Actions(
    val connect: () -> Unit,
    val demo: () -> Unit,
    val stop: () -> Unit,
    val pickAdapter: () -> Unit,
    val share: () -> Unit,
    val keyTest: () -> Unit,
    val shareTest: () -> Unit,
    val markKey: (String) -> Unit,
    val shareDrive: () -> Unit,
    val pickCar: () -> Unit,
    val newCar: () -> Unit,
    val probe: () -> Unit,
    val gears: () -> Unit,
    val refuel: () -> Unit,
    val replay: () -> Unit,
    val voice: () -> Unit,
    val aiImport: () -> Unit,
    val aiRemove: () -> Unit,
    val aiAsk: () -> Unit,     // type a question
    val aiTest: () -> Unit,    // the 30 Oct drive test question
    val aiStop: () -> Unit,
    val aiSetting: (String) -> Unit,   // edit one setting: system, temperature, topK, topP, maxTokens, backend
    val shareCrash: () -> Unit,
    val clearCrash: () -> Unit,
)

@Composable
fun TerminalApp(adapterName: String?, voiceOn: Boolean, aiSettings: AiSettings, actions: Actions) {
    val snap by Dash.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.DASH) }   // DASH when the app opens
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }   // Back closes the sidebar first
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Keep the screen on while something runs: a locked phone may pause the app (gaps in recordings).
    val view = LocalView.current
    val running = snap.mode != Mode.OFF
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(BLACK).safeDrawingPadding().padding(4.dp)) {
        val landscape = maxWidth > maxHeight
        val density = LocalDensity.current
        val keyPaddingPx = with(density) { 20.dp.toPx() }   // the ≡ key is taller than a text line
        val cols = if (landscape) PANE * 2 + GAP else PANE
        val lines = if (landscape) {
            FRAME_LINES + maxOf(DRIVE_LINES + CAR_LINES, ENGINE_LINES + FUEL_AIR_LINES)
        } else {
            FRAME_LINES + DRIVE_LINES + ENGINE_LINES + FUEL_AIR_LINES + CAR_LINES
        }
        val byWidth = constraints.maxWidth / (cols * CHAR)
        val byHeight = (constraints.maxHeight - keyPaddingPx) / (lines * LINE)
        val fontPx = minOf(byWidth, byHeight) * 0.97f
        val fitCols = (constraints.maxWidth / (fontPx * CHAR)).toInt()   // what actually fits across
        val fontSize = with(density) { fontPx.toSp() }
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = fontSize * LINE, color = GREEN)

        CompositionLocalProvider(LocalTerm provides style, LocalCell provides Cell(fontPx * CHAR, fontPx * LINE)) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Header(snap, now, fitCols, tab) { menuOpen = true }
                    Rule(fitCols)
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            Tab.DASH -> if (landscape) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                                        Column { DriveBlock(snap, now) }
                                        Column { CarBlock(snap, now) }
                                    }
                                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                                        Column { EngineBlock(snap, now) }
                                        Column { FuelAirBlock(snap, now) }
                                    }
                                }
                            } else {
                                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                                    Column { DriveBlock(snap, now) }
                                    Column { EngineBlock(snap, now) }
                                    Column { FuelAirBlock(snap, now) }
                                    Column { CarBlock(snap, now) }
                                }
                            }
                            Tab.INFO -> InfoView(snap, now)
                            Tab.ALL -> AllValues(snap, now, perRow = if (landscape) 2 else 1)
                            Tab.AI -> AiView(aiSettings, actions)
                            Tab.DEV -> DevView(snap, adapterName, voiceOn, actions)
                        }
                    }
                }
                Sidebar(menuOpen, tab, snap, actions, close = { menuOpen = false }) {
                    tab = it
                    menuOpen = false
                }
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

/** One line of terminal text made of coloured parts; [scale] makes the whole line bigger. */
@Composable
private fun Line(scale: Float = 1f, wrap: Boolean = false, lines: Int = 4, build: AnnotatedString.Builder.() -> Unit) {
    val style = LocalTerm.current
    BasicText(
        text = buildAnnotatedString(build),
        style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale),
        maxLines = if (wrap) lines else 1,
        softWrap = wrap,
    )
}

/** Appends coloured text; [size] lets part of a big line stay small. */
private fun AnnotatedString.Builder.put(text: String, color: Color = GREEN, size: TextUnit = TextUnit.Unspecified) {
    withStyle(SpanStyle(color = color, fontSize = size)) { append(text) }
}

/**
 * A bar like [█████░░░░] after some text: [cells] character cells wide. Drawn as rectangles,
 * because the block characters come from a fallback font that is wider than the monospace
 * text (bars overflowed in build 4). Each filled cell is coloured by where it sits on the scale.
 */
@Composable
private fun BarLine(
    value: Double?,
    max: Double,
    cells: Int,
    colorAt: (Double) -> Color = { GREEN },
    before: AnnotatedString.Builder.() -> Unit = {},
) {
    val cell = LocalCell.current
    val density = LocalDensity.current
    val filled = if (value == null) 0 else ((value / max).coerceIn(0.0, 1.0) * cells).roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Line { before(); put("[", DIM) }
        Canvas(Modifier.size(with(density) { (cells * cell.width).toDp() }, with(density) { cell.height.toDp() })) {
            val w = size.width / cells
            val top = size.height * 0.15f
            val h = size.height * 0.7f
            for (i in 0 until cells) {
                if (i < filled) {
                    drawRect(colorAt((i + 0.5) / cells * max), Offset(i * w, top), Size(w + 0.5f, h))
                } else {
                    drawRect(DIM.copy(alpha = 0.35f), Offset(i * w + w * 0.15f, top), Size(w * 0.7f, h))
                }
            }
        }
        Line { put("]", DIM) }
    }
}

/** "label   value" in a fixed width, value right-aligned. */
private fun AnnotatedString.Builder.cell(label: String, value: String, color: Color, width: Int = 14) {
    put(label, DIM)
    put(value.take(width - label.length - 1).padStart(width - label.length), color)
}

@Composable
private fun Rule(cols: Int) = Line { put("─".repeat(cols), DIM) }

@Composable
private fun Section(title: String) = Line { put("─ $title ", DIM); put("─".repeat(PANE - title.length - 3), DIM) }

private fun Level.color() = when (this) {
    Level.OK -> GREEN
    Level.WARN -> AMBER
    Level.ALARM -> RED
}

private fun Snapshot.fresh(pid: Int, now: Long): Boolean {
    val r = readings[pid] ?: return false
    return r.number != null && now - r.time < OLD_MS
}

/** Colour for a value: its warning level if fresh, dim if missing or old. */
private fun Snapshot.tint(pid: Int, now: Long, level: (Double?) -> Level = { Level.OK }): Color =
    if (fresh(pid, now)) level(number(pid)).color() else DIM

/** A value in a compact format, e.g. "%.0fkPa" -> "57kPa"; "--" if missing. */
private fun Snapshot.f(pid: Int, format: String) = number(pid)?.let { String.format(Locale.US, format, it) } ?: "--"

/** A calculated value (logic/Pipeline) by name, formatted; "--" if missing, "n/a" if the car can't give it. */
private fun Snapshot.calc(label: String, format: String): String {
    val v = derived?.values ?: return "--"
    if (v.isUnsupported(label)) return "n/a"
    return v[label]?.let { String.format(Locale.US, format, it) } ?: "--"
}

/** The PID a named value is read from on this car (e.g. throttle 45, or 11 if the car lacks 45). */
private fun Snapshot.pidOf(label: String): Int = Values.pidFor(label, supported) ?: 0

/** The battery monitor's verdict (smart alternator aware), OK until it has one. */
private fun Snapshot.batteryLevel(): Level = derived?.battery?.level ?: Level.OK

// ---------------------------------------------------------------- header and key bar

private val clock = SimpleDateFormat("HH:mm", Locale.US)

@Composable
private fun Header(s: Snapshot, now: Long, cols: Int, tab: Tab, openMenu: () -> Unit) {
    val (mark, modeColor) = when (s.mode) {
        Mode.LIVE -> "● LIVE" to GREEN
        Mode.DEMO -> "◆ DEMO" to AMBER
        Mode.OFF -> "○ OFF" to DIM
        Mode.TEST -> "◇ TEST" to AMBER
        Mode.REPLAY -> "◆ REPLAY" to AMBER
        Mode.PROBE -> "◇ SCAN" to AMBER
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Key("≡", false, GREEN, openMenu)
        Line {
            put(" ${tab.name}  ", GREEN)
            put(mark, modeColor)
            put("  ")
            put(clock.format(Date(now)), GREEN)
        }
    }
    val stateColor = when (s.carState) {
        CarState.ENGINE_ON -> GREEN
        CarState.STARTING, CarState.STOPPING, CarState.IGNITION_ON -> AMBER
        CarState.IGNITION_OFF, CarState.UNKNOWN -> DIM
    }
    val details = buildList {
        s.car?.let { add(it.shortName) }
        if (s.mode == Mode.LIVE && s.recording != null) add("REC")
        if (s.rounds > 0) add(String.format(Locale.US, "%.1f/s", s.roundsPerSec))
        if (s.fastRead.startsWith("on")) add("fast") else if (s.fastRead.startsWith("off")) add("slow")
    }
    Line {
        put(s.status.take(cols), if (s.mode == Mode.LIVE) stateColor else DIM)
        val rest = details.joinToString(" · ")
        if (rest.isNotEmpty()) put(" · $rest".take(maxOf(0, cols - s.status.length)), DIM)
    }
}

/**
 * The sidebar: slides in from the left over a dimmed screen. Tapping a screen opens it and
 * closes the sidebar; tapping the dimmed part or Back just closes it. LIVE / STOP at the bottom.
 * (Not opened by swiping from the edge: on Android that swipe means Back.)
 */
@Composable
private fun Sidebar(open: Boolean, tab: Tab, s: Snapshot, actions: Actions, close: () -> Unit, select: (Tab) -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(BLACK.copy(alpha = 0.7f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        )
    }
    AnimatedVisibility(open, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
        val width = with(LocalDensity.current) { (SIDEBAR * LocalCell.current.width).toDp() }
        Row(Modifier.fillMaxHeight()) {
            Column(Modifier.width(width).fillMaxHeight().background(BLACK)) {
                Line { put("CAR DASH", GREEN) }
                Line { put(BuildConfig.VERSION_NAME, DIM) }
                Line { put("─".repeat(SIDEBAR), DIM) }
                for (t in Tab.entries) SideItem(t.name, t == tab) { select(t) }
                Line { put("─".repeat(SIDEBAR), DIM) }
                when (s.mode) {
                    Mode.OFF -> SideItem("LIVE", false, GREEN) { actions.connect(); close() }
                    Mode.LIVE -> SideItem("STOP", false, GREEN) { actions.stop(); close() }
                    else -> SideItem("STOP", false, AMBER) { actions.stop(); close() }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(DIM))
        }
    }
}

/** One sidebar entry, as wide as the sidebar (easy to hit), inverted when selected. */
@Composable
private fun SideItem(label: String, selected: Boolean, color: Color = GREEN, onClick: () -> Unit) {
    val style = LocalTerm.current
    BasicText(
        text = " $label",
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) color else BLACK)
            .padding(vertical = 12.dp),
        style = style.copy(color = if (selected) BLACK else color),
        maxLines = 1,
        softWrap = false,
    )
}

/** A terminal-style button: [LABEL], inverted when selected. */
@Composable
private fun Key(label: String, selected: Boolean, color: Color = GREEN, onClick: () -> Unit) {
    val style = LocalTerm.current
    BasicText(
        text = "[$label]",
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (selected) color else BLACK)
            .padding(vertical = 10.dp),
        style = style.copy(color = if (selected) BLACK else color),
        maxLines = 1,
        softWrap = false,
    )
}

// ---------------------------------------------------------------- DASH tab

/** The values that matter while driving: speed, gear, RPM, pedal, throttle, temperatures. */
@Composable
private fun DriveBlock(s: Snapshot, now: Long) {
    val base = LocalTerm.current.fontSize
    val car = s.car
    val speed = s.number(0x0D)
    val rpm = s.number(0x0C)
    val gear = if (car == null) null else Calculations.gear(car, speed, rpm)
    val limit = s.derived?.warmUp?.rpmLimit   // lower RPM limit while the engine is cold
    val rpmLevel = { r: Double? -> if (car == null) Level.OK else Rules.rpm(r, car, limit) }
    val pedal = s.pidOf("Accelerator pedal")
    val throttle = s.pidOf("Throttle")

    // 3 characters of speed at 3x = 9 columns, " km/h  G " = 9, gear up to 4 at 3x = 12: 30 in total.
    Line(scale = 3f) {
        put((speed?.roundToInt()?.toString() ?: "--").padStart(3), s.tint(0x0D, now))
        put(" km/h  G ", DIM, base)
        put(Calculations.gearName(gear), if (s.fresh(0x0D, now)) GREEN else DIM)
    }
    Line(scale = 1.5f) {
        put("RPM ", DIM, base)
        put((rpm?.roundToInt()?.toString() ?: "--").padStart(5), s.tint(0x0C, now) { rpmLevel(it) })
        limit?.let { put("  max $it", AMBER, base) }
    }
    BarLine(rpm, car?.let { Rules.rpmScale(it) } ?: 5000.0, PANE - 2, colorAt = { rpmLevel(it).color() })
    BarLine(s.number(pedal), 100.0, PANE - 13) {
        put("PEDAL ", DIM)
        put(s.f(pedal, "%.0f%%").padStart(4), s.tint(pedal, now))
        put(" ")
    }
    BarLine(s.number(throttle), 100.0, PANE - 13) {
        put("THROT ", DIM)
        put(s.f(throttle, "%.0f%%").padStart(4), s.tint(throttle, now))
        put(" ")
    }
    Line {
        put("COOL ", DIM)
        put(s.f(0x05, "%.0f°C").padStart(5), s.tint(0x05, now) { Rules.coolant(it) })
        put(" OIL ", DIM)
        put(s.f(0x5C, "%.0f°C").padStart(5), s.tint(0x5C, now) { Rules.oil(it) })
        put(" BAT ", DIM)
        put(s.f(0x42, "%.1fV").padStart(5), s.tint(0x42, now) { s.batteryLevel() })
    }
    // All active warnings in one place (logic/Pipeline), so nothing amber/red goes unnoticed.
    val alerts = s.derived?.alerts.orEmpty()
    Line {
        put("> ", DIM)
        if (alerts.isEmpty()) put("all normal", DIM)
        alerts.forEachIndexed { i, alert ->
            if (i > 0) put(" · ", DIM)
            put(alert.text, alert.level.color())
        }
    }
}

/** Two label/value cells on one line (14 + 2 + 14 characters), each coloured by its level. */
@Composable
private fun Pair2(
    s: Snapshot,
    now: Long,
    l1: String,
    p1: Int,
    f1: String,
    l2: String,
    p2: Int,
    f2: String,
    level1: (Double?) -> Level = { Level.OK },
    level2: (Double?) -> Level = { Level.OK },
) = Line {
    cell(l1, s.f(p1, f1), s.tint(p1, now, level1))
    put("  ")
    cell(l2, s.f(p2, f2), s.tint(p2, now, level2))
}

@Composable
private fun EngineBlock(s: Snapshot, now: Long) {
    Section("ENGINE")
    Pair2(s, now, "load", 0x04, "%.1f%%", "timing", 0x0E, "%.1f°")
    Line {
        cell("MAP", s.f(0x0B, "%.0fkPa"), s.tint(0x0B, now))
        put("  ")
        cell("torque", s.calc("Torque", "%.0fNm"), s.tint(0x62, now))
    }
    Pair2(s, now, "trim S", 0x06, "%+.1f%%", "trim L", 0x07, "%+.1f%%", level2 = { Rules.fuelTrim(it) })
    Line {
        cell("λ cmd", s.f(0x44, "%.3f"), s.tint(0x44, now))
        put("  ")
        cell("fuel", fuelSystemShort(s.number(0x03)), s.tint(0x03, now))
    }
}

@Composable
private fun FuelAirBlock(s: Snapshot, now: Long) {
    Section("FUEL")
    Pair2(s, now, "level", 0x2F, "%.1f%%", "rate", 0x5E, "%.2fL/h")
    Section("AIR")
    Pair2(s, now, "intake", 0x0F, "%.0f°C", "outside", 0x46, "%.0f°C", level1 = { Rules.intake(it) })
    Pair2(s, now, "baro", 0x33, "%.0fkPa", "cat", 0x3C, "%.0f°C", level2 = { Rules.catalyst(it) })
}

@Composable
private fun CarBlock(s: Snapshot, now: Long) {
    Section("CAR")
    Line {
        cell("battery", s.f(0x42, "%.2fV"), s.tint(0x42, now) { s.batteryLevel() })
        put("  ")
        cell("port", s.adapterVoltage?.let { String.format(Locale.US, "%.1fV", it) } ?: "--", GREEN)
    }
    Line {
        cell("run", s.number(0x1F)?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "--", s.tint(0x1F, now))
        put("  ")
        cell("clr", s.f(0x31, "%.0fkm"), s.tint(0x31, now))
    }
}

private fun fuelSystemShort(code: Double?) = when (code?.toInt()) {
    null -> "--"
    1 -> "open/cold"
    2 -> "closed"
    4 -> "fuel cut"
    8, 16 -> "fault"
    else -> "off"
}

// ---------------------------------------------------------------- ALL and LOG tabs

@Composable
private fun AllValues(s: Snapshot, now: Long, perRow: Int) {
    val rows = s.readings.values.sortedBy { it.pid }.chunked(perRow)
    LazyColumn {
        items(rows) { row ->
            Line {
                row.forEachIndexed { i, r ->
                    if (i > 0) put(" ".repeat(GAP))
                    val color = if (now - r.time < OLD_MS) GREEN else DIM
                    put("%02X ".format(r.pid), DIM)
                    put(r.name.take(13).padEnd(14), DIM)
                    put(r.text.take(13).padEnd(13), color)
                }
            }
        }
    }
}

/** DEV tab: cars and their tools, demo/replay, adapter choice, the KEY TEST, recording and the log. */
@Composable
private fun DevView(s: Snapshot, adapterName: String?, voiceOn: Boolean, actions: Actions) {
    val test = s.test
    val testing = s.mode == Mode.TEST
    val idle = s.mode == Mode.OFF
    LazyColumn {
        item { Section("CAR") }
        item { Line(wrap = true) { put("car: ", DIM); put(s.car?.let { "${it.shortName} (${it.key})" } ?: "--") } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key("CAR", false, GREEN, actions.pickCar)
                Key("NEW CAR", false, if (idle) GREEN else DIM, actions.newCar)
                Key("PROBE", false, if (idle) GREEN else DIM, actions.probe)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key("GEARS", false, GREEN, actions.gears)
                Key("REFUEL", false, GREEN, actions.refuel)
                Key(if (voiceOn) "VOICE ON" else "VOICE OFF", false, if (voiceOn) GREEN else DIM, actions.voice)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key("DEMO", s.mode == Mode.DEMO, AMBER, actions.demo)
                Key("REPLAY", s.mode == Mode.REPLAY, AMBER, actions.replay)
                Key("ADAPTER", false, GREEN, actions.pickAdapter)
            }
        }
        item { Line { put("adapter: ${adapterName ?: "not chosen"}", DIM) } }
        item { Section("KEY TEST") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (testing) Key("STOP TEST", false, AMBER, actions.stop) else Key("START TEST", false, GREEN, actions.keyTest)
                Key("SHARE TEST", false, GREEN, actions.shareTest)
            }
        }
        item {
            // Tap the key's position each time you turn it; the label goes into the recording.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (position in KeyMarks.POSITIONS) {
                    Key(position, testing && test?.label == position, AMBER) { actions.markKey(position) }
                }
            }
        }
        item {
            Line(wrap = true) {
                if (test == null) put("LIVE/DEMO off; START TEST, then tap the key position at every turn.", DIM)
                else put("${test.file} · ${test.rows} rows", DIM)
            }
        }
        if (test != null) {
            item { Line { put("   t label     volt ecu  net", DIM) } }
            items(test.recent.reversed()) { line -> Line { put(line) } }
        }
        item { Section("RECORDING") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Line { put(if (s.mode == Mode.LIVE) "● REC" else "every LIVE run", if (s.mode == Mode.LIVE) RED else DIM) }
                Key("SHARE DRIVE", false, GREEN, actions.shareDrive)
            }
        }
        item {
            Line(wrap = true) {
                put(s.recording?.let { "$it · ${s.recordedRows} rows" } ?: "the newest drive of this car is shared", DIM)
            }
        }
        item { Section("LOG") }
        item { Key("SHARE LOG", false, GREEN, actions.share) }
        item { Line(wrap = true) { put("fast read: ${s.fastRead.ifEmpty { "-" }}", DIM) } }
        items(s.log.reversed()) { line -> Line(wrap = true) { put(line) } }
    }
}

// ---------------------------------------------------------------- INFO tab

private fun fmt(x: Double?, format: String) = x?.let { String.format(Locale.US, format, it) } ?: "--"

/** Two label/value cells on one line (14 + 2 + 14 characters). */
@Composable
private fun Cells(l1: String, v1: String, c1: Color, l2: String = "", v2: String = "", c2: Color = GREEN) = Line {
    cell(l1, v1, c1)
    if (l2.isNotEmpty()) {
        put("  ")
        cell(l2, v2, c2)
    }
}

/**
 * Everything the Python dashboard calculates, as terminal lines: the car, engine state
 * (hot / warm-up stage), torque and power, fuel left and range, battery and charging,
 * trip computer, temperature trends and the pedal sensor check.
 */
@Composable
private fun InfoView(s: Snapshot, now: Long) {
    val d = s.derived
    val car = s.car
    val live = s.mode != Mode.OFF && d != null
    val on = if (live) GREEN else DIM
    LazyColumn {
        item { Section("CAR") }
        item { Line(wrap = true) { put(car?.name ?: "no car chosen", GREEN) } }
        item { Line { put("VIN ", DIM); put(s.vin ?: "--", on) } }
        item {
            Line(wrap = true) {
                put("trouble codes ", DIM)
                val codes = s.troubleCodes
                put(codes ?: "--", if (codes == null || codes.startsWith("none")) on else AMBER)
            }
        }
        item {
            Line(wrap = true) {
                put("gears ", DIM)
                put(car?.gears?.joinToString(" ") { String.format(Locale.US, "%.0f", it) } ?: "not known: drive, then [GEARS]", GREEN)
            }
        }
        item { Line(wrap = true) { put(s.protocol ?: "", DIM) } }

        item { Section("ENGINE") }
        item {
            Line(wrap = true) {
                val e = d?.engine
                if (e == null) put("state --", DIM) else { put(e.state, e.level.color()); put("  ${e.detail}", DIM) }
            }
        }
        item { Cells("torque", s.calc("Torque", "%.0fNm"), on, "power", s.calc("Power", "%.0fbhp"), on) }
        item { Cells("peak", fmt(d?.peaks?.get("Torque"), "%.0fNm"), on, "peak", fmt(d?.peaks?.get("Power"), "%.0fbhp"), on) }
        item {
            val soak = d?.values?.get("Heat soak")
            Cells("heat soak", if (d?.values?.isUnsupported("Heat soak") == true) "n/a" else fmt(soak, "%+.0f°C"),
                if (live) Rules.heatSoak(soak).color() else DIM, "boost", s.calc("Boost", "%+.2fbar"), on)
        }
        item {
            val t = d?.trends.orEmpty()
            Cells("cool/min", fmt(t["Coolant"], "%+.1f°"), on, "oil/min", fmt(t["Oil"], "%+.1f°"), on)
        }
        item {
            val t = d?.trends.orEmpty()
            Cells("air/min", fmt(t["Intake air"], "%+.1f°"), on, "cat/min", fmt(t["Catalyst"], "%+.0f°"), on)
        }

        item { Section("FUEL") }
        val fuel = d?.fuel
        if (fuel == null) {
            item { Line(wrap = true) { put(if (car?.tankLitres == null) "tank size not set for this car" else "--", DIM) } }
        } else {
            item {
                Line {
                    put("left ", DIM)
                    val low = fuel.litres?.let { it < 8 } == true
                    put(fmt(fuel.litres, "%.1fL"), if (low) AMBER else on)
                    put(fuel.sigma?.let { String.format(Locale.US, " ±%.1f", it) } ?: "", DIM)
                    put("  range ", DIM)
                    put(fmt(fuel.rangeKm, "%.0fkm"), on)
                }
            }
            item { Cells("now L/100", fmt(fuel.nowLPer100km, "%.1f"), on, "trip L/100", fmt(fuel.tripLPer100km, "%.1f"), on) }
            item { Cells("avg L/100", fmt(fuel.consumptionLPer100km, "%.1f"), on, "used", fmt(fuel.usedTrip, "%.2fL"), on) }
            item { Line(wrap = true) { put("flow ", DIM); put(fmt(fuel.flowLph, "%.2fL/h"), on); put(" ${fuel.flowSource ?: ""}", DIM) } }
            item {
                Line(wrap = true) {
                    put("float ", DIM)
                    put(fmt(fuel.sensorPct, "%.0f%%") + " = " + fmt(fuel.sensorLitres, "%.1fL"), on)
                    put("  fill-ups ${fuel.fillUpsLearned}", DIM)
                }
            }
            items(fuel.notes) { note -> Line(wrap = true) { put(note, AMBER) } }
        }

        item { Section("BATTERY") }
        val b = d?.battery
        if (b == null) {
            item { Line { put("--", DIM) } }
        } else {
            item {
                Line(wrap = true) {
                    put(b.state.ifEmpty { "BATTERY" }, b.level.color())
                    put(String.format(Locale.US, " %.2fV", b.volts), on)
                    put(" (${b.source})", DIM)
                }
            }
            if (b.detail.isNotEmpty()) item { Line(wrap = true) { put(b.detail, DIM) } }
            item { Cells("ECU", fmt(b.ecuVolts, "%.2fV"), on, "port", fmt(b.portVolts, "%.2fV"), on) }
            b.crank?.let { c ->
                item {
                    Line(wrap = true) {
                        put("start dip ", DIM)
                        put(String.format(Locale.US, "%.2fV", c.minVolts), Rules.of(c.level).color())
                        put(" ${c.text} (${c.samples} readings, ${c.time.replace('T', ' ')})", DIM)
                    }
                }
            }
            b.sourcesNote?.let { note -> item { Line(wrap = true) { put(note, AMBER) } } }
        }

        item { Section("TRIP") }
        item { Cells("distance", s.calc("Trip distance", "%.1fkm"), on, "avg", s.calc("Trip avg speed", "%.0fkm/h"), on) }
        item { Cells("accel", s.calc("Acceleration", "%+.2fg"), on, "rpm/kmh", s.calc("RPM per km/h", "%.1f"), on) }
        item { Cells("0-60", s.calc("0-60 km/h best", "%.1fs"), on, "0-100", s.calc("0-100 km/h best", "%.1fs"), on) }
        item { Cells("top speed", fmt(d?.peaks?.get("Speed"), "%.0fkm/h"), on, "top rpm", fmt(d?.peaks?.get("RPM"), "%.0f"), on) }
        item {
            val mismatch = d?.values?.get("Pedal sensor mismatch")
            val text = when {
                d?.values?.isUnsupported("Pedal sensor mismatch") == true -> "n/a"
                mismatch == null -> "move pedal fully once"
                else -> String.format(Locale.US, "%.0fpts", mismatch)
            }
            Line { put("pedal check ", DIM); put(text, if (live) Rules.pedalMismatch(mismatch).color() else DIM) }
        }
        item {
            val km = s.number(0x21)
            Cells("km w/ MIL", s.f(0x21, "%.0f"), if (live) Rules.drivenWithMil(km).color() else DIM,
                "clr", s.f(0x31, "%.0fkm"), on)
        }
    }
}

// ---------------------------------------------------------------- AI tab

/**
 * The on-phone language model (ai/LocalLlm): the model file, its settings (as in Google's AI
 * Edge Gallery app) and a test: ask a question, the answer appears word by word.
 */
@Composable
private fun AiView(settings: AiSettings, actions: Actions) {
    val ai by LocalLlm.state.collectAsState()
    val crash by CrashReport.report.collectAsState()
    val hasModel = ai.modelName != null
    LazyColumn {
        crash?.let { report ->
            // Why the app closed last time (build 13: the AI crashed on GPU and CPU).
            item { Section("LAST CRASH") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Key("SHARE CRASH", false, RED, actions.shareCrash)
                    Key("CLEAR", false, DIM, actions.clearCrash)
                }
            }
            items(report.lines().take(14)) { line -> Line(wrap = true) { put(line, AMBER) } }
            item { Line { put("(full report: SHARE CRASH)", DIM) } }
        }
        item { Section("MODEL") }
        item {
            Line(wrap = true) {
                put(ai.modelName ?: "none", if (hasModel) GREEN else AMBER)
                if (hasModel) put(String.format(Locale.US, "  %.2f GB", ai.modelBytes / 1e9), DIM)
            }
        }
        item { Line(wrap = true) { put(ai.status, if (ai.status.startsWith("failed") || ai.status.startsWith("import failed")) RED else DIM) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key("IMPORT MODEL", false, if (ai.busy) DIM else GREEN, actions.aiImport)
                Key("REMOVE", false, if (hasModel && !ai.busy) AMBER else DIM, actions.aiRemove)
            }
        }
        item { Section("SETTINGS") }
        item { SettingLine("temperature", String.format(Locale.US, "%.2f", settings.temperature), "temperature", actions) }
        item { SettingLine("top-K", settings.topK.toString(), "topK", actions) }
        item { SettingLine("top-P", String.format(Locale.US, "%.2f", settings.topP), "topP", actions) }
        item { SettingLine("max answer", "${settings.maxTokens} tokens", "maxTokens", actions) }
        item { SettingLine("runs on", if (settings.gpu) "GPU" else "CPU", "backend", actions) }
        item { SettingLine("system prompt", "[EDIT]", "system", actions) }
        item { Line(wrap = true, lines = Int.MAX_VALUE) { put(settings.systemPrompt, DIM) } }
        item { Section("ASK") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (ai.busy) {
                    Key("STOP", false, AMBER, actions.aiStop)
                } else {
                    Key("ASK", false, if (hasModel) GREEN else DIM, actions.aiAsk)
                    Key("TEST DRIVE", false, if (hasModel) GREEN else DIM, actions.aiTest)
                }
            }
        }
        if (ai.question.isNotEmpty()) {
            item { Line(wrap = true) { put("> ", DIM); put(ai.question.lineSequence().first().take(120), DIM) } }
        }
        if (ai.answer.isNotEmpty()) {
            items(ai.answer.split("\n")) { line -> Line(wrap = true, lines = Int.MAX_VALUE) { put(line) } }
        }
        if (ai.stats.isNotEmpty()) item { Line(wrap = true) { put(ai.stats, DIM) } }
    }
}

/** "label ........ value" with the value tappable to change it. */
@Composable
private fun SettingLine(label: String, value: String, key: String, actions: Actions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Line { put(label, DIM) }
        Key(value, false, GREEN) { actions.aiSetting(key) }
    }
}
