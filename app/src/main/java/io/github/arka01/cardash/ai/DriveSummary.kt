package io.github.arka01.cardash.ai

import android.content.Context
import io.github.arka01.cardash.Dash
import io.github.arka01.cardash.Speech
import io.github.arka01.cardash.car.CarControls
import io.github.arka01.cardash.cars.CarProfile
import io.github.arka01.cardash.logic.DriveReport
import io.github.arka01.cardash.logic.DriveTotals
import io.github.arka01.cardash.logic.FuelReport
import io.github.arka01.cardash.logic.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A drive's AI summary: [witty] line, [refuel] reminder (only when the fuel range is low), [spoken]
 * (read aloud, a few sentences) and [full] (shown in DRIVES).
 */
class Summary(val spoken: String, val full: String, val witty: String = "", val refuel: String = "")

/**
 * The on-phone AI's summary of a drive, written after the engine is switched off and read aloud
 * (owner's wish, 2026-10-31: car speakers while Android Auto is on, else the phone; short version).
 *
 * Grounded: the model gets the drive report with the app's own verdicts (it must not judge numbers
 * itself; build 14's test showed it guesses what's normal), short notes about the car (car_notes.txt
 * in the car's folder, editable; only when there's a WATCH or CHECK to explain). Saved as drive_<…>_summary.txt.
 */
object DriveSummary {
    private const val MAX_TOKENS = 450
    private const val LOW_RANGE_KM = 50.0   // below this range the AI reminds the driver to fuel up

    /** File name of the drive being summarised, or null. */
    private val working = MutableStateFlow<String?>(null)
    val busyWith: StateFlow<String?> = working

    /** Goes up whenever a summary is saved, so screens read it again. */
    private val saved = MutableStateFlow(0)
    val changes: StateFlow<Int> = saved

    private const val SYSTEM = "You are a car mechanic writing a short after-drive summary for the owner, a " +
        "non-expert. The verdicts in the report (OK, WATCH, CHECK) were decided by the app with this car's own " +
        "rules: trust them and never judge a number yourself. OK means normal. Only talk about THIS DRIVE: its " +
        "findings are exactly the WATCH and CHECK lines in its VERDICT, nothing else. Explain them in simple words " +
        "and say what to do. If there are none, say the drive was normal. The car notes are " +
        "only background to explain things: never present anything from them as a finding of this drive. " +
        "RPM is how fast the engine turns, not the car's speed. A car battery is charged by driving: if it's low " +
        "on charge, suggest longer drives (or a battery charger if it stays low); never say to top it up. " +
        "Be brief and calm."

    fun file(csv: File) = File(csv.parentFile, csv.name.removeSuffix(".csv") + "_summary.txt")

    /** The drive (CSV file name) the last question was about, so its report shows the answer. */
    private val lastAsk = MutableStateFlow<String?>(null)
    val lastAskDrive: StateFlow<String?> = lastAsk

    private const val SYSTEM_QUESTION = "You are a car mechanic answering the owner's question about one drive " +
        "of their car. Use only the drive report and the notes given. The verdicts (OK, WATCH, CHECK) were decided by " +
        "the app with this car's own rules: trust them and never judge a number yourself; OK means normal. The car " +
        "notes and earlier drives are background, not findings of this drive. FUEL NOW is the tank today, not at " +
        "the time of the drive. If the report doesn't contain the " +
        "answer, say so. Copy every number exactly as written in the report, with its unit; never change, join or invent " +
        "digits. Answer in plain words, briefly."

    /** [lastAskDrive] when the last question was about several drives (Drives → Ask AI). */
    const val SEVERAL = "several drives"

    private const val SYSTEM_SEVERAL = "You are a car mechanic answering the owner's question about several drives " +
        "of their car, tagged D1 (oldest) to the newest. Use only the totals, trends, drive reports and notes given. " +
        "The totals and trends were calculated by the app: trust them, never recalculate them or judge a number " +
        "yourself. The verdicts (OK, WATCH, CHECK) were decided by the app with this car's own rules; OK means normal. " +
        "When you mention a drive, give its tag and date. The car notes are background, not findings. FUEL NOW is " +
        "the tank today. RPM is how fast the engine turns, not the car's speed. A car battery is charged by driving: " +
        "if it's low on charge, suggest longer drives (or a battery charger if it stays low); never say to top it up. " +
        "If the answer isn't in the data, say so. Answer in plain words in 2 to 4 sentences, with the key numbers " +
        "and the drive tags (D1, D2 …) behind your answer. If the totals say it's only a rough sign, say so too. Copy " +
        "every number exactly as written in the data, with its unit; never change, join or invent digits."
    private const val SEVERAL_TOKENS = 700         // answer; the prompt gets the rest of the model's 4096
    private const val SEVERAL_FULL_CHARS = 8500    // longer than this: one line per drive instead of its report

