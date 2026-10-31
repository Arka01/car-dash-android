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
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.ai.AiSettings
import kotlinx.coroutines.delay

/*
 * The phone app's frame: top bar, the screen chosen in the sidebar, and the sidebar itself
 * (slides in from the left over a dimmed screen; ☰ in the top-left corner). All screens use the
 * modern look in the terminal colours (Modern.kt: Dash, Drives; Screens.kt: the rest).
 */

/** The screens, in sidebar order. */
private enum class Tab(val title: String) {
    DASH("Dash"), INFO("Info"), DRIVES("Drives"), ALL("All sensors"), AI("AI"), DEV("Dev tools"),
}

/** What the screens can ask the activity to do. */
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
    val update: () -> Unit,   // opens Car Dash's Play Store page
)

@Composable
fun CarDashApp(adapterName: String?, voiceOn: Boolean, aiSettings: AiSettings, actions: Actions) {
    val snap by Dash.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.DASH) }   // Dash when the app opens
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

    Box(Modifier.fillMaxSize().background(BG).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(snap, now, tab.title) { menuOpen = true }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.DASH -> DashScreen(snap, now)
                    Tab.INFO -> InfoScreen(snap)
                    Tab.DRIVES -> DrivesScreen(snap)
                    Tab.ALL -> AllScreen(snap, now)
                    Tab.AI -> AiScreen(aiSettings, actions)
                    Tab.DEV -> DevScreen(snap, adapterName, voiceOn, actions)
                }
            }
        }
        Sidebar(menuOpen, tab, snap, actions, close = { menuOpen = false }) {
            tab = it
            menuOpen = false
        }
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
                Spacer(Modifier.height(16.dp))
                for (t in Tab.entries) SideItem(t.title, t == tab) { select(t) }
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
