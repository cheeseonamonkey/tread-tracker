package dev.treadtracker.app.util

import java.util.Locale

object NumFmt {
    fun group(value: Long): String = String.format(Locale.getDefault(), "%,d", value)

    fun distance(meters: Double, km: Boolean): String =
        if (km) String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
        else String.format(Locale.getDefault(), "%.2f mi", meters / 1609.344)

    fun calories(kcal: Double): String =
        String.format(Locale.getDefault(), "%,d kcal", kotlin.math.round(kcal).toLong())

    fun percent(steps: Long, goal: Int): Int =
        if (goal <= 0) 0 else ((steps * 100.0) / goal).toInt().coerceIn(0, 999)
}
