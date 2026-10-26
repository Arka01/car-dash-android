package io.github.arka01.cardash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.arka01.cardash.BuildConfig
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.DriveRecorder
import io.github.arka01.cardash.KeyMarks
import io.github.arka01.cardash.Reading
import io.github.arka01.cardash.TestInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
private const val PROTOCOL = "6"   // ISO 15765-4 CAN 11 bit 500 kbaud, from the Tiago's car.json
private const val ENGINE = "7E8"   // the engine computer's answers; it listens on 7E0
private const val MAX_BATCH = 6    // the OBD standard allows up to 6 values per question

/** How often each value is read, in rounds. Anything not listed: every 50th round. */
private val PERIOD: Map<Int, Int> =
    listOf(0x0C, 0x0D, 0x04, 0x45, 0x5A, 0x62).associateWith { 1 } +
        listOf(0x0B, 0x0E, 0x06, 0x07, 0x44, 0x5E, 0x11, 0x49, 0x4A, 0x4C, 0x47, 0x43, 0x87, 0x9E, 0x34, 0x15, 0x61)
            .associateWith { 3 } +
        listOf(0x05, 0x0F, 0x5C, 0x46, 0x3C, 0x67, 0x42, 0x2F, 0x33, 0x1F, 0x2E, 0x03, 0x56).associateWith { 10 }

/** Values read together in one question (or one by one if batching is off). */
private class Group(val pids: List<Int>, val period: Int, val phase: Int) {
    var frames = 0       // answer lines seen the first time; lets the adapter stop waiting early
    var failures = 0
    var batched = true
}

/**
 * The live connection to the vLinker. Runs in the background: connect, find the engine
 * computer's values, then read them in a loop. If the link drops it retries every 5 s.
 *
 * Reading speed (each question to the car takes ~35 ms):
 * - Priorities: RPM, speed, load, throttle, pedal and torque every round; the rest every
 *   3rd, 10th or 50th round ([PERIOD]), so the dash values refresh several times a second.
 * - Batching ("fast read", as in the Python dashboard's --fast-read): up to 6 values per
 *   question. Self-tested at connect against normal reads; a group that keeps giving
 *   garbled answers falls back to one value per question.
 */
object ObdSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var socket: BluetoothSocket? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Live reading, recorded by [recorder]; with [keyTest] set, the KEY TEST is recorded
     * into that file instead.
     */
    fun start(context: Context, adapter: SavedAdapter, gen: Int, recorder: DriveRecorder? = null, keyTest: File? = null) {
        if (isRunning) return
        val appContext = context.applicationContext
        val previous = job
        job = scope.launch {
            previous?.join()   // let a just-stopped loop finish closing its connection first
            while (isActive) {
                try {
                    Reader(gen, recorder, keyTest).connectAndRead(appContext, adapter.address)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isActive) break   // stop() closed the link on purpose
                    Dash.log(gen, "Lost: ${e.message ?: e.javaClass.simpleName}")
                    Dash.update(gen) { it.copy(status = "Connection lost, retrying in 5 s") }
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
    }

    /** One connection attempt: everything here belongs to that attempt only. */
    private class Reader(val gen: Int, val recorder: DriveRecorder?, val keyTest: File?) {
        private lateinit var elm: Elm327
        private var countSuffix = true            // "010C1": stop waiting after 1 answer line
        private val sizes = HashMap<Int, Int>()   // data bytes per value, learned from single reads
        private val readings = HashMap<Int, Reading>()
        private var portVolts: Double? = null

        private fun log(line: String) = Dash.log(gen, line)
        private fun status(text: String) = Dash.update(gen) { it.copy(status = text) }

        @SuppressLint("MissingPermission")   // the screens check the permission before starting
        fun connectAndRead(context: Context, address: String) {
            val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("This phone has no Bluetooth")
            if (!bluetooth.isEnabled) throw IOException("Bluetooth is off")
            val device = bluetooth.getRemoteDevice(address)

            status("Connecting to ${device.name ?: address}…")
            elm = Elm327(openSocket(device))
            log("Bluetooth connected")

            status("Setting up adapter…")
            for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP$PROTOCOL", "ATI")) {
                val answer = elm.command(cmd, timeoutMs = if (cmd == "ATZ") 5000 else 2000)
                log("$cmd → ${answer.joinToString(" | ")}")
            }

            if (keyTest != null) return runKeyTest(keyTest)

            status("Finding sensors…")
            val modules = findSupported()
            for ((header, pids) in modules) {
                log("${moduleName(header)}: ${pids.size} sensors ${pids.joinToString(" ") { "%02X".format(it) }}")
            }
            // Only the engine computer is read: the AMT (7E9) refuses direct requests, and its
            // 9 values just repeat the engine's (test drive 2026-10-26).
            val pids = modules[ENGINE] ?: throw IOException("The engine computer (7E8) didn't answer")
            elm.command("ATSH7E0")   // from now on, ask the engine computer directly

            // 1. One normal round: first values on screen, and each value's size and timing.
            status("Reading all values once…")
            val singleMs = HashMap<Int, Long>()
            for (pid in pids) {
                val started = System.currentTimeMillis()
                readSingle(pid)
                singleMs[pid] = System.currentTimeMillis() - started
            }
            publish()
            readAdapterVoltage()

            // 2. Groups by how often they're read; self-test the batches.
            val groups = buildGroups(pids)
            selfTest(groups, singleMs)
            recorder?.saveProfile(device.name, modules, Dash.state.value.fastRead, readings)

            // 3. The reading loop.
            status("Connected")
            var round = 0
            var rate = 0.0
            var last = System.currentTimeMillis()
            while (true) {
                for (group in groups) {
                    if ((round + group.phase) % group.period == 0) readGroup(group)
                }
                if (round % 10 == 5) readAdapterVoltage()
                round++
                val now = System.currentTimeMillis()
                val instant = 1000.0 / maxOf(now - last, 1L)
                rate = if (rate == 0.0) instant else rate * 0.9 + instant * 0.1
                last = now
                val r = round
                val perSec = rate
                val snapshot = readings.toMap()
                recorder?.write(snapshot, portVolts)
                val recorded = recorder?.rows ?: 0
                Dash.update(gen) { it.copy(readings = snapshot, rounds = r, roundsPerSec = perSec, recordedRows = recorded) }
            }
        }

        /**
         * KEY TEST loop, about once a second until stopped. Each row: battery voltage at the
         * OBD port (3 readings, so the starter's dip isn't missed), which computers answer
         * "01 00", the engine's RPM, and what the car's network carries in 300 ms of listening.
         * Appends to [file], so a reconnect continues the same test.
         */
        private fun runKeyTest(file: File) {
            val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            val writer = FileWriter(file, true)
            try {
                if (file.length() == 0L) {
                    writer.write("# Car Dash ${BuildConfig.VERSION_NAME} KEY TEST, Tata Tiago, adapter ${elm.command("ATI").firstOrNull()}\n")
                    writer.write("# label = key position tapped in the DEV tab; tap = set on the row right after a tap\n")
                    writer.write("time,t,label,tap,volt1,volt2,volt3,ecus,rpm,frames,ids,id_list\n")
                }
                val started = System.currentTimeMillis()
                var rows = 0
                var recent = listOf<String>()
                status("Key test running")
                while (true) {
                    val v1 = volts()
                    val ecus = assembleCan11(elm.command("0100", 3000)).keys.sorted()
                    val v2 = volts()
                    val rpmData = assembleCan11(elm.command("010C", 3000))[ENGINE]
                    val rpm = if (rpmData != null && rpmData.size >= 4 && rpmData[0] == 0x41 && rpmData[1] == 0x0C) {
                        (rpmData[2] * 256 + rpmData[3]) / 4.0
                    } else {
                        null
                    }
                    val v3 = volts()
                    val frames = elm.monitor(300)
                    val ids = frames.map { it.substring(0, 3) }.toSortedSet()

                    val label = KeyMarks.label
                    val tap = KeyMarks.takeTap() ?: ""
                    val t = (System.currentTimeMillis() - started) / 1000.0
                    val volt = listOf(v1, v2, v3).map { it?.let { v -> String.format(Locale.US, "%.2f", v) } ?: "" }
                    val row = listOf(
                        clock.format(Date()), String.format(Locale.US, "%.1f", t), label, tap,
                        volt[0], volt[1], volt[2], ecus.joinToString(" "), rpm?.let { String.format(Locale.US, "%.0f", it) } ?: "",
                        frames.size.toString(), ids.size.toString(), ids.joinToString(" "),
                    )
                    writer.write(row.joinToString(",") + "\n")
                    writer.flush()
                    rows++

                    // Short line for the screen (30 characters): seconds, label, lowest voltage, computers, messages.
                    val low = listOfNotNull(v1, v2, v3).minOrNull()
                    val who = ecus.joinToString("") { it.takeLast(2) }.ifEmpty { "--" }
                    val line = String.format(
                        Locale.US, "%4.0fs %-5s %5s %-4s %4df", t, label,
                        low?.let { String.format(Locale.US, "%.2fV", it) } ?: "--", who.take(4), frames.size,
                    )
                    recent = (recent + line).takeLast(12)
                    val n = rows
                    val r = recent
                    Dash.update(gen) {
                        it.copy(
                            status = "Key test: $n rows",
                            adapterVoltage = low ?: it.adapterVoltage,
                            test = TestInfo(file.name, KeyMarks.label, n, r),
                        )
                    }
                }
            } finally {
                writer.close()
            }
        }

        private fun volts(): Double? = elm.command("ATRV").firstOrNull()?.trimEnd('V', 'v')?.toDoubleOrNull()

        /** Asks "which sensors do you have?" (01 00, 01 20, …) and collects the answers per computer. */
        private fun findSupported(): Map<String, List<Int>> {
            val supported = sortedMapOf<String, MutableList<Int>>()
            var base = 0x00
            while (base <= 0xE0) {
                if (base != 0x00 && supported.values.none { base in it }) break
                val answer = elm.command("01%02X".format(base), timeoutMs = 5000)
                log("01%02X → %s".format(base, answer.joinToString(" | ")))
                for ((header, d) in assembleCan11(answer)) {
                    if (d.size < 6 || d[0] != 0x41 || d[1] != base) continue
                    val pids = supported.getOrPut(header) { mutableListOf() }
                    for (i in 0 until 32) {
                        if (d[2 + i / 8] and (0x80 shr (i % 8)) != 0) pids += base + i + 1
                    }
                }
                base += 0x20
            }
            if (supported.isEmpty()) throw IOException("No car computer answered (ignition on?)")
            // 0x20, 0x40, … only say "there are more"; they are not sensors.
            return supported.mapValues { (_, pids) -> pids.filter { it % 0x20 != 0 } }
        }

        /** Sends a request, adding the answer-count digit if the adapter supports it. */
        private fun ask(request: String, count: Int): List<String> {
            if (countSuffix && count in 1..15) {
                val answer = elm.command(request + "%X".format(count))
                if (answer != listOf("?")) return answer
                log("Adapter doesn't support the answer-count digit; reading without it")
                countSuffix = false
            }
            return elm.command(request)
        }

        /** Reads one value the normal way and stores it. */
        private fun readSingle(pid: Int) {
            val answer = ask("01%02X".format(pid), 1)
            val d = assembleCan11(answer)[ENGINE]
            when {
                d != null && d.size >= 2 && d[0] == 0x41 && d[1] == pid -> {
                    val data = d.copyOfRange(2, d.size)
                    sizes[pid] = data.size
                    store(pid, data)
                }
                d != null && d.isNotEmpty() && d[0] == 0x7F -> storeText(pid, "refused")
                else -> storeText(pid, answer.firstOrNull() ?: "NO DATA")
            }
        }

        /** Reads a group: in one question if batching works, else value by value. */
        private fun readGroup(group: Group) {
            if (group.batched) {
                val values = askBatch(group)
                if (values != null) {
                    group.failures = 0
                    for ((pid, data) in values) store(pid, data)
                    // Values the car left out this time are read the normal way.
                    for (pid in group.pids) if (pid !in values) readSingle(pid)
                    return
                }
                group.failures++
                if (group.failures >= 3) {
                    group.batched = false
                    log("Fast read off for ${group.pids.joinToString(" ") { "%02X".format(it) }}: garbled answers")
                }
            }
            for (pid in group.pids) readSingle(pid)
        }

        /**
         * One question for all values of a group, e.g. "010C0D04455A62" -> {0x0C: [..], 0x0D: [..]}.
         * Empty map: the car didn't answer (e.g. ignition off). null: garbled (bytes don't line up).
         */
        private fun askBatch(group: Group): Map<Int, IntArray>? {
            val request = "01" + group.pids.joinToString("") { "%02X".format(it) }
            val answer = ask(request, group.frames)
            val d = assembleCan11(answer)[ENGINE] ?: return emptyMap()
            if (d.isEmpty() || d[0] != 0x41) return emptyMap()   // silence or a refusal
            val values = LinkedHashMap<Int, IntArray>()
            var i = 1
            while (i < d.size) {
                val pid = d[i]
                val size = if (pid in group.pids) sizes[pid] else null
                if (size == null) {
                    if ((i until d.size).all { d[it] == 0 }) break   // padding at the end
                    return null   // an unexpected value: the bytes don't line up
                }
                if (i + 1 + size > d.size) return null   // cut off
                values[pid] = d.copyOfRange(i + 1, i + 1 + size)
                i += 1 + size
            }
            if (group.frames == 0) group.frames = answer.count { it.replace(" ", "").startsWith(ENGINE) }
            return values
        }

        private fun buildGroups(pids: List<Int>): List<Group> {
            val groups = mutableListOf<Group>()
            val answered = pids.filter { it in sizes }
            for (period in listOf(1, 3, 10, 50)) {
                val chunks = answered.filter { (PERIOD[it] ?: 50) == period }.chunked(MAX_BATCH)
                chunks.forEachIndexed { index, chunk -> groups += Group(chunk, period, index % period) }
            }
            // Values that didn't answer at connect: retried one by one now and then.
            val silent = pids.filter { it !in sizes }
            if (silent.isNotEmpty()) groups += Group(silent, 50, 25).also { it.batched = false }
            return groups
        }

        /**
         * Switches batching on only if every group's combined answer has exactly its values,
         * slow-changing values agree with the normal reads, and it's clearly faster.
         */
        private fun selfTest(groups: List<Group>, singleMs: Map<Int, Long>) {
            fun off(reason: String) {
                groups.forEach { it.batched = false }
                Dash.update(gen) { it.copy(fastRead = "off: $reason") }
                log("Fast read off: $reason")
            }
            status("Testing fast read…")
            val testable = groups.filter { g -> g.pids.size > 1 && g.pids.all { it in sizes } }
            groups.filter { it !in testable }.forEach { it.batched = false }
            if (testable.isEmpty()) return off("nothing to combine")

            var combinedMs = 0L
            var normalMs = 0L
            for (group in testable) {
                askBatch(group)   // the first time teaches us how many answer lines to expect
                val started = System.currentTimeMillis()
                val got = askBatch(group)
                combinedMs += System.currentTimeMillis() - started
                normalMs += group.pids.sumOf { singleMs[it] ?: 0L }
                val names = group.pids.joinToString(" ") { "%02X".format(it) }
                if (got == null || got.keys != group.pids.toSet()) return off("incomplete answer for $names")
                for ((pid, data) in got) {
                    if ((PERIOD[pid] ?: 50) < 10) continue   // fast-changing values may really differ
                    val combined = Pids.decode(pid, data).number ?: continue
                    val normal = readings[pid]?.number ?: continue
                    if (kotlin.math.abs(combined - normal) > maxOf(1.0, 0.02 * kotlin.math.abs(normal))) {
                        return off("%02X read %.4g combined vs %.4g normally".format(pid, combined, normal))
                    }
                }
            }
            if (combinedMs * 1.5 > normalMs) return off("not faster (${combinedMs} vs ${normalMs} ms)")
            val note = String.format(Locale.US, "on: %d groups, %d vs %d ms", testable.size, combinedMs, normalMs)
            Dash.update(gen) { it.copy(fastRead = note) }
            log("Fast read $note")
        }

        private fun readAdapterVoltage() {
            val v = volts()
            portVolts = v
            Dash.update(gen) { it.copy(adapterVoltage = v) }
        }

        private fun store(pid: Int, data: IntArray) {
            val value = Pids.decode(pid, data)
            readings[pid] = Reading(pid, Pids.name(pid), value.number, value.text, System.currentTimeMillis())
        }

        private fun storeText(pid: Int, text: String) {
            readings[pid] = Reading(pid, Pids.name(pid), null, text, System.currentTimeMillis())
        }

        private fun publish() = Dash.update(gen) { it.copy(readings = readings.toMap()) }

        private fun moduleName(header: String) = when (header) {
            "7E8" -> "ENGINE 7E8"
            "7E9" -> "AMT 7E9"
            else -> "ECU $header"
        }

        /** Opens the Bluetooth serial link; some adapters only accept the "insecure" variant. */
        @SuppressLint("MissingPermission")
        private fun openSocket(device: BluetoothDevice): BluetoothSocket {
            for (secure in listOf(true, false)) {
                val s = if (secure) device.createRfcommSocketToServiceRecord(SPP_UUID)
                else device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                ObdSession.socket = s   // so stop() can interrupt a connect() that is still waiting
                try {
                    s.connect()
                    return s
                } catch (e: IOException) {
                    ObdSession.closeSocket()
                    if (!secure) throw e
                    log("Secure connect failed (${e.message}), trying insecure")
                }
            }
            throw IOException("unreachable")
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }
}
