package io.github.arka01.cardash.logic

/** How fast slow values (temperatures) are changing, from the readings of the last minute. */
class Trends(labels: List<String>) {
    private val history = labels.associateWith { ArrayDeque<Pair<Double, Double>>() }

    fun update(v: Values, now: Double) {
        for ((label, h) in history) {
            val value = v[label] ?: continue
            h.addLast(now to value)
            while (now - h.first().first > WINDOW) h.removeFirst()
        }
    }

    /** Change per minute for each label, or null without enough history yet. */
    fun perMinute(): Map<String, Double?> = history.mapValues { (_, h) ->
        if (h.size < 2 || h.last().first - h.first().first < MIN_SPAN) {
            null
        } else {
            val (t0, v0) = h.first()
            val (t1, v1) = h.last()
            (v1 - v0) / (t1 - t0) * 60
        }
    }

    private companion object {
        const val WINDOW = 60.0     // seconds of history to compare against
        const val MIN_SPAN = 10.0   // seconds of history needed before showing a trend
    }
}
