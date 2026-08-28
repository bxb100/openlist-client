@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.asf.AsfExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import org.openlist.mobile.BuildConfig

/**
 * Uses the FongMi extractors while repairing ASF seek metadata and the WVC1 initialization-data
 * prefix. Both corrections stay at the extractor boundary so Media3 receives a valid timeline and
 * every renderer sees the same valid [Format].
 */
internal class OpenListExtractorsFactory(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory(),
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { index ->
            val extractor = extractors[index]
            val corrected = when (extractor) {
                is AsfExtractor -> OpenListAsfExtractor(extractor)
                is FragmentedMp4Extractor -> OpenListFragmentedMp4Extractor(extractor)
                else -> extractor
            }
            if (BuildConfig.DEBUG) DebugSeekMapLoggingExtractor(corrected) else corrected
        }
}

/** Debug-only, content-agnostic diagnostics for the extractor that wins sniffing. */
private class DebugSeekMapLoggingExtractor(
    delegate: Extractor,
) : ForwardingExtractor(delegate) {
    private val extractorName = delegate.underlyingImplementation.javaClass.simpleName
    private var activeInput: ExtractorInput? = null

    override fun init(output: ExtractorOutput) {
        super.init(
            object : ForwardingExtractorOutput(output) {
                override fun seekMap(seekMap: SeekMap) {
                    Log.d(
                        SEEK_MAP_LOG_TAG,
                        "extractor=$extractorName, map=${seekMap.javaClass.simpleName}, " +
                            "seekable=${seekMap.isSeekable}, durationUs=${seekMap.durationUs}, " +
                            "position=${activeInput?.position}, inputLength=${activeInput?.length}",
                    )
                    super.seekMap(seekMap)
                }
            },
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        activeInput = input
        return try {
            super.read(input, seekPosition)
        } finally {
            activeInput = null
        }
    }
}

/** Repairs seek metadata and VC-1 codec data at the boundary of FongMi's ASF extractor. */
internal class OpenListAsfExtractor(
    delegate: Extractor,
) : ForwardingExtractor(delegate) {
    private var activeInput: ExtractorInput? = null
    private var seekMetadataInspected = false
    private var seekMetadata: AsfSeekMetadata? = null

    override fun init(output: ExtractorOutput) {
        super.init(
            object : ForwardingExtractorOutput(output) {
                override fun seekMap(seekMap: SeekMap) {
                    val input = activeInput
                    val repairedSeekMap = repairAsfSeekMap(
                        original = seekMap,
                        metadata = seekMetadata,
                        firstPacketPosition = input?.position ?: -1L,
                    )
                    if (BuildConfig.DEBUG && !seekMap.isSeekable) {
                        Log.d(
                            ASF_SEEK_LOG_TAG,
                            "repaired=${repairedSeekMap.isSeekable}, durationUs=${seekMap.durationUs}, " +
                                "packetCount=${seekMetadata?.packetCount}, " +
                                "packetSize=${seekMetadata?.packetSize}, " +
                                "firstPacketPosition=${input?.position}, inputLength=${input?.length}",
                        )
                    }
                    super.seekMap(repairedSeekMap)
                }

                override fun track(id: Int, type: Int): TrackOutput {
                    val trackOutput = super.track(id, type)
                    if (type != C.TRACK_TYPE_VIDEO) return trackOutput
                    return object : ForwardingTrackOutput(trackOutput) {
                        override fun format(format: Format) {
                            super.format(Vc1InitializationDataSanitizer.sanitize(format))
                        }
                    }
                }
            },
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!seekMetadataInspected) {
            seekMetadata = input.peekAsfSeekMetadata()
            seekMetadataInspected = true
        }
        activeInput = input
        return try {
            super.read(input, seekPosition)
        } finally {
            activeInput = null
        }
    }
}

internal data class AsfSeekMetadata(
    val packetCount: Long,
    val packetSize: Long,
)

/**
 * FongMi 1.11 skips File Properties.packet_count and only trusts the Data Object copy. Some valid
 * ASF files leave either or both count fields at zero even though Data Object size and fixed packet
 * size define the count exactly. Peek the mandatory headers without consuming extractor input so
 * those files retain their normal packet-based seek map.
 */
private fun ExtractorInput.peekAsfSeekMetadata(): AsfSeekMetadata? {
    resetPeekPosition()
    return try {
        val preamble = ByteArray(ASF_HEADER_PREAMBLE_SIZE)
        if (!peekFully(preamble, 0, preamble.size, true) || !preamble.matchesAt(ASF_HEADER_GUID, 0)) {
            return null
        }
        val headerSize = preamble.readLittleEndianLong(16)
        val bodySize = headerSize - ASF_HEADER_PREAMBLE_SIZE
        if (bodySize < 0L || bodySize > ASF_MAX_HEADER_BODY_SIZE) return null

        val body = ByteArray(bodySize.toInt())
        if (!peekFully(body, 0, body.size, true)) return null
        val fileProperties = parseAsfFileProperties(body) ?: return null
        val dataObjectHeader = ByteArray(ASF_DATA_OBJECT_HEADER_SIZE)
        if (!peekFully(dataObjectHeader, 0, dataObjectHeader.size, true)) return null
        resolveAsfSeekMetadata(fileProperties, dataObjectHeader)
    } finally {
        resetPeekPosition()
    }
}

internal fun parseAsfFileProperties(headerBody: ByteArray): AsfSeekMetadata? {
    var objectOffset = 0
    while (objectOffset + ASF_OBJECT_HEADER_SIZE <= headerBody.size) {
        val objectSize = headerBody.readLittleEndianLong(objectOffset + 16)
        if (
            objectSize < ASF_OBJECT_HEADER_SIZE ||
            objectSize > headerBody.size.toLong() - objectOffset
        ) {
            return null
        }
        if (headerBody.matchesAt(ASF_FILE_PROPERTIES_GUID, objectOffset)) {
            val bodyOffset = objectOffset + ASF_OBJECT_HEADER_SIZE
            if (objectSize - ASF_OBJECT_HEADER_SIZE < ASF_FILE_PROPERTIES_BODY_SIZE) return null

            val packetCount = headerBody.readLittleEndianLong(bodyOffset + 32)
            val flags = headerBody.readLittleEndianUnsignedInt(bodyOffset + 64)
            val packetSize = headerBody.readLittleEndianUnsignedInt(bodyOffset + 72)
            val isBroadcast = flags and 0x01L != 0L
            return AsfSeekMetadata(packetCount, packetSize)
                .takeIf {
                    !isBroadcast &&
                        it.packetCount >= 0L &&
                        it.packetSize > 0L &&
                        it.packetSize <= Int.MAX_VALUE
                }
        }
        objectOffset += objectSize.toInt()
    }
    return null
}

internal fun resolveAsfSeekMetadata(
    fileProperties: AsfSeekMetadata,
    dataObjectHeader: ByteArray,
): AsfSeekMetadata? {
    if (
        dataObjectHeader.size < ASF_DATA_OBJECT_HEADER_SIZE ||
        !dataObjectHeader.matchesAt(ASF_DATA_OBJECT_GUID, 0)
    ) {
        return null
    }
    val dataObjectSize = dataObjectHeader.readLittleEndianLong(16)
    val dataObjectPacketCount = dataObjectHeader.readLittleEndianLong(40)
    if (dataObjectPacketCount < 0L) return null

    val packetBytes = dataObjectSize - ASF_DATA_OBJECT_HEADER_SIZE
    val derivedPacketCount = if (
        dataObjectSize >= ASF_DATA_OBJECT_HEADER_SIZE &&
        packetBytes % fileProperties.packetSize == 0L
    ) {
        packetBytes / fileProperties.packetSize
    } else {
        0L
    }
    val packetCount = when {
        dataObjectPacketCount > 0L -> dataObjectPacketCount
        fileProperties.packetCount > 0L -> fileProperties.packetCount
        derivedPacketCount > 0L -> derivedPacketCount
        else -> return null
    }
    return fileProperties.copy(packetCount = packetCount)
}

internal fun repairAsfSeekMap(
    original: SeekMap,
    metadata: AsfSeekMetadata?,
    firstPacketPosition: Long,
): SeekMap {
    if (
        original.isSeekable ||
        original.durationUs <= 0L ||
        metadata == null ||
        metadata.packetCount <= 0L ||
        metadata.packetSize <= 0L ||
        firstPacketPosition <= 0L ||
        metadata.packetCount > (Long.MAX_VALUE - firstPacketPosition) / metadata.packetSize
    ) {
        return original
    }
    return FixedPacketAsfSeekMap(
        durationUs = original.durationUs,
        firstPacketPosition = firstPacketPosition,
        packetSize = metadata.packetSize,
        packetCount = metadata.packetCount,
    )
}

private class FixedPacketAsfSeekMap(
    private val durationUs: Long,
    private val firstPacketPosition: Long,
    private val packetSize: Long,
    private val packetCount: Long,
) : SeekMap {
    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val clampedTimeUs = timeUs.coerceIn(0L, durationUs)
        val fraction = clampedTimeUs.toDouble() / durationUs.toDouble()
        val packetIndex = (fraction * packetCount.toDouble())
            .toLong()
            .coerceIn(0L, packetCount - 1L)
        val earlier = seekPoint(packetIndex)
        return if (packetIndex + 1L < packetCount) {
            SeekMap.SeekPoints(earlier, seekPoint(packetIndex + 1L))
        } else {
            SeekMap.SeekPoints(earlier)
        }
    }

    private fun seekPoint(packetIndex: Long): SeekPoint = SeekPoint(
        (packetIndex.toDouble() / packetCount.toDouble() * durationUs.toDouble()).toLong(),
        firstPacketPosition + packetIndex * packetSize,
    )
}

