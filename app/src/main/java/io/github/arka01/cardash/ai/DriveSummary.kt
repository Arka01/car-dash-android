package io.github.arka01.cardash.ai

import android.content.Context
import io.github.arka01.cardash.Speech
import io.github.arka01.cardash.cars.CarProfile
import io.github.arka01.cardash.logic.DriveReport
import io.github.arka01.cardash.logic.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** A drive's AI summary: [spoken] (read aloud, a few sentences) and [full] (shown in DRIVES). */
class Summary(val spoken: String, val full: String)

/**
 * The on-phone AI's summary of a drive, written after the engine is switched off and read aloud
 * (owner's wish, 2026-10-31: car speakers while Android Auto is on, else the phone; short version).
 *
 * Grounded: the model gets the drive report with the app's own verdicts (it must not judge numbers
 * itself; build 14's test showed it guesses what's normal), short notes about the car (car_notes.txt
 * in the car's folder, editable) and one line per earlier drive. Saved as drive_<…>_summary.txt.
 */
object DriveSummary {
    private const val MAX_TOKENS = 450

    /** File name of the drive being summarised, or null. */
    private val working = MutableStateFlow<String?>(null)
    val busyWith: StateFlow<String?> = working

    /** Goes up whenever a summary is saved, so screens read it again. */
    private val saved = MutableStateFlow(0)
    val changes: StateFlow<Int> = saved

    private const val SYSTEM = "You are a car mechanic writing a short after-drive summary for the owner, a " +
        "non-expert. The verdicts in the report (OK, WATCH, CHECK) were decided by the app with this car's own " +
        "rules: trust them and never judge a number yourself. OK means normal. Explain the WATCH and CHECK items " +
        "in simple words and say what to do about them. Use the car notes as background. Be brief and calm."

    fun file(csv: File) = File(csv.parentFile, csv.name.removeSuffix(".csv") + "_summary.txt")

    fun read(csv: File): Summary? = runCatching {
        val text = file(csv).takeIf { it.exists() }?.readText() ?: return null
        val spoken = text.substringAfter("SPOKEN:\n", "").substringBefore("\n\nFULL:\n").trim()
        val full = text.substringAfter("FULL:\n", text).trim()
        Summary(spoken, full)
    }.getOrNull()

    /**
     * Writes the summary of [report] (drive [csv]) with the on-phone AI, saves it and, if [speak],
     * reads the short version aloud. Returns why not, or null when started.
     */
    fun start(context: Context, car: CarProfile, csv: File, report: DriveReport, earlier: List<DriveReport>, speak: Boolean): String? {
        val app = context.applicationContext
        if (!LocalLlm.hasModel(app)) return "No AI model imported (AI screen)"
        val prompt = buildString {
            append("CAR NOTES\n").append(notes(car)).append("\n\n")
            append("EARLIER DRIVES\n").append(history(earlier)).append("\n\n")
            append("THIS DRIVE\n").append(report.text()).append('\n')
            append("Write exactly two parts:\n")
            append("SPOKEN: 2 to 4 short sentences to read aloud to the driver. Plain words, at most two numbers, no lists, no symbols.\n")
            append("FULL: a short summary of up to 8 lines, with the reasons.")
        }
        working.value = csv.name
        val why = LocalLlm.ask(app, prompt, system = SYSTEM, maxTokens = MAX_TOKENS, allowLive = true) { answer ->
            if (answer != null) {
                val summary = parse(answer)
                runCatching { file(csv).writeText("SPOKEN:\n${summary.spoken}\n\nFULL:\n${summary.full}\n") }
                saved.value++
                if (speak && summary.spoken.isNotEmpty()) Speech.say(app, summary.spoken)
            }
            working.value = null
        }
        if (why != null) working.value = null
        return why
    }

    /** Stops a summary being written (the engine was started again). */
    fun cancel() {
        if (working.value != null) LocalLlm.stop()
    }

    /** SPOKEN / FULL parts of the answer, without Markdown marks; first sentences if the model ignored the format. */
    private fun parse(answer: String): Summary {
        val clean = plain(answer)
        val spokenAt = Regex("SPOKEN\\s*:", RegexOption.IGNORE_CASE).find(clean)
        val fullAt = Regex("FULL\\s*:", RegexOption.IGNORE_CASE).find(clean)
        if (spokenAt != null && fullAt != null && fullAt.range.first > spokenAt.range.last) {
            val spoken = clean.substring(spokenAt.range.last + 1, fullAt.range.first).trim()
            val full = clean.substring(fullAt.range.last + 1).trim()
            return Summary(spoken, full)
        }
        val sentences = Regex("(?<=[.!?])\\s+").split(clean.trim())
        return Summary(sentences.take(3).joinToString(" "), clean.trim())
    }

    /** Text without Markdown: no **bold**, # headings or list stars. */
    fun plain(text: String): String = text
        .replace("**", "")
        .replace("__", "")
        .lines()
        .joinToString("\n") { it.replace(Regex("^\\s*#+\\s*"), "").replace(Regex("^\\s*[*-]\\s+"), "- ") }

    /** The car notes: car_notes.txt in the car's folder (written with defaults the first time). */
    fun notes(car: CarProfile): String {
        val f = car.file("car_notes.txt")
        f?.takeIf { it.exists() }?.let { return it.readText().trim() }
        val text = DEFAULT_NOTES[car.key] ?: buildString {
            append("${car.name}, ${car.fuel}.\n")
            if (car.smartAlternator) append("- Smart alternator: 12-13 V while accelerating is normal.\n")
            append("- Owner's limits: shift light ${car.shiftRpm} rpm, red line ${car.redlineRpm} rpm.\n")
        }
        runCatching { f?.writeText(text + "\n") }
        return text.trim()
    }

    /** One line per earlier drive (newest 5), e.g. "30 Oct: 3.5 km, 10.0 L/100 km; WATCH battery only ~44 % charged". */
    private fun history(earlier: List<DriveReport>): String {
        val date = SimpleDateFormat("d MMM", Locale.UK)
        val lines = earlier.sortedByDescending { it.start }.take(5).map { r ->
            val issues = r.verdicts.filter { it.verdict == Verdict.WATCH || it.verdict == Verdict.CHECK }
                .joinToString("; ") { "${it.verdict} ${it.label}" }.ifEmpty { "all OK" }
            String.format(Locale.US, "%s: %.1f km, %s; %s", date.format(r.start), r.km,
                r.lPer100?.let { String.format(Locale.US, "%.1f L/100 km", it) } ?: "fuel --", issues)
        }
        return lines.joinToString("\n").ifEmpty { "none" }
    }

    /** What we found out about each car (drives 2026-10-13…31); the owner can edit car_notes.txt. */
    private val DEFAULT_NOTES = mapOf(
        "tata_tiago_2023" to """
            2023 Tata Tiago 1.2 petrol, AMT gearbox, 3 cylinders, about 113 Nm and 84 bhp new.
            - Smart alternator: charges mostly while slowing down; 12-13 V while accelerating is normal. Charging is judged by the highest voltage in 5 minutes.
            - Battery: resting voltage has often been only 12.2-12.3 V (about half charged; short trips don't fill it). Start dips 9.7-10.5 V.
            - Normal warm engine: coolant 85-100 °C, oil similar.
            - Intake air reaches 60-67 °C in slow city traffic: normal heat soak.
            - The outside-air temperature reading of this car is unreliable: ignore it.
            - Owner's limits: shift light 3500 rpm, red line 4000 rpm.
        """.trimIndent(),
    )
}