    /**
     * Ask about several drives (up to [DriveTotals.MAX_DRIVES]): totals and trends calculated by the
     * app (logic/DriveTotals) plus each drive's report, tagged D1 (oldest) … Returns why not, or null when started.
     */
    fun askSeveral(context: Context, question: String, car: CarProfile, reports: List<DriveReport>, fuel: FuelReport?): String? {
        fun prompt(compact: Boolean) = buildString {
            append("BACKGROUND, NOT FINDINGS: CAR NOTES\n").append(notes(car)).append("\n\n")
            append(fuelText(fuel)).append("\n")
            append("TOTALS AND TRENDS (calculated by the app, trust them)\n").append(DriveTotals.totals(reports)).append("\n")
            append("THE DRIVES (findings are only the WATCH / CHECK lines)\n").append(DriveTotals.drives(reports, compact)).append("\n")
            append("QUESTION: ").append(question)
        }
        val full = prompt(compact = false)
        val text = if (full.length + SYSTEM_SEVERAL.length > SEVERAL_FULL_CHARS) prompt(compact = true) else full
        val why = LocalLlm.ask(context, text, system = SYSTEM_SEVERAL, maxTokens = SEVERAL_TOKENS, shown = question, greedy = true)
        if (why == null) lastAsk.value = SEVERAL
        return why
    }

    /**
     * Ask: [question] with the chosen drive's report as context (grounded like the summary), so
     * "How was my drive?" can be answered (2026-11-01: a plain question had no drive data).
     * Returns why not, or null when started.
     */
    fun ask(context: Context, question: String, car: CarProfile, report: DriveReport, earlier: List<DriveReport>, csvName: String,
            fuel: FuelReport?): String? {
        val prompt = buildString {
            append("BACKGROUND, NOT FINDINGS: CAR NOTES\n").append(notes(car)).append("\n\n")
            append("BACKGROUND, NOT FINDINGS: EARLIER DRIVES\n").append(history(earlier)).append("\n\n")
            append(fuelText(fuel)).append("\n")
            append("THE DRIVE (its findings are only the WATCH / CHECK lines under VERDICT)\n").append(report.text()).append("\n")
            append("QUESTION: ").append(question)
        }
        val why = LocalLlm.ask(context, prompt, system = SYSTEM_QUESTION, shown = question, greedy = true)
        if (why == null) lastAsk.value = csvName
        return why
    }

    fun read(csv: File): Summary? = runCatching {
        val text = file(csv).takeIf { it.exists() }?.readText() ?: return null
        val witty = text.substringAfter("WITTY:\n", "").substringBefore("\n\n").trim()
        val refuel = text.substringAfter("REFUEL:\n", "").substringBefore("\n\n").trim()
        val spoken = text.substringAfter("SPOKEN:\n", "").substringBefore("\n\nFULL:\n").trim()
        val full = text.substringAfter("FULL:\n", text).trim()
        Summary(spoken, full, witty, refuel)
    }.getOrNull()

