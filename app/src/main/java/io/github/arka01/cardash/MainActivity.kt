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
import io.github.arka01.cardash.ai.DriveSummary
import io.github.arka01.cardash.ai.LocalLlm
import io.github.arka01.cardash.car.CarControls
import io.github.arka01.cardash.cars.Cars
import io.github.arka01.cardash.logic.GearCalibration
import io.github.arka01.cardash.obd.Adapters
import io.github.arka01.cardash.obd.SavedAdapter
import io.github.arka01.cardash.ui.Actions
import io.github.arka01.cardash.ui.CarDashApp

/**
 * Phone screen. The look is in ui/ (App.kt, Modern.kt, Screens.kt); this class handles Bluetooth permission, adapter,
 * sharing and the dialogs (NEW CAR questions, fill-up, car settings, questions about a drive, AI settings).
 */
class MainActivity : ComponentActivity() {
    private var adapterName by mutableStateOf<String?>(null)
    private var voiceOn by mutableStateOf(true)
    private var aiSettings by mutableStateOf<AiSettings?>(null)

    /** IMPORT MODEL: Android's file picker; the chosen .litertlm file is copied into the app. */
    /** Talk (phone): asks for the microphone the first time, then starts. */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) talk() else toast("Talk needs the microphone")
    }

    private fun talk() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val why = runCatching { io.github.arka01.cardash.ai.Talk.startPhone(this) }
            .getOrElse { "Talk failed: ${it.message}" }
        why?.let { toast(it) }
    }

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
        CarControls.watchConnection(this)   // for "still in the car?" (spoken summaries)
        voiceOn = !Speech.muted(this)
        CrashReport.init(this)   // first: catches crashes, and reads why the app closed last time
        LocalLlm.init(this)
        GearLearner.check(this)   // new gear values from the drives? (shown on the Car screen)
        aiSettings = LocalLlm.settings(this)
        if (Dash.state.value.car == null) Dash.chooseCar(this, Cars.active(this).key)
        val actions = Actions(
            connect = ::connect,
            demo = { Dash.startDemo(this) },
            stop = { Dash.stop() },
            pickAdapter = ::pickAdapter,
            shareLog = ::share,
            shareDrive = ::shareDrive,
            replayDrive = { Dash.startReplay(this, it) },
            askDrive = ::askAboutDrive,
            askDrives = ::askAboutDrives,
            shareDrives = ::shareDrives,
            talk = ::talk,
            chooseCar = ::switchCar,
            newCar = { readyAdapter()?.let { Dash.startProbe(this, it, newCar = true) } },
            scanCar = { readyAdapter()?.let { Dash.startProbe(this, it, newCar = false) } },
            editCar = ::editCar,
            refuel = ::refuel,
            voice = {
                voiceOn = !voiceOn
                Speech.setMuted(this, !voiceOn)
            },
            voiceSetting = ::voiceSetting,
            aiImport = { pickModel.launch(arrayOf("*/*")) },
            aiRemove = ::removeModel,
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
            CarDashApp(adapterName, voiceOn, aiSettings ?: LocalLlm.settings(this), actions)
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

    /** Share log (Support): the readings and the connection log as text. */
    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Car Dash ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, Report.full(Dash.state.value))
        }
        startActivity(Intent.createChooser(send, "Share readings"))
    }

    /**
     * Share (in a drive's report): one ZIP with its recording, profile, report, AI summary and the car's
     * fuel_state.json, e.g. by email.
     * A ZIP so apps pass the files on untouched (a loose CSV gets converted by some apps) and each file
     * carries a checksum, so a damaged copy fails to unzip instead of giving wrong data.
     */
    private fun shareDrive(csv: java.io.File) {
        val files = driveFiles(csv)
        if (files.isEmpty()) {
            toast("This drive's files are gone")
            return
        }
        shareZip("CarDash_${csv.name.removeSuffix(".csv")}.zip", "Car Dash drive ${driveLabel(csv.name)}", "Share drive",
            files + fuelState(csv), emptyMap())
    }

    /**
     * Share (Drives → Ask about several drives): one ZIP with the chosen drives' files, the car's
     * fuel_state.json and ai_answer.txt (question, answer and the exact prompt, D1 … tags explained).
     */
    private fun shareDrives(csvNames: List<String>) {
        val csvs = Dash.drives(this).filter { it.name in csvNames }.sortedBy { it.name }   // oldest first, as D1 …
        val files = csvs.flatMap { driveFiles(it) }
        if (files.isEmpty()) {
            toast("These drives' files are gone")
            return
        }
        val ai = LocalLlm.state.value
        val answered = DriveSummary.lastAskDrive.value == DriveSummary.SEVERAL && ai.question.isNotEmpty() && !ai.busy
        val text = if (!answered) emptyMap() else mapOf("ai_answer.txt" to buildString {
            append("Car Dash ${BuildConfig.VERSION_NAME} · question about ${csvs.size} drive${if (csvs.size == 1) "" else "s"}\n")
            append("Shared ${io.github.arka01.cardash.logic.TimeFormat.day(System.currentTimeMillis())}\n\n")
            append("DRIVES\n")
            csvs.forEachIndexed { i, f -> append("  ${io.github.arka01.cardash.logic.DriveTotals.tag(i)}  ${driveLabel(f.name)}  (${f.name})\n") }
            append("\nQUESTION\n").append(ai.question).append("\n\n")
            append("ANSWER\n").append(DriveSummary.plain(ai.answer)).append("\n")
            if (ai.stats.isNotEmpty()) append("(").append(ai.stats).append(")\n")
            append("\nSYSTEM INSTRUCTION\n").append(ai.sentSystem).append("\n\n")
            append("MESSAGE\n").append(ai.sentPrompt).append("\n")
        })
        val first = csvs.first().name.removePrefix("drive_").removeSuffix(".csv")
        shareZip("CarDash_${csvs.size}_drives_from_$first.zip", "Car Dash: ${csvs.size} drives" + if (answered) " + AI answer" else "",
            "Share drives", files + fuelState(csvs.first()), text)
    }

    /** A drive's recording, profile, report and AI summary (the ones that exist). */
    private fun driveFiles(csv: java.io.File): List<java.io.File> {
        val base = csv.name.removeSuffix(".csv")
        return listOf(csv.name, "${base}_profile.json", "${base}_report.json", "${base}_summary.txt")
            .map { java.io.File(csv.parentFile, it) }.filter { it.exists() }
    }

    /**
     * The car's fuel memory (fuel left, fill-ups, averages) and Talk log (questions, answers, timings):
     * let the developer check the fuel numbers and how Talk affects the readings.
     */
    private fun fuelState(csv: java.io.File): List<java.io.File> =
        listOf("fuel_state.json", "talk_log.txt").mapNotNull { name ->
            csv.parentFile?.parentFile?.let { java.io.File(it, name) }?.takeIf { it.exists() }
        }

    /** Packs [files] and [texts] (name to content) into one ZIP in the background, then opens Android's share menu. */
    private fun shareZip(zipName: String, subject: String, title: String, files: List<java.io.File>, texts: Map<String, String>) {
        Thread {
            val zip = runCatching {
                val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }   // only the latest shared ZIP is kept
                java.io.File(dir, zipName).also { out ->
                    java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
                        files.distinctBy { it.path }.forEach { f ->
                            z.putNextEntry(java.util.zip.ZipEntry(f.name).apply { time = f.lastModified() })
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                        texts.forEach { (name, content) ->
                            z.putNextEntry(java.util.zip.ZipEntry(name))
                            z.write(content.toByteArray(Charsets.UTF_8))
                            z.closeEntry()
                        }
                    }
                }
            }
            runOnUiThread {
                val file = zip.getOrNull() ?: return@runOnUiThread toast("Couldn't pack the files: ${zip.exceptionOrNull()?.message}")
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, title))
            }
        }.start()
    }

    // ---------------------------------------------------------------- cars and their tools

    /** Sidebar car switcher: use another car (not while LIVE / a scan reads a car). */
    private fun switchCar(key: String) {
        when (Dash.state.value.mode) {
            Mode.LIVE, Mode.PROBE, Mode.TEST -> toast("Stop first: one recording is for one car")
            else -> {
                Dash.chooseCar(this, key)
                toast("Now using ${Cars.load(key).shortName}")
            }
        }
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



    /** Ask about this drive (Drives): the question goes to the on-phone AI with this drive's report. */
    private fun askAboutDrive(csvName: String) {
        val input = EditText(this).apply {
            hint = "e.g. How was my drive?"
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle("Ask about this drive")
            .setView(input)
            .setPositiveButton("Ask") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                val ctx = DriveReports.forQuestion(this, csvName)
                val why = if (ctx == null) "This drive has no report yet" else DriveSummary.ask(this, q, ctx.first, ctx.second, ctx.third, csvName, DriveReports.fuelNow(ctx.first))
                why?.let { toast(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Ask about several drives (Drives → Ask AI): their reports plus totals and trends worked out by the app. */
    private fun askAboutDrives(csvNames: List<String>) {
        val input = EditText(this).apply {
            hint = "e.g. Is my fuel use getting better?"
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle("Ask about ${csvNames.size} drive${if (csvNames.size == 1) "" else "s"}")
            .setView(input)
            .setPositiveButton("Ask") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) return@setPositiveButton
                val ctx = DriveReports.forSeveral(this, csvNames)
                val why = if (ctx == null) "These drives have no reports yet" else DriveSummary.askSeveral(this, q, ctx.first, ctx.second, DriveReports.fuelNow(ctx.first))
                why?.let { toast(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Car screen: change tank size, engine size, shift light or red line. */
    private fun editCar(key: String) {
        val car = Cars.active(this)
        val (title, value) = when (key) {
            "tank_litres" -> "Fuel tank size (litres)" to car.tankLitres?.let { String.format(java.util.Locale.US, "%.0f", it) }
            "engine_litres" -> "Engine size (litres, e.g. 1.2)" to car.engineLitres?.let { String.format(java.util.Locale.US, "%.1f", it) }
            "shift_rpm" -> "Shift light from (rpm)" to car.shiftRpm.toString()
            else -> "Red line, your limit (rpm)" to car.redlineRpm.toString()
        }
        val input = EditText(this).apply {
            setText(value ?: "")
            inputType = if (key.endsWith("rpm")) InputType.TYPE_CLASS_NUMBER else DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                val ok = when (key) {
                    "tank_litres" -> t.toDoubleOrNull()?.takeIf { it in 10.0..200.0 }
                    "engine_litres" -> t.toDoubleOrNull()?.let { if (it > 20) it / 1000 else it }?.takeIf { it in 0.5..10.0 }   // cc -> litres
                    else -> t.toIntOrNull()?.takeIf { it in 1000..12000 }
                }
                if (ok == null) {
                    toast("Not a sensible value")
                    return@setPositiveButton
                }
                Cars.saveSetting(car, key, ok)
                Dash.chooseCar(this, car.key)   // reload its settings
                toast(if (Dash.isRunning) "Saved: used from the next start" else "Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Settings → Voice: choose the voice, speed or pitch, or play a sample. */
    private fun voiceSetting(key: String) {
        when (key) {
            "sample" -> Speech.sample(this)
            "voice" -> {
                val voices = Speech.voices()
                if (voices.isEmpty()) {
                    toast("No offline voices found yet: open again in a moment, or install voice data in Android's settings")
                    Speech.init(this)
                    return
                }
                val labels = listOf("Default (English, UK)") + voices.map { Speech.label(it.name) }
                AlertDialog.Builder(this)
                    .setTitle("Voice")
                    .setItems(labels.toTypedArray()) { _, i ->
                        Speech.setVoice(this, if (i == 0) null else voices[i - 1].name)
                        Speech.sample(this)
                    }
                    .show()
            }
            else -> {
                val options = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f)
                val speed = key == "speed"
                AlertDialog.Builder(this)
                    .setTitle(if (speed) "Speed" else "Pitch")
                    .setItems(options.map { String.format(java.util.Locale.US, if (speed) "%.1f×" else "%.1f", it) + if (it == 1.0f) "  (normal)" else "" }.toTypedArray()) { _, i ->
                        if (speed) Speech.setRate(this, options[i]) else Speech.setPitch(this, options[i])
                        Speech.sample(this)
                    }
                    .show()
            }
        }
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
        // Nearby devices (Bluetooth to the adapter); Android 13+: notifications ("Recording your drive").
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }
}

private const val DECIMAL = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

/** "drive_20261031_191520.csv" -> "Sat 31 Oct 2026 · 7:15 PM". */
fun driveLabel(fileName: String): String = runCatching {
    val stamp = fileName.removePrefix("drive_").removeSuffix(".csv")
    val date = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse(stamp)!!
    io.github.arka01.cardash.logic.TimeFormat.day(date.time)
}.getOrDefault(fileName)
