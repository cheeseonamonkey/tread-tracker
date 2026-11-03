package dev.treadtracker.app.sensor

import android.content.Context
import dev.treadtracker.app.data.StepDb
import dev.treadtracker.app.util.Prefs
import dev.treadtracker.app.util.Dates
import dev.treadtracker.app.widget.StepsWidget
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingCount = 0
    private var pendingWalkMs = 0L
    private var pendingAt = 0L
    private val flushRunnable = Runnable { flushPending() }
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
        flushPending()
        when (val current = source) {
            is HardwareStepSource -> current.flush()
            is HardwareStepDetectorSource -> current.flush()
        }
    }

    fun stop() {
        active = false
        source?.stop()
        source = null
        flushPending()
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

    private fun flushPending() {
        mainHandler.removeCallbacks(flushRunnable)
        if (pendingCount == 0) return
        val count = pendingCount
        val time = pendingAt
        val activeMs = pendingWalkMs
        pendingCount = 0
        pendingWalkMs = 0
        executor.execute {
            db.recordSteps(count, time, Prefs.kcalPerStep(context),
                Prefs.metersPerStep(context), activeMs)
            changed()
        }
    }

    override fun onSteps(count: Int, eventTimeMillis: Long) {
        if (!active || count <= 0) return
        val activeMs = if (lastStepAt > 0 &&
            eventTimeMillis - lastStepAt in 1..3_000) eventTimeMillis - lastStepAt else 0L
        lastStepAt = eventTimeMillis
        if (pendingCount > 0 && StepBatchPolicy.crossesHour(pendingAt, eventTimeMillis)) {
            flushPending()
        }
        if (pendingCount == 0) {
            pendingAt = eventTimeMillis
            mainHandler.postDelayed(flushRunnable, StepBatchPolicy.MAX_DELAY_MS)
        }
        pendingCount += count
        pendingWalkMs += activeMs
        if (pendingCount >= StepBatchPolicy.MAX_STEPS) flushPending()
    }
}