    /**
     * Writes the summary of [report] (drive [csv]) with the on-phone AI, saves it and, if [speak],
     * reads the short version aloud. [fuel] = the tank right now, so a low tank gets a reminder.
     * [inCarOnly]: speak only if the phone is still connected to the car when it's ready (owner's rule,
     * 2026-11-02: unplugged = in a hurry or a break, don't play it on the phone; it's still saved).
     * Returns why not, or null when started.
     */
    fun start(context: Context, car: CarProfile, csv: File, report: DriveReport, fuel: FuelReport?, speak: Boolean,
              inCarOnly: Boolean = false): String? {
        val app = context.applicationContext
        if (!LocalLlm.hasModel(app)) return "No AI model imported (AI screen)"
        val prompt = buildString {
            // The small model reports background as findings even when it's marked (2026-10-31 and 2026-11-02:
            // a battery WATCH from an earlier drive / the notes on a clean drive). So: no earlier drives here,
            // and the car notes only when there's a WATCH or CHECK for them to explain.
            if (report.verdicts.any { it.verdict != Verdict.OK }) {
                append("BACKGROUND, NOT FINDINGS: CAR NOTES\n").append(notes(car)).append("\n\n")
            }
            append("THIS DRIVE (its findings are only the WATCH / CHECK lines under VERDICT)\n").append(report.text()).append('\n')
            // Fuel only when it's low, as its own part (2026-11-02: inside SPOKEN, "fuel up soon" got glued
            // onto the battery finding: "get the battery topped up").
            val range = lowRange(fuel)
            if (range != null) append(fuelText(fuel)).append('\n')
            append(if (range != null) "Write exactly four parts:\n" else "Write exactly three parts:\n")
            // The kind of drive in plain words, not just a bracketed label (2026-11-07: for 'standing still' the
            // small model followed the old highway/traffic examples and wrote "a slow and steady morning cruise").
            append("WITTY: one short, light-hearted, friendly sentence that fits this kind of drive: ${driveMeaning(report.driveType)} " +
                "Be playful and creative, in your own words. Never mock the driver. No numbers.\n")
            if (range != null) {
                append(String.format(Locale.US, "REFUEL: one short, friendly sentence in your own words telling the driver the " +
                    "fuel tank is getting low, with about %.0f kilometres left, and to fill up with ${car.fuel} soon. This is " +
                    "about fuel in the tank only, not the battery. Write the word kilometres.\n", range))
            }
            append("SPOKEN: 2 to 4 short sentences to read aloud to the driver. Plain words, at most two numbers, no lists, no symbols. " +
                (if (range != null) "Leave fuel out of it: that's in REFUEL. " else "") +
                "End with a short, warm goodbye in your own words that fits the ${partOfDay()}, different each time.\n")
            append("FULL: a short summary of up to 8 lines, with the reasons.")
        }
        working.value = csv.name
        val why = LocalLlm.ask(app, prompt, system = SYSTEM, maxTokens = MAX_TOKENS, allowLive = true, shown = "Summary of the drive") { answer ->
            if (answer != null) {
                val summary = parse(answer)
                val witty = summary.witty.ifEmpty { fallbackWitty(report.driveType) }
                val range = lowRange(fuel)
                val refuel = if (range == null) "" else summary.refuel.ifEmpty {
                    String.format(Locale.US, "Fuel is getting low: about %.0f kilometres left.", range)
                }
                val refuelPart = if (refuel.isEmpty()) "" else "REFUEL:\n$refuel\n\n"
                runCatching { file(csv).writeText("WITTY:\n$witty\n\n${refuelPart}SPOKEN:\n${summary.spoken}\n\nFULL:\n${summary.full}\n") }
                saved.value++
                if (speak && inCarOnly && !CarControls.inCar) {
                    Dash.note("Drive summary saved, not spoken: the phone isn't connected to the car")
                } else if (speak && summary.spoken.isNotEmpty()) {
                    Speech.say(app, listOf(greeting(report), witty, refuel, summary.spoken).filter { it.isNotEmpty() }.joinToString(" "))
                }
            }
            working.value = null
        }
        if (why != null) working.value = null
        return why
    }

    /**
     * The friendly start of the spoken summary (2026-11-01: it began "very mechanically" with stats):
     * a greeting for the time of day and the drive in one line, e.g. "Good evening! Your drive is
     * complete: 15 minutes and 3.5 kilometres." Made by the app, so it's instant and always right.
     */
    private fun greeting(report: DriveReport): String {
        val hello = when (partOfDay()) {
            "morning" -> "Good morning!"
            "afternoon" -> "Good afternoon!"
            "evening" -> "Good evening!"
            else -> "Hello!"
        }
        val minutes = report.minutes.toInt().coerceAtLeast(1)
        val km = if (report.km >= 10) String.format(Locale.US, "%.0f", report.km) else String.format(Locale.US, "%.1f", report.km)
        return "$hello Your drive is complete: $minutes ${if (minutes == 1) "minute" else "minutes"} and $km kilometres."
    }

    /** The range left in km when it's under [LOW_RANGE_KM] (owner's rule, 2026-11-02), else null. Decided here, not by the model. */
    private fun lowRange(fuel: FuelReport?): Double? = fuel?.takeIf { it.litres != null }?.rangeKm?.takeIf { it < LOW_RANGE_KM }

