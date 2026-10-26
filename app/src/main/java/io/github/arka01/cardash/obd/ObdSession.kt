package io.github.arka01.cardash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** One value from one car computer. */
data class Reading(val module: String, val pid: Int, val name: String, val value: String, val time: Long)

/** Everything the screens show; replaced as a whole on every change. */
data class Snapshot(
    val status: String = "Not connected",
    val adapterName: String? = null,
    val adapterVoltage: String = "--",
    val readings: List<Reading> = emptyList(),
    val sweeps: Int = 0,
    val sweepMs: Long = 0,
    val log: List<String> = emptyList(),
)

/**
 * The one connection to the vLinker, shared by the phone screen and the car screen.
 * Runs in the background: connect, find the supported sensors, then read them all
 * in a loop. If the link drops it retries every 5 seconds until stopped.
 */
object ObdSession {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val PROTOCOL = "6"   // ISO 15765-4 CAN 11 bit 500 kbaud, from the Tiago's car.json
    private const val ENGINE = "7E8"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var socket: BluetoothSocket? = null

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    val isRunning: Boolean get() = job?.isActive == true

    fun start(context: Context, adapter: SavedAdapter) {
        if (isRunning) return
        val appContext = context.applicationContext
        _state.value = Snapshot(status = "Connecting…", adapterName = adapter.name)
        val previous = job
        job = scope.launch {
            previous?.join()   // let a just-stopped loop finish closing its connection first
            while (isActive) {
                try {
                    connectAndRead(appContext, adapter.address)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isActive) break   // stop() closed the link on purpose
                    log("Lost: ${e.message ?: e.javaClass.simpleName}")
                    setStatus("Connection lost, retrying in 5 s")
                } finally {
                    closeSocket()
                }
                delay(5000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        closeSocket()   // unblocks a connect() or read that is still waiting
        setStatus("Stopped")
    }

    @SuppressLint("MissingPermission")   // the screens check the permission before calling start()
    private fun connectAndRead(context: Context, address: String) {
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This phone has no Bluetooth")
        if (!bluetooth.isEnabled) throw IOException("Bluetooth is off")
        val device = bluetooth.getRemoteDevice(address)

        setStatus("Connecting to ${device.name ?: address}…")
        val elm = Elm327(openSocket(device))
        log("Bluetooth connected")

        setStatus("Setting up adapter…")
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP$PROTOCOL", "ATI", "ATRV")) {
            val answer = elm.command(cmd, timeoutMs = if (cmd == "ATZ") 5000 else 2000)
            log("$cmd → ${answer.joinToString(" | ")}")
        }

        setStatus("Finding sensors…")
        val modules = findSupported(elm)
        if (modules.isEmpty()) throw IOException("No car computer answered (ignition on?)")
        for ((header, pids) in modules) {
            log("${moduleName(header)}: ${pids.size} sensors ${pids.joinToString(" ") { "%02X".format(it) }}")
        }
        // Only the engine computer is read: the AMT (7E9) refuses direct requests, and its
        // 9 values just repeat the engine's (test drive 2026-10-26).
        val engineOnly = modules.filterKeys { it == ENGINE }
        if (engineOnly.isEmpty()) throw IOException("The engine computer (7E8) didn't answer")

        // Reading loop: ask each computer directly (ATSH) for each of its sensors.
        // The trailing "1" tells the adapter to stop waiting after the first answer.
        val readings = LinkedHashMap<String, Reading>()
        var countSuffix = "1"
        var sweeps = 0
        setStatus("Connected")
        while (true) {
            val sweepStart = System.currentTimeMillis()
            for ((header, pids) in engineOnly) {
                elm.command("ATSH" + requestHeader(header))
                for (pid in pids) {
                    var answer = elm.command("01%02X%s".format(pid, countSuffix))
                    if (answer == listOf("?") && countSuffix.isNotEmpty()) {
                        log("Adapter doesn't support the answer-count trick; reading without it")
                        countSuffix = ""
                        answer = elm.command("01%02X".format(pid))
                    }
                    val value = valueFrom(answer, header, pid)
                    readings["$header:$pid"] = Reading(moduleName(header), pid, Pids.name(pid), value, System.currentTimeMillis())
                    _state.update { it.copy(readings = readings.values.toList()) }
                }
            }
            val voltage = elm.command("ATRV").firstOrNull() ?: "--"
            sweeps++
            val sweepMs = System.currentTimeMillis() - sweepStart
            _state.update { it.copy(adapterVoltage = voltage, sweeps = sweeps, sweepMs = sweepMs) }
        }
    }

    /** Opens the Bluetooth serial link; some adapters only accept the "insecure" variant. */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        for (secure in listOf(true, false)) {
            val s = if (secure) device.createRfcommSocketToServiceRecord(SPP_UUID)
            else device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            socket = s   // so stop() can interrupt a connect() that is still waiting
            try {
                s.connect()
                return s
            } catch (e: IOException) {
                closeSocket()
                if (!secure) throw e
                log("Secure connect failed (${e.message}), trying insecure")
            }
        }
        throw IOException("unreachable")
    }

    /** Asks "which sensors do you have?" (01 00, 01 20, …) and collects the answers per computer. */
    private fun findSupported(elm: Elm327): Map<String, List<Int>> {
        val supported = sortedMapOf<String, MutableList<Int>>()
        var base = 0x00
        while (base <= 0xE0) {
            if (base != 0x00 && supported.values.none { base in it }) break
            val answer = elm.command("01%02X".format(base), timeoutMs = 5000)
            log("01%02X → %s".format(base, answer.joinToString(" | ")))
            for (reply in parseCan11(answer)) {
                val d = reply.data
                if (d.size < 6 || d[0] != 0x41 || d[1] != base) continue
                val pids = supported.getOrPut(reply.header) { mutableListOf() }
                for (i in 0 until 32) {
                    if (d[2 + i / 8] and (0x80 shr (i % 8)) != 0) pids += base + i + 1
                }
            }
            base += 0x20
        }
        // 0x20, 0x40, … only say "there are more"; they are not sensors.
        return supported.mapValues { (_, pids) -> pids.filter { it % 0x20 != 0 } }
    }

    private fun valueFrom(answer: List<String>, header: String, pid: Int): String {
        val reply = parseCan11(answer).firstOrNull { it.header == header } ?: return answer.firstOrNull() ?: "NO DATA"
        val d = reply.data
        return when {
            d.size >= 2 && d[0] == 0x41 && d[1] == pid -> Pids.decode(pid, d.copyOfRange(2, d.size))
            d.isNotEmpty() && d[0] == 0x7F -> "refused"
            else -> "? " + d.joinToString(" ") { "%02X".format(it) }
        }
    }

    /** Answers come from 7E8/7E9/…; requests to that computer go to 7E0/7E1/…. */
    private fun requestHeader(replyHeader: String) = "%03X".format(replyHeader.toInt(16) - 8)

    private fun moduleName(header: String) = when (header) {
        "7E8" -> "ENGINE 7E8"
        "7E9" -> "AMT 7E9"
        else -> "ECU $header"
    }

    private fun setStatus(status: String) = _state.update { it.copy(status = status) }

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun log(line: String) = _state.update {
        it.copy(log = (it.log + "${clock.format(Date())} $line").takeLast(40))
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }
}
