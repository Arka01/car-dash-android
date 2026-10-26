package io.github.arka01.cardash.obd

import android.bluetooth.BluetoothSocket
import java.io.IOException

/**
 * Talks to an ELM327-style adapter (the vLinker) over a Bluetooth serial link.
 *
 * The protocol is plain text: send a command ending in "\r", the adapter answers
 * with one or more lines and then prints ">" when it is ready for the next command.
 */
class Elm327(socket: BluetoothSocket) {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val buffer = ByteArray(256)

    /** Sends [cmd] and returns the answer lines (echo and empty lines removed). */
    fun command(cmd: String, timeoutMs: Long = 2000): List<String> {
        // Throw away anything left over from an earlier, interrupted answer.
        while (input.available() > 0) input.read(buffer)

        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()

        val answer = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val available = input.available()
            if (available > 0) {
                val n = input.read(buffer, 0, minOf(available, buffer.size))
                if (n < 0) throw IOException("Adapter closed the connection")
                answer.append(String(buffer, 0, n, Charsets.US_ASCII))
                if (answer.contains('>')) break
            } else {
                if (System.currentTimeMillis() > deadline) {
                    throw IOException("No answer to $cmd within $timeoutMs ms")
                }
                Thread.sleep(2)
            }
        }
        return answer.substring(0, answer.indexOf('>'))
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != cmd }
    }
}

/** One answer from one car computer: who sent it (e.g. "7E8") and the data bytes. */
class EcuReply(val header: String, val data: IntArray)

/**
 * Parses answers on CAN 11-bit (the Tiago's protocol 6) with headers on and spaces off,
 * e.g. "7E8064100BE3FA813" = from 7E8, 6 bytes: 41 00 BE 3F A8 13.
 * Only single-frame answers, which is all that service 01 (live data) uses.
 */
fun parseCan11(lines: List<String>): List<EcuReply> = lines.mapNotNull { raw ->
    val line = raw.replace(" ", "")
    if (line.length < 7 || !line.all { it.isHex() }) return@mapNotNull null
    val pci = line.substring(3, 5).toInt(16)
    if (pci shr 4 != 0) return@mapNotNull null
    val length = pci and 0x0F
    val hex = line.substring(5)
    if (hex.length < length * 2) return@mapNotNull null
    EcuReply(line.substring(0, 3), IntArray(length) { hex.substring(it * 2, it * 2 + 2).toInt(16) })
}

private fun Char.isHex() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'
