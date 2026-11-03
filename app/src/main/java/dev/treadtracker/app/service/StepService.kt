package dev.treadtracker.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.treadtracker.app.MainActivity
import dev.treadtracker.app.R
import dev.treadtracker.app.data.StepDb
import dev.treadtracker.app.sensor.StepRecorder
import dev.treadtracker.app.util.Prefs
import java.util.concurrent.Executors

/** Owns the sensor across Activity transitions and keeps the counter registered. */
class StepService : Service() {
    companion object {
        const val ACTION_STEPS_UPDATED = "dev.treadtracker.app.STEPS_UPDATED"
        private const val CHANNEL = "background_tracking_v2"
        private const val NOTIFICATION_ID = 7421
        private const val UPDATE_INTERVAL_MS = 1_500L

        fun start(context: Context) {
            if (!Prefs.isCountingEnabled(context) ||
                (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACTIVITY_RECOGNITION
                ) != PackageManager.PERMISSION_GRANTED)) return
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext, Intent(context, StepService::class.java)
                )
            } catch (e: RuntimeException) {
                // The OS can reject a background start (for example after an app stop).
                Log.w("TreadTracker", "Background tracking start rejected", e)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context, StepService::class.java))
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var db: StepDb
    private lateinit var recorder: StepRecorder
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastBroadcastAt = 0L

    override fun onCreate() {
        super.onCreate()
        if (!Prefs.isCountingEnabled(this)) {
            stopSelf()
            return
        }
        db = StepDb(this)
        if (!startForegroundSafely()) {
            stopSelf()
            return
        }
        recorder = StepRecorder(this, db, executor) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBroadcastAt >= UPDATE_INTERVAL_MS) {
                lastBroadcastAt = now
                sendBroadcast(Intent(ACTION_STEPS_UPDATED).setPackage(packageName))
            }
        }
        recorder.start()
        if (!recorder.available) {
            stopSelf()
            return
        }
        // Hardware step counters remain active during device sleep without a wake lock.
        // Software accelerometer fallback needs the CPU awake to process samples.
        if (recorder.usesAccelerometer) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:accelerometer").also { it.acquire() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (Prefs.isCountingEnabled(this) && ::recorder.isInitialized) START_STICKY
        else START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::recorder.isInitialized) recorder.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        // Queue the close behind pending recordings; never close the DB while
        // asynchronous sensor events are still being committed.
        if (::db.isInitialized) executor.execute { db.close() }
        executor.shutdown()
        super.onDestroy()
    }

    private fun startForegroundSafely(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_tracking),
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.channel_tracking_details)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
        val contentIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_background))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else startForeground(NOTIFICATION_ID, notification)
            true
        } catch (e: RuntimeException) {
            Log.w("TreadTracker", "Foreground registration denied", e)
            false
        }
    }
}
