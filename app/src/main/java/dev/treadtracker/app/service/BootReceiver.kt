package dev.treadtracker.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resume background tracking after reboot or a package update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) StepService.start(context)
    }
}
