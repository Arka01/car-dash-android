package io.github.arka01.cardash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.arka01.cardash.BuildConfig
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.KeyMarks
import io.github.arka01.cardash.Level
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.Tiago
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The phone screen: a green-on-black terminal. Every line is fixed-width monospace text,
 * 30 characters per column. Portrait: one column. Landscape: two columns side by side.
 * The font size is the largest that fits both the width and the height, so the DASH tab
 * always fits on one screen without scrolling.
 */

private val GREEN = Color(0xFF33FF66)
private val DIM = Color(0xFF1F8A3F)
private val AMBER = Color(0xFFFFB000)
private val RED = Color(0xFFFF4D4D)
private val BLACK = Color.Black

private const val PANE = 30       // characters per column
private const val GAP = 2         // characters between the two landscape columns
private const val CHAR = 0.6f     // a monospace character is ~0.6 of the font size wide
private const val LINE = 1.2f     // line height, in font sizes
private const val OLD_MS = 10_000

// Height of each part in lines (bigger lines count by their size); used to fit the font.
private const val FRAME_LINES = 5f     // header (2), two rules, the key bar
private const val DRIVE_LINES = 9.5f   // speed (3), RPM (1.5), RPM bar, pedal, throttle, temps, warnings
private const val ENGINE_LINES = 5f
private const val FUEL_AIR_LINES = 5f
private const val CAR_LINES = 3f

private val LocalTerm = staticCompositionLocalOf { TextStyle() }

/** Size of one character cell in pixels, for drawing bars exactly as wide as text. */
private class Cell(val width: Float, val height: Float)

private val LocalCell = staticCompositionLocalOf { Cell(10f, 12f) }

private enum class Tab { DASH, ALL, DEV }

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
)

@Composable
fun TerminalApp(adapterName: String?, actions: Actions) {
    val snap by Dash.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.DASH) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(BLACK).safeDrawingPadding().padding(4.dp)) {
        val landscape = maxWidth > maxHeight
        val density = LocalDensity.current
        val keyPaddingPx = with(density) { 20.dp.toPx() }
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
            Column(Modifier.fillMaxSize()) {
                Header(snap, now, fitCols)
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
                        Tab.ALL -> AllValues(snap, now, perRow = if (landscape) 2 else 1)
                        Tab.DEV -> DevView(snap, adapterName, actions)
                    }
                }
                Rule(fitCols)
                BottomBar(tab, snap, actions) { tab = it }
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

