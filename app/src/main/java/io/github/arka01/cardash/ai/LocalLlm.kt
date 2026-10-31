package io.github.arka01.cardash.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import io.github.arka01.cardash.CrashReport
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.Mode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the AI screen shows. */
data class AiState(
    val modelName: String? = null,   // imported model file, null = none
    val modelBytes: Long = 0,
    val status: String = "no model",
    val busy: Boolean = false,       // importing, loading or answering
    val question: String = "",
    val answer: String = "",
    val stats: String = "",          // e.g. "38.2 s · 412 words · GPU"
)

/** Inference settings, as in Google's AI Edge Gallery app; kept between app starts. */
data class AiSettings(
    val systemPrompt: String,
    val temperature: Double,
    val topK: Int,
    val topP: Double,
    val maxTokens: Int,
    val gpu: Boolean,
)

/**
 * A language model running on the phone itself (Google's LiteRT-LM engine, the one inside the
 * AI Edge Gallery app), e.g. Gemma 4 E2B. Nothing leaves the phone and no internet is needed:
 * the model file (.litertlm, a few GB) is downloaded by the user and imported from Downloads.
 *
 * The model is loaded only when a question is asked (a few seconds, ~2-3 GB of memory) and
 * unloaded after [IDLE_UNLOAD_MS] without questions. It doesn't run while LIVE reading runs:
 * asking is refused then, and starting LIVE stops an answer and unloads the model ([release]).
 */
object LocalLlm {
    private const val DIR = "models"
    private const val PREFS = "ai"
    private const val IDLE_UNLOAD_MS = 3 * 60_000L
    private const val CONTEXT_TOKENS = 4096   // prompt + answer

    const val DEFAULT_PROMPT = "You are an experienced car mechanic. You explain car data in simple words to a " +
        "non-expert owner. Be short and concrete. Only use the numbers given; never invent values. If something " +
        "is normal, say so. If something is worrying, say why and what to check."

    /** TEST: the 30 Oct 2026 drive in plain numbers, the same text tried in the Gallery app (to compare). */
    const val TEST_QUESTION = "Explain this drive of my 2023 Tata Tiago 1.2 petrol (AMT) and tell me if anything " +
        "is worrying.\n\nDrive: 30 Oct, 15 min, 3.5 km city driving, top speed 60 km/h, 28% of the time idling.\n" +
        "Fuel: 0.35 L used, 10.0 L/100 km.\nEngine start: coolant 27 °C (cold start). Coolant reached 70 °C after " +
        "5 min, 80 °C after 7 min, oil 85 °C after 8 min. Max RPM while cold: 2330.\nBattery: resting 12.2 V before " +
        "start. Lowest voltage while cranking 10.5 V. While driving mostly 12.6 to 14.6 V, but for one minute it " +
        "averaged 11.9 V (lowest 11.6 V) at 31 km/h. At the end 13.9 V. The car has a smart alternator.\n" +
        "Engine: peak torque 111 Nm, peak power 58 bhp. No trouble codes."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()   // one import / load / answer / unload at a time
    private val store = MutableStateFlow(AiState())
    val state: StateFlow<AiState> = store

    @Volatile private var initialized = false
    @Volatile private var engine: Engine? = null
    @Volatile private var engineGpu: Boolean? = null      // backend the loaded engine really uses
    @Volatile private var engineWanted: Boolean? = null   // the setting it was loaded for (GPU may have fallen back)
    @Volatile private var conversation: Conversation? = null
    @Volatile private var job: Job? = null
    @Volatile private var unloadJob: Job? = null

    private fun status(text: String) = store.update { it.copy(status = text) }

