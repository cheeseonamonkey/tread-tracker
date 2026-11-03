package dev.treadtracker.app.sensor

import android.content.Context
import dev.treadtracker.app.data.StepDb
import dev.treadtracker.app.util.Prefs
import dev.treadtracker.app.util.Dates
import dev.treadtracker.app.widget.StepsWidget
import android.os.SystemClock
import java.util.concurrent.Executor

/** Single service-owned hardware/accelerometer sensor registration. */
class StepRecorder(
    private val context: Context,
    private val db: StepDb,
    private val executor: Executor,
    private val onChanged: () -> Unit
) : StepSource.Listener {
    private var source: StepSource? = null
    private var lastStepAt = 0L
    private var lastWidgetUpdate = 0L
    @Volatile private var active = false
    val available: Boolean get() = source != null
    val usesAccelerometer: Boolean get() = source?.isHardware == false

    fun start() {
        if (source != null || !Prefs.isCountingEnabled(context)) return
        val hardware = HardwareStepSource(context) { raw, whenMs ->
            if (!active) return@HardwareStepSource
            executor.execute {
                if (db.recordCounter(raw, whenMs, Prefs.kcalPerStep(context),
                        Prefs.metersPerStep(context)) > 0) changed()
            }
        }
        source = if (hardware.available) hardware else
            AccelerometerStepSource(context, this, Prefs.sensitivity(context))
                .takeIf { it.available }
        active = true
        source?.start()
    }

    fun stop() {
        active = false
        source?.stop()
        source = null
        lastStepAt = 0L
    }

    private fun changed() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWidgetUpdate >= 5_000L) {
            lastWidgetUpdate = now
            StepsWidget.push(context, db.dayTotal(Dates.today()).steps)
        }
        onChanged()
    }

    override fun onSteps(count: Int, eventTimeMillis: Long) {
        if (!active || count <= 0) return
        val activeMs = if (lastStepAt > 0 &&
            eventTimeMillis - lastStepAt in 1..3_000) eventTimeMillis - lastStepAt else 0L
        lastStepAt = eventTimeMillis
        executor.execute {
            db.recordSteps(count, eventTimeMillis, Prefs.kcalPerStep(context),
                Prefs.metersPerStep(context), activeMs)
            changed()
        }
    }
}
