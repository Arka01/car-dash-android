package io.github.arka01.cardash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.arka01.cardash.BuildConfig
import io.github.arka01.cardash.Dash
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
 * The phone screen: a green-on-black terminal. Every line is fixed-width monospace text
 * (40 characters per column), and the font size is chosen so 40 characters fill the width.
 * Portrait: one column. Landscape: two columns side by side (82 characters in total).
 */

private val GREEN = Color(0xFF33FF66)
private val DIM = Color(0xFF1F8A3F)
private val AMBER = Color(0xFFFFB000)
private val RED = Color(0xFFFF4D4D)
private val BLACK = Color.Black

private const val COLS = 40
private const val OLD_MS = 10_000

private val LocalTerm = staticCompositionLocalOf { TextStyle() }

private enum class Tab { DASH, ALL, LOG }

/** What the screen can ask the activity to do. */
class Actions(
    val connect: () -> Unit,
    val demo: () -> Unit,
    val stop: () -> Unit,
    val pickAdapter: () -> Unit,
    val share: () -> Unit,
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
        val cols = if (landscape) COLS * 2 + 2 else COLS
        // A monospace character is ~0.6 of the font size wide.
        val fontSize = with(LocalDensity.current) { (constraints.maxWidth / (cols * 0.6f)).toSp() }
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, color = GREEN)

        CompositionLocalProvider(LocalTerm provides style) {
            Column(Modifier.fillMaxSize()) {
                Header(snap, now, cols)
                Rule(cols)
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.DASH -> if (landscape) {
                            Row {
                                Column(Modifier.weight(1f)) { DriveBlock(snap, now) }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) { DetailBlock(snap, now) }
                            }
                        } else {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                DriveBlock(snap, now)
                                DetailBlock(snap, now)
                            }
                        }
                        Tab.ALL -> AllValues(snap, now, perRow = if (landscape) 2 else 1)
                        Tab.LOG -> LogView(snap, adapterName, actions)
                    }
                }
                Rule(cols)
                BottomBar(tab, snap, actions) { tab = it }
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

/** One line of terminal text made of coloured parts. */
@Composable
private fun Line(scale: Float = 1f, build: AnnotatedString.Builder.() -> Unit) {
    val style = LocalTerm.current
    BasicText(
        text = buildAnnotatedString(build),
        style = style.copy(fontSize = style.fontSize * scale),
        maxLines = 1,
        softWrap = false,
    )
}

private fun AnnotatedString.Builder.put(text: String, color: Color = GREEN) {
    withStyle(SpanStyle(color = color)) { append(text) }
}

/** A text bar like [█████░░░░], coloured by where each cell sits on the scale. */
private fun AnnotatedString.Builder.bar(value: Double?, max: Double, cells: Int, colorAt: (Double) -> Color = { GREEN }) {
    val filled = if (value == null) 0 else ((value / max).coerceIn(0.0, 1.0) * cells).roundToInt()
    put("[", DIM)
    for (i in 0 until cells) {
        if (i < filled) put("█", colorAt((i + 0.5) / cells * max)) else put("░", DIM)
    }
    put("]", DIM)
}

/** "label      value" in a fixed width, value right-aligned. */
private fun AnnotatedString.Builder.cell(label: String, value: String, color: Color, width: Int = 18) {
    put(label, DIM)
    put(value.take(width - label.length - 1).padStart(width - label.length), color)
}

@Composable
private fun Rule(cols: Int) = Line { put("─".repeat(cols), DIM) }

@Composable
private fun Section(title: String) = Line { put("─ $title ", DIM); put("─".repeat(COLS - title.length - 3), DIM) }

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

private fun Snapshot.text(pid: Int) = readings[pid]?.text ?: "--"

private fun Snapshot.compact(pid: Int, format: String) = number(pid)?.let { String.format(Locale.US, format, it) } ?: "--"

// ---------------------------------------------------------------- header and bottom bar

private val clock = SimpleDateFormat("HH:mm", Locale.US)

