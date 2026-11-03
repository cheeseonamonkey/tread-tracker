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
    private val widgetIntervalMs = 60_000L
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
        // Counter (cumulative, low power) -> detector (low power) ->
        // accelerometer (software fallback, requires a wake lock).
        val detector = HardwareStepDetectorSource(context, this)
        val accelerometer = AccelerometerStepSource(context, this, Prefs.sensitivity(context))
        active = true
        source = listOf(hardware, detector, accelerometer).firstOrNull {
            it.available && it.start()
        }
        if (source == null) active = false
    }

    /** Ask the hardware FIFO to deliver recent steps when the app opens. */
    fun flush() {
        when (val current = source) {
            is HardwareStepSource -> current.flush()
            is HardwareStepDetectorSource -> current.flush()
        }
    }

    fun stop() {
        active = false
        source?.stop()
        source = null
        lastStepAt = 0L
    }

    private fun changed() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWidgetUpdate >= widgetIntervalMs) {
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
        // Commit every detected step without retaining an uncommitted buffer.
        // More DB transactions than batching, but fewer steps lost on process death.
        executor.execute {
            db.recordSteps(count, eventTimeMillis, Prefs.kcalPerStep(context),
                Prefs.metersPerStep(context), activeMs)
            changed()
        }
    }
}
