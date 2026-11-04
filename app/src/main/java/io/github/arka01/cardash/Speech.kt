package io.github.arka01.cardash

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Speaks the drive summaries and warm-up tips, so the driver needn't look at the screen. Uses the
 * phone's own text-to-speech; if there is none, nothing happens. Settings → Voice: on/off, which
 * voice, speed and pitch (2026-11-01). Only **offline** voices are offered: network voices would
 * send the text to the speech provider, and Car Dash keeps everything on the phone.
 */
object Speech {
    private const val PREFS = "speech"
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var waiting: String? = null   // said once the voice is ready

    /** Goes up when a voice setting changes (or the voices become known), so Settings shows it. */
    private val changed = MutableStateFlow(0)
    val changes: StateFlow<Int> = changed

    fun muted(context: Context) = prefs(context).getBoolean("muted", false)

    fun setMuted(context: Context, muted: Boolean) {
        prefs(context).edit().putBoolean("muted", muted).apply()
        changed.value++
    }

    @Synchronized
    fun init(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                apply(app)
                ready = true
                changed.value++
                waiting?.let { speak(it) }
                waiting = null
            }
        }
    }

    fun say(context: Context, sentence: String) {
        if (muted(context)) return
        init(context)
        if (ready) speak(sentence) else waiting = sentence
    }

    /** An answer the driver asked for (Talk): spoken even if the voice for drive summaries is off. */
    fun reply(context: Context, sentence: String) {
        init(context)
        if (ready) speak(sentence) else waiting = sentence
    }

    /** Play sample (Settings): says a sentence with the chosen voice, even if the voice is off. */
    fun sample(context: Context) {
        init(context)
        val text = "Good evening! Your drive is complete: 15 minutes and 3.5 kilometres. This drive was normal."
        if (ready) {
            tts?.stop()
            speak(text)
        } else {
            waiting = text
        }
    }

    // ---------------------------------------------------------------- voice settings

    /** The English voices installed on the phone that work offline, by accent. */
    fun voices(): List<Voice> = runCatching {
        tts?.voices.orEmpty()
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && !it.features.contains("notInstalled") }
            .sortedWith(compareBy({ it.locale.displayCountry }, { it.name }))
    }.getOrDefault(emptyList())

    /** "English (India) · voice 2" for the voice called [name], numbered within its accent. */
    fun label(name: String?): String {
        val all = voices()
        val v = all.firstOrNull { it.name == name } ?: return "Default (English, UK)"
        val sameAccent = all.filter { it.locale == v.locale }
        val n = sameAccent.indexOf(v) + 1
        return v.locale.getDisplayName(Locale.UK) + if (sameAccent.size > 1) " · voice $n" else ""
    }

    fun voiceName(context: Context): String? = prefs(context).getString("voice", null)
    fun rate(context: Context) = prefs(context).getFloat("rate", 1.0f)
    fun pitch(context: Context) = prefs(context).getFloat("pitch", 1.0f)

    fun setVoice(context: Context, name: String?) = save(context) { putString("voice", name) }
    fun setRate(context: Context, rate: Float) = save(context) { putFloat("rate", rate) }
    fun setPitch(context: Context, pitch: Float) = save(context) { putFloat("pitch", pitch) }

    private fun save(context: Context, edit: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(context).edit().apply(edit).apply()
        if (ready) apply(context.applicationContext)
        changed.value++
    }

    /** Puts the saved voice, speed and pitch into the speech engine. */
    private fun apply(context: Context) {
        val t = tts ?: return
        val chosen = voiceName(context)?.let { name -> voices().firstOrNull { it.name == name } }
        if (chosen != null) t.voice = chosen else t.language = Locale.UK
        t.setSpeechRate(rate(context))
        t.setPitch(pitch(context))
    }

    private fun speak(sentence: String) {
        tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, sentence.hashCode().toString())
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