@Composable
private fun Header(s: Snapshot, now: Long, cols: Int) {
    val (mark, modeColor) = when (s.mode) {
        Mode.LIVE -> "● LIVE" to GREEN
        Mode.DEMO -> "◆ DEMO" to AMBER
        Mode.OFF -> "○ OFF" to DIM
    }
    Line {
        put("CAR DASH ${BuildConfig.VERSION_NAME}", GREEN)
        put(" ─ TIAGO ─ ", DIM)
        put(mark, modeColor)
        put(" ─ ", DIM)
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
        Key("LOG", tab == Tab.LOG) { select(Tab.LOG) }
        when (s.mode) {
            Mode.OFF -> {
                Key("CONNECT", false, GREEN, actions.connect)
                Key("DEMO", false, AMBER, actions.demo)
            }
            Mode.LIVE -> Key("STOP", false, GREEN, actions.stop)
            Mode.DEMO -> Key("STOP DEMO", false, AMBER, actions.stop)
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

/** The values that matter while driving: speed, gear, RPM, pedal, temperatures. */
@Composable
private fun DriveBlock(s: Snapshot, now: Long) {
    val speed = s.number(0x0D)
    val rpm = s.number(0x0C)
    val gear = Tiago.gear(speed, rpm)
    val coolant = s.number(0x05)
    val oil = s.number(0x5C)
    val battery = s.number(0x42)

    Line(scale = 2f) {
        put((speed?.roundToInt()?.toString() ?: "--").padStart(3), s.tint(0x0D, now))
        put(" km/h  ", DIM)
        put("G ", DIM)
        put(Tiago.gearName(gear), if (s.fresh(0x0D, now)) GREEN else DIM)
    }
    Line {
        put(" RPM ", DIM)
        put((rpm?.roundToInt()?.toString() ?: "--").padStart(5), s.tint(0x0C, now) { Tiago.rpmLevel(it) })
        put(" ")
        bar(rpm, Tiago.RPM_SCALE, 25) { Tiago.rpmLevel(it).color() }
    }
    Line {
        put(" PED ", DIM)
        put(s.compact(0x5A, "%.0f%%").padStart(4), s.tint(0x5A, now))
        put(" ")
        bar(s.number(0x5A), 100.0, 7)
        put("  THR ", DIM)
        put(s.compact(0x45, "%.0f%%").padStart(4), s.tint(0x45, now))
        put(" ")
        bar(s.number(0x45), 100.0, 7)
    }
    Line {
        put(" COOL ", DIM)
        put(s.compact(0x05, "%.0f°C").padStart(5), s.tint(0x05, now) { Tiago.coolantLevel(it) })
        put("  OIL ", DIM)
        put(s.compact(0x5C, "%.0f°C").padStart(5), s.tint(0x5C, now) { Tiago.oilLevel(it) })
        put("  BAT ", DIM)
        put(s.compact(0x42, "%.1fV").padStart(5), s.tint(0x42, now) { Tiago.batteryLevel(it) })
    }
    // Spoken-style warnings in one place, so nothing amber/red goes unnoticed.
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
        put(" > ", DIM)
        if (alerts.isEmpty()) put("all normal", DIM)
        alerts.forEachIndexed { i, (text, color) ->
            if (i > 0) put(" · ", DIM)
            put(text, color)
        }
    }
}

/** Engine, fuel and car details in label/value pairs. */
@Composable
private fun DetailBlock(s: Snapshot, now: Long) {
    Section("ENGINE")
    Pair2(s, now, "load", 0x04, "timing", 0x0E)
    Pair2(s, now, "MAP", 0x0B, "torque", 0x62)
    Pair2(s, now, "trim S", 0x06, "trim L", 0x07, level2 = { Tiago.fuelTrimLevel(it) })
    Line {
        put(" ")
        cell("λ cmd", s.text(0x44), s.tint(0x44, now))
        put("  ")
        cell("fuel", fuelSystemShort(s.number(0x03)), s.tint(0x03, now))
    }
    Section("FUEL")
    Pair2(s, now, "level", 0x2F, "rate", 0x5E)
    Section("AIR")
    Pair2(s, now, "intake", 0x0F, "outside", 0x46, level1 = { Tiago.intakeLevel(it) })
    Pair2(s, now, "baro", 0x33, "cat", 0x3C, level2 = { Tiago.catalystLevel(it) })
    Section("CAR")
    Line {
        put(" ")
        cell("battery", s.text(0x42), s.tint(0x42, now) { Tiago.batteryLevel(it) })
        put("  ")
        cell("port", s.adapterVoltage?.let { String.format(Locale.US, "%.1f V", it) } ?: "--", GREEN)
    }
    Line {
        put(" ")
        cell("run", s.number(0x1F)?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "--", s.tint(0x1F, now))
        put("  ")
        cell("since clr", s.text(0x31), s.tint(0x31, now))
    }
}

/** Two label/value cells on one line, each coloured by its warning level. */
@Composable
private fun Pair2(
    s: Snapshot,
    now: Long,
    l1: String,
    p1: Int,
    l2: String,
    p2: Int,
    level1: (Double?) -> Level = { Level.OK },
    level2: (Double?) -> Level = { Level.OK },
) = Line {
    put(" ")
    cell(l1, s.text(p1), s.tint(p1, now, level1))
    put("  ")
    cell(l2, s.text(p2), s.tint(p2, now, level2))
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
                    if (i > 0) put("  ")
                    val color = if (now - r.time < OLD_MS) GREEN else DIM
                    put("%02X ".format(r.pid), DIM)
                    put(r.name.take(19).padEnd(20), DIM)
                    put(r.text.take(17).padEnd(17), color)
                }
            }
        }
    }
}

@Composable
private fun LogView(s: Snapshot, adapterName: String?, actions: Actions) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Key("ADAPTER: ${adapterName ?: "choose"}", false, GREEN, actions.pickAdapter)
            Key("SHARE", false, GREEN, actions.share)
        }
        Line { put(" fast read: ${s.fastRead.ifEmpty { "-" }}", DIM) }
        LazyColumn {
            items(s.log.reversed()) { line -> Line { put(" $line") } }
        }
    }
}
