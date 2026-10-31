package io.github.arka01.cardash

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import io.github.arka01.cardash.ai.AiSettings
import io.github.arka01.cardash.ai.LocalLlm
import io.github.arka01.cardash.cars.Cars
import io.github.arka01.cardash.logic.GearCalibration
import io.github.arka01.cardash.obd.Adapters
import io.github.arka01.cardash.obd.SavedAdapter
import io.github.arka01.cardash.ui.Actions
import io.github.arka01.cardash.ui.TerminalApp

/**
 * Phone screen. The look is in ui/Terminal.kt; this class handles Bluetooth permission, adapter,
 * sharing and the dialogs (car choice, NEW CAR questions, REFUEL, GEARS, REPLAY, AI).
 */
class MainActivity : ComponentActivity() {
    private var adapterName by mutableStateOf<String?>(null)
    private var voiceOn by mutableStateOf(true)
    private var aiSettings by mutableStateOf<AiSettings?>(null)

    /** IMPORT MODEL: Android's file picker; the chosen .litertlm file is copied into the app. */
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) LocalLlm.importModel(this, uri)?.let { toast(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val black = android.graphics.Color.BLACK
        enableEdgeToEdge(SystemBarStyle.dark(black), SystemBarStyle.dark(black))

        adapterName = Adapters.chosen(this)?.name
        Cars.init(this)
        Speech.init(this)
        voiceOn = !Speech.muted(this)
        CrashReport.init(this)   // first: catches crashes, and reads why the app closed last time
        LocalLlm.init(this)
        aiSettings = LocalLlm.settings(this)
        if (Dash.state.value.car == null) Dash.chooseCar(this, Cars.active(this).key)
        val actions = Actions(
            connect = ::connect,
            demo = { Dash.startDemo(this) },
            stop = { Dash.stop() },
            pickAdapter = ::pickAdapter,
            share = ::share,
            keyTest = ::keyTest,
            shareTest = ::shareTest,
            markKey = { Dash.markKey(it) },
            shareDrive = ::shareDrive,
            pickCar = ::pickCar,
            newCar = { readyAdapter()?.let { Dash.startProbe(this, it, newCar = true) } },
            probe = { readyAdapter()?.let { Dash.startProbe(this, it, newCar = false) } },
            gears = ::calibrateGears,
            refuel = ::refuel,
            replay = ::replay,
            voice = {
                voiceOn = !voiceOn
                Speech.setMuted(this, !voiceOn)
            },
            aiImport = { pickModel.launch(arrayOf("*/*")) },
            aiRemove = ::removeModel,
            aiAsk = ::askAi,
            aiTest = { LocalLlm.ask(this, LocalLlm.TEST_QUESTION)?.let { toast(it) } },
            aiStop = { LocalLlm.stop() },
            aiSetting = ::editAiSetting,
            shareCrash = ::shareCrash,
            clearCrash = { CrashReport.clear(this) },
            update = ::openStore,
        )
        setContent {
            val snap by Dash.state.collectAsState()
            // A finished NEW CAR scan of a car not set up yet: ask the questions.
            LaunchedEffect(snap.probe) { snap.probe?.let { askNewCar(it) } }
            TerminalApp(adapterName, voiceOn, aiSettings ?: LocalLlm.settings(this), actions)
        }

        if (!Adapters.hasPermission(this)) askPermission()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        adapterName = Adapters.chosen(this)?.name   // paired devices are only visible with the permission
    }

    private fun connect() {
        val adapter = readyAdapter() ?: return
        Dash.startLive(this, adapter)
    }

    private fun keyTest() {
        val adapter = readyAdapter() ?: return
        Dash.startKeyTest(this, adapter)
    }

    /** The chosen adapter, or null after asking for the permission / a choice first. */
    private fun readyAdapter(): SavedAdapter? {
        if (!Adapters.hasPermission(this)) {
            askPermission()
            return null
        }
        val adapter = Adapters.chosen(this)
        if (adapter == null) pickAdapter()
        return adapter
    }

    private fun pickAdapter() {
        if (!Adapters.hasPermission(this)) {
            askPermission()
            return
        }
        val paired = Adapters.paired(this)
        if (paired.isEmpty()) {
            Toast.makeText(this, "Pair the vLinker in Android's Bluetooth settings first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Which device is the OBD adapter?")
            .setItems(paired.map { it.name }.toTypedArray()) { _, i ->
                Adapters.save(this, paired[i])
                adapterName = paired[i].name
            }
            .show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Car Dash ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, Report.full(Dash.state.value))
        }
        startActivity(Intent.createChooser(send, "Share readings"))
    }

    /** Sends the newest KEY TEST file (CSV) as an attachment, e.g. by email. */
    private fun shareTest() {
        val file = Dash.latestKeyTest(this)
        if (file == null) {
            Toast.makeText(this, "No key test recorded yet", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Car Dash key test ${file.name}")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share key test"))
    }

    /** Sends the newest drive recording (CSV + profile JSON) as attachments. */
    private fun shareDrive() {
        val files = Dash.latestDrive(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "No drive recorded yet", Toast.LENGTH_LONG).show()
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, "Car Dash drive ${files.first().name}")
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(files.first().name, uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share drive"))
    }

    // ---------------------------------------------------------------- cars and their tools

    private fun pickCar() {
        val keys = Cars.all()
        val names = keys.map { key -> Cars.load(key).let { "${it.shortName}  ·  ${it.name}" } }
        AlertDialog.Builder(this)
            .setTitle("Which car? (NEW CAR sets up another)")
            .setItems(names.toTypedArray()) { _, i ->
                Dash.chooseCar(this, keys[i])
                if (Dash.isRunning) toast("Used from the next start")
            }
            .show()
    }

    /** The NEW CAR questions (new_car_probe.py asks the same); every answer is optional. */
    private fun askNewCar(p: ProbeResult) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        fun field(hint: String, value: String, type: Int): EditText {
            box.addView(TextView(this).apply { text = hint })
            return EditText(this).apply { setText(value); inputType = type }.also { box.addView(it) }
        }
        val text = InputType.TYPE_CLASS_TEXT
        val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val name = field("Car name, e.g. Maruti Swift 1.2 petrol (2019)", p.maker?.let { "$it car" } ?: "My car", text)
        val fuel = field("Fuel (petrol / diesel / cng)", p.fuel ?: "petrol", text)
        val tank = field("Fuel tank size in litres (owner's manual)", "", decimal)
        val engine = field("Engine size in litres or cc, e.g. 1.2 or 1199", "", decimal)
        val redline = field("RPM where the display turns red (your own limit)", "4000", InputType.TYPE_CLASS_NUMBER)
        AlertDialog.Builder(this)
            .setTitle("New car" + (p.vin?.let { " · VIN $it" } ?: ""))
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Set up") { _, _ ->
                val size = engine.text.toString().toDoubleOrNull()
                val key = Dash.createCar(
                    this, p,
                    title = name.text.toString().trim().ifEmpty { "My car" },
                    fuel = fuel.text.toString().trim().lowercase().ifEmpty { "petrol" },
                    tank = tank.text.toString().toDoubleOrNull(),
                    engine = size?.let { if (it > 20) it / 1000 else it },   // cc -> litres
                    redline = redline.text.toString().toIntOrNull() ?: 4000,
                )
                toast("Set up: $key. Drive through every gear, then GEARS.")
            }
            .setNegativeButton("Cancel") { _, _ -> Dash.clearProbe() }
            .show()
    }

    /** REFUEL: litres from the pump receipt, and whether it was filled to the automatic cut-off. */
    private fun refuel() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val litres = EditText(this).apply {
            hint = "litres added (receipt)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val full = CheckBox(this).apply { text = "Filled to the automatic cut-off (full)" }
        box.addView(litres)
        box.addView(full)
        AlertDialog.Builder(this)
            .setTitle("Fill-up")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val l = litres.text.toString().toDoubleOrNull()
                if (l == null && !full.isChecked) toast("Nothing saved: enter litres or tick full")
                else toast(Dash.refuel(this, l, full.isChecked))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** GEARS: measure each gear from the car's recorded drives, then offer to save (--calibrate-gears). */
    private fun calibrateGears() {
        val car = Cars.active(this)
        toast("Measuring gears from ${car.shortName}'s drives…")
        Thread {
            val result = try {
                GearCalibration.run(car)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (result == null) {
                    toast("Gear calibration failed")
                    return@runOnUiThread
                }
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Gears: ${car.shortName}")
                    .setMessage(result.lines.joinToString("\n"))
                    .setNegativeButton(if (result.suggested == null) "OK" else "Don't save", null)
                result.suggested?.let { gears ->
                    dialog.setPositiveButton("Save") { _, _ ->
                        Cars.saveGears(car, gears)
                        Dash.chooseCar(this, car.key)   // reload its settings
                        toast("Saved: the gear display now uses them" + if (Dash.isRunning) " (from the next start)" else "")
                    }
                }
                dialog.show()
            }
        }.start()
    }

    /** REPLAY: play a drive of the active car again (the Python --replay). */
    private fun replay() {
        val drives = Dash.drives(this)
        if (drives.isEmpty()) {
            toast("No drive recorded for ${Cars.active(this).shortName} yet")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Replay which drive?")
            .setItems(drives.map { it.name }.toTypedArray()) { _, i -> Dash.startReplay(this, drives[i]) }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- AI

    /** UPDATE: Car Dash's page in the Play Store app (the browser if the Play Store isn't there). */
    private fun openStore() {
        val play = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName"))
            .setPackage("com.android.vending")
        try {
            startActivity(play)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }

    /** Sends the crash report as text (e.g. by email or WhatsApp). */
    private fun shareCrash() {
        val text = CrashReport.report.value ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Car Dash ${BuildConfig.VERSION_NAME} crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share crash report"))
    }

    private fun removeModel() {
        AlertDialog.Builder(this)
            .setTitle("Remove the model from Car Dash?")
            .setMessage("Frees its storage. Import it again to use the AI.")
            .setPositiveButton("Remove") { _, _ -> LocalLlm.remove(this) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** ASK: type a question; the answer appears on the AI screen. */
    private fun askAi() {
        val input = EditText(this).apply {
            setText(LocalLlm.state.value.question)
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("Ask the on-phone AI")
            .setView(input)
            .setPositiveButton("Ask") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) LocalLlm.ask(this, q)?.let { toast(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One AI setting: a text box with the current value (backend: a GPU / CPU choice). */
    private fun editAiSetting(key: String) {
        val s = LocalLlm.settings(this)
        fun apply(next: AiSettings) {
            LocalLlm.save(this, next)
            aiSettings = next
        }
        if (key == "backend") {
            AlertDialog.Builder(this)
                .setTitle("Run the model on")
                .setItems(arrayOf("GPU (usually faster)", "CPU")) { _, i -> apply(s.copy(gpu = i == 0)) }
                .show()
            return
        }
        val (title, value, type) = when (key) {
            "system" -> Triple("System prompt (blank = default)", s.systemPrompt, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            "temperature" -> Triple("Temperature (0 = factual … 1+ = creative)", s.temperature.toString(), DECIMAL)
            "topK" -> Triple("Top-K (e.g. 40)", s.topK.toString(), InputType.TYPE_CLASS_NUMBER)
            "topP" -> Triple("Top-P (0-1, e.g. 0.95)", s.topP.toString(), DECIMAL)
            else -> Triple("Max answer length in tokens (e.g. 1024)", s.maxTokens.toString(), InputType.TYPE_CLASS_NUMBER)
        }
        val input = EditText(this).apply {
            setText(value)
            inputType = type
            if (key == "system") minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                val next = when (key) {
                    "system" -> s.copy(systemPrompt = t.ifEmpty { LocalLlm.DEFAULT_PROMPT })
                    "temperature" -> t.toDoubleOrNull()?.coerceIn(0.0, 2.0)?.let { s.copy(temperature = it) }
                    "topK" -> t.toIntOrNull()?.coerceIn(1, 200)?.let { s.copy(topK = it) }
                    "topP" -> t.toDoubleOrNull()?.coerceIn(0.01, 1.0)?.let { s.copy(topP = it) }
                    else -> t.toIntOrNull()?.coerceIn(64, 3000)?.let { s.copy(maxTokens = it) }
                }
                if (next == null) toast("Not a valid number") else apply(next)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }
}

private const val DECIMAL = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
