package com.tavi.novelreader

import com.tavi.novelreader.data.Goals
import com.tavi.novelreader.parser.PdfText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class GoalsTest {
    private fun cal(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }
    private val min = 60_000L

    @Test fun streakCountsBackFromYesterdayWhenTodayNotMet() {
        val days = mapOf("2026-10-09" to 40 * min, "2026-10-10" to 31 * min, "2026-10-11" to 30 * min, "2026-10-12" to 5 * min,
            "2026-10-01" to 60 * min, "2026-10-02" to 60 * min, "2026-10-03" to 60 * min, "2026-10-04" to 60 * min)
        val s = Goals.streak(days, 30, cal(2026, 10, 12))
        assertEquals(3, s.current); assertEquals(4, s.best); assertEquals(false, s.doneToday)
    }

    @Test fun streakIncludesTodayWhenMet() {
        val s = Goals.streak(mapOf("2026-10-11" to 30 * min, "2026-10-12" to 30 * min), 30, cal(2026, 10, 12))
        assertEquals(2, s.current); assertEquals(true, s.doneToday)
    }

    @Test fun streakAcrossMonthBoundary() {
        val s = Goals.streak(mapOf("2026-09-30" to 30 * min, "2026-10-01" to 30 * min), 30, cal(2026, 10, 1))
        assertEquals(2, s.current); assertEquals(2, s.best)
    }

    @Test fun tidyJoinsPdfLines() {
        assertEquals("中文换行合并在一起\nThe quick brown fox jumps", PdfText.tidy("中文换行\n合并在一起\n\nThe quick brown\nfox jum-\nps"))
    }
}
