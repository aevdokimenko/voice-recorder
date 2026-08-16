package org.fossify.voicerecorder.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecMappingTest {
    @Test
    fun `maps server codec names onto extension constants`() {
        assertEquals(EXTENSION_M4A, extensionForCodec("aac"))
        assertEquals(EXTENSION_OGG, extensionForCodec("opus"))
    }

    @Test
    fun `codec matching is case insensitive`() {
        assertEquals(EXTENSION_M4A, extensionForCodec("AAC"))
    }

    @Test
    fun `an unsupported codec maps to null so it can be rejected`() {
        assertNull(extensionForCodec("flac"))
        assertNull(extensionForCodec(""))
    }
}
