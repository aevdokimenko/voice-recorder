package org.fossify.voicerecorder.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentTest {

    private val validEnroll = """
        {
          "deviceToken": "tok-123",
          "format": { "codec": "aac", "bitrate": 96000, "sampleRate": 44100 },
          "retention": { "daysUntilTrash": 15, "daysUntilPurge": 30 }
        }
    """.trimIndent()

    @Test
    fun `parses a full enrollment response`() {
        val enrollment = parseEnrollment(validEnroll)!!

        assertEquals("tok-123", enrollment.deviceToken)
        assertEquals(ServerFormat("aac", 96_000, 44_100), enrollment.format)
        assertEquals(ServerRetention(15, 30), enrollment.retention)
    }

    @Test
    fun `enrollment without a device token is rejected`() {
        val json = """{"format":{"codec":"aac","bitrate":96000,"sampleRate":44100}}"""
        assertNull(parseEnrollment(json))
    }

    @Test
    fun `an unknown codec is rejected rather than silently defaulted`() {
        val json = """
            {"deviceToken":"t","format":{"codec":"flac","bitrate":96000,"sampleRate":44100},
             "retention":{"daysUntilTrash":15,"daysUntilPurge":30}}
        """.trimIndent()
        assertNull(parseEnrollment(json))
    }

    @Test
    fun `malformed json is rejected`() {
        assertNull(parseEnrollment("{ not json"))
        assertNull(parseConfig("{ not json"))
        assertNull(parsePresign("{ not json"))
    }

    @Test
    fun `retention falls back to defaults when the server omits it`() {
        val json = """{"deviceToken":"t","format":{"codec":"opus","bitrate":64000,"sampleRate":48000}}"""
        val enrollment = parseEnrollment(json)!!

        assertEquals(DEFAULT_DAYS_UNTIL_TRASH, enrollment.retention.daysUntilTrash)
        assertEquals(DEFAULT_DAYS_UNTIL_PURGE, enrollment.retention.daysUntilPurge)
    }

    @Test
    fun `parses a config response that carries no device token`() {
        val json = """
            {"format":{"codec":"opus","bitrate":64000,"sampleRate":48000},
             "retention":{"daysUntilTrash":7,"daysUntilPurge":14}}
        """.trimIndent()
        val (format, retention) = parseConfig(json)!!

        assertEquals(ServerFormat("opus", 64_000, 48_000), format)
        assertEquals(ServerRetention(7, 14), retention)
    }

    @Test
    fun `extracts the upload url from a presign response`() {
        assertEquals(
            "https://storage.example/put/abc?sig=xyz",
            parsePresign("""{"uploadUrl":"https://storage.example/put/abc?sig=xyz","expiresAt":123}""")
        )
    }

    @Test
    fun `a presign response without an upload url is rejected`() {
        assertNull(parsePresign("""{"expiresAt":123}"""))
    }

    @Test
    fun `derives the host from a qr enrollment url`() {
        assertEquals(
            "https://lr.example.com",
            enrollHostFrom("https://lr.example.com/api/v1/enroll?token=abc")
        )
        assertEquals(
            "https://lr.example.com:8443",
            enrollHostFrom("https://lr.example.com:8443/api/v1/enroll")
        )
    }

    @Test
    fun `derives the host from a bare base url`() {
        assertEquals("https://lr.example.com", enrollHostFrom("https://lr.example.com"))
        assertEquals("https://lr.example.com", enrollHostFrom("https://lr.example.com/"))
    }

    @Test
    fun `rejects a qr payload that is not a url`() {
        assertNull(enrollHostFrom("just some text"))
        assertNull(enrollHostFrom(""))
    }

    @Test
    fun `extracts the token from a qr enrollment url`() {
        assertEquals("abc123", enrollTokenFrom("https://lr.example.com/api/v1/enroll?token=abc123"))
        assertNull(enrollTokenFrom("https://lr.example.com/api/v1/enroll"))
    }
}
