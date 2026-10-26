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

    /**
     * Listens to the car's network for [ms] without sending anything ("ATMA", monitor all;
     * the adapter stays silent on the bus). Returns the messages seen, e.g. "0C9" + data bytes.
     * Any character sent to the adapter stops the listening.
     */
    fun monitor(ms: Long): List<String> {
        while (input.available() > 0) input.read(buffer)
        output.write("ATMA\r".toByteArray(Charsets.US_ASCII))
        output.flush()

        val heard = StringBuilder()
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (!readAvailable(heard)) Thread.sleep(2)
        }
        output.write("\r".toByteArray(Charsets.US_ASCII))   // stop listening
        output.flush()
        val deadline = System.currentTimeMillis() + 1500
        while (!heard.contains('>')) {
            if (System.currentTimeMillis() > deadline) throw IOException("Adapter didn't stop listening")
            if (!readAvailable(heard)) Thread.sleep(2)
        }
        return heard.substring(0, heard.indexOf('>'))
            .split('\r', '\n')
            .map { it.trim().replace(" ", "") }
            .filter { it.length >= 5 && it.length % 2 == 1 && it.all { c -> c.isHex() } }
    }

    /** Appends whatever the adapter has sent so far; false if there was nothing. */
    private fun readAvailable(into: StringBuilder): Boolean {
        val available = input.available()
        if (available <= 0) return false
        val n = input.read(buffer, 0, minOf(available, buffer.size))
        if (n < 0) throw IOException("Adapter closed the connection")
        into.append(String(buffer, 0, n, Charsets.US_ASCII))
        return true
    }
}

/**
 * Joins the answer lines into one message per car computer, on CAN 11-bit (the Tiago's
 * protocol 6) with headers on and spaces off. Short answers fit in one line ("single frame"):
 *   7E8 06 41 00 BE 3F A8 13          -> from 7E8, 6 bytes: 41 00 BE 3F A8 13
 * Long ones (e.g. 6 values asked at once) come in pieces that must be put back together:
 *   7E8 10 13 41 0C 1A F8 0D 32       first frame: 0x013 = 19 bytes in total, 6 of them here
 *   7E8 21 11 2B 04 40 5A 00 62       consecutive frame 1: 7 more bytes
 *   7E8 22 8A 00 00 00 00 00 00       consecutive frame 2: the rest, padded
 * Returns e.g. {"7E8": [0x41, 0x0C, ...]}; computers whose answer is incomplete are left out.
 */
fun assembleCan11(lines: List<String>): Map<String, IntArray> {
    val done = LinkedHashMap<String, IntArray>()
    val expected = HashMap<String, Int>()
    val partial = HashMap<String, MutableList<Int>>()
    for (raw in lines) {
        val line = raw.replace(" ", "")
        if (line.length < 5 || line.length % 2 == 0 || !line.all { it.isHex() }) continue
        val header = line.substring(0, 3)
        val bytes = (3 until line.length step 2).map { line.substring(it, it + 2).toInt(16) }
        when (bytes[0] shr 4) {
            0 -> {   // single frame
                val length = bytes[0] and 0x0F
                if (bytes.size - 1 >= length) done[header] = bytes.subList(1, 1 + length).toIntArray()
            }
            1 -> {   // first frame
                if (bytes.size < 2) continue
                expected[header] = ((bytes[0] and 0x0F) shl 8) or bytes[1]
                partial[header] = bytes.drop(2).toMutableList()
            }
            2 -> partial[header]?.addAll(bytes.drop(1))   // consecutive frame
        }
        val want = expected[header] ?: continue
        val got = partial[header] ?: continue
        if (got.size >= want) {
            done[header] = got.subList(0, want).toIntArray()
            expected.remove(header)
            partial.remove(header)
        }
    }
    return done
}

private fun Char.isHex() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'
