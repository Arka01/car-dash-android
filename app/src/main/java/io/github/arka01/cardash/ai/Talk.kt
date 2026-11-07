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
import io.github.arka01.cardash.DriveReports
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
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Talk (2026-11-04): ask Car Dash out loud, even while driving, and hear the answer.
 *
 * 1. Listen: the car's microphone (car screen only; the phone's Talk button was removed 2026-11-05,
 *    the owner asks about drives by voice in Drives instead), until the driver stops speaking.
 *    Nothing heard (e.g. a ghost tap on the Tiago's touch screen) = it stops quietly.
 * 2. The on-phone AI hears the recording itself (2026-11-06: Android's speech recognition heard "no
 *    words" in every recording handed to it, though the saved sound was clear speech) together with
 *    LIVE NOW (the current readings with the app's own verdicts, fuel, trip, warnings; ~400 tokens), the
 *    drive's report so far with its timeline, earlier drives and the last engine starts (2026-11-07)
 *    and writes what it heard, then a 1-2 sentence answer. A model file without its audio part:
 *    Android's speech recognition, then the AI with the text, as before.
 * 4. The answer is spoken (car speakers with Android Auto).
 *
 * Unlike the other AI uses, Talk runs while LIVE reads the car. Every Talk is written to the car's
 * talk_log.txt with its timings and the reading rate, so its effect on the readings can be checked.
 */
object Talk {
    private const val TOKENS = 120
    // 2026-11-07: with only LIVE NOW, "what is the highest rpm" got the current rpm and "the dip while starting" the
    // current voltage. Now the drive's own report (calculated by the app) comes with it, and which part to use is spelled out.
    private const val SYSTEM = "You are Car Dash, a calm co-driver answering the driver's spoken question. Use only " +
        "the data given: LIVE NOW (this moment), THIS DRIVE SO FAR or LAST DRIVE (the app's report of the whole drive, " +
        "with a TIMELINE of what happened when), EARLIER DRIVES and LAST ENGINE STARTS. For highest, lowest, top, when, " +
        "start dip or how the drive is going, use THIS DRIVE SO FAR, not LIVE NOW. For other days, use EARLIER DRIVES. " +
        "The levels and verdicts (normal, watch, check, OK, WATCH, CHECK) were decided by the app with this car's own " +
        "rules: trust them and never judge a number yourself. Answer in one or two short spoken sentences: plain words, no lists, " +
        "no symbols, rounded numbers. If the data doesn't answer the question, say so briefly. RPM is how fast the " +
        "engine turns, not the car's speed. Never ask the driver to look at the phone. Copy every number exactly as " +
        "written in the data, with its unit; never change, join or invent digits; if unsure of a number, leave it out."

    /** Talk with sound: the AI writes what it heard, then its answer (both read by askHeard). */
    private const val HEARD = "The driver's question is in the audio. Reply in exactly this form:\n" +
        "HEARD: <the question, word for word>\nANSWER: <your answer>\n" +
        "If there is no clear question in the audio, write HEARD: (nothing) and ANSWER: Sorry, I didn't catch that."

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
        LocalLlm.preload(app, audio = true)   // loads the model while the driver speaks (~10 s the first time)
        // The drive's report and history are worked out while the driver speaks, so they add no waiting.
        val prepared = FutureTask(Callable { background() })
        Thread(prepared).start()
        Thread {
            val pressed = System.currentTimeMillis()
            // While LIVE, the drive service also declares the microphone, so Android lets Car Dash hear
            // with the phone locked (Android 14 "while in use" rule). Its result goes into the log.
            if (DriveService.micOn(app)) Thread.sleep(300)
            val focus = holdFocus(app)
            val sound = try {
                listen(app, mic)
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
            val loud = boost(sound)
            note(app, String.format(Locale.US, "volume boost x%.1f (loudest sample %d → %d)", loud.second, loud.third, peak(loud.first)))
            saveLast(app, loud.first)
            say("Thinking…")
            val extra = runCatching { prepared.get(BACKGROUND_WAIT_S, TimeUnit.SECONDS) }.getOrDefault("")
            askHeard(app, wav(loud.first), extra, pressed, mic, show) { recognizeAndAsk(app, loud.first, extra, pressed, mic, show) }
        }.start()
        return null
    }

    /** The old way (a model file that can't hear): Android's speech recognition, then the AI with the text. */
    private fun recognizeAndAsk(app: Context, sound: ByteArray, extra: String, pressed: Long, mic: Mic, show: (String) -> Unit) {
        say(app, show, "Understanding…")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { finish(""); return }
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
            say(app, show, "Thinking…")
            ask(app, text, extra, show)
        }
    }

    private fun say(app: Context, show: (String) -> Unit, text: String) {
        status.value = text
        main.post { show(text) }
    }

    /**
     * The question as sound straight to the AI, with LIVE NOW: it writes "HEARD: …" and "ANSWER: …".
     * [cannotHear] runs if the model fails with sound (e.g. its file has no audio part).
     */
    private fun askHeard(app: Context, wav: ByteArray, extra: String, pressed: Long, mic: Mic, show: (String) -> Unit, cannotHear: () -> Unit) {
        val asked = System.currentTimeMillis()
        val prompt = extra + live(Dash.state.value) + "\n" + HEARD
        saveText(app, "talk_last_prompt.txt", "SYSTEM:\n$SYSTEM\n\nPROMPT (with the question as sound):\n$prompt\n")
        val first = FirstSentence(app, SYSTEM + "\n" + prompt, asked)
        val why = LocalLlm.ask(app, prompt, system = SYSTEM, maxTokens = TOKENS + 40, allowLive = true,
            shown = "(spoken question)", greedy = true, audio = wav,
            onText = { all -> first.offer(answerPart(all), heardPart(all)) }) { answer ->
            if (answer == null) {
                note(app, "the AI couldn't take the sound (${LocalLlm.state.value.status}); trying speech recognition")
                cannotHear()
                return@ask
            }
            val heard = heardPart(answer)
            val reply = answerPart(answer) ?: answer.replace(Regex("""HEARD:.*""", RegexOption.IGNORE_CASE), "")
            note(app, String.format(Locale.US, "heard by the AI after %.1f s (%s; reading %.1f/s): %s",
                (System.currentTimeMillis() - pressed) / 1000.0, mic.name, Dash.state.value.roundsPerSec, heard.ifEmpty { "?" }))
            speak(app, heard.ifEmpty { "(not clear)" }, reply, prompt + "\n" + heard, asked, first)
        }
        if (why != null) {
            note(app, "not asked: $why")
            Speech.reply(app, "Sorry, the AI isn't ready.")
            finish("")
            main.post { show(why) }
        }
    }

    /** The question to the AI with the drive's context and LIVE NOW; the answer is spoken and logged. */
    private fun ask(app: Context, question: String, extra: String, show: (String) -> Unit) {
        val asked = System.currentTimeMillis()
        val prompt = extra + live(Dash.state.value) + "\nQUESTION: " + question
        saveText(app, "talk_last_prompt.txt", "SYSTEM:\n$SYSTEM\n\nPROMPT:\n$prompt\n")
        val first = FirstSentence(app, SYSTEM + "\n" + prompt, asked)
        val why = LocalLlm.ask(app, prompt, system = SYSTEM, maxTokens = TOKENS, allowLive = true, shown = question,
            greedy = true, onText = { all -> first.offer(all) }) { answer -> speak(app, question, answer, prompt, asked, first) }
        if (why != null) {
            note(app, "not asked: $why")
            Speech.reply(app, "Sorry, the AI isn't ready.")
            finish("")
            main.post { show(why) }
        }
    }

    /**
     * Speaks the AI's [answer] (null = it failed) and logs it; [source] = what its numbers may come from.
     * [first]: its first sentence may already have been spoken while the rest was written.
     */
    private fun speak(app: Context, question: String, answer: String?, source: String, asked: Long, first: FirstSentence) {
        val text = answer?.let { DriveSummary.plain(it).replace(Regex("\\s+"), " ").trim() }.orEmpty()
        // Numbers not in LIVE NOW: those sentences aren't spoken (no time for a second try while driving).
        val bad = NumberCheck.bad(text, SYSTEM + "\n" + source)
        val spoken = if (bad.isEmpty()) text else NumberCheck.withoutBad(text, bad)
        note(app, String.format(Locale.US, "answered in %.1f s (%s; prompt %d chars;%s reading %.1f/s): %s%s",
            (System.currentTimeMillis() - asked) / 1000.0, LocalLlm.state.value.stats, source.length,
            if (first.spoken.isEmpty()) "" else String.format(Locale.US, " first sentence spoken after %.1f s;", first.after),
            Dash.state.value.roundsPerSec,
            text.ifEmpty { "(no answer)" }, if (bad.isEmpty()) "" else " | not in the data, not spoken: ${bad.joinToString(", ")}"))
        val say = when {
            text.isEmpty() -> "Sorry, I couldn't answer that."
            spoken.isEmpty() -> "I'm not sure about that one. Ask me again when you're parked."
            else -> spoken
        }
        val already = first.spoken
        when {
            already.isEmpty() -> Speech.reply(app, say)
            say.startsWith(already) -> say.removePrefix(already).trim().takeIf { it.isNotEmpty() }?.let { Speech.reply(app, it) }
            else -> Speech.reply(app, say)   // shouldn't happen: the first sentence passed the same check
        }
        lastTalk.value = "You: $question\nCar Dash: $say"
        finish("")
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
    private fun listen(app: Context, mic: Mic): ByteArray? {
        val out = ByteArrayOutputStream()
        val frame = ByteArray(Mic.RATE / 50 * 2)   // up to 20 ms (the car's microphone may give less per read)
        var noise = 0.0
        var bytes = 0L
        var speechMs = 0
        var quietMs = 0
        var heardAt = -1L
        // For the log (2026-11-06: the car gave "nothing heard" every time; why it stopped and how loud it was).
        var ms = 0L
        var reads = 0
        var silent = 0
        var loudest = 0.0
        var firstData = -1L
        var why = "?"
        val opened = System.currentTimeMillis()
        mic.start()
        try {
            while (true) {
                val n = mic.read(frame)
                if (n <= 0) {
                    why = "the car closed the microphone (read $n)"
                    break
                }
                if (firstData < 0) firstData = System.currentTimeMillis() - opened
                reads++
                bytes += n
                ms = bytes * 1000 / (Mic.RATE * 2)   // time into the recording
                val pieceMs = n * 1000 / (Mic.RATE * 2)
                val level = rms(frame, n)
                if (level == 0.0) silent++
                if (ms <= 200) noise = maxOf(noise, level)   // the first 200 ms: background noise
                else loudest = maxOf(loudest, level)
                val loud = ms > 200 && level > maxOf(noise * 2.5, 300.0)
                out.write(frame, 0, n)   // keep the start too: the first word may be quiet
                if (heardAt < 0) {
                    speechMs = if (loud) speechMs + pieceMs else 0
                    if (speechMs >= 100) heardAt = ms   // 100 ms of speech
                    if (heardAt < 0 && ms > START_TIMEOUT_MS) {
                        why = "no speech within ${START_TIMEOUT_MS / 1000} s"
                        return null
                    }
                } else {
                    quietMs = if (loud) 0 else quietMs + pieceMs
                    if (quietMs >= END_SILENCE_MS) { why = "quiet after speaking"; break }
                    if (ms > MAX_MS) { why = "${MAX_MS / 1000} s limit"; break }
                }
            }
        } finally {
            runCatching { mic.stop() }
            note(app, String.format(Locale.US,
                "listened %.1f s of sound: %s; %d reads (%d all-zero), first sound after %d ms; background %.0f, " +
                    "loudest %.0f, speech needs over %.0f; speech %s",
                ms / 1000.0, why, reads, silent, firstData, noise, loudest, maxOf(noise * 2.5, 300.0),
                if (heardAt >= 0) String.format(Locale.US, "from %.1f s", heardAt / 1000.0) else "not found"))
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

    /** What the AI wrote after "HEARD:" (Talk with sound), or "". */
    private fun heardPart(text: String): String = Regex("""HEARD:\s*(.*)""", RegexOption.IGNORE_CASE).find(text)
        ?.groupValues?.get(1)?.substringBefore("ANSWER:")?.trim().orEmpty()

    /** What the AI wrote after "ANSWER:" (Talk with sound), or null if it hasn't got there (yet). */
    private fun answerPart(text: String): String? =
        Regex("""ANSWER:\s*([\s\S]*)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)

    /**
     * Speaks the answer's first sentence as soon as it's written (2026-11-07, less waiting); the rest
     * follows when the answer is done. Only once a second sentence has begun, so the first is complete.
     * A first sentence with a number that isn't in the data waits for the end, where it's left out.
     */
    private class FirstSentence(private val app: Context, private val source: String, private val asked: Long) {
        @Volatile var spoken = ""
            private set
        @Volatile var after = 0.0   // seconds from asking to speaking it
            private set
        @Volatile private var decided = false

        fun offer(answer: String?, heard: String = "") {
            if (decided || answer == null) return
            val clean = DriveSummary.plain(answer).replace(Regex("\\s+"), " ").trimStart()
            val sentence = FIRST.find(clean)?.groupValues?.get(1)?.trim() ?: return
            decided = true
            if (NumberCheck.bad(sentence, source + "\n" + heard).isNotEmpty()) return
            after = (System.currentTimeMillis() - asked) / 1000.0
            spoken = sentence
            Speech.reply(app, sentence)
        }

        private companion object {
            val FIRST = Regex("""^(.+?[.!?])\s+\S""")   // a sentence end, then the next one has started
        }
    }

    // ------------------------------------------------------------ the drive and earlier drives

    /**
     * Talk's context besides LIVE NOW. The AI hears the question itself (sound), so the app can't read
     * it first and pick by question: it picks by situation. Recording a drive = EARLIER DRIVES + THIS
     * DRIVE SO FAR (the live report, DriveReports.live); not = EARLIER DRIVES + LAST DRIVE (its final
     * report). EARLIER DRIVES first and the drive last, nearest the question. "" if there's no car.
     */
    private fun background(): String {
        return runCatching { backgroundText() }.getOrDefault("")
    }

    private fun backgroundText(): String {
        val car = Dash.state.value.car ?: return ""
        val recording = DriveReports.recordingNow()
        val drive = if (recording != null) DriveReports.live() else DriveReports.newest(car, null)
        val earlier = DriveReports.history(car, drive?.drive ?: recording)
        val driveText = buildString {
            if (drive == null) return@buildString
            if (recording != null) {
                append("THIS DRIVE SO FAR (the app's report of the whole drive until now; its findings are only the WATCH / CHECK lines under VERDICT)\n")
            } else {
                append("LAST DRIVE (finished; the app's report; its findings are only the WATCH / CHECK lines under VERDICT)\n")
            }
            append(drive.text(withData = false, withTimeline = true))
            if (recording != null && drive.timeline.none { "engine started" in it }) {
                append("Start dip of this drive: none, the engine was already running when it began. The last engine start " +
                    "before it is the newest one in LAST ENGINE STARTS.\n")
            }
        }
        // Too long for a quick answer: the history goes first (the drive matters most).
        val both = earlier + "\n" + driveText
        return (if (both.length > MAX_CONTEXT_CHARS) driveText else both) + "\n"
    }

    /** A text file in the car's folder (replaced each time), e.g. the last Talk's prompt for checking. */
    private fun saveText(context: Context, name: String, text: String) {
        val file: File = Dash.state.value.car?.file(name) ?: File(context.filesDir, name)
        runCatching { file.writeText(text) }
    }

    // ------------------------------------------------------------ LIVE NOW

    /** The car right now, with the app's own levels, for the AI (~400 tokens). */
    fun live(s: Snapshot): String = buildString {
        append("LIVE NOW (today is ").append(TimeFormat.forAi(System.currentTimeMillis())).append(")\n")
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

    /**
     * The recording made louder (2026-11-06: the car's microphone gave speech at only ~3 % of full
     * scale, and every recognizer heard "no words"): its loudest sample up to [TARGET_PEAK], at most
     * [MAX_GAIN] times. Returns the louder sound, the gain and the loudest sample before.
     */
    private fun boost(sound: ByteArray): Triple<ByteArray, Double, Int> {
        val before = peak(sound)
        val gain = if (before == 0) 1.0 else minOf(MAX_GAIN, TARGET_PEAK.toDouble() / before).coerceAtLeast(1.0)
        if (gain == 1.0) return Triple(sound, 1.0, before)
        val out = ByteArray(sound.size)
        var i = 0
        while (i + 1 < sound.size) {
            val v = ((sound[i + 1].toInt() shl 8) or (sound[i].toInt() and 0xFF)).toShort().toInt()
            val w = (v * gain).toInt().coerceIn(-32768, 32767)
            out[i] = (w and 0xFF).toByte()
            out[i + 1] = ((w shr 8) and 0xFF).toByte()
            i += 2
        }
        return Triple(out, gain, before)
    }

    private fun peak(sound: ByteArray): Int {
        var max = 0
        var i = 0
        while (i + 1 < sound.size) {
            val v = ((sound[i + 1].toInt() shl 8) or (sound[i].toInt() and 0xFF)).toShort().toInt()
            if (kotlin.math.abs(v) > max) max = kotlin.math.abs(v)
            i += 2
        }
        return max
    }

    /**
     * The last question's sound as talk_last.wav in the car's folder (replaced each time, never sent
     * anywhere; it goes into a drive's ZIP only when the owner shares it), to check what the
     * recognizer was given.
     */
    private fun saveLast(context: Context, sound: ByteArray) {
        val file: File = Dash.state.value.car?.file("talk_last.wav") ?: File(context.filesDir, "talk_last.wav")
        runCatching { file.writeBytes(wav(sound)) }
    }

    /** The recording (16-bit mono PCM) as a WAV file's bytes: what the AI and talk_last.wav get. */
    private fun wav(sound: ByteArray): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + sound.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)   // PCM, mono
            putInt(Mic.RATE); putInt(Mic.RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(sound.size)
        }.array()
        return header + sound
    }

    private const val TARGET_PEAK = 24_000   // ~73 % of full scale: loud, with room to spare
    private const val MAX_GAIN = 20.0
    private const val START_TIMEOUT_MS = 5_000    // no speech by then: stop (e.g. a ghost tap)
    private const val END_SILENCE_MS = 1_200      // quiet this long after speaking = done
    private const val MAX_MS = 12_000
    private const val RECOGNIZE_TIMEOUT_MS = 15_000L
    private const val BACKGROUND_WAIT_S = 3L         // the drive's report should be ready long before
    private const val MAX_CONTEXT_CHARS = 4_500      // drive + history; above: the drive alone
}
