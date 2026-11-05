package io.github.arka01.cardash.ai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import io.github.arka01.cardash.CarState
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.DriveService
import io.github.arka01.cardash.Mode
import io.github.arka01.cardash.Snapshot
import io.github.arka01.cardash.Speech
import io.github.arka01.cardash.logic.Level
import io.github.arka01.cardash.logic.Rules
import io.github.arka01.cardash.logic.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Talk (2026-11-04): ask Car Dash out loud, even while driving, and hear the answer.
 *
 * 1. Listen: the car's microphone (car screen only; the phone's Talk button was removed 2026-11-05,
 *    the owner asks about drives by voice in Drives instead), until the driver stops speaking.
 *    Nothing heard (e.g. a ghost tap on the Tiago's touch screen) = it stops quietly.
 * 2. Speech to text: Android's speech recognition, on the phone itself where it can.
 * 3. The on-phone AI gets LIVE NOW (the current readings with the app's own verdicts, fuel, trip,
 *    warnings; ~400 tokens, so it answers in a few seconds) and the question; 1-2 sentences.
 * 4. The answer is spoken (car speakers with Android Auto).
 *
 * Unlike the other AI uses, Talk runs while LIVE reads the car. Every Talk is written to the car's
 * talk_log.txt with its timings and the reading rate, so its effect on the readings can be checked.
 */
object Talk {
    private const val TOKENS = 120
    private const val SYSTEM = "You are Car Dash, a calm co-driver answering the driver's spoken question. Use only " +
        "the LIVE NOW data. Its levels (normal, watch, check) were decided by the app with this car's own rules: trust " +
        "them and never judge a number yourself. Answer in one or two short spoken sentences: plain words, no lists, " +
        "no symbols, rounded numbers. If the data doesn't answer the question, say so briefly. RPM is how fast the " +
        "engine turns, not the car's speed. Never ask the driver to look at the phone. Copy every number exactly as " +
        "written in the data, with its unit; never change, join or invent digits; if unsure of a number, leave it out."

    private val status = MutableStateFlow("")
    /** What Talk is doing, for the Dash: "Listening…", "Thinking…", or "" when idle. */
    val state: StateFlow<String> = status
    private val lastTalk = MutableStateFlow("")
    /** The last question and answer, for the Dash ("You: …" / "Car Dash: …"). */
    val last: StateFlow<String> = lastTalk
    @Volatile private var active = false
    private val main = Handler(Looper.getMainLooper())

    /** True if this phone can do Talk (speech from recorded sound needs Android 13). */
    fun supported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Starts one Talk: listen on [mic], understand, ask, speak. [show] gets short messages for the
     * screen it was started from (the car screen shows them as toasts). Returns why not, or null.
     */
    fun start(context: Context, mic: Mic, show: (String) -> Unit = {}): String? {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "Talk needs Android 13 or newer"
        if (!LocalLlm.hasModel(app)) return "Talk needs the AI: import a model in Settings → AI"
        if (active || LocalLlm.state.value.busy) return "Busy: wait for the last answer"
        active = true
        fun say(text: String) {
            status.value = text
            main.post { show(text) }
        }
        say("Listening…")
        LocalLlm.preload(app)   // loads the model while the driver speaks (~10 s the first time)
        Thread {
            val pressed = System.currentTimeMillis()
            // While LIVE, the drive service also declares the microphone, so Android lets Car Dash hear
            // with the phone locked (Android 14 "while in use" rule). Its result goes into the log.
            if (DriveService.micOn(app)) Thread.sleep(300)
            val focus = holdFocus(app)
            val sound = try {
                listen(mic)
            } catch (e: Throwable) {
                note(app, "mic failed (${mic.name}): ${e.javaClass.simpleName}: ${e.message}")
                null
            } finally {
                focus?.let { (am, request) -> am.abandonAudioFocusRequest(request) }
                DriveService.micOff(app)
            }
            if (sound == null) {
                note(app, "nothing heard (${mic.name})")
                finish("")
                main.post { show("Didn't hear anything") }
                return@Thread
            }
            say("Understanding…")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@Thread
            recognize(app, sound) { text, how ->
                val heard = (System.currentTimeMillis() - pressed) / 1000.0
                if (text.isNullOrBlank()) {
                    note(app, String.format(Locale.US, "not understood after %.1f s (%s; %s)", heard, mic.name, how))
                    Speech.reply(app, "Sorry, I didn't catch that.")
                    finish("")
                    return@recognize
                }
                note(app, String.format(Locale.US, "asked after %.1f s (%s; %s; reading %.1f/s): %s",
                    heard, mic.name, how, Dash.state.value.roundsPerSec, text))
                say("Thinking…")
                ask(app, text, show)
            }
        }.start()
        return null
    }

    /** The question to the AI with LIVE NOW; the answer is spoken and logged. */
    private fun ask(app: Context, question: String, show: (String) -> Unit) {
        val asked = System.currentTimeMillis()
        val prompt = live(Dash.state.value) + "\nQUESTION: " + question
        val why = LocalLlm.ask(app, prompt, system = SYSTEM, maxTokens = TOKENS, allowLive = true, shown = question,
            greedy = true) { answer ->
            val text = answer?.let { DriveSummary.plain(it).replace(Regex("\\s+"), " ").trim() }.orEmpty()
            // Numbers not in LIVE NOW: those sentences aren't spoken (no time for a second try while driving).
            val bad = NumberCheck.bad(text, SYSTEM + "\n" + prompt)
            val spoken = if (bad.isEmpty()) text else NumberCheck.withoutBad(text, bad)
            note(app, String.format(Locale.US, "answered in %.1f s (%s; reading %.1f/s): %s%s",
                (System.currentTimeMillis() - asked) / 1000.0, LocalLlm.state.value.stats, Dash.state.value.roundsPerSec,
                text.ifEmpty { "(no answer)" }, if (bad.isEmpty()) "" else " | not in the data, not spoken: ${bad.joinToString(", ")}"))
            val say = when {
                text.isEmpty() -> "Sorry, I couldn't answer that."
                spoken.isEmpty() -> "I'm not sure about that one. Ask me again when you're parked."
                else -> spoken
            }
            Speech.reply(app, say)
            lastTalk.value = "You: $question\nCar Dash: $say"
            finish("")
        }
        if (why != null) {
            note(app, "not asked: $why")
            Speech.reply(app, "Sorry, the AI isn't ready.")
            finish("")
            main.post { show(why) }
        }
    }

    private fun finish(text: String) {
        status.value = text
        active = false
    }

    // ------------------------------------------------------------ listening

    /**
     * Records until the driver has spoken and then been quiet for [END_SILENCE_MS]: a simple loudness
     * check, measured against the first moment's background noise (engine, road). Null if no speech
     * started within [START_TIMEOUT_MS]. At most [MAX_MS] of sound.
     */
    private fun listen(mic: Mic): ByteArray? {
        val out = ByteArrayOutputStream()
        val frame = ByteArray(Mic.RATE / 50 * 2)   // up to 20 ms (the car's microphone may give less per read)
        var noise = 0.0
        var bytes = 0L
        var speechMs = 0
        var quietMs = 0
        var heardAt = -1L
        mic.start()
        try {
            while (true) {
                val n = mic.read(frame)
                if (n <= 0) break
                bytes += n
                val ms = bytes * 1000 / (Mic.RATE * 2)   // time into the recording
                val pieceMs = n * 1000 / (Mic.RATE * 2)
                val level = rms(frame, n)
                if (ms <= 200) noise = maxOf(noise, level)   // the first 200 ms: background noise
                val loud = ms > 200 && level > maxOf(noise * 2.5, 300.0)
                out.write(frame, 0, n)   // keep the start too: the first word may be quiet
                if (heardAt < 0) {
                    speechMs = if (loud) speechMs + pieceMs else 0
                    if (speechMs >= 100) heardAt = ms   // 100 ms of speech
                    if (heardAt < 0 && ms > START_TIMEOUT_MS) return null
                } else {
                    quietMs = if (loud) 0 else quietMs + pieceMs
                    if (quietMs >= END_SILENCE_MS || ms > MAX_MS) break
                }
            }
        } finally {
            runCatching { mic.stop() }
        }
        return if (heardAt >= 0) out.toByteArray() else null
    }

    private fun rms(b: ByteArray, n: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return sqrt(sum / maxOf(1, n / 2))
    }

    /** Pauses music while listening (Android Auto expects an assistant to ask for this). */
    private fun holdFocus(context: Context): Pair<AudioManager, AudioFocusRequest>? = runCatching {
        val am = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        am.requestAudioFocus(request)
        am to request
    }.getOrNull()

    // ------------------------------------------------------------ speech to text

    /**
     * The recorded sound (car microphone) to text, trying in turn: Android's standard speech service
     * in Indian English (may use the internet if no offline language is installed), then the phone's
     * own on-device recognizer in Indian English, then in its default English. 2026-11-05: the
     * on-device one heard "no words" every time on the owner's phone, so it comes last, and "no
     * words" goes on to the next one. [done] gets the text (null if nothing) and what each one did.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun recognize(context: Context, sound: ByteArray, done: (String?, String) -> Unit) {
        val onDevice: () -> SpeechRecognizer = { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
        val standard: () -> SpeechRecognizer = { SpeechRecognizer.createSpeechRecognizer(context) }
        val tries = buildList<Triple<String, () -> SpeechRecognizer, String?>> {
            if (SpeechRecognizer.isRecognitionAvailable(context)) add(Triple("standard en-IN", standard, "en-IN"))
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                add(Triple("on-device en-IN", onDevice, "en-IN"))
                add(Triple("on-device", onDevice, null))
            }
        }
        val failures = mutableListOf<String>()
        fun attempt(i: Int) {
            if (i >= tries.size) {
                done(null, failures.joinToString("; ").ifEmpty { "no speech recognition on this phone" })
                return
            }
            val (name, create, language) = tries[i]
            main.post {
                val recognizer = runCatching { create() }.getOrNull()
                if (recognizer == null) {
                    failures += "$name: unavailable"
                    attempt(i + 1)
                    return@post
                }
                val (read, write) = ParcelFileDescriptor.createPipe()
                var finished = false
                fun end(text: String?, error: Int?) {
                    if (finished) return
                    finished = true
                    runCatching { recognizer.destroy() }
                    runCatching { read.close() }
                    if (text != null) {
                        done(text, (failures + name).joinToString("; "))
                    } else {
                        failures += "$name: " + if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                            "no words (error $error)" else "error $error"
                        attempt(i + 1)
                    }
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (text.isNullOrBlank()) end(null, SpeechRecognizer.ERROR_NO_MATCH) else end(text, null)
                    }
                    override fun onError(error: Int) = end(null, error)
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Mic.RATE)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                }
                runCatching { recognizer.startListening(intent) }.onFailure {
                    runCatching { write.close() }
                    end(null, -1)
                    return@post
                }
                Thread {   // the sound, then end of file: the recognizer finishes with what it got
                    runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(sound) } }
                }.start()
                main.postDelayed({ end(null, -2) }, RECOGNIZE_TIMEOUT_MS)   // no answer from it: try the next
            }
        }
        attempt(0)
    }

    // ------------------------------------------------------------ LIVE NOW

    /** The car right now, with the app's own levels, for the AI (~400 tokens). */
    fun live(s: Snapshot): String = buildString {
        append("LIVE NOW (").append(TimeFormat.clock(System.currentTimeMillis())).append(")\n")
        if (s.mode != Mode.LIVE) {
            append("Car Dash is not reading the car right now")
            if (s.derived != null) append("; these are the last readings")
            append(".\n")
        }
        s.car?.let { append("Car: ").append(it.name).append('\n') }
        if (s.mode == Mode.LIVE && s.carState != CarState.UNKNOWN) append("State: ").append(s.carState.label.lowercase()).append('\n')
        val d = s.derived
        val v = d?.values
        fun n(label: String) = v?.get(label)
        n("Speed")?.let { append(String.format(Locale.US, "Speed %.0f km/h", it)) }
        n("RPM")?.let { rpm ->
            append(String.format(Locale.US, ", engine %.0f rpm", rpm))
            s.car?.let { append(" (").append(word(Rules.rpm(rpm, it, d?.warmUp?.rpmLimit))).append(')') }
        }
        n("Gear (estimated)")?.takeIf { it > 0 }?.let { append(String.format(Locale.US, ", gear %.0f", it)) }
        append('\n')
        n("Coolant")?.let { append(String.format(Locale.US, "Coolant %.0f °C (%s)", it, word(Rules.coolant(it)))) }
        n("Oil")?.let { append(String.format(Locale.US, ", oil %.0f °C (%s)", it, word(Rules.oil(it)))) }
        n("Intake air")?.let { append(String.format(Locale.US, ", intake air %.0f °C (%s)", it, word(Rules.intake(it)))) }
        append('\n')
        d?.engine?.let { e ->
            append("Engine: ").append(e.state.lowercase())
            if (e.detail.isNotEmpty()) append(", ").append(e.detail)
            e.rpmLimit?.let { append(", keep under $it rpm for now") }
            append(" (").append(word(e.level)).append(")\n")
        }
        d?.battery?.let { b ->
            append(String.format(Locale.US, "Battery %.1f V", b.volts))
            if (b.state.isNotEmpty()) append(", ").append(b.state.lowercase())
            append(" (").append(word(b.level)).append(")")
            if (b.detail.isNotEmpty()) append(": ").append(b.detail)
            append('\n')
        }
        d?.fuel?.let { f ->
            f.litres?.let { l ->
                append(String.format(Locale.US, "Fuel about %.0f litres left of %.0f", l, f.tank))
                f.rangeKm?.let { append(String.format(Locale.US, ", range about %.0f km", it)) }
                append(if (l < 8) " (low)" else " (normal)").append('\n')
            }
            f.tripLPer100km?.let { append(String.format(Locale.US, "Fuel use this trip %.1f L/100 km", it)) }
            f.consumptionLPer100km?.let { append(String.format(Locale.US, ", usual %.1f L/100 km", it)) }
            if (f.tripLPer100km != null || f.consumptionLPer100km != null) append('\n')
        }
        val km = n("Trip distance")
        val avg = n("Trip avg speed")
        if (km != null && km > 0.05) {
            append(String.format(Locale.US, "This trip %.1f km", km))
            if (avg != null && avg > 0) append(String.format(Locale.US, " in %.0f min, average %.0f km/h", km / avg * 60, avg))
            append('\n')
        }
        val alerts = d?.alerts.orEmpty()
        append("Warnings now: ").append(if (alerts.isEmpty()) "none" else alerts.joinToString(", ") { it.text.lowercase() }).append('\n')
        s.troubleCodes?.takeIf { it.isNotBlank() }?.let { append("Trouble codes: ").append(it).append('\n') }
        if (d == null) append("No readings yet.\n")
    }

    private fun word(level: Level) = when (level) {
        Level.OK -> "normal"
        Level.WARN -> "watch"
        Level.ALARM -> "check"
    }

    // ------------------------------------------------------------ log

    /** One line per step in the car's talk_log.txt (24-hour times, to match the drive recordings). */
    fun note(context: Context, line: String) {
        val file: File = Dash.state.value.car?.file("talk_log.txt") ?: File(context.filesDir, "talk_log.txt")
        runCatching {
            if (file.length() > 200_000) file.writeText(file.readLines().takeLast(500).joinToString("\n", postfix = "\n"))
            file.appendText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + " | " + line + "\n")
        }
    }

    private const val START_TIMEOUT_MS = 5_000    // no speech by then: stop (e.g. a ghost tap)
    private const val END_SILENCE_MS = 1_200      // quiet this long after speaking = done
    private const val MAX_MS = 12_000
    private const val RECOGNIZE_TIMEOUT_MS = 15_000L
}
