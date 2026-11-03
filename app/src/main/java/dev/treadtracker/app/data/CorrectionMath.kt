package dev.treadtracker.app.data

/** An edit changes displayed steps, never the hardware sensor's running count. */
internal object CorrectionMath {
    fun deltaForTarget(sensorSteps: Long, requestedTotal: Long): Long =
        requestedTotal - sensorSteps

    fun displayed(sensorSteps: Long, adjustment: Long): Long =
        (sensorSteps + adjustment).coerceAtLeast(0L)
}