private fun ByteArray.matchesAt(expected: ByteArray, offset: Int): Boolean =
    offset >= 0 &&
        size - offset >= expected.size &&
        expected.indices.all { index -> this[offset + index] == expected[index] }

private fun ByteArray.readLittleEndianLong(offset: Int): Long {
    var value = 0L
    repeat(Long.SIZE_BYTES) { index ->
        value = value or ((this[offset + index].toLong() and 0xFFL) shl (index * 8))
    }
    return value
}

private fun ByteArray.readLittleEndianUnsignedInt(offset: Int): Long {
    var value = 0L
    repeat(Int.SIZE_BYTES) { index ->
        value = value or ((this[offset + index].toLong() and 0xFFL) shl (index * 8))
    }
    return value
}

private const val ASF_HEADER_PREAMBLE_SIZE = 30
private const val ASF_OBJECT_HEADER_SIZE = 24
private const val ASF_FILE_PROPERTIES_BODY_SIZE = 80
private const val ASF_DATA_OBJECT_HEADER_SIZE = 50
private const val ASF_MAX_HEADER_BODY_SIZE = 10L * 1024L * 1024L
private const val ASF_SEEK_LOG_TAG = "OpenListAsfSeek"
private const val SEEK_MAP_LOG_TAG = "OpenListSeekMap"

