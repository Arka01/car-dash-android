package io.github.arka01.cardash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arka01.cardash.BuildConfig
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.GearLearner
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.ai.AiSettings
import io.github.arka01.cardash.cars.Cars
import kotlinx.coroutines.delay

/*
 * The phone app's frame: top bar, the screen chosen in the sidebar, and the sidebar itself
 * (slides in from the left over a dimmed screen; ☰ in the top-left corner). All screens use the
 * modern look in the terminal colours (Modern.kt: Dash, Drives; Screens.kt: the rest).
 */

/**
 * The screens, in sidebar order, from a new customer's point of view (2026-11-01): driving, live
 * details, past drives (with the AI), this car, app settings, and support (logs, crash report).
 */
private enum class Tab(val title: String) {
    DASH("Dash"), LIVE("Live details"), DRIVES("Drives"), CAR("Car"), SETTINGS("Settings"), SUPPORT("Support"),
}

/** What the screens can ask the activity to do. */
class Actions(
    val connect: () -> Unit,
    val demo: () -> Unit,
    val stop: () -> Unit,
    val pickAdapter: () -> Unit,
    val shareLog: () -> Unit,
    val shareDrive: (java.io.File) -> Unit,   // a drive's CSV (+ profile, report, summary)
    val replayDrive: (java.io.File) -> Unit,
    val askDrive: (String) -> Unit,           // type a question about this drive (CSV file name)
    val chooseCar: (String) -> Unit,          // switch to this car (folder name)
    val newCar: () -> Unit,
    val scanCar: () -> Unit,                  // read-only scan of the car's computers
    val editCar: (String) -> Unit,            // change one car setting: tank, engine, redline, shift
    val refuel: () -> Unit,
    val voice: () -> Unit,
    val aiImport: () -> Unit,
    val aiRemove: () -> Unit,
    val aiStop: () -> Unit,
    val aiSetting: (String) -> Unit,          // edit one AI setting: system, temperature, topK, topP, maxTokens, backend
    val shareCrash: () -> Unit,
    val clearCrash: () -> Unit,
    val update: () -> Unit,                   // opens Car Dash's Play Store page
)

@Composable
fun CarDashApp(adapterName: String?, voiceOn: Boolean, aiSettings: AiSettings, actions: Actions) {
    val snap by Dash.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.DASH) }   // Dash when the app opens
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    // Screens opened before, for Back (names, so it survives screen rotation).
    var history by rememberSaveable { mutableStateOf("") }
    val previous = history.split(",").filter { it.isNotEmpty() }
    // Back (button or swipe): the previous screen; on Dash with nothing before, Android closes the app.
    // Handlers composed later win: a drive's report (Drives) and the open sidebar come first.
    BackHandler(enabled = previous.isNotEmpty() || tab != Tab.DASH) {
        val back = previous.lastOrNull()?.let { name -> Tab.entries.firstOrNull { it.name == name } } ?: Tab.DASH
        history = previous.dropLast(1).joinToString(",")
        tab = back
    }
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

    Box(Modifier.fillMaxSize().background(BG).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(snap, now, tab.title) { menuOpen = true }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.DASH -> DashScreen(snap, now, actions)
                    Tab.LIVE -> LiveScreen(snap, now, actions)
                    Tab.DRIVES -> DrivesScreen(snap, actions)
                    Tab.CAR -> CarScreen(snap, actions)
                    Tab.SETTINGS -> SettingsScreen(snap, adapterName, voiceOn, aiSettings, actions)
                    Tab.SUPPORT -> SupportScreen(snap, adapterName, actions)
                }
            }
        }
        Sidebar(menuOpen, tab, snap, actions, close = { menuOpen = false }) {
            if (it != tab) history = (previous + tab.name).takeLast(10).joinToString(",")
            tab = it
            menuOpen = false
        }
        BackHandler(enabled = menuOpen) { menuOpen = false }   // Back closes the sidebar first
    }
}

/**
 * The sidebar: the screens, then LIVE / STOP and UPDATE. Tapping a screen opens it and closes the
 * sidebar; tapping the dimmed part or Back just closes it. (Not opened by swiping from the edge:
 * on Android that swipe means Back.)
 */
@Composable
private fun Sidebar(open: Boolean, tab: Tab, s: Snapshot, actions: Actions, close: () -> Unit, select: (Tab) -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        )
    }
    AnimatedVisibility(open, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
        Row(Modifier.fillMaxHeight()) {
            Column(Modifier.width(260.dp).fillMaxHeight().background(CARD).padding(12.dp)) {
                Txt("Car Dash", 22.sp, NEON, FontWeight.Bold, glow = true)
                Txt(BuildConfig.VERSION_NAME, 13.sp, FAINT, mono = true)
                Spacer(Modifier.height(12.dp))
                CarSwitcher(s, actions, close)
                Spacer(Modifier.height(12.dp))
                val gears by GearLearner.suggestion.collectAsState()
                for (t in Tab.entries) {
                    // A dot on Car while newly learned gears wait for the owner's OK.
                    val title = if (t == Tab.CAR && gears != null) "${t.title}  ●" else t.title
                    SideItem(title, t == tab) { select(t) }
                }
                Spacer(Modifier.height(16.dp))
                when (s.mode) {
                    Mode.OFF -> Button("▶  Start LIVE", NEON, Modifier.fillMaxWidth()) { actions.connect(); close() }
                    Mode.LIVE -> Button("■  Stop", NEON, Modifier.fillMaxWidth(), filled = true) { actions.stop(); close() }
                    else -> Button("■  Stop ${s.mode.name.lowercase()}", WARN, Modifier.fillMaxWidth()) { actions.stop(); close() }
                }
                Spacer(Modifier.weight(1f))
                // Play Store page: shows "Update" there if a newer version is out (10–15 min after a build).
                SideItem("Update app", false, FAINT) { actions.update(); close() }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(CARD_BORDER))
        }
    }
}

/**
 * The car in use, at the top of the sidebar: tap it for the list of cars set up on this phone
 * (tap one to switch: screens, drives and settings follow) and "Add a new car". Not while LIVE
 * reads a car (one recording is for one car).
 */
@Composable
private fun CarSwitcher(s: Snapshot, actions: Actions, close: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = s.car
    val cars = remember(current?.key, expanded) { if (expanded) Cars.all().map { Cars.load(it) } else emptyList() }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, CARD_BORDER, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(current?.shortName ?: "No car", 18.sp, NEON, FontWeight.SemiBold, glow = true)
                Txt(current?.name ?: "", 12.sp, FAINT, maxLines = 1)
            }
            Txt(if (expanded) "▴" else "▾", 18.sp, MUTED)
        }
        if (expanded) {
            for (car in cars) {
                val isCurrent = car.key == current?.key
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (!isCurrent) actions.chooseCar(car.key)
                        expanded = false
                        close()
                    }.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Txt(if (isCurrent) "✓  " else "    ", 15.sp, NEON)
                    Txt(car.name, 15.sp, if (isCurrent) NEON else MUTED, maxLines = 2)
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable {
                    expanded = false
                    close()
                    actions.newCar()
                }.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Txt("+  Add a new car", 15.sp, NEON, FontWeight.Medium)
            }
        }
    }
}

/** One sidebar entry: a full-width row, highlighted when it's the open screen. */
@Composable
private fun SideItem(label: String, selected: Boolean, color: Color = NEON, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(shape)
            .background(if (selected) NEON.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, CARD_BORDER, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 17.sp, if (selected) NEON else if (color == NEON) MUTED else color, if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
