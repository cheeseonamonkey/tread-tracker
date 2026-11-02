package dev.treadtracker.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.treadtracker.app.MainActivity
import dev.treadtracker.app.R
import dev.treadtracker.app.data.StepDb
import dev.treadtracker.app.sensor.AccelerometerStepSource
import dev.treadtracker.app.sensor.HardwareStepSource
import dev.treadtracker.app.sensor.StepSource
import dev.treadtracker.app.util.Dates
import dev.treadtracker.app.util.NumFmt
import dev.treadtracker.app.util.Prefs
import dev.treadtracker.app.widget.StepsWidget
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground step-counting service.
 *
 * Mirrors the original PedometerService/mg4 pair: steps are accumulated in
 * memory per (date, hour), flushed to SQLite at least every FLUSH_STEP_THRESHOLD
 * steps or FLUSH_INTERVAL_MS (whichever comes first), and an UPDATE_WALK_COUNT
 * broadcast with the day total is sent on every batch. Day/hour rollovers are
 * detected on each batch and on TIME_SET / TIMEZONE_CHANGED.
 */
class StepService : Service(), StepSource.Listener {

    companion object {
        const val CHANNEL_ID = "step_service"
        const val NOTIFICATION_ID = 1564

        const val ACTION_UPDATE_WALK_COUNT =
            "dev.treadtracker.app.UPDATE_WALK_COUNT"
        const val ACTION_DAY_ROLLOVER =
            "dev.treadtracker.app.UPDATE_SCREEN_HOUR_DATE"

        const val EXTRA_WALK_COUNT = "WALK_COUNT"
        const val EXTRA_DURATION = "DURATION"
        const val EXTRA_DATE = "WALK_UPDATE_DATE"
        const val EXTRA_HOUR = "WALK_UPDATE_HOUR"
        const val EXTRA_DISTANCE = "WALK_DISTANCE"
        const val EXTRA_CALORIES = "WALK_CALORIES"

        private const val FLUSH_STEP_THRESHOLD = 20L
        private const val FLUSH_INTERVAL_MS = 15_000L
        private const val WIDGET_MIN_INTERVAL_MS = 10_000L
        private const val WALK_TIME_MAX_GAP_MS = 3_000L

        fun start(context: Context) {
            // On API 29+ the health foreground service type requires the
            // ACTIVITY_RECOGNITION runtime permission to be granted first;
            // starting without it would crash on Android 14+.
            if (Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACTIVITY_RECOGNITION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ContextCompat.startForegroundService(
                context, Intent(context, StepService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepService::class.java))
        }
    }

    private lateinit var db: StepDb
    private var source: StepSource? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dbExecutor = Executors.newSingleThreadExecutor()

    private val stateLock = Any()

    // In-memory state for the current day (absolute values per hour).
    private var currentDate = -1L
    private var currentHour = -1
    private val hourRows = HashMap<Int, StepDb.HourRow>()
    private var todaySteps = 0L
    private var todayCal = 0.0
    private var todayDistance = 0.0
    private var todayWalkTime = 0L
    private var lastStepAt = 0L
    private var stepsSinceFlush = 0L
    private var lastNotifiedSteps = -1L
    private var lastWidgetUpdate = 0L

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> onClockChanged()
            }
        }
    }

    private val flushLoop = object : Runnable {
        override fun run() {
            flush()
            mainHandler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    // ------------------------------------------------------------- lifecycle

    private fun enterForeground() {
        val notification = buildNotification(0)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Rare OEM / revoked-permission edge case: try the plain overload.
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = StepDb(this)
        enterForeground()
        loadToday()
        startSource()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this, tickReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        mainHandler.postDelayed(flushLoop, FLUSH_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        mainHandler.removeCallbacks(flushLoop)
        unregisterReceiver(tickReceiver)
        source?.stop()
        source = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        // Best-effort synchronous final flush.
        try {
            dbExecutor.submit { flush() }.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        dbExecutor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sources

    private fun startSource() {
        val hardware = HardwareStepSource(this, this)
        if (hardware.available) {
            source = hardware
            hardware.start()
        } else {
            val accel = AccelerometerStepSource(this, this, Prefs.sensitivity(this))
            if (accel.available) {
                acquireWakeLock()
                source = accel
                accel.start()
            }
        }
    }

    /**
     * The original app held a partial wake lock whenever it used the
     * accelerometer (software) path; the hardware counter needs none.
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "dev.treadtracker.app:sensor"
        ).also { it.acquire() }
    }

    // ------------------------------------------------------------------ state

    private fun loadToday() {
        val now = System.currentTimeMillis()
        val date = Dates.dayStart(now)
        val hour = Dates.hourOfDay(now)
        synchronized(stateLock) {
            currentDate = date
            currentHour = hour
            hourRows.clear()
            val rows = db.hourRows(date)
            for (h in 0..23) {
                rows[h]?.let { hourRows[h] = it }
            }
            recomputeTotalsLocked()
            stepsSinceFlush = 0
        }
    }

    private fun recomputeTotalsLocked() {
        var steps = 0L
        var cal = 0.0
        var dist = 0.0
        var time = 0L
        for (row in hourRows.values) {
            steps += row.steps
            cal += row.cal
            dist += row.distanceMeters
            time += row.walkTimeMs
        }
        todaySteps = steps
        todayCal = cal
        todayDistance = dist
        todayWalkTime = time
    }

    private fun onClockChanged() {
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            if (Dates.dayStart(now) != currentDate) {
                flushLocked()
                loadToday()
                broadcastDayRollover()
            }
        }
    }

    // ------------------------------------------------------------- step input

    override fun onSteps(count: Int, eventTimeMillis: Long) {
        if (count <= 0) return
        val kcalPerStep = Prefs.kcalPerStep(this)
        val metersPerStep = Prefs.metersPerStep(this)

        var broadcast = false
        var rollover = false
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            val date = Dates.dayStart(now)
            val hour = Dates.hourOfDay(now)

            if (date != currentDate) {
                // Day rollover: persist the old day, start the new one.
                flushLocked()
                loadToday()
                rollover = true
            } else if (hour != currentHour) {
                currentHour = hour
            }

            val row = hourRows.getOrPut(currentHour) { StepDb.HourRow() }
            val dt = if (lastStepAt in 1 until eventTimeMillis &&
                eventTimeMillis - lastStepAt <= WALK_TIME_MAX_GAP_MS
            ) eventTimeMillis - lastStepAt else 0L
            row.addSteps(count, kcalPerStep, metersPerStep, dt)
            lastStepAt = eventTimeMillis

            todaySteps += count
            todayCal += count * kcalPerStep
            todayDistance += count * metersPerStep
            todayWalkTime += dt
            stepsSinceFlush += count

            if (stepsSinceFlush >= FLUSH_STEP_THRESHOLD) {
                flushLocked()
            }
            broadcast = true
        }
        if (rollover) broadcastDayRollover()
        if (broadcast) broadcastUpdate()
    }

    // ------------------------------------------------------------------ flush

    private fun flush() = synchronized(stateLock) { flushLocked() }

    private fun flushLocked() {
        if (currentDate < 0) return
        val date = currentDate
        val snapshot = ArrayList<Map.Entry<Int, StepDb.HourRow>>(hourRows.size)
        for (e in hourRows) snapshot.add(e)
        stepsSinceFlush = 0
        if (snapshot.isEmpty()) return
        dbExecutor.execute {
            for (e in snapshot) {
                db.upsertHour(date, e.key, e.value)
            }
        }
    }

    // -------------------------------------------------------------- broadcast

    private fun broadcastUpdate() {
        val (steps, cal, dist, time, date, hour) = synchronized(stateLock) {
            Sextet(todaySteps, todayCal, todayDistance, todayWalkTime, currentDate, currentHour)
        }

        val intent = Intent(ACTION_UPDATE_WALK_COUNT).apply {
            setPackage(packageName)
            putExtra(EXTRA_WALK_COUNT, steps)
            putExtra(EXTRA_CALORIES, cal)
            putExtra(EXTRA_DISTANCE, dist)
            putExtra(EXTRA_DURATION, time)
            putExtra(EXTRA_DATE, date)
            putExtra(EXTRA_HOUR, hour)
        }
        sendBroadcast(intent)

        if (steps != lastNotifiedSteps) {
            lastNotifiedSteps = steps
            notifyNotification(steps)
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWidgetUpdate >= WIDGET_MIN_INTERVAL_MS) {
            lastWidgetUpdate = now
            StepsWidget.push(this, steps)
        }
    }

    private fun broadcastDayRollover() {
        val intent = Intent(ACTION_DAY_ROLLOVER).apply { setPackage(packageName) }
        sendBroadcast(intent)
        StepsWidget.push(applicationContext, 0)
    }

    // ---------------------------------------------------------- notification

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 4050, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(steps: Long): Notification {
        val text = if (steps > 0) {
            getString(R.string.notification_counting, NumFmt.group(steps))
        } else {
            getString(R.string.notification_counting_zero)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF2FAAFF.toInt())
            .build()
    }

    private fun notifyNotification(steps: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(steps))
    }

    private data class Sextet(
        val steps: Long, val cal: Double, val dist: Double,
        val time: Long, val date: Long, val hour: Int
    )
}
