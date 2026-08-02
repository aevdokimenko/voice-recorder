package org.fossify.voicerecorder.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploaderTest {

    @Test
    fun `2xx is success`() {
        listOf(200, 201, 202, 204).forEach {
            assertEquals("HTTP $it", UploadResult.Success, classifyResponse(it))
        }
    }

    @Test
    fun `5xx is retryable`() {
        listOf(500, 502, 503, 504).forEach {
            assertTrue("HTTP $it", classifyResponse(it) is UploadResult.Retryable)
        }
    }

    @Test
    fun `timeout and rate limiting are retryable`() {
        assertTrue(classifyResponse(408) is UploadResult.Retryable)
        assertTrue(classifyResponse(429) is UploadResult.Retryable)
    }

    @Test
    fun `auth and client errors are permanent so a bad token does not burn every retry`() {
        listOf(400, 401, 403, 404, 409, 413).forEach {
            assertTrue("HTTP $it", classifyResponse(it) is UploadResult.Permanent)
        }
    }

    @Test
    fun `the reason carries the status code`() {
        assertEquals("HTTP 503", (classifyResponse(503) as UploadResult.Retryable).reason)
        assertEquals("HTTP 401", (classifyResponse(401) as UploadResult.Permanent).reason)
    }
}