    /** Shows the model already imported, if any. Once per app start (not on every screen rotation). */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val model = modelFile(context)
        store.update {
            if (model != null) it.copy(modelName = model.name, modelBytes = model.length(), status = "ready (not loaded)")
            else it.copy(modelName = null, status = "no model: download a .litertlm file, then IMPORT MODEL")
        }
    }

    private fun modelFile(context: Context): File? =
        File(context.filesDir, DIR).listFiles { f -> f.name.endsWith(".litertlm") }?.firstOrNull()

    // ---------------------------------------------------------------- settings

    fun settings(context: Context): AiSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AiSettings(
            systemPrompt = p.getString("system", null) ?: DEFAULT_PROMPT,
            temperature = p.getFloat("temperature", 0.3f).toDouble(),   // low: factual, consistent
            topK = p.getInt("topK", 40),
            topP = p.getFloat("topP", 0.95f).toDouble(),
            maxTokens = p.getInt("maxTokens", 1024),
            gpu = p.getBoolean("gpu", true),
        )
    }

    fun save(context: Context, s: AiSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("system", s.systemPrompt)
            .putFloat("temperature", s.temperature.toFloat())
            .putInt("topK", s.topK)
            .putFloat("topP", s.topP.toFloat())
            .putInt("maxTokens", s.maxTokens)
            .putBoolean("gpu", s.gpu)
            .apply()
    }

    // ---------------------------------------------------------------- the model file

    /**
     * Copies a .litertlm file (picked in Downloads) into the app's own storage, replacing any
     * earlier model. Returns a reason why not, or null when the copy has started. STOP cancels it.
     */
    fun importModel(context: Context, uri: Uri): String? {
        if (store.value.busy) return "Busy: wait until it's finished"
        val app = context.applicationContext
        var name = "model.litertlm"
        var size = -1L
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        if (!name.endsWith(".litertlm")) return "Not a .litertlm file: $name"
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }   // an earlier copy that broke off
        if (size > 0 && dir.usableSpace < size + 300_000_000L) {
            return String.format(Locale.US, "Not enough space: needs %.1f GB free", (size + 300_000_000L) / 1e9)
        }
        store.update { it.copy(busy = true, status = "importing $name…") }
        unloadJob?.cancel()
        job = scope.launch {
            val temp = File(dir, "$name.part")
            try {
                lock.withLock {
                    unload()
                    app.contentResolver.openInputStream(uri)!!.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(1 shl 20)
                            var copied = 0L
                            var shown = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()   // STOP
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                copied += n
                                if (copied - shown > 50_000_000L) {   // every 50 MB
                                    shown = copied
                                    val pct = if (size > 0) String.format(Locale.US, " %d %%", copied * 100 / size) else ""
                                    status(String.format(Locale.US, "importing… %.2f GB%s", copied / 1e9, pct))
                                }
                            }
                        }
                    }
                    dir.listFiles { f -> f.name.endsWith(".litertlm") }?.forEach { it.delete() }   // the old model
                    val target = File(dir, name)
                    if (!temp.renameTo(target)) throw IOException("couldn't rename the copied file")
                    store.update {
                        it.copy(modelName = target.name, modelBytes = target.length(),
                            status = "imported: ready (you can delete the copy in Downloads)")
                    }
                }
            } catch (e: CancellationException) {
                temp.delete()
                status("import stopped")
                throw e
            } catch (e: Exception) {
                temp.delete()
                status("import failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                store.update { it.copy(busy = false) }
            }
        }
        return null
    }

    fun remove(context: Context) {
        if (store.value.busy) return
        unloadJob?.cancel()
        scope.launch {
            lock.withLock {
                unload()
                File(context.applicationContext.filesDir, DIR).listFiles()?.forEach { it.delete() }
                store.value = AiState(status = "model removed")
            }
        }
    }

    // ---------------------------------------------------------------- asking

    /** Asks one question (no memory of earlier ones); the answer streams into [state]. */
    fun ask(context: Context, question: String): String? {
        val app = context.applicationContext
        val model = modelFile(app) ?: return "Import a model first"
        if (store.value.busy) return "Busy: wait or STOP"
        // The drive recording comes first: the model uses a lot of memory and processor.
        if (Dash.state.value.mode == Mode.LIVE) return "Not while LIVE: stop reading first"
        val s = settings(app)
        store.update { it.copy(busy = true, question = question, answer = "", stats = "") }
        CrashReport.step(String.format(Locale.US, "ask: %s, model %s (%.2f GB), temp %.2f, topK %d, topP %.2f, max %d, question %d chars",
            if (s.gpu) "GPU" else "CPU", model.name, model.length() / 1e9, s.temperature, s.topK, s.topP, s.maxTokens, question.length), first = true)
        job = scope.launch {
            val started = System.currentTimeMillis()
            try {
                lock.withLock {
                    val e = load(app, model, s.gpu)
                    status("thinking…")
                    CrashReport.step("conversation: creating")
                    val c = e.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(s.systemPrompt),
                            samplerConfig = SamplerConfig(topK = s.topK, topP = s.topP, temperature = s.temperature),
                            maxOutputToken = s.maxTokens,
                        ),
                    )
                    conversation = c
                    CrashReport.step("conversation: created; sending the question")
                    val answer = StringBuilder()
                    var firstAt = 0L
                    try {
                        currentCoroutineContext().ensureActive()   // STOP pressed while loading
                        // The callback version, not the Flow one: in 0.16.1 the Flow's end calls a coroutines
                        // function that no longer exists in the newer coroutines this app uses (crash report
                        // 2026-10-31: NoSuchMethodError SendChannel.close$default at the end of the answer).
                        suspendCancellableCoroutine { done ->
                            c.sendMessageAsync(question, object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    if (firstAt == 0L) {
                                        firstAt = System.currentTimeMillis()
                                        CrashReport.step("first words")
                                    }
                                    val piece = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                                    val text = synchronized(answer) { answer.append(piece).toString() }
                                    store.update { it.copy(answer = text, status = "answering…") }
                                }

                                override fun onDone() {
                                    if (done.isActive) done.resume(Unit)
                                }

                                override fun onError(throwable: Throwable) {
                                    if (done.isActive) done.resumeWithException(throwable)
                                }
                            })
                            done.invokeOnCancellation { runCatching { c.cancelProcess() } }
                        }
                    } finally {
                        conversation = null
                        // Stopping the collector doesn't stop the engine (0.16.1): end the native work
                        // before closing, so it can't call back into a closed conversation.
                        runCatching { c.cancelProcess() }
                        runCatching { c.close() }
                    }
                    val total = (System.currentTimeMillis() - started) / 1000.0
                    val wait = if (firstAt > 0) (firstAt - started) / 1000.0 else total
                    val words = answer.split(Regex("\\s+")).count { it.isNotBlank() }
                    status("done")
                    CrashReport.step("done: ${answer.length} characters")
                    store.update {
                        it.copy(stats = String.format(Locale.US, "%.1f s (first words after %.1f s) · %d words · %s",
                            total, wait, words, if (engineGpu == true) "GPU" else "CPU"))
                    }
                }
            } catch (e: CancellationException) {
                status("stopped")
                throw e
            } catch (e: Throwable) {
                CrashReport.step("failed: ${e.javaClass.name}: ${e.message}")
                val tooLong = (e.message ?: "").contains("token", ignoreCase = true)
                status("failed: ${e.message ?: e.javaClass.simpleName}" +
                    if (tooLong) " (question + answer too long? lower 'max answer')" else "")
            } finally {
                store.update { it.copy(busy = false) }
                scheduleUnload()
            }
        }
        return null
    }

    /** STOP: ends an answer being written, or an import. */
    fun stop() {
        conversation?.let { runCatching { it.cancelProcess() } }
        job?.cancel()
    }

    /** LIVE starts: stop any answer and free the model's memory now (the recording comes first). */
    fun release() {
        stop()
        unloadJob?.cancel()
        scope.launch {
            lock.withLock { unload() }
            if (store.value.modelName != null && !store.value.busy) status("ready (unloaded for LIVE)")
        }
    }

    /** The engine for [model]; loads it (GPU, else CPU) unless it's loaded for this setting already. */
    private fun load(context: Context, model: File, gpu: Boolean): Engine {
        unloadJob?.cancel()
        engine?.let { if (engineWanted == gpu) return it.also { CrashReport.step("engine: already loaded") } }
        unload()
        status("loading model (${if (gpu) "GPU" else "CPU"}, ~10 s)…")
        fun open(useGpu: Boolean): Engine {
            CrashReport.step("engine: creating (${if (useGpu) "GPU" else "CPU"}, context $CONTEXT_TOKENS tokens, cache ${context.cacheDir.path})")
            val e = Engine(
                EngineConfig(
                    modelPath = model.path,
                    backend = if (useGpu) Backend.GPU() else Backend.CPU(),
                    maxNumTokens = CONTEXT_TOKENS,
                    cacheDir = context.cacheDir.path,   // makes the next load faster
                ),
            )
            try {
                CrashReport.step("engine: initialize() …")
                e.initialize()
                CrashReport.step("engine: initialized")
            } catch (t: Throwable) {
                CrashReport.step("engine: initialize failed: ${t.javaClass.name}: ${t.message}")
                runCatching { e.close() }   // free what the failed start took before trying again
                throw t
            }
            engine = e
            engineGpu = useGpu
            engineWanted = gpu
            return e
        }
        return try {
            open(gpu)
        } catch (e: Throwable) {
            if (!gpu) throw e
            status("GPU failed (${e.message ?: e.javaClass.simpleName}), loading on CPU…")
            open(false)
        }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            lock.withLock { unload() }
            if (store.value.modelName != null && !store.value.busy) status("ready (unloaded to free memory)")
        }
    }

    private fun unload() {
        runCatching { engine?.close() }
        engine = null
        engineGpu = null
        engineWanted = null
    }
}
