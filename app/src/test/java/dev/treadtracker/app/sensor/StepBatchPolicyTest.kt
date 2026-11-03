package dev.treadtracker.app.sensor

import dev.treadtracker.app.util.Dates
import org.junit.Assert.*
import org.junit.Test

class StepBatchPolicyTest {
    private val day = Dates.today()

    @Test fun groupsStepsWithinTheSameHour() {
        assertFalse(StepBatchPolicy.crossesHour(day + 60_000, day + 20 * 60_000))
    }

    @Test fun flushesAtTheHourBoundary() {
        assertTrue(StepBatchPolicy.crossesHour(day + 59 * 60_000, day + 61 * 60_000))
    }

    @Test fun flushesBeforeMidnight() {
        assertTrue(StepBatchPolicy.crossesHour(Dates.addDays(day, -1) + 23 * 3_600_000,
            day + 30_000))
    }

    @Test fun boundedBuffering() {
        assertTrue(StepBatchPolicy.MAX_STEPS in 1..30)
        assertTrue(StepBatchPolicy.MAX_DELAY_MS in 1..30_000)
    }
}