/** One line of terminal text made of coloured parts; [scale] makes the whole line bigger. */
@Composable
private fun Line(scale: Float = 1f, wrap: Boolean = false, build: AnnotatedString.Builder.() -> Unit) {
    val style = LocalTerm.current
    BasicText(
        text = buildAnnotatedString(build),
        style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale),
        maxLines = if (wrap) 4 else 1,
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

// ---------------------------------------------------------------- header and key bar

private val clock = SimpleDateFormat("HH:mm", Locale.US)

@Composable
private fun Header(s: Snapshot, now: Long, cols: Int) {
    val (mark, modeColor) = when (s.mode) {
        Mode.LIVE -> "● LIVE" to GREEN
        Mode.DEMO -> "◆ DEMO" to AMBER
        Mode.OFF -> "○ OFF" to DIM
        Mode.TEST -> "◇ TEST" to AMBER
    }
    Line {
        put("CAR DASH ${BuildConfig.VERSION_NAME} ", GREEN)
        put(mark, modeColor)
        put("  ")
        put(clock.format(Date(now)), GREEN)
    }
    val details = buildList {
        add(s.status)
        if (s.rounds > 0) add(String.format(Locale.US, "%.1f/s", s.roundsPerSec))
        if (s.fastRead.startsWith("on")) add("fast") else if (s.fastRead.startsWith("off")) add("slow")
    }.joinToString(" · ")
    Line { put(details.take(cols), DIM) }
}

@Composable
private fun BottomBar(tab: Tab, s: Snapshot, actions: Actions, select: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Key("DASH", tab == Tab.DASH) { select(Tab.DASH) }
        Key("ALL", tab == Tab.ALL) { select(Tab.ALL) }
        Key("DEV", tab == Tab.DEV) { select(Tab.DEV) }
        when (s.mode) {
            Mode.OFF -> {
                Key("LIVE", false, GREEN, actions.connect)
                Key("DEMO", false, AMBER, actions.demo)
            }
            Mode.LIVE -> Key("STOP", false, GREEN, actions.stop)
            Mode.DEMO -> Key("STOP DEMO", false, AMBER, actions.stop)
            Mode.TEST -> Key("STOP TEST", false, AMBER, actions.stop)
        }
    }
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
    val speed = s.number(0x0D)
    val rpm = s.number(0x0C)
    val gear = Tiago.gear(speed, rpm)
    val coolant = s.number(0x05)
    val oil = s.number(0x5C)
    val battery = s.number(0x42)

    // 3 characters of speed at 3x = 9 columns, " km/h  G " = 9, gear up to 4 at 3x = 12: 30 in total.
    Line(scale = 3f) {
        put((speed?.roundToInt()?.toString() ?: "--").padStart(3), s.tint(0x0D, now))
        put(" km/h  G ", DIM, base)
        put(Tiago.gearName(gear), if (s.fresh(0x0D, now)) GREEN else DIM)
    }
    Line(scale = 1.5f) {
        put("RPM ", DIM, base)
        put((rpm?.roundToInt()?.toString() ?: "--").padStart(5), s.tint(0x0C, now) { Tiago.rpmLevel(it) })
    }
    BarLine(rpm, Tiago.RPM_SCALE, PANE - 2, colorAt = { Tiago.rpmLevel(it).color() })
    BarLine(s.number(0x5A), 100.0, PANE - 13) {
        put("PEDAL ", DIM)
        put(s.f(0x5A, "%.0f%%").padStart(4), s.tint(0x5A, now))
        put(" ")
    }
    BarLine(s.number(0x45), 100.0, PANE - 13) {
        put("THROT ", DIM)
        put(s.f(0x45, "%.0f%%").padStart(4), s.tint(0x45, now))
        put(" ")
    }
    Line {
        put("COOL ", DIM)
        put(s.f(0x05, "%.0f°C").padStart(5), s.tint(0x05, now) { Tiago.coolantLevel(it) })
        put(" OIL ", DIM)
        put(s.f(0x5C, "%.0f°C").padStart(5), s.tint(0x5C, now) { Tiago.oilLevel(it) })
        put(" BAT ", DIM)
        put(s.f(0x42, "%.1fV").padStart(5), s.tint(0x42, now) { Tiago.batteryLevel(it) })
    }
    // All active warnings in one place, so nothing amber/red goes unnoticed.
    val alerts = buildList {
        if (Tiago.rpmLevel(rpm) == Level.ALARM) add("REDLINE" to RED)
        else if (Tiago.rpmLevel(rpm) == Level.WARN) add("SHIFT" to AMBER)
        when (Tiago.coolantLevel(coolant)) {
            Level.ALARM -> add("OVERHEATING" to RED)
            Level.WARN -> add((if ((coolant ?: 0.0) < 60) "WARMING UP" else "COOLANT HOT") to AMBER)
            Level.OK -> {}
        }
        if (Tiago.oilLevel(oil) == Level.ALARM) add("OIL OVERHEATING" to RED)
        else if (Tiago.oilLevel(oil) == Level.WARN && (oil ?: 0.0) >= 60) add("OIL HOT" to AMBER)
        if (Tiago.batteryLevel(battery) != Level.OK) add("BATTERY" to Tiago.batteryLevel(battery).color())
    }
    Line {
        put("> ", DIM)
        if (alerts.isEmpty()) put("all normal", DIM)
        alerts.forEachIndexed { i, (text, color) ->
            if (i > 0) put(" · ", DIM)
            put(text, color)
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
    Pair2(s, now, "MAP", 0x0B, "%.0fkPa", "torque", 0x62, "%.0f%%")
    Pair2(s, now, "trim S", 0x06, "%+.1f%%", "trim L", 0x07, "%+.1f%%", level2 = { Tiago.fuelTrimLevel(it) })
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
    Pair2(s, now, "intake", 0x0F, "%.0f°C", "outside", 0x46, "%.0f°C", level1 = { Tiago.intakeLevel(it) })
    Pair2(s, now, "baro", 0x33, "%.0fkPa", "cat", 0x3C, "%.0f°C", level2 = { Tiago.catalystLevel(it) })
}

@Composable
private fun CarBlock(s: Snapshot, now: Long) {
    Section("CAR")
    Line {
        cell("battery", s.f(0x42, "%.2fV"), s.tint(0x42, now) { Tiago.batteryLevel(it) })
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

/** DEV tab: adapter choice, the KEY TEST, and the connection log. */
@Composable
private fun DevView(s: Snapshot, adapterName: String?, actions: Actions) {
    val test = s.test
    val testing = s.mode == Mode.TEST
    LazyColumn {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Key("ADAPTER", false, GREEN, actions.pickAdapter)
                Key("SHARE LOG", false, GREEN, actions.share)
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
        item { Section("LOG") }
        item { Line(wrap = true) { put("fast read: ${s.fastRead.ifEmpty { "-" }}", DIM) } }
        items(s.log.reversed()) { line -> Line(wrap = true) { put(line) } }
    }
}
