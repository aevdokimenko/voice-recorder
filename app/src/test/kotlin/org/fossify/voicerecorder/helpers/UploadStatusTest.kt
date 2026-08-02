package org.fossify.voicerecorder.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadStatusTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun recording(name: String = "20260801_140000.m4a"): String {
        val file = File(tempFolder.root, name)
        file.writeText("audio")
        return file.absolutePath
    }

    @Test
    fun `sidecar sits next to the recording with a status json suffix`() {
        val path = recording()
        assertEquals("20260801_140000.m4a.status.json", sidecarFileFor(path).name)
        assertEquals(tempFolder.root.absolutePath, sidecarFileFor(path).parentFile!!.absolutePath)
    }

    @Test
    fun `reading a recording with no sidecar returns null`() {
        assertNull(readUploadStatus(recording()))
    }

    @Test
    fun `round-trips every field`() {
        val path = recording()
        val written = UploadStatus(
            state = UploadState.FAILED,
            attempts = 3,
            lastError = "HTTP 503",
            uploadedAt = 1_770_000_000_000L
        )
        writeUploadStatus(path, written)

        assertEquals(written, readUploadStatus(path))
    }

    @Test
    fun `round-trips a null lastError`() {
        val path = recording()
        writeUploadStatus(path, UploadStatus(state = UploadState.UPLOADED, uploadedAt = 42L))

        val read = readUploadStatus(path)!!
        assertEquals(UploadState.UPLOADED, read.state)
        assertNull(read.lastError)
        assertEquals(42L, read.uploadedAt)
    }

    @Test
    fun `a corrupt sidecar reads as null rather than throwing`() {
        val path = recording()
        sidecarFileFor(path).writeText("{ not json")

        assertNull(readUploadStatus(path))
    }

    @Test
    fun `an unknown state reads as null rather than throwing`() {
        val path = recording()
        sidecarFileFor(path).writeText("""{"state":"TELEPORTED","attempts":0}""")

        assertNull(readUploadStatus(path))
    }

    @Test
    fun `delete removes the sidecar`() {
        val path = recording()
        writeUploadStatus(path, UploadStatus(UploadState.PENDING))
        assertTrue(sidecarFileFor(path).exists())

        deleteUploadStatus(path)

        assertFalse(sidecarFileFor(path).exists())
    }

    @Test
    fun `move relocates the sidecar to the new recording path`() {
        val from = recording()
        val to = File(tempFolder.newFolder("trash"), "20260801_140000.m4a").absolutePath
        writeUploadStatus(from, UploadStatus(UploadState.UPLOADED, attempts = 1))

        moveUploadStatus(from, to)

        assertFalse(sidecarFileFor(from).exists())
        assertEquals(UploadState.UPLOADED, readUploadStatus(to)!!.state)
    }

    @Test
    fun `moving a recording that has no sidecar is a no-op`() {
        val from = recording()
        val to = File(tempFolder.newFolder("trash"), "20260801_140000.m4a").absolutePath

        moveUploadStatus(from, to)

        assertNull(readUploadStatus(to))
    }
}
