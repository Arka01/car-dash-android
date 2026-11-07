package io.github.arka01.cardash.logic

/**
 * A drive's values column by column, as the drive report needs them: read times (epoch ms, 0 if a
 * recorded time couldn't be read) and one column per value in [NAMES] (NaN = no value in that row).
 * From a recording (DriveReport.read) or from the drive going on now ([DriveBuffer]).
 */
class DriveData(val times: LongArray, private val columns: Map<String, DoubleArray>) {
    val size: Int get() = times.size

    fun col(name: String): DoubleArray = columns[name] ?: DoubleArray(size) { Double.NaN }

    companion object {
        /** The recording's columns the report uses (their CSV names). */
        val NAMES = listOf(
            "Speed", "RPM", "Coolant", "Oil", "Intake air", "Catalyst", "Long fuel trim", "Fuel rate",
            "Battery", Values.PORT, "Torque", "Power",
        )
    }
}

/**
 * The report's columns of the drive going on now, kept in memory row by row (LIVE, 2026-11-07), so
 * the drive report can be calculated at any moment, the same way as after the drive: "THIS DRIVE SO
 * FAR" for Talk. About 100 bytes a row: a 3-hour drive at 10 rows a second is ~11 MB.
 */
class DriveBuffer {
    private var n = 0
    private var times = LongArray(START)
    private val columns = DriveData.NAMES.associateWith { DoubleArray(START) }.toMutableMap()

    val rows: Int get() = n

    /** One row: its time and each column's value by name (null = none). */
    @Synchronized
    fun add(timeMs: Long, value: (String) -> Double?) {
        if (n == times.size) grow()
        times[n] = timeMs
        for ((name, column) in columns) column[n] = value(name) ?: Double.NaN
        n++
    }

    /** A copy of everything so far, for one calculation. */
    @Synchronized
    fun snapshot(): DriveData = DriveData(times.copyOf(n), columns.mapValues { it.value.copyOf(n) })

    private fun grow() {
        val size = times.size * 2
        times = times.copyOf(size)
        for (name in columns.keys.toList()) columns[name] = columns.getValue(name).copyOf(size)
    }

    private companion object {
        const val START = 4096
    }
}