private val ASF_HEADER_GUID = byteArrayOf(
    0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
    0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C,
)

private val ASF_FILE_PROPERTIES_GUID = byteArrayOf(
    0xA1.toByte(), 0xDC.toByte(), 0xAB.toByte(), 0x8C.toByte(), 0x47, 0xA9.toByte(),
    0xCF.toByte(), 0x11, 0x8E.toByte(), 0xE4.toByte(), 0x00, 0xC0.toByte(), 0x0C, 0x20,
    0x53, 0x65,
)

private val ASF_DATA_OBJECT_GUID = byteArrayOf(
    0x36, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
    0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C,
)

/** Repairs the exact duplicate sequence marker produced by FongMi's ASF WVC1 prefix handling. */
internal object Vc1InitializationDataSanitizer {
    private val sequenceHeader = byteArrayOf(0x00, 0x00, 0x01, 0x0F)

    fun sanitize(format: Format): Format {
        if (format.sampleMimeType != MimeTypes.VIDEO_VC1 || format.initializationData.isEmpty()) {
            return format
        }
        val first = format.initializationData.first()
        if (!hasSequenceHeaderAt(first, 0) || !hasSequenceHeaderAt(first, 5)) return format

        // WVC1 private data may start with one size byte before its real sequence marker. The
        // FongMi fork only checks offset zero, so it prepends another marker and creates
        // [fake marker][size byte][real marker]. FFmpeg already scans past the size byte itself.
        val repaired = first.copyOfRange(sequenceHeader.size, first.size)
        val initializationData = format.initializationData.toMutableList()
        initializationData[0] = repaired
        return format.buildUpon().setInitializationData(initializationData).build()
    }

    private fun hasSequenceHeaderAt(data: ByteArray, offset: Int): Boolean =
        offset >= 0 &&
            data.size >= offset + sequenceHeader.size &&
            sequenceHeader.indices.all { index -> data[offset + index] == sequenceHeader[index] }
}
