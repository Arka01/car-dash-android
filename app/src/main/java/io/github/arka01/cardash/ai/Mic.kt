package io.github.arka01.cardash.ai

import android.annotation.SuppressLint
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord

/**
 * Where Talk hears the driver from the car screen: 16 kHz mono 16-bit sound, read in small pieces,
 * from the car's own microphone through Android Auto. (Talk on the phone uses Android's speech
 * recognition with the phone's microphone directly: ai/Talk.startPhone.)
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
