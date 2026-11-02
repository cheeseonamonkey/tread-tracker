package dev.treadtracker.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.treadtracker.app.service.StepService

/**
 * Application entry point. Deliberately tiny: it only registers the service
 * notification channel. No ad SDK initialization, no Firebase, no telemetry -
 * the original PedometerApplication did all of that.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                StepService.CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
