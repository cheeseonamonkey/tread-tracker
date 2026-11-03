package dev.treadtracker.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.treadtracker.app.util.Dates
import android.database.sqlite.SQLiteOpenHelper
import dev.treadtracker.app.util.Prefs

/**
 * Step storage. The database name, table name and columns are identical to
 * the original app (`DAY_WALK_COUNT` db, `DAY_WALK_COUNT(date, hour, count,
 * cal, walk_time, distance)` table), so data from an original install is
 * read and continued seamlessly.
 *
 * Rows are keyed by (date=local-midnight-millis, hour=0..23); each row holds
 * absolute totals for that hour.
 */
class StepDb(private val context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "DAY_WALK_COUNT"
        const val DB_VERSION = 2
        const val TABLE = "DAY_WALK_COUNT"
        const val COL_ID = "_id"
        const val COL_DATE = "date"
        const val COL_HOUR = "hour"
        const val COL_COUNT = "count"
        const val COL_CAL = "cal"
        const val COL_WALK_TIME = "walk_time"
        const val COL_DISTANCE = "distance"

        private const val CREATE_SQL =
            "CREATE TABLE $TABLE(" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COL_DATE INTEGER," +
                "$COL_HOUR INTEGER," +
                "$COL_COUNT INTEGER," +
                "$COL_CAL REAL," +
                "$COL_WALK_TIME INTEGER," +
                "$COL_DISTANCE INTEGER)"

        private const val MANUAL_TABLE = "daily_manual_delta"
        private const val MANUAL_SQL =
            "CREATE TABLE IF NOT EXISTS $MANUAL_TABLE(date INTEGER PRIMARY KEY, delta INTEGER NOT NULL)"

        private const val CHECKPOINT_SQL =
            "CREATE TABLE IF NOT EXISTS sensor_checkpoint(" +
                "id INTEGER PRIMARY KEY CHECK(id=1), raw INTEGER NOT NULL, seen_at INTEGER NOT NULL)"

        private const val UNIQUE_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS index_day_walk_date_hour " +
                "ON $TABLE($COL_DATE, $COL_HOUR)"
    }

    /** In-memory row for one (date, hour) bucket. */
    data class HourRow(
        var steps: Long = 0,
        var cal: Double = 0.0,
        var walkTimeMs: Long = 0,
        var distanceMeters: Double = 0.0
    ) {
        fun addSteps(count: Int, kcalPerStep: Double, metersPerStep: Double, dtMs: Long) {
            steps += count
            cal += count * kcalPerStep
            distanceMeters += count * metersPerStep
            walkTimeMs += dtMs
        }
    }

    data class DayTotal(
        val steps: Long = 0,
        val cal: Double = 0.0,
        val walkTimeMs: Long = 0,
        val distanceMeters: Double = 0.0
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_SQL)
        db.execSQL(UNIQUE_INDEX_SQL)
        db.execSQL(CHECKPOINT_SQL)
        db.execSQL(MANUAL_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migration: never discard steps, checkpoints, or corrections.
        if (oldVersion < 2) db.execSQL(MANUAL_SQL)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureUniqueIndex(db)
        db.execSQL(CHECKPOINT_SQL)
        db.execSQL(MANUAL_SQL)
    }

    /**
     * The original table had no unique index on (date, hour); if we inherit
     * such a database, merge duplicates and add the index so upserts work.
     */
    private fun ensureUniqueIndex(db: SQLiteDatabase) {
        try {
            db.execSQL(UNIQUE_INDEX_SQL)
            return
        } catch (e: SQLiteException) {
            // fall through to dedupe
        }
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE $TABLE RENAME TO ${TABLE}_old")
            db.execSQL(CREATE_SQL)
            db.execSQL(
                "INSERT INTO $TABLE($COL_DATE,$COL_HOUR,$COL_COUNT,$COL_CAL,$COL_WALK_TIME,$COL_DISTANCE) " +
                    "SELECT $COL_DATE,$COL_HOUR,SUM($COL_COUNT),SUM($COL_CAL),SUM($COL_WALK_TIME),SUM($COL_DISTANCE) " +
                    "FROM ${TABLE}_old GROUP BY $COL_DATE,$COL_HOUR"
            )
            db.execSQL("DROP TABLE ${TABLE}_old")
            db.execSQL(UNIQUE_INDEX_SQL)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Commit the cumulative sensor checkpoint and steps in one transaction.
     * A fresh baseline, reboot/reset, or a gap across midnight yields no
     * steps: the sensor does not tell us when steps were taken while inactive.
     */
    fun recordCounter(raw: Long, now: Long, kcal: Double, meters: Double): Int {
        if (raw < 0) return 0
        val sql = writableDatabase
        sql.beginTransaction()
        try {
            var previousRaw = -1L
            var previousAt = -1L
            sql.rawQuery("SELECT raw,seen_at FROM sensor_checkpoint WHERE id=1", null).use {
                if (it.moveToFirst()) {
                    previousRaw = it.getLong(0)
                    previousAt = it.getLong(1)
                }
            }
            val delta = CounterDelta.between(previousRaw, previousAt, raw, now)
            sql.execSQL(
                "INSERT OR REPLACE INTO sensor_checkpoint(id,raw,seen_at) VALUES(1,?,?)",
                arrayOf(raw, now)
            )
            if (delta > 0) incrementHour(sql, delta, now, kcal, meters, 0L)
            sql.setTransactionSuccessful()
            return delta
        } finally {
            sql.endTransaction()
        }
    }

    fun recordSteps(steps: Int, now: Long, kcal: Double, meters: Double, activeMs: Long) {
        if (steps <= 0) return
        val sql = writableDatabase
        sql.beginTransaction()
        try {
            incrementHour(sql, steps, now, kcal, meters, activeMs)
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    private fun incrementHour(
        sql: SQLiteDatabase, steps: Int, now: Long,
        kcal: Double, meters: Double, activeMs: Long
    ) {
        val date = Dates.dayStart(now)
        val hour = Dates.hourOfDay(now)
        val row = sql.query(TABLE,
            arrayOf(COL_COUNT, COL_CAL, COL_WALK_TIME, COL_DISTANCE),
            "$COL_DATE=? AND $COL_HOUR=?",
            arrayOf(date.toString(), hour.toString()), null, null, null
        ).use {
            if (it.moveToFirst()) HourRow(it.getLong(0), it.getDouble(1),
                it.getLong(2), it.getDouble(3)) else HourRow()
        }
        row.addSteps(steps, kcal, meters, activeMs)
        upsertHour(date, hour, row)
    }

    /** Insert or update the absolute totals of one (date, hour) row. */
    fun upsertHour(date: Long, hour: Int, row: HourRow) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_COUNT, row.steps)
            put(COL_CAL, row.cal)
            put(COL_WALK_TIME, row.walkTimeMs)
            put(COL_DISTANCE, row.distanceMeters.toLong())
        }
        val updated = db.update(
            TABLE, cv, "$COL_DATE=? AND $COL_HOUR=?",
            arrayOf(date.toString(), hour.toString())
        )
        if (updated == 0) {
            cv.put(COL_DATE, date)
            cv.put(COL_HOUR, hour)
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Sensor-only daily total, excluding user corrections. */
    fun sensorDayTotal(date: Long): Long = rawDayTotal(readableDatabase, date)

    /**
     * Set a *correction* relative to the current sensor count. Future sensor
     * samples still increment the visible count; the sensor checkpoint remains
     * untouched. Runs atomically with sensor transactions on the same DB.
     */
    fun setDailyTotal(date: Long, total: Long) = setDailyTotals(mapOf(date to total))

    fun setDailyTotals(totals: Map<Long, Long>) {
        require(totals.values.all { it in 0..HistoryCsv.MAX_STEPS })
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((date, target) in totals) {
                require(date == Dates.dayStart(date))
                val delta = CorrectionMath.deltaForTarget(rawDayTotal(db, date), target)
                if (delta == 0L) db.delete(MANUAL_TABLE, "date=?", arrayOf(date.toString()))
                else db.execSQL("INSERT OR REPLACE INTO $MANUAL_TABLE(date,delta) VALUES(?,?)",
                    arrayOf(date, delta))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearDailyCorrection(date: Long) {
        writableDatabase.delete(MANUAL_TABLE, "date=?", arrayOf(date.toString()))
    }

    private fun rawDayTotal(db: SQLiteDatabase, date: Long): Long =
        db.rawQuery("SELECT COALESCE(SUM($COL_COUNT),0) FROM $TABLE WHERE $COL_DATE=?",
            arrayOf(date.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    private fun manualDeltas(db: SQLiteDatabase, from: Long, to: Long): Map<Long, Long> {
        val result = LinkedHashMap<Long, Long>()
        db.query(MANUAL_TABLE, arrayOf("date", "delta"), "date BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString()), null, null, null).use { c ->
            while (c.moveToNext()) result[c.getLong(0)] = c.getLong(1)
        }
        return result
    }

    /** Per-hour rows for one date; missing hours map to null. */
    fun hourRows(date: Long): Array<HourRow?> {
        val result = arrayOfNulls<HourRow>(24)
        readableDatabase.query(
            TABLE,
            arrayOf(COL_HOUR, COL_COUNT, COL_CAL, COL_WALK_TIME, COL_DISTANCE),
            "$COL_DATE=?", arrayOf(date.toString()),
            null, null, "$COL_HOUR ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val hour = c.getInt(0)
                if (hour in 0..23) {
                    result[hour] = HourRow(
                        steps = c.getLong(1),
                        cal = c.getDouble(2),
                        walkTimeMs = c.getLong(3),
                        distanceMeters = c.getDouble(4)
                    )
                }
            }
        }
        val delta = manualDeltas(readableDatabase, date, date)[date] ?: 0L
        if (delta != 0L) {
            val raw = result.sumOf { it?.steps ?: 0L }
            val corrected = CorrectionMath.displayed(raw, delta)
            if (raw > 0L) {
                // Scale existing hours proportionally; don't invent a peak hour.
                val factor = corrected.toDouble() / raw
                var accounted = 0L
                var rawAccum = 0L
                for (i in result.indices) {
                    result[i]?.let { hour ->
                        rawAccum += hour.steps
                        val next = (rawAccum * factor).toLong().coerceAtMost(corrected)
                        hour.cal *= factor
                        hour.distanceMeters *= factor
                        hour.steps = next - accounted
                        accounted = next
                    }
                }
            } else if (corrected > 0L) {
                // Imported daily totals cannot imply a real walking hour.
                result[12] = HourRow(corrected,
                    corrected * Prefs.kcalPerStep(context), 0L,
                    corrected * Prefs.metersPerStep(context))
            }
        }
        return result
    }

    /** Totals per date for a date range, inclusive. */
    fun dayTotals(from: Long, to: Long): Map<Long, DayTotal> {
        val result = HashMap<Long, DayTotal>()
        readableDatabase.query(
            TABLE,
            arrayOf(
                COL_DATE,
                "SUM($COL_COUNT)",
                "SUM($COL_CAL)",
                "SUM($COL_WALK_TIME)",
                "SUM($COL_DISTANCE)"
            ),
            "$COL_DATE BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString()),
            COL_DATE, null, "$COL_DATE ASC"
        ).use { c ->
            while (c.moveToNext()) {
                result[c.getLong(0)] = DayTotal(
                    steps = c.getLong(1),
                    cal = c.getDouble(2),
                    walkTimeMs = c.getLong(3),
                    distanceMeters = c.getDouble(4)
                )
            }
        }
        for ((date, delta) in manualDeltas(readableDatabase, from, to)) {
            val raw = result[date] ?: DayTotal()
            val total = CorrectionMath.displayed(raw.steps, delta)
            val ratio = if (raw.steps > 0L) total.toDouble() / raw.steps else 0.0
            result[date] = raw.copy(
                steps = total,
                cal = if (raw.steps > 0L) raw.cal * ratio
                    else total * Prefs.kcalPerStep(context),
                distanceMeters = if (raw.steps > 0L) raw.distanceMeters * ratio
                    else total * Prefs.metersPerStep(context)
            )
        }
        return result
    }

    fun dayTotal(date: Long): DayTotal = dayTotals(date, date)[date] ?: DayTotal()

    /** All dates that have at least one row, oldest first. */
    fun distinctDates(): List<Long> {
        val result = ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT $COL_DATE FROM $TABLE UNION SELECT date FROM $MANUAL_TABLE ORDER BY 1",
            null
        ).use { c ->
            while (c.moveToNext()) result.add(c.getLong(0))
        }
        return result
    }
}
