package io.github.arka01.cardash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Finds out why the app last closed unexpectedly, so a crash can be shared and fixed (build 13,
 * after the on-phone AI crashed on 2026-10-31 on both GPU and CPU).
 *
 * - Android 11+ keeps a record of why an app's process ended (native crash, out of memory, …),
 *   for native crashes with the crash details ("tombstone"). Read at the next start.
 * - Java/Kotlin crashes: the stack trace is written to a file just before the app closes.
 * - [step]: the on-phone AI notes each step (loading, engine started, asking, …) in a file, so
 *   the report shows the last step reached before the crash.
 *
 * Reports are saved in files/dev/crash_<date>_<time>.txt and shown on the AI screen.
 */
object CrashReport {
    private const val PREFS = "crash"
    private const val STEPS = "ai_steps.txt"
    private const val JAVA_CRASH = "java_crash.txt"

    private val store = MutableStateFlow<String?>(null)
    /** The newest unexpected exit not yet cleared, as text, or null. */
    val report: StateFlow<String?> = store

    @Volatile private var dir: File? = null
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** At app start: install the Java crash catcher and look for an exit since the last check. */
    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, Dash.DEV_DIR).apply { mkdirs() }
        dir = d
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                File(d, JAVA_CRASH).writeText("Thread: ${thread.name}\n$trace")
            }
            previous?.uncaughtException(thread, e)
        }
        runCatching { check(context, d) }.onFailure { store.value = "Couldn't read the last exit: ${it.message}" }
    }

    /** One AI step, appended to the steps file at once (it must survive a crash right after). */
    fun step(text: String, first: Boolean = false) {
        val d = dir ?: return
        runCatching {
            FileWriter(File(d, STEPS), !first).use { it.write("${clock.format(Date())} $text\n") }
        }
    }

    fun clear(context: Context) {
        store.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("seen", System.currentTimeMillis()).apply()
    }

    private fun check(context: Context, d: File) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen", 0L)
        val out = StringBuilder()
        var found = false

        val javaCrash = File(d, JAVA_CRASH)
        if (javaCrash.exists()) {
            out.append("== Java/Kotlin crash ==\n").append(javaCrash.readText().take(6000)).append('\n')
            javaCrash.delete()
            found = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            val exit = exits.firstOrNull { it.timestamp > seen && it.reason in INTERESTING }
            if (exit != null) {
                found = true
                out.append(describe(exit))
            }
        }
        prefs.edit().putLong("seen", System.currentTimeMillis()).apply()
        if (!found) return

        val steps = File(d, STEPS)
        if (steps.exists()) out.append("\n== Last AI steps ==\n").append(steps.readText().takeLast(3000))
        out.append("\n== Phone ==\n").append(phone(context))
        val text = out.toString()
        store.value = text
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        runCatching { File(d, "crash_$stamp.txt").writeText(text) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(e: ApplicationExitInfo): String {
        val out = StringBuilder()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp))
        out.append("== Exit ==\n")
        out.append("when: $time\n")
        out.append("reason: ${REASONS[e.reason] ?: e.reason.toString()}")
        if (e.reason == SIGNALED || e.reason == CRASH_NATIVE) out.append(" (signal ${e.status}${SIGNALS[e.status]?.let { " $it" } ?: ""})")
        out.append('\n')
        e.description?.let { out.append("description: $it\n") }
        out.append(String.format(Locale.US, "memory: pss %.0f MB, rss %.0f MB\n", e.pss / 1024.0, e.rss / 1024.0))
        // Native crashes: Android 12+ attaches the tombstone (protobuf); its readable parts are enough.
        if (e.reason == CRASH_NATIVE || e.reason == CRASH || e.reason == ANR) {
            runCatching {
                e.traceInputStream?.use { input ->
                    val bytes = input.readBytes().let { if (it.size > 3_000_000) it.copyOf(3_000_000) else it }
                    out.append("\n== Crash details (readable parts) ==\n").append(readable(bytes))
                }
            }.onFailure { out.append("(no crash details: ${it.message})\n") }
        }
        return out.toString()
    }

    /** Printable text runs from binary data, the interesting ones first (abort message, libraries, functions). */
    private fun readable(bytes: ByteArray): String {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                current.append(c.toChar())
            } else {
                if (current.length >= 5) runs += current.toString()
                current.clear()
            }
        }
        if (current.length >= 5) runs += current.toString()
        val keys = listOf("abort", "check failed", "fault", "sig", "error", "assert", "fatal", ".so", "litert", "jni", "oom", "memory")
        val important = runs.filter { r -> keys.any { r.lowercase().contains(it) } }.distinct().take(120)
        val rest = runs.distinct().filter { it !in important }.take(60)
        return (important + listOf("--- other ---") + rest).joinToString("\n").take(12000) + "\n"
    }

    private fun phone(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return String.format(
            Locale.US, "%s %s (%s), Android %s (SDK %d), app %s, RAM %.1f GB total / %.1f GB free now\n",
            Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            BuildConfig.VERSION_NAME, mem.totalMem / 1e9, mem.availMem / 1e9,
        )
    }

    // ApplicationExitInfo reasons as numbers (some constants are newer than minSdk).
    private const val SIGNALED = 2
    private const val CRASH = 4
    private const val CRASH_NATIVE = 5
    private const val ANR = 6
    private val INTERESTING = setOf(SIGNALED, 3, CRASH, CRASH_NATIVE, ANR, 7, 9, 13)
    private val REASONS = mapOf(
        0 to "unknown", 1 to "exited by itself", 2 to "killed by a signal", 3 to "LOW MEMORY (Android closed it)",
        4 to "CRASH (Java/Kotlin)", 5 to "NATIVE CRASH", 6 to "ANR (not responding)", 7 to "start-up failure",
        8 to "permission change", 9 to "excessive resource use", 10 to "user requested", 11 to "user stopped",
        12 to "dependency died", 13 to "other", 14 to "freezer", 15 to "package state change", 16 to "app updated",
    )
    private val SIGNALS = mapOf(6 to "SIGABRT", 9 to "SIGKILL", 11 to "SIGSEGV", 7 to "SIGBUS", 4 to "SIGILL", 8 to "SIGFPE")
}
