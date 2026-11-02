package dev.treadtracker.app.util

import android.content.Context
import android.preference.PreferenceManager

/**
 * Settings access. Key names match the original app so an existing install
 * keeps its preferences.
 *
 * Original encoding quirks kept for compatibility:
 *  - "is_kg"     "0" = kilograms, "1" = pounds
 *  - "is_meter"  "0" = centimeters, "1" = inches
 *  - "distance_unit" "1" = km, "2" = miles
 *  - "first_day_week" 1 = Sunday, 2 = Monday, 3 = Saturday
 */
object Prefs {

    private fun sp(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun weightKg(context: Context): Double {
        val p = sp(context)
        val value = p.getString("weight", "70")?.toDoubleOrNull() ?: 70.0
        val isKg = p.getString("is_kg", "0") == "0"
        return if (isKg) value else value * 0.45359237
    }

    fun weightDisplay(context: Context): Double =
        sp(context).getString("weight", "70")?.toDoubleOrNull() ?: 70.0

    fun usesKg(context: Context): Boolean = sp(context).getString("is_kg", "0") == "0"

    fun setWeight(context: Context, value: Double, kg: Boolean) {
        sp(context).edit()
            .putString("weight", value.toInt().toString())
            .putString("is_kg", if (kg) "0" else "1")
            .apply()
    }

    fun stepLengthCm(context: Context): Double {
        val p = sp(context)
        val value = p.getString("step_length", "70")?.toDoubleOrNull() ?: 70.0
        val isCm = p.getString("is_meter", "0") == "0"
        return if (isCm) value else value * 2.54
    }

    fun usesCm(context: Context): Boolean = sp(context).getString("is_meter", "0") == "0"

    fun stepLengthDisplay(context: Context): Double {
        val p = sp(context)
        val value = p.getString("step_length", "70")?.toDoubleOrNull() ?: 70.0
        return if (usesCm(context)) value else value
    }

    fun setStepLength(context: Context, value: Double, cm: Boolean) {
        sp(context).edit()
            .putString("step_length", value.toInt().toString())
            .putString("is_meter", if (cm) "0" else "1")
            .apply()
    }

    fun usesKilometers(context: Context): Boolean =
        sp(context).getString("distance_unit", "1") != "2"

    fun setUsesKilometers(context: Context, km: Boolean) {
        sp(context).edit().putString("distance_unit", if (km) "1" else "2").apply()
    }

    fun firstDayOfWeek(context: Context): Int =
        sp(context).getString("first_day_week", "1")?.toIntOrNull() ?: Dates.FIRST_DAY_SUNDAY

    fun setFirstDayOfWeek(context: Context, value: Int) {
        sp(context).edit().putString("first_day_week", value.toString()).apply()
    }

    /** Accelerometer detector sensitivity, 10..100 (higher = more sensitive). */
    fun sensitivity(context: Context): Int =
        sp(context).getString("sensitivity", "50")?.toIntOrNull()?.coerceIn(10, 100) ?: 50

    fun setSensitivity(context: Context, value: Int) {
        sp(context).edit().putString("sensitivity", value.toString()).apply()
    }

    fun goal(context: Context): Int =
        sp(context).getInt("goal_steps", 10000).coerceAtLeast(0)

    fun setGoal(context: Context, value: Int) {
        sp(context).edit().putInt("goal_steps", value.coerceAtLeast(0)).apply()
    }

    fun isCountingEnabled(context: Context): Boolean =
        sp(context).getBoolean("service_enabled", true)

    fun setCountingEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean("service_enabled", enabled).apply()
    }

    fun isBatteryPromptShown(context: Context): Boolean =
        sp(context).getBoolean("battery_prompt_shown", false)

    fun setBatteryPromptShown(context: Context) {
        sp(context).edit().putBoolean("battery_prompt_shown", true).apply()
    }

    /** Approximate kcal per step for the current weight. */
    fun kcalPerStep(context: Context): Double = weightKg(context) * 0.0005

    /** Step length in meters. */
    fun metersPerStep(context: Context): Double = stepLengthCm(context) / 100.0
}
