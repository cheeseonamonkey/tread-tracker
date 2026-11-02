package dev.treadtracker.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/**
 * Step storage. The database name, table name and columns are identical to
 * the original app (`DAY_WALK_COUNT` db, `DAY_WALK_COUNT(date, hour, count,
 * cal, walk_time, distance)` table), so data from an original install is
 * read and continued seamlessly.
 *
 * Rows are keyed by (date=local-midnight-millis, hour=0..23); each row holds
 * absolute totals for that hour.
 */
class StepDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "DAY_WALK_COUNT"
        const val DB_VERSION = 1
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The original app did the same (v1 only).
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureUniqueIndex(db)
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
        return result
    }

    fun dayTotal(date: Long): DayTotal = dayTotals(date, date)[date] ?: DayTotal()

    /** All dates that have at least one row, oldest first. */
    fun distinctDates(): List<Long> {
        val result = ArrayList<Long>()
        readableDatabase.query(
            true, TABLE, arrayOf(COL_DATE), null, null,
            null, null, "$COL_DATE ASC", null
        ).use { c ->
            while (c.moveToNext()) result.add(c.getLong(0))
        }
        return result
    }
}