    /** The tank right now, for the AI. */
    private fun fuelText(fuel: FuelReport?): String = buildString {
        append("FUEL NOW\n")
        val litres = fuel?.litres
        if (fuel == null || litres == null) {
            append("  not known\n  LOW FUEL: unknown\n")
            return@buildString
        }
        append(String.format(Locale.US, "  about %.0f litres left of %.0f (%.0f %%)", litres, fuel.tank, litres / fuel.tank * 100))
        fuel.sigma?.let { append(String.format(Locale.US, ", give or take %.0f", maxOf(it, 1.0))) }
        append('\n')
        fuel.rangeKm?.let { append(String.format(Locale.US, "  range about %.0f km\n", it)) }
        append("  LOW FUEL: ").append(if (lowRange(fuel) != null) "yes" else "no").append('\n')
    }

    /** "morning", "afternoon", "evening" or "night", for the greeting and the AI's goodbye. */
    private fun partOfDay(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    /** The kind of drive spelled out for the WITTY line, so the model can't turn "standing still" into a cruise. */
    private fun driveMeaning(type: String): String = when {
        type.startsWith("standing still") -> "the car did NOT move at all, the engine only idled while parked " +
            "(for example warming up or waiting). Joke about the idling, for example a car that got ready for an " +
            "adventure and stayed put. Do not call it a drive, ride, trip or cruise."
        type.startsWith("traffic jam") -> "a traffic jam: crawling at walking pace with lots of waiting, " +
            "for example the patience it took. Do not call it a cruise."
        type.startsWith("highway") -> "a highway run: long stretches at highway speed, for example enjoying the open road."
        type.startsWith("city") -> "a city drive: town streets, stop and go, for example signals, turns and busy roads."
        else -> "a mixed drive: a bit of town and a bit of open road."
    } + if (type.endsWith("spirited")) " The driver also revved the engine hard for a moment, so a sporty touch fits." else ""

    /** A ready-made witty line for the kind of drive, if the AI didn't write one. */
    private fun fallbackWitty(type: String): String = when {
        type.startsWith("standing still") -> "I hope you enjoyed just sitting in one place."
        type.startsWith("traffic jam") -> "Well done for surviving that traffic."
        type.startsWith("highway") -> "I hope you enjoyed the open road."
        type.startsWith("city") -> "A proper city drive, stop and go."
        else -> "A nice mix of everything today."
    } + if (type.endsWith("spirited")) " And a little sporty, too." else ""

    /** Stops a summary being written (the engine was started again). */
    fun cancel() {
        if (working.value != null) LocalLlm.stop()
    }

    /** WITTY / SPOKEN / FULL parts of the answer, without Markdown marks; first sentences if the model ignored the format. */
    private fun parse(answer: String): Summary {
        val clean = plain(answer)
        val wittyAt = heading("WITTY").find(clean)
        val refuelAt = heading("REFUEL").find(clean)
        val spokenAt = heading("SPOKEN").find(clean)
        val fullAt = heading("FULL").find(clean)
        if (spokenAt != null && fullAt != null && fullAt.range.first > spokenAt.range.last) {
            val refuelEnd = refuelAt?.takeIf { it.range.last < spokenAt.range.first }
            val witty = if (wittyAt != null && wittyAt.range.last < (refuelEnd ?: spokenAt).range.first) {
                firstLine(clean.substring(wittyAt.range.last + 1, (refuelEnd ?: spokenAt).range.first))
            } else ""
            val refuel = refuelEnd?.let { firstLine(clean.substring(it.range.last + 1, spokenAt.range.first)) } ?: ""
            val spoken = clean.substring(spokenAt.range.last + 1, fullAt.range.first).trim()
            val full = clean.substring(fullAt.range.last + 1).trim()
            return Summary(spoken, full, witty, refuel)
        }
        val sentences = Regex("(?<=[.!?])\\s+").split(clean.trim())
        return Summary(sentences.take(3).joinToString(" "), clean.trim())
    }

    private fun firstLine(text: String) = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""

    /** "NAME:" anywhere, or NAME alone on its line (2026-11-02: the model wrote headings without colons). */
    private fun heading(name: String) = Regex("(?im)(^[ \\t]*$name[ \\t]*(:|$)|$name[ \\t]*:)")

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
