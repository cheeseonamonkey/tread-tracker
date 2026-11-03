package dev.treadtracker.app.data

import dev.treadtracker.app.util.Dates
import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class HistoryCsvTest {
    private val day = Dates.today()

    @Test fun roundTrip() {
        val out = StringWriter()
        HistoryCsv.write(out, mapOf(day to 12543L, Dates.addDays(day, -1) to 7654L))
        assertEquals(mapOf(day to 12543L, Dates.addDays(day, -1) to 7654L),
            HistoryCsv.parse(StringReader(out.toString())))
    }

    @Test fun acceptsExcelBomAndWindowsNewlines() {
        val csv = "\uFEFFdate,steps\r\n${HistoryCsv.dateLabel(day)},123\r\n"
        assertEquals(123L, HistoryCsv.parse(StringReader(csv))[day])
    }

    @Test fun rejectsDuplicatesAndInvalidValues() {
        val label = HistoryCsv.dateLabel(day)
        for (csv in listOf("date,steps\n$label,10\n$label,20\n",
            "date,steps\n$label,-1\n", "date,steps\n2026-02-30,123\n",
            "date,steps\n$label,abc\n")) {
            try { HistoryCsv.parse(StringReader(csv)); fail("Accepted invalid $csv") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun allowsZeroAndQuotedNumbers() {
        assertEquals(0L, HistoryCsv.parse(StringReader(
            "date,steps\n\"${HistoryCsv.dateLabel(day)}\",\"0\"\n"))[day])
    }
}
