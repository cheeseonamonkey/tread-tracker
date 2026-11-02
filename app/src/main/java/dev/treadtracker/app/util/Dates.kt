package dev.treadtracker.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Date helpers. `dayStart()` mirrors the original app's `ge6.r()`:
 * truncates a timestamp to local midnight, which is the "date" value stored
 * in the DAY_WALK_COUNT table.
 */
object Dates {

    const val FIRST_DAY_SUNDAY = 1
    const val FIRST_DAY_MONDAY = 2
    const val FIRST_DAY_SATURDAY = 3

    fun dayStart(timeMillis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = timeMillis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun today(): Long = dayStart(System.currentTimeMillis())

    fun hourOfDay(timeMillis: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = timeMillis
        return c.get(Calendar.HOUR_OF_DAY)
    }

    fun addDays(date: Long, days: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = date
        c.add(Calendar.DATE, days)
        return dayStart(c.timeInMillis)
    }

    /** Maps Calendar.getFirstDayOfWeek() to the stored preference value. */
    fun firstDayOfWeekValue(calendarFirstDay: Int): Int = when (calendarFirstDay) {
        Calendar.MONDAY -> FIRST_DAY_MONDAY
        Calendar.SATURDAY -> FIRST_DAY_SATURDAY
        else -> FIRST_DAY_SUNDAY
    }

    private fun targetDow(firstDayPref: Int): Int = when (firstDayPref) {
        FIRST_DAY_MONDAY -> Calendar.MONDAY
        FIRST_DAY_SATURDAY -> Calendar.SATURDAY
        else -> Calendar.SUNDAY
    }

    /** Start (midnight) of the week containing [date]. */
    fun weekStart(date: Long, firstDayPref: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = date
        val target = targetDow(firstDayPref)
        var guard = 0
        while (c.get(Calendar.DAY_OF_WEEK) != target && guard++ < 8) {
            c.add(Calendar.DATE, -1)
        }
        return dayStart(c.timeInMillis)
    }

    /** Last day (midnight) of the week starting at [start]. */
    fun weekEnd(start: Long): Long = addDays(start, 6)

    fun monthStart(date: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = date
        c.set(Calendar.DAY_OF_MONTH, 1)
        return dayStart(c.timeInMillis)
    }

    fun monthEnd(date: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = date
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, 1)
        c.add(Calendar.DATE, -1)
        return dayStart(c.timeInMillis)
    }

    fun daysInMonth(date: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = date
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    fun formatDay(date: Long): String =
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(date))

    fun formatDayShort(date: Long): String =
        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(date))

    fun formatWeek(start: Long): String {
        val end = weekEnd(start)
        val startYear = yearOf(start)
        val endYear = yearOf(end)
        return if (startYear != endYear) {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(start)) + " – " +
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(end))
        } else {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(start)) + " – " +
                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(end))
        }
    }

    fun formatMonth(date: Long): String {
        val fmt = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
        return fmt.format(Date(date))
    }

    fun formatWeekdayLetter(date: Long): String =
        SimpleDateFormat("EEEEE", Locale.getDefault()).format(Date(date))

    private fun yearOf(date: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = date
        return c.get(Calendar.YEAR)
    }

    fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
