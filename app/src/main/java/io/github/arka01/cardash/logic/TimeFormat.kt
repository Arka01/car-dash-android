package io.github.arka01.cardash.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Times as the owner sees them: 12-hour with AM/PM everywhere on screen, in reports and for the AI
 * (owner's wish, 2026-11-04). Recorded files and logs keep 24-hour times (data, not for reading).
 */
object TimeFormat {
    /** "9:10 AM" */
    fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))

    /** "Wed 4 Nov 2026 · 9:10 AM" */
    fun day(ms: Long): String = SimpleDateFormat("EEE d MMM yyyy · h:mm a", Locale.US).format(Date(ms))

    /**
     * "Wed 4 Nov, 9:10 AM": dates for the AI. No year (2026-11-05: the small model wrote "5 Nov 202064";
     * fewer digits, less to garble, and the year adds nothing).
     */
    fun forAi(ms: Long): String = SimpleDateFormat("EEE d MMM, h:mm a", Locale.US).format(Date(ms))

    /** "09:36" or "21:16" (from a recording) -> "9:36 AM" / "9:16 PM"; unchanged if it isn't a time. */
    fun from24(hhmm: String): String {
        val m = Regex("^(\\d{1,2}):(\\d{2})").find(hhmm) ?: return hhmm
        val h = m.groupValues[1].toInt()
        val suffix = if (h < 12) "AM" else "PM"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${m.groupValues[2]} $suffix"
    }
}
