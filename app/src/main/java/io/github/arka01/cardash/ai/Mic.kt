package io.github.arka01.cardash.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord

/**
 * Where Talk hears the driver: 16 kHz mono 16-bit sound, read in small pieces. The car's own
 * microphone through Android Auto ([CarMic], from the car screen's Talk) or the phone's ([PhoneMic]).
 */
interface Mic {
    val name: String
    fun start()
    fun read(buffer: ByteArray): Int   // bytes read, <= 0 when it ended
    fun stop()

    companion object {
        const val RATE = 16_000   // samples per second (Android Auto's car microphone format)
    }
}

/** The car's microphone (Android Auto, car app API level 5 and up). Needs RECORD_AUDIO. */
class CarMic(carContext: CarContext) : Mic {
    override val name = "car microphone"
    @SuppressLint("MissingPermission")   // checked before Talk starts
    private val record = CarAudioRecord.create(carContext)
    override fun start() = record.startRecording()
    override fun read(buffer: ByteArray) = record.read(buffer, 0, buffer.size)
    override fun stop() = record.stopRecording()
}

/** The phone's microphone, tuned for speech. Needs RECORD_AUDIO. */
class PhoneMic : Mic {
    override val name = "phone microphone"
    @SuppressLint("MissingPermission")   // checked before Talk starts
    private val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, Mic.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(AudioRecord.getMinBufferSize(Mic.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192),
    )
    override fun start() = record.startRecording()
    override fun read(buffer: ByteArray) = record.read(buffer, 0, buffer.size)
    override fun stop() {
        runCatching { record.stop() }
        record.release()
    }
}
