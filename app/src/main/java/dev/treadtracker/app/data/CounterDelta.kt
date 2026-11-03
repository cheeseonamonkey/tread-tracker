package dev.treadtracker.app.data

import dev.treadtracker.app.util.Dates

internal object CounterDelta {
    fun between(previous: Long, previousAt: Long, raw: Long, now: Long): Int =
        if (previous >= 0 && raw >= previous &&
            Dates.dayStart(previousAt) == Dates.dayStart(now)) {
            (raw - previous).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else 0
}
