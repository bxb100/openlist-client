package org.openlist.mobile.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class OpenListAsfSeekMapTest {
    @Test
    fun `file properties packet count repairs FongMi unseekable map`() {
        val metadata = parseAsfFileProperties(
            asfFilePropertiesObject(packetCount = 10L, packetSize = 100, flags = 2),
        )
        val original = SeekMap.Unseekable(10_000_000L)

        val repaired = repairAsfSeekMap(
            original = original,
            metadata = metadata,
            firstPacketPosition = 184L,
        )

        assertThat(metadata).isEqualTo(AsfSeekMetadata(packetCount = 10L, packetSize = 100L))
        assertThat(repaired.isSeekable).isTrue()
        assertThat(repaired.durationUs).isEqualTo(10_000_000L)
        assertThat(repaired.getSeekPoints(5_000_000L).first.timeUs).isEqualTo(5_000_000L)
        assertThat(repaired.getSeekPoints(5_000_000L).first.position).isEqualTo(684L)
    }

    @Test
    fun `broadcast file does not receive a synthetic seek map`() {
        val metadata = parseAsfFileProperties(
            asfFilePropertiesObject(packetCount = 10L, packetSize = 100, flags = 1),
        )
        val original = SeekMap.Unseekable(10_000_000L)

        val repaired = repairAsfSeekMap(
            original = original,
            metadata = metadata,
            firstPacketPosition = 184L,
        )

        assertThat(metadata).isNull()
        assertThat(repaired).isSameInstanceAs(original)
    }

    @Test
    fun `zero packet fields derive count from fixed-size data object`() {
        val fileProperties = parseAsfFileProperties(
            asfFilePropertiesObject(packetCount = 0L, packetSize = 100, flags = 2),
        )

        val metadata = resolveAsfSeekMetadata(
            fileProperties = checkNotNull(fileProperties),
            dataObjectHeader = asfDataObjectHeader(objectSize = 1_050L, packetCount = 0L),
        )
        val repaired = repairAsfSeekMap(
            original = SeekMap.Unseekable(10_000_000L),
            metadata = metadata,
            firstPacketPosition = 184L,
        )

        assertThat(metadata).isEqualTo(AsfSeekMetadata(packetCount = 10L, packetSize = 100L))
        assertThat(repaired.isSeekable).isTrue()
        assertThat(repaired.getSeekPoints(9_000_000L).first.position).isEqualTo(1_084L)
    }

    @Test
    fun `extractor wrapper publishes repaired map without consuming peeked header`() {
        val bytes = asfHeaderPreamble(childObjectCount = 1, headerBodySize = 104) +
            asfFilePropertiesObject(packetCount = 0L, packetSize = 100, flags = 2) +
            asfDataObjectHeader(objectSize = 1_050L, packetCount = 0L)
        var sourcePosition = 0
        val input = DefaultExtractorInput(
            DataReader { target, offset, length ->
                if (length == 0) {
                    0
                } else if (sourcePosition >= bytes.size) {
                    C.RESULT_END_OF_INPUT
                } else {
                    val bytesToRead = minOf(length, bytes.size - sourcePosition)
                    bytes.copyInto(target, offset, sourcePosition, sourcePosition + bytesToRead)
                    sourcePosition += bytesToRead
                    bytesToRead
                }
            },
            0L,
            bytes.size.toLong(),
        )
        val output = CapturingExtractorOutput()
        val extractor = OpenListAsfExtractor(
            UnseekableDelegate(
                firstPacketPosition = bytes.size,
                durationUs = 10_000_000L,
            ),
        )

        extractor.init(output)
        extractor.read(input, PositionHolder())

        assertThat(input.position).isEqualTo(bytes.size.toLong())
        assertThat(output.seekMap).isNotNull()
        assertThat(output.seekMap!!.isSeekable).isTrue()
        assertThat(output.seekMap!!.getSeekPoints(5_000_000L).first.position).isEqualTo(684L)
    }

    @Test
    fun `overflowing packet table does not receive a synthetic seek map`() {
        val original = SeekMap.Unseekable(10_000_000L)

        val repaired = repairAsfSeekMap(
            original = original,
            metadata = AsfSeekMetadata(packetCount = Long.MAX_VALUE, packetSize = 2L),
            firstPacketPosition = 184L,
        )

        assertThat(repaired).isSameInstanceAs(original)
    }

    private fun asfFilePropertiesObject(
        packetCount: Long,
        packetSize: Int,
        flags: Int,
    ): ByteArray = ByteBuffer.allocate(104)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put(ASF_FILE_PROPERTIES_GUID)
            putLong(104L)
            put(ByteArray(16)) // File ID.
            putLong(0L) // File size.
            putLong(0L) // Creation time.
            putLong(packetCount)
            putLong(100_000_000L) // Play duration in 100 ns units.
            putLong(0L) // Send duration.
            putLong(0L) // Preroll.
            putInt(flags)
            putInt(packetSize)
            putInt(packetSize)
            putInt(0) // Maximum bitrate.
        }
        .array()

    private fun asfHeaderPreamble(
        childObjectCount: Int,
        headerBodySize: Int,
    ): ByteArray = ByteBuffer.allocate(30)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put(ASF_HEADER_GUID)
            putLong(30L + headerBodySize)
            putInt(childObjectCount)
            put(1)
            put(2)
        }
        .array()

    private fun asfDataObjectHeader(
        objectSize: Long,
        packetCount: Long,
    ): ByteArray = ByteBuffer.allocate(50)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put(ASF_DATA_OBJECT_GUID)
            putLong(objectSize)
            put(ByteArray(16)) // File ID.
            putLong(packetCount)
            putShort(0)
        }
        .array()

    private class UnseekableDelegate(
        private val firstPacketPosition: Int,
        private val durationUs: Long,
    ) : Extractor {
        private lateinit var output: ExtractorOutput

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            val remainingHeaderBytes = firstPacketPosition - input.position.toInt()
            if (remainingHeaderBytes > 0) input.skipFully(remainingHeaderBytes)
            output.seekMap(SeekMap.Unseekable(durationUs))
            return Extractor.RESULT_END_OF_INPUT
        }

        override fun seek(position: Long, timeUs: Long) = Unit

        override fun release() = Unit
    }

    private class CapturingExtractorOutput : ExtractorOutput {
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private companion object {
        val ASF_HEADER_GUID = byteArrayOf(
            0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(),
            0x11, 0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62,
            0xCE.toByte(), 0x6C,
        )

        val ASF_FILE_PROPERTIES_GUID = byteArrayOf(
            0xA1.toByte(), 0xDC.toByte(), 0xAB.toByte(), 0x8C.toByte(), 0x47,
            0xA9.toByte(), 0xCF.toByte(), 0x11, 0x8E.toByte(), 0xE4.toByte(),
            0x00, 0xC0.toByte(), 0x0C, 0x20, 0x53, 0x65,
        )

        val ASF_DATA_OBJECT_GUID = byteArrayOf(
            0x36, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(),
            0x11, 0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62,
            0xCE.toByte(), 0x6C,
        )
    }
}
