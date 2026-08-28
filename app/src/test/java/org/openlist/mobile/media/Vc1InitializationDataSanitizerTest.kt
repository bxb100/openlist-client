package org.openlist.mobile.media

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Vc1InitializationDataSanitizerTest {
    @Test
    fun duplicateMarkerBeforeWvc1SizePrefixIsRemoved() {
        val malformed = byteArrayOf(
            0x00, 0x00, 0x01, 0x0F,
            0x2A,
            0x00, 0x00, 0x01, 0x0F,
            0x11, 0x22,
            0x00, 0x00, 0x01, 0x0E,
            0x33,
        )
        val format = vc1Format(malformed)

        val repaired = Vc1InitializationDataSanitizer.sanitize(format)

        assertArrayEquals(malformed.copyOfRange(4, malformed.size), repaired.initializationData[0])
    }

    @Test
    fun validVc1InitializationDataIsUnchanged() {
        val valid = byteArrayOf(
            0x00, 0x00, 0x01, 0x0F,
            0x11, 0x22, 0x33,
            0x00, 0x00, 0x01, 0x0E,
        )
        val format = vc1Format(valid)

        assertSame(format, Vc1InitializationDataSanitizer.sanitize(format))
    }

    @Test
    fun nonVc1FormatIsUnchanged() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_WMV)
            .setInitializationData(
                listOf(
                    byteArrayOf(
                        0x00, 0x00, 0x01, 0x0F,
                        0x2A,
                        0x00, 0x00, 0x01, 0x0F,
                    ),
                ),
            )
            .build()

        assertSame(format, Vc1InitializationDataSanitizer.sanitize(format))
    }

    private fun vc1Format(initializationData: ByteArray): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_VC1)
        .setInitializationData(listOf(initializationData))
        .build()
}
