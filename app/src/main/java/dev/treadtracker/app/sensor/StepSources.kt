package dev.treadtracker.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/** Abstraction over the two step data sources. */
interface StepSource {
    interface Listener {
        /** Called on a background/sensor thread with a batch of detected steps. */
        fun onSteps(count: Int, eventTimeMillis: Long)
    }

    val isHardware: Boolean
    val available: Boolean
    fun start(): Boolean
    fun stop()
}

/**
 * Hardware step counter (TYPE_STEP_COUNTER). Preferred source: accurate and
 * battery-friendly, keeps counting with the screen off and needs no wake lock.
 * This mirrors the original app's "hardware_saving" path (class lt3).
 */
class HardwareStepSource(
    context: Context,
    private val onReading: (Long, Long) -> Unit
) : StepSource, SensorEventListener {
    override val isHardware = true
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    override val available get() = sensor != null

    override fun start(): Boolean = sensor?.let {
        // Deliver counter changes promptly: prefer persistence over FIFO latency.
        // Step detection still runs in hardware without a wake lock.
        manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0)
    } ?: false

    fun flush(): Boolean = manager.flush(this)

    override fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            onReading(event.values[0].toLong(), sensorEventWallTime(event))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Hardware step detector, preferred over CPU-intensive accelerometer processing. */
class HardwareStepDetectorSource(
    context: Context,
    private val listener: StepSource.Listener
) : StepSource, SensorEventListener {
    override val isHardware = true
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    override val available get() = sensor != null

    override fun start(): Boolean = sensor?.let {
        manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0)
    } ?: false

    fun flush(): Boolean = manager.flush(this)

    override fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR && event.values[0] > 0f) {
            listener.onSteps(1, sensorEventWallTime(event))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** A batched sensor event still belongs to the time it happened, not read time. */
private fun sensorEventWallTime(event: SensorEvent): Long {
    val ageMs = ((SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L)
        .coerceAtLeast(0L)
    return System.currentTimeMillis() - ageMs
}

/**
 * Clean-room Kotlin reimplementation of the accelerometer step detector that
 * used to live in the original app's native libPedometer.so.
 *
 * Classic adaptive-threshold peak detection:
 *  - estimate gravity with an exponential low-pass,
 *  - work on the magnitude of the gravity-free signal,
 *  - track a running mean and mean-absolute-deviation,
 *  - a step is a peak that rises above mean + k * deviation and returns
 *    below the mean, at least MIN_STEP_INTERVAL_MS after the previous one.
 *
 * The user sensitivity setting (10..100) scales k.
 */
class AccelerometerStepSource(
    context: Context,
    private val listener: StepSource.Listener,
    private val sensitivity: Int
) : StepSource, SensorEventListener {

    companion object {
        private const val SAMPLING_PERIOD_US = 50_000 // 20 Hz, as the original
        private const val MIN_STEP_INTERVAL_MS = 250L
        private const val WARMUP_SAMPLES = 20
        private const val ALPHA = 0.10f
    }

    override val isHardware = false

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gravity = FloatArray(3)
    private var mean = 0.0
    private var dev = 0.0
    private var samples = 0
    private var aboveThreshold = false
    private var lastStepAt = 0L

    /** sensitivity 10..100 -> k 1.6..0.5 (higher sensitivity, lower threshold). */
    private val k = 1.6 - (sensitivity.coerceIn(10, 100) / 100.0) * 1.1

    override val available: Boolean get() = sensor != null

    override fun start(): Boolean {
        val s = sensor ?: return false
        reset()
        // Software fallback needs continuous samples; avoid FIFO latency/loss.
        return sensorManager.registerListener(this, s, SAMPLING_PERIOD_US, 0)
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
        reset()
    }

    private fun reset() {
        gravity.fill(0f)
        mean = 0.0
        dev = 0.0
        samples = 0
        aboveThreshold = false
        lastStepAt = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Exponential gravity estimate.
        gravity[0] = ALPHA * x + (1 - ALPHA) * gravity[0]
        gravity[1] = ALPHA * y + (1 - ALPHA) * gravity[1]
        gravity[2] = ALPHA * z + (1 - ALPHA) * gravity[2]

        val dx = (x - gravity[0]).toDouble()
        val dy = (y - gravity[1]).toDouble()
        val dz = (z - gravity[2]).toDouble()
        val magnitude = sqrt(dx * dx + dy * dy + dz * dz)

        if (samples < WARMUP_SAMPLES) {
            samples++
            mean = if (samples == 1) magnitude else mean * 0.9 + magnitude * 0.1
            return
        }

        // Running statistics.
        mean = mean * (1 - ALPHA) + magnitude * ALPHA
        dev = dev * (1 - ALPHA) + abs(magnitude - mean) * ALPHA

        val threshold = mean + k * dev + 0.15

        if (!aboveThreshold) {
            if (magnitude > threshold) aboveThreshold = true
        } else if (magnitude < mean) {
            // Completed a peak: that is one step.
            aboveThreshold = false
            val eventAt = event.timestamp / 1_000_000L
            if (eventAt - lastStepAt >= MIN_STEP_INTERVAL_MS) {
                lastStepAt = eventAt
                listener.onSteps(1, sensorEventWallTime(event))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
