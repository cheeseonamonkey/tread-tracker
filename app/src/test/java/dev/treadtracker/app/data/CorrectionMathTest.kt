package dev.treadtracker.app.data

import org.junit.Assert.*
import org.junit.Test

class CorrectionMathTest {
    @Test fun newStepsContinueAfterManualEdit() {
        val adjustment = CorrectionMath.deltaForTarget(1000, 1500)
        assertEquals(1500L, CorrectionMath.displayed(1000, adjustment))
        assertEquals(1600L, CorrectionMath.displayed(1100, adjustment))
    }

    @Test fun negativeCorrectionDoesNotReplaceSensorCounter() {
        val adjustment = CorrectionMath.deltaForTarget(1200, 900)
        assertEquals(-300L, adjustment)
        assertEquals(900L, CorrectionMath.displayed(1200, adjustment))
        assertEquals(1400L, CorrectionMath.displayed(1700, adjustment))
    }

    @Test fun reimportSameTargetHasNoAdditionalEffect() {
        val first = CorrectionMath.deltaForTarget(400, 1500)
        val second = CorrectionMath.deltaForTarget(400, 1500)
        assertEquals(first, second)
        assertEquals(1500L, CorrectionMath.displayed(400, second))
    }
}
