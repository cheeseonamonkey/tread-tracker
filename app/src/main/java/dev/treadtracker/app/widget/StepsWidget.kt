package dev.treadtracker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import dev.treadtracker.app.MainActivity
import dev.treadtracker.app.R
import dev.treadtracker.app.data.StepDb
import dev.treadtracker.app.util.Dates
import dev.treadtracker.app.util.NumFmt
import dev.treadtracker.app.util.Prefs
import java.util.Locale
import java.util.concurrent.Executors

/** Independently resizable, notification-free home-screen step widget. */
class StepsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, options: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, id, options)
        update(context, intArrayOf(id))
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()

        private fun update(context: Context, ids: IntArray) {
            val app = context.applicationContext
            executor.execute {
                val steps = StepDb(app).use { it.dayTotal(Dates.today()).steps }
                val manager = AppWidgetManager.getInstance(app)
                val goal = Prefs.goal(app)
                ids.forEach { render(app, manager, it, steps, goal) }
            }
        }

        /** Refresh all instances after goal changes, without launching a service. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            update(app, manager.getAppWidgetIds(ComponentName(app, StepsWidget::class.java)))
        }

        /** Called when steps change while the app is visible. */
        fun push(context: Context, steps: Long) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, StepsWidget::class.java))
            val goal = Prefs.goal(app)
            ids.forEach { render(app, manager, it, steps, goal) }
        }

        private fun render(
            context: Context, manager: AppWidgetManager, id: Int, steps: Long, goal: Int
        ) {
            val options = manager.getAppWidgetOptions(id)
            // Android 12+: the launcher can provide distinct portrait/landscape
            // sizes. Give RemoteViews a layout for each actual supported size.
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                val sizes = options.getParcelableArrayList<SizeF>(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES
                )
                if (!sizes.isNullOrEmpty()) {
                    manager.updateAppWidget(id, RemoteViews(sizes.associateWith {
                        createViews(context, it.width, it.height, steps, goal)
                    }))
                    return
                }
            }
            // Older / nonconforming launchers: use the available minimum bounds.
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150).toFloat()
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100).toFloat()
            manager.updateAppWidget(id, createViews(context, width, height, steps, goal))
        }

        private fun createViews(
            context: Context, width: Float, height: Float, steps: Long, goal: Int
        ): RemoteViews {
            val kind = WidgetSizing.layout(width, height)
            val layout = when (kind) {
                WidgetSizing.Layout.TINY -> R.layout.widget_tiny
                WidgetSizing.Layout.COMPACT -> R.layout.widget_compact
                WidgetSizing.Layout.REGULAR -> R.layout.widget_steps
            }
            val value = if (kind == WidgetSizing.Layout.TINY) compact(steps) else NumFmt.group(steps)
            return RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.widget_steps, value)
                setContentDescription(R.id.widget_steps, context.getString(
                    R.string.widget_accessibility, NumFmt.group(steps)))
                val sp = WidgetSizing.textSize(width, kind, value.length)
                setTextViewTextSize(R.id.widget_steps, TypedValue.COMPLEX_UNIT_SP, sp)
                if (kind != WidgetSizing.Layout.TINY) {
                    setProgressBar(R.id.widget_progress, goal.coerceAtLeast(1),
                        steps.coerceIn(0L, goal.toLong().coerceAtLeast(1L)).toInt(), false)
                }
                if (kind == WidgetSizing.Layout.REGULAR) {
                    setTextViewText(R.id.widget_caption, context.getString(
                        R.string.widget_of_goal, NumFmt.group(goal.toLong())))
                }
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
            }
        }

        private fun compact(steps: Long): String = when {
            steps >= 1_000_000 -> String.format(Locale.US, "%.1fm", steps / 1_000_000.0)
            steps >= 10_000 -> String.format(Locale.US, "%.1fk", steps / 1_000.0)
            else -> NumFmt.group(steps)
        }
    }
}
