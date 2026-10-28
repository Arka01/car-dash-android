package io.github.arka01.cardash

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Says the warm-up advisor's messages out loud, so the driver needn't look at the screen
 * (the Python dashboard's speak()). Uses the phone's own text-to-speech voice; if there is
 * none, nothing happens. VOICE in the DEV tab switches it off (the Python --mute).
 */
object Speech {
    private const val PREFS = "speech"
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var waiting: String? = null   // said once the voice is ready

    fun muted(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("muted", false)

    fun setMuted(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("muted", muted).apply()
    }

    @Synchronized
    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                ready = true
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

    private fun speak(sentence: String) {
        tts?.speak(sentence, TextToSpeech.QUEUE_ADD, null, sentence.hashCode().toString())
    }
}
