package dev.treadtracker.app.ui

import android.app.Activity
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.treadtracker.app.R
import dev.treadtracker.app.data.HistoryCsv
import dev.treadtracker.app.data.StepDb
import dev.treadtracker.app.widget.StepsWidget
import java.util.concurrent.Executor

/** Edit a displayed day's total without mutating sensor history or checkpoints. */
object HistoryEditor {
    fun show(activity: Activity, day: Long, executor: Executor, onSaved: () -> Unit) {
        executor.execute {
            try {
                val (total, sensor) = StepDb(activity).use {
                    it.dayTotal(day).steps to it.sensorDayTotal(day)
                }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val dp = activity.resources.displayMetrics.density
                    val column = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((24 * dp).toInt(), (8 * dp).toInt(),
                            (24 * dp).toInt(), (4 * dp).toInt())
                    }
                    column.addView(TextView(activity).apply {
                        text = activity.getString(R.string.sensor_steps_caption, sensor)
                        textSize = 13f
                    })
                    val field = EditText(activity).apply {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setSingleLine(true)
                        setText(total.toString())
                        setSelectAllOnFocus(true)
                        hint = activity.getString(R.string.step_total_hint)
                    }
                    column.addView(field, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val dialog = AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.edit_steps_for, HistoryCsv.dateLabel(day)))
                        .setView(column)
                        .setMessage(R.string.manual_steps_explanation)
                        .setPositiveButton(R.string.save, null)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.reset_adjustment, null)
                        .create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val count = field.text.toString().toLongOrNull()
                            if (count == null || count !in 0..HistoryCsv.MAX_STEPS) {
                                field.error = activity.getString(R.string.invalid_steps)
                                return@setOnClickListener
                            }
                            change(activity, executor, onSaved) { setDailyTotal(day, count) }
                            dialog.dismiss()
                        }
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            change(activity, executor, onSaved) { clearDailyCorrection(day) }
                            dialog.dismiss()
                        }
                    }
                    dialog.show()
                }
            } catch (e: Exception) {
                activity.runOnUiThread { report(activity, e) }
            }
        }
    }

    private fun change(activity: Activity, executor: Executor,
        onSaved: () -> Unit, operation: StepDb.() -> Unit) {
        executor.execute {
            try {
                StepDb(activity).use { it.operation() }
                StepsWidget.refresh(activity)
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onSaved()
                        Toast.makeText(activity, R.string.history_saved, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread { report(activity, e) }
            }
        }
    }

    private fun report(activity: Activity, error: Exception) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            Toast.makeText(activity, error.message ?: activity.getString(R.string.history_error),
                Toast.LENGTH_LONG).show()
        }
    }
}
