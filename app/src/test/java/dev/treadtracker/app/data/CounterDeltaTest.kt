package dev.treadtracker.app.data

import dev.treadtracker.app.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Test

class CounterDeltaTest {
    private val today = Dates.today()

    @Test fun sameDayDelta() {
        assertEquals(42, CounterDelta.between(500, today + 60_000, 542, today + 120_000))
    }

    @Test fun firstReadingIsBaseline() {
        assertEquals(0, CounterDelta.between(-1, -1, 500, today))
    }

    @Test fun rebootOrSensorResetStartsNewBaseline() {
        assertEquals(0, CounterDelta.between(500, today, 10, today + 600_000))
    }

    @Test fun crossingMidnightDoesNotMisattributeSteps() {
        assertEquals(0, CounterDelta.between(500, Dates.addDays(today,-1), 800, today + 60_000))
    }

    @Test fun validLargeCounterGapIsNotSilentlyClipped() {
        assertEquals(200_000, CounterDelta.between(0, today, 200_000, today + 60_000))
    }
}
