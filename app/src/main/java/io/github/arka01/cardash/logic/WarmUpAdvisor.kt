package io.github.arka01.cardash.logic

/**
 * Warm-up advisor (the Python dashboard's warmup_advisor.py): after a cold start, tells the
 * driver when to move off and when it's safe to push. Stages only move forward; restarting the
 * engine starts over:
 *
 *   COLD START     wait for the fast idle to settle: idle RPM steady and near the warm idle, or 60 s at most
 *   READY TO MOVE  drive gently, below 2,500 rpm
 *   WARMING        coolant at 70 °C: moderate driving, below 3,500 rpm
 *   READY TO PUSH  coolant >= 80 °C and oil >= 85 °C, held for a minute: full power OK
 *
 * A start with the engine already warm (coolant >= 70 °C) skips straight to the matching stage.
 * Each stage has an RPM limit (a "variable redline") and a sentence to say. Thresholds are partly
 * measured (Tiago warm idle ~860 rpm, 2026-10-13) and partly general guidance for petrol engines.
 */
class WarmUpAdvisor {
    private class Stage(val name: String, val level: Level, val detail: String, val rpmLimit: Int?, val say: String)

    private var start: Double? = null      // time the engine started (this run)
    private var cold: Boolean? = null      // was it a cold start?
    private var stage: Stage? = null
    private val idle = ArrayDeque<Pair<Double, Double>>()   // (time, rpm) while idling, for the settle check
    private var pushSince: Double? = null
    private var pending: String? = null    // sentence to announce
    private var running = false

    fun update(v: Values, now: Double) {
        val rpm = v["RPM"] ?: return
        val speed = v["Speed"]
        val coolant = v["Coolant"]
        val oil = v["Oil"]
        val isRunning = rpm >= RUNNING_RPM
        if (!isRunning) {
            running = false
            start = null
            stage = null
            return
        }
        if (!running) {   // just started (or the dashboard started with the engine running)
            start = now - (v["Engine run time"] ?: 0.0)
            cold = null
            stage = null
            idle.clear()
            pushSince = null
        }
        running = true
        if (coolant == null) return   // need the temperature to judge
        if (cold == null) cold = coolant < COLD_BELOW

        if (speed != null && speed < 1) idle.addLast(now to rpm)
        while (idle.isNotEmpty() && now - idle.first().first > SETTLE_WINDOW) idle.removeFirst()

        advance(target(now, coolant, oil))
    }

    /** The stage for the dashboard, or null while the engine is off. */
    fun report(): EngineState? = stage?.let { EngineState(it.name, it.level, it.detail, it.rpmLimit) }

    /** The sentence to say for a stage change (once), or null. */
    fun announcement(): String? = pending.also { pending = null }

    // ------------------------------------------------------------ stages

    /** The stage the readings justify right now. */
    private fun target(now: Double, coolant: Double, oil: Double?): Stage {
        val warmEnough = coolant >= PUSH_COOLANT && (oil == null || oil >= PUSH_OIL)
        pushSince = if (warmEnough) pushSince ?: now else null
        if (cold != true) {   // warm start: no waiting, no hold
            if (warmEnough) return PUSH
            return if (coolant >= WARMING_COOLANT) WARMING else MOVE
        }
        if ((stage == null || stage === COLD) && !idleSettled(now)) return COLD
        val since = pushSince
        if (since != null && now - since >= PUSH_HOLD) return PUSH
        return if (coolant >= WARMING_COOLANT) WARMING else MOVE
    }

    private fun idleSettled(now: Double): Boolean {
        val elapsed = now - (start ?: now)
        if (elapsed >= MAX_WAIT) return true
        if (elapsed < MIN_WAIT || idle.isEmpty() || now - idle.first().first < SETTLE_WINDOW - 1) return false
        val rpms = idle.map { it.second }
        return rpms.max() <= WARM_IDLE_RPM + SETTLED_MARGIN && rpms.max() - rpms.min() <= SETTLED_SPREAD
    }

    /** Moves to the target stage, never backwards; a cold start announces every stage. */
    private fun advance(target: Stage) {
        val current = stage
        if (current != null && ORDER.indexOf(target) <= ORDER.indexOf(current)) return
        val first = current == null
        stage = target
        if (cold == true || !first) pending = target.say   // a warm start says nothing
    }

    private companion object {
        const val RUNNING_RPM = 400.0
        const val COLD_BELOW = 70.0       // °C coolant at start: below this it's a cold start
        const val WARM_IDLE_RPM = 860.0   // normal warm idle (measured on the Tiago)
        const val SETTLED_MARGIN = 140.0  // idle within this of the warm idle = fast idle is over
        const val SETTLED_SPREAD = 100.0  // ...and steady: max - min over the window
        const val SETTLE_WINDOW = 5.0     // seconds of idle readings looked at
        const val MIN_WAIT = 20.0         // seconds after starting
        const val MAX_WAIT = 60.0
        const val WARMING_COOLANT = 70.0
        const val PUSH_COOLANT = 80.0
        const val PUSH_OIL = 85.0
        const val PUSH_HOLD = 60.0        // seconds they must hold

        val COLD = Stage("COLD START", Level.WARN, "wait: idle settling", 2000, "Cold start. Wait for the idle to settle.")
        val MOVE = Stage("READY TO MOVE", Level.OK, "gently, < 2,500 rpm", 2500, "Ready to move. Drive gently.")
        val WARMING = Stage("WARMING", Level.OK, "moderate, < 3,500 rpm", 3500, "Engine warming up. Moderate driving.")
        val PUSH = Stage("READY TO PUSH", Level.OK, "full power OK", null, "Engine warm. Full power OK.")
        val ORDER = listOf(COLD, MOVE, WARMING, PUSH)
    }
}
