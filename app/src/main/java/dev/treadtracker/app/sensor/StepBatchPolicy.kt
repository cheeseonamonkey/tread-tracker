package dev.treadtracker.app.sensor

import dev.treadtracker.app.util.Dates

/** Bound buffered steps while batching writes; never straddle a local hour. */
internal object StepBatchPolicy {
    const val MAX_STEPS = 16
    const val MAX_DELAY_MS = 15_000L

    fun crossesHour(previousAt: Long, currentAt: Long): Boolean =
        Dates.dayStart(previousAt) != Dates.dayStart(currentAt) ||
            Dates.hourOfDay(previousAt) != Dates.hourOfDay(currentAt)
}
