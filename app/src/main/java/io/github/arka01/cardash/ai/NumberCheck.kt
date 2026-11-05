package io.github.arka01.cardash.ai

import kotlin.math.abs
import kotlin.math.floor

/**
 * The AI's numbers against the data it was given (2026-11-05: the small model once wrote "546 km/h"
 * for 54). A number in the answer that isn't in the data, allowing for rounding, is "bad". No retry
 * (the owner didn't want the delay): written answers show a ⚠, spoken ones (Talk) leave out the
 * sentences with a bad number. Milliseconds, no AI involved.
 */
object NumberCheck {
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")
    private val THOUSANDS = Regex("(?<=\\d),(?=\\d{3})")   // "4,094" -> "4094"
    private val UNIT = Regex("^(km|l\\b|litre|liter|v\\b|volt|°|rpm|%|nm|bhp|min|kpa|s\\b|sec)", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

    /** Numbers in [answer] that appear nowhere in [data] (the system instruction + message the AI got). */
    fun bad(answer: String, data: String): List<String> {
        val known = NUMBER.findAll(data.replace(THOUSANDS, "")).mapNotNull { it.value.toDoubleOrNull() }.toSet()
        val text = answer.replace(THOUSANDS, "")
        val out = mutableListOf<String>()
        for (m in NUMBER.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            val before = text.getOrNull(start - 1)
            if (before == 'D' || before == 'd') continue                          // a drive tag: D2
            if (before == ':' || text.getOrNull(end) == ':') continue             // a clock time: 9:16
            val v = m.value.toDoubleOrNull() ?: continue
            val unit = UNIT.containsMatchIn(text.substring(end).trimStart())
            if (v <= 10 && v == floor(v) && !unit) continue                       // a count: "2 drives"
            if (known.any { k -> close(k, v) }) continue
            out += m.value
        }
        return out.distinct()
    }

    /** Rounded is fine: 12.4 -> "12", 46 -> "about 50", 4094 -> "about 4100". */
    private fun close(known: Double, said: Double): Boolean {
        if (abs(known - said) <= 0.5) return true
        if (said % 10 == 0.0 && abs(known - said) <= 5) return true
        return known != 0.0 && abs(known - said) / abs(known) <= 0.02
    }

    /** [answer] without the sentences that contain one of [bad] (for speaking). */
    fun withoutBad(answer: String, bad: List<String>): String =
        answer.split(SENTENCE_END).filter { sentence ->
            val s = sentence.replace(THOUSANDS, "")
            bad.none { b -> Regex("(?<![\\d.])" + Regex.escape(b) + "(?!\\d)").containsMatchIn(s) }
        }.joinToString(" ").trim()
}
