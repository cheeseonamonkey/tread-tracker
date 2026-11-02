package dev.treadtracker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import dev.treadtracker.app.MainActivity
import dev.treadtracker.app.R
import dev.treadtracker.app.data.StepDb
import dev.treadtracker.app.service.StepService
import dev.treadtracker.app.util.Dates
import dev.treadtracker.app.util.NumFmt
import dev.treadtracker.app.util.Prefs
import java.util.concurrent.Executors

/**
 * NEW in this rebuild: a home-screen widget showing today's steps and
 * progress toward the daily goal.
 *
 * Updated from the service's UPDATE_WALK_COUNT broadcast (throttled), by the
 * system's periodic update, and whenever the launcher asks for onUpdate.
 */
class StepsWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            StepService.ACTION_UPDATE_WALK_COUNT,
            StepService.ACTION_DAY_ROLLOVER -> requestUpdate(context)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        requestUpdate(context)
    }

    private fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            val total = StepDb(appContext).dayTotal(Dates.today())
            mainHandler.post { push(appContext, total.steps) }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())

        /** Push a steps value to all instances of the widget. */
        fun push(context: Context, steps: Long) {
            val goal = Prefs.goal(context)
            val views = RemoteViews(context.packageName, R.layout.widget_steps).apply {
                setTextViewText(R.id.widget_steps, NumFmt.group(steps))
                setTextViewText(
                    R.id.widget_caption,
                    context.getString(R.string.widget_of_goal, NumFmt.group(goal.toLong()))
                )
                if (goal > 0) {
                    setProgressBar(
                        R.id.widget_progress, goal,
                        steps.coerceAtMost(goal.toLong()).toInt(), false
                    )
                } else {
                    setProgressBar(R.id.widget_progress, 1, 1, false)
                }
                setOnClickPendingIntent(R.id.widget_root, clickIntent(context))
            }
            AppWidgetManager.getInstance(context).updateAppWidget(
                ComponentName(context, StepsWidget::class.java), views
            )
        }

        private fun clickIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
