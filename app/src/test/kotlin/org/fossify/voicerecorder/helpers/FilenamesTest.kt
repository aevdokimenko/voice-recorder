package org.fossify.voicerecorder.helpers

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FilenamesTest {
    private fun calendarFor(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Calendar {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.set(year, month - 1, day, hour, minute, second)
        return calendar
    }

    @Test
    fun `formats as yyyyMMdd underscore HHmmss`() {
        val calendar = calendarFor(2026, 7, 29, 14, 5, 9)
        assertEquals("20260729_140509", generateRecordingFilename(calendar))
    }

    @Test
    fun `zero-pads single-digit month, day, hour, minute, and second`() {
        val calendar = calendarFor(2026, 1, 2, 3, 4, 5)
        assertEquals("20260102_030405", generateRecordingFilename(calendar))
    }
}
