package dev.treadtracker.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.treadtracker.app.util.Prefs

/**
 * Restarts the step service after boot or app update, unless the user turned
 * counting off. (The original app used two receivers, RestartServiceReceiver
 * and BootReceiver, for the same job.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Prefs.isCountingEnabled(context)) {
                    StepService.start(context)
                }
            }
        }
    }
}
