package dev.treadtracker.app.data

import dev.treadtracker.app.util.Dates
import java.io.Reader
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Two-column, human-editable daily snapshot. Never replaces sensor history. */
object HistoryCsv {
    const val MAX_STEPS = 1_000_000L
    private const val MAX_DAYS = 40_000

    private fun format() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }

    fun dateLabel(day: Long): String = format().format(Date(day))

    fun parse(reader: Reader): Map<Long, Long> {
        val dates = LinkedHashMap<Long, Long>()
        reader.buffered().useLines { lines ->
            var first = true
            var lineNo = 0
            for (original in lines) {
                lineNo++
                require(lineNo <= MAX_DAYS + 1) { "CSV exceeds $MAX_DAYS days" }
                require(original.length <= 128) { "Line $lineNo is too long" }
                val line = original.removePrefix("\uFEFF").trim()
                if (line.isEmpty()) continue
                val fields = line.split(',').map { it.trim().removeSurrounding("\"") }
                require(fields.size == 2) { "Line $lineNo: expected date,steps" }
                if (first && fields[0].equals("date", true) && fields[1].equals("steps", true)) {
                    first = false
                    continue
                }
                first = false
                val dateString = fields[0]
                require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(dateString)) {
                    "Line $lineNo: use date format YYYY-MM-DD"
                }
                val date = try { format().parse(dateString)?.time } catch (_: Exception) { null }
                require(date != null && dateLabel(date) == dateString) {
                    "Line $lineNo: invalid date"
                }
                require(date <= Dates.today()) { "Line $lineNo: future dates aren't supported" }
                val steps = fields[1].toLongOrNull()
                require(steps != null && steps in 0..MAX_STEPS) {
                    "Line $lineNo: steps must be 0–$MAX_STEPS"
                }
                require(dates.putIfAbsent(date, steps) == null) {
                    "Line $lineNo: duplicate date $dateString"
                }
            }
        }
        require(dates.isNotEmpty()) { "CSV contains no days" }
        return dates
    }

    fun write(writer: Writer, totals: Map<Long, Long>) {
        writer.write("date,steps\n")
        for ((date, count) in totals.toSortedMap()) {
            writer.write("${dateLabel(date)},$count\n")
        }
        writer.flush()
    }
}
