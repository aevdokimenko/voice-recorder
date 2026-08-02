package org.fossify.voicerecorder.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun `a recording younger than the window is not expired`() {
        assertFalse(isOlderThanDays(now - 14 * day, 15, now))
    }

    @Test
    fun `a recording older than the window is expired`() {
        assertTrue(isOlderThanDays(now - 16 * day, 15, now))
    }

    @Test
    fun `exactly at the boundary is not yet expired`() {
        assertFalse(isOlderThanDays(now - 15 * day, 15, now))
    }

    @Test
    fun `an absent timestamp never expires, so a never-uploaded recording is not auto-trashed`() {
        assertFalse(isOlderThanDays(0L, 15, now))
        assertFalse(isOlderThanDays(-1L, 15, now))
    }

    @Test
    fun `a zero-day window expires anything with a real timestamp`() {
        assertTrue(isOlderThanDays(now - 1000L, 0, now))
    }
}
