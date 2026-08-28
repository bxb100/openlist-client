package org.openlist.mobile.media

/** Minimal, defensive ISO-BMFF metadata needed to restart a fragmented MP4 at a real moof. */
internal data class FragmentedMp4SeekMetadata(
    val primaryTrack: FragmentedMp4TrackMetadata,
    val tracks: Map<Int, FragmentedMp4TrackMetadata>,
    val firstMoofPosition: Long,
    val firstPresentationTime: Long,
) {
    fun parseSeekAnchor(
        moof: ByteArray,
        moofPosition: Long,
    ): FragmentedMp4SeekAnchor? {
        if (moofPosition < firstMoofPosition) return null
        val presentationTime = parseFragment(moof, this, moofPosition)
            ?.primarySyncPresentationTime
            ?: return null
        return createSeekAnchor(presentationTime, moofPosition)
    }

    fun createSeekAnchor(
        presentationTime: Long,
        moofPosition: Long,
    ): FragmentedMp4SeekAnchor? {
        if (presentationTime < firstPresentationTime) return null
        val relativeTime = checkedSubtract(presentationTime, firstPresentationTime) ?: return null
        val timeUs = scaleToMicroseconds(relativeTime, primaryTrack.timescale) ?: return null
        return FragmentedMp4SeekAnchor(timeUs = timeUs, position = moofPosition)
    }
}

internal data class FragmentedMp4TrackMetadata(
    val id: Int,
    val timescale: Long,
    val kind: FragmentedMp4TrackKind,
    val defaultSampleDuration: Long,
    val defaultSampleSize: Long,
    val defaultSampleFlags: Long,
)

internal enum class FragmentedMp4TrackKind {
    VIDEO,
    AUDIO,
    OTHER,
}

internal data class FragmentedMp4SeekAnchor(
    val timeUs: Long,
    val position: Long,
)

/**
 * Parses an initialization segment and its first media fragment.
 *
 * The first fragment establishes the media-time origin. Subtracting that origin also cancels the
 * constant offset used by the common single-edit-list case without duplicating Media3's edit-list
 * implementation here.
 */
internal fun parseFragmentedMp4SeekMetadata(
    moov: ByteArray,
    firstMoof: ByteArray,
    firstMoofPosition: Long,
): FragmentedMp4SeekMetadata? {
    if (firstMoofPosition < 0L) return null
    val moovRoot = readIsoBox(moov, 0, moov.size) ?: return null
    if (moovRoot.type != TYPE_MOOV || moovRoot.end != moov.size) return null
    val moovChildren = directChildren(moov, moovRoot) ?: return null
    val mvex = moovChildren.firstOrNull { it.type == TYPE_MVEX } ?: return null
    val mvexChildren = directChildren(moov, mvex) ?: return null

    val defaults = LinkedHashMap<Int, TrackDefaults>()
    for (trex in mvexChildren.filter { it.type == TYPE_TREX }) {
        val payload = trex.payloadStart
        if (trex.end - payload < TREX_PAYLOAD_SIZE) return null
        val trackId = moov.readPositiveInt(payload + 4) ?: return null
        val duration = moov.readNonNegativeInt(payload + 12) ?: return null
        val size = moov.readNonNegativeInt(payload + 16) ?: return null
        val flags = moov.readUnsignedInt(payload + 20) ?: return null
        if (defaults.put(trackId, TrackDefaults(duration, size, flags)) != null) return null
    }
    if (defaults.isEmpty()) return null

    val tracks = LinkedHashMap<Int, FragmentedMp4TrackMetadata>()
    for (trak in moovChildren.filter { it.type == TYPE_TRAK }) {
        val trakChildren = directChildren(moov, trak) ?: return null
        val tkhd = trakChildren.firstOrNull { it.type == TYPE_TKHD } ?: continue
        val mdia = trakChildren.firstOrNull { it.type == TYPE_MDIA } ?: continue
        val mdiaChildren = directChildren(moov, mdia) ?: return null
        val mdhd = mdiaChildren.firstOrNull { it.type == TYPE_MDHD } ?: continue
        val hdlr = mdiaChildren.firstOrNull { it.type == TYPE_HDLR } ?: continue

        val trackId = parseTrackId(moov, tkhd) ?: continue
        val timescale = parseTimescale(moov, mdhd) ?: continue
        if (hdlr.end - hdlr.payloadStart < FULL_BOX_HEADER_SIZE + 2 * INT_SIZE) continue
        val handlerType = moov.readInt(hdlr.payloadStart + 8) ?: continue
        val trackDefaults = defaults[trackId] ?: continue
        val kind = when (handlerType) {
            TYPE_VIDE -> FragmentedMp4TrackKind.VIDEO
            TYPE_SOUN -> FragmentedMp4TrackKind.AUDIO
            else -> FragmentedMp4TrackKind.OTHER
        }
        val track = FragmentedMp4TrackMetadata(
            id = trackId,
            timescale = timescale,
            kind = kind,
            defaultSampleDuration = trackDefaults.duration,
            defaultSampleSize = trackDefaults.size,
            defaultSampleFlags = trackDefaults.flags,
        )
        if (tracks.put(trackId, track) != null) return null
    }
    if (tracks.isEmpty()) return null

    val candidates = tracks.values.filter { it.kind == FragmentedMp4TrackKind.VIDEO }
        .ifEmpty { tracks.values.filter { it.kind == FragmentedMp4TrackKind.AUDIO } }
    for (candidate in candidates) {
        val provisional = FragmentedMp4SeekMetadata(
            primaryTrack = candidate,
            tracks = tracks,
            firstMoofPosition = firstMoofPosition,
            firstPresentationTime = 0L,
        )
        val firstPresentationTime = parseFragment(
            firstMoof,
            provisional,
            firstMoofPosition,
        )?.primarySyncPresentationTime
            ?: continue
        return provisional.copy(firstPresentationTime = firstPresentationTime)
    }
    return null
}

/**
 * A single validated step in the top-level ISO-BMFF box chain.
 *
 * [nextPosition] is derived only from a box header at a position already proven to be top-level.
 * For a `moof`, it skips the complete immediately-following `mdat`; [anchor] is non-null only when
 * the fragment is independently restartable at a primary-track sync sample.
 */
internal data class FragmentedMp4TopLevelStep(
    val anchor: FragmentedMp4SeekAnchor?,
    val nextPosition: Long,
)

internal fun parseFragmentedMp4TopLevelStep(
    data: ByteArray,
    absoluteStart: Long,
    inputLength: Long,
    metadata: FragmentedMp4SeekMetadata,
): FragmentedMp4TopLevelStep? {
    if (absoluteStart < metadata.firstMoofPosition || absoluteStart >= inputLength) return null
    val header = readWindowBoxHeader(data, 0) ?: return null
    val boxSize = if (header.extendsToEnd) inputLength - absoluteStart else header.size
    if (boxSize < header.headerSize || boxSize > inputLength - absoluteStart) return null
    val boxEnd = checkedAdd(absoluteStart, boxSize) ?: return null

    // Non-fragment top-level boxes are safe to skip because absoluteStart was itself established
    // by walking the declared top-level box chain. No payload byte is ever searched for a box tag.
    if (header.type != TYPE_MOOF) {
        return FragmentedMp4TopLevelStep(anchor = null, nextPosition = boxEnd)
    }
    if (header.extendsToEnd || header.size > MAX_VALIDATED_MOOF_SIZE) return null
    val moofEnd = checkedWindowEnd(0, header.size, data.size) ?: return null
    val mdatHeader = readWindowBoxHeader(data, moofEnd) ?: return null
    if (mdatHeader.type != TYPE_MDAT) return null

    val absoluteMdatPosition = checkedAdd(absoluteStart, header.size) ?: return null
    val mdatSize = if (mdatHeader.extendsToEnd) {
        inputLength - absoluteMdatPosition
    } else {
        mdatHeader.size
    }
    if (mdatSize < mdatHeader.headerSize || mdatSize > inputLength - absoluteMdatPosition) return null
    val mediaDataStart = checkedAdd(absoluteMdatPosition, mdatHeader.headerSize) ?: return null
    val mediaDataEnd = checkedAdd(absoluteMdatPosition, mdatSize) ?: return null

    val parsed = parseFragment(
        moof = data.copyOfRange(0, moofEnd),
        metadata = metadata,
        moofPosition = absoluteStart,
    ) ?: return null
    if (parsed.sampleDataRanges.any { it.start < mediaDataStart || it.endExclusive > mediaDataEnd }) {
        return null
    }
    val anchor = parsed.primarySyncPresentationTime?.let {
        metadata.createSeekAnchor(it, absoluteStart)
    }
    return FragmentedMp4TopLevelStep(anchor = anchor, nextPosition = mediaDataEnd)
}

private data class TrackDefaults(
    val duration: Long,
    val size: Long,
    val flags: Long,
)

private data class IsoBox(
    val start: Int,
    val end: Int,
    val headerSize: Int,
    val type: Int,
) {
    val payloadStart: Int get() = start + headerSize
}

private data class WindowBoxHeader(
    val size: Long,
    val headerSize: Long,
    val type: Int,
    val extendsToEnd: Boolean,
)

private data class TrackFragmentHeader(
    val track: FragmentedMp4TrackMetadata,
    val defaultSampleDuration: Long,
    val defaultSampleSize: Long,
    val defaultSampleFlags: Long,
    val baseDataPosition: Long,
)

private data class SampleDataRange(
    val start: Long,
    val endExclusive: Long,
)

private data class ParsedFragment(
    val primarySyncPresentationTime: Long?,
    val sampleDataRanges: List<SampleDataRange>,
)

private fun parseFragment(
    moof: ByteArray,
    metadata: FragmentedMp4SeekMetadata,
    moofPosition: Long,
): ParsedFragment? {
    val root = readIsoBox(moof, 0, moof.size) ?: return null
    if (root.type != TYPE_MOOF || root.end != moof.size) return null
    val moofChildren = directChildren(moof, root) ?: return null
    val mfhd = moofChildren.firstOrNull { it.type == TYPE_MFHD } ?: return null
    if (mfhd.end - mfhd.payloadStart < FULL_BOX_HEADER_SIZE + INT_SIZE) return null

    var primaryPresentationTime: Long? = null
    val requiredTrackIds = metadata.tracks.values
        .filter { it.kind != FragmentedMp4TrackKind.OTHER }
        .mapTo(HashSet()) { it.id }
    val seenRelevantTrackIds = HashSet<Int>()
    val sampleBudget = MoofSampleBudget()
    val sampleDataRanges = ArrayList<SampleDataRange>()
    for (traf in moofChildren.filter { it.type == TYPE_TRAF }) {
        val children = directChildren(moof, traf) ?: return null
        val tfhds = children.filter { it.type == TYPE_TFHD }
        if (tfhds.size != 1) return null
        val header = parseTrackFragmentHeader(
            data = moof,
            tfhd = tfhds.single(),
            tracks = metadata.tracks,
            moofPosition = moofPosition,
        ) ?: return null
        if (header.track.kind == FragmentedMp4TrackKind.OTHER) continue
        if (!seenRelevantTrackIds.add(header.track.id)) return null

        // FragmentedMp4Extractor resets each track's accumulated decode time on seek. A random
        // access fragment therefore needs an explicit tfdt for every A/V traf it contains.
        val tfdts = children.filter { it.type == TYPE_TFDT }
        if (tfdts.size != 1) return null
        val decodeTime = parseBaseDecodeTime(moof, tfdts.single()) ?: return null
        val truns = children.filter { it.type == TYPE_TRUN }
        if (truns.isEmpty()) return null
        val parsedRuns = parseTrackRuns(
            data = moof,
            truns = truns,
            baseDecodeTime = decodeTime,
            header = header,
            moofPosition = moofPosition,
            sampleBudget = sampleBudget,
        ) ?: return null
        sampleDataRanges += parsedRuns.sampleDataRanges
        if (header.track.id == metadata.primaryTrack.id) {
            primaryPresentationTime = parsedRuns.firstSyncPresentationTime
        }
    }
    if (!seenRelevantTrackIds.containsAll(requiredTrackIds)) return null
    return ParsedFragment(primaryPresentationTime, sampleDataRanges)
}

private fun parseTrackFragmentHeader(
    data: ByteArray,
    tfhd: IsoBox,
    tracks: Map<Int, FragmentedMp4TrackMetadata>,
    moofPosition: Long,
): TrackFragmentHeader? {
    if (tfhd.end - tfhd.payloadStart < FULL_BOX_HEADER_SIZE + INT_SIZE) return null
    var cursor = tfhd.payloadStart
    val fullBox = data.readUnsignedInt(cursor) ?: return null
    cursor += INT_SIZE
    val flags = fullBox and FULL_BOX_FLAGS_MASK
    val trackId = data.readPositiveInt(cursor) ?: return null
    cursor += INT_SIZE
    val track = tracks[trackId] ?: return null

    var baseDataPosition = moofPosition
    if (flags and TFHD_BASE_DATA_OFFSET_PRESENT != 0L) {
        val nextCursor = checkedAdvance(cursor, LONG_SIZE, tfhd.end) ?: return null
        baseDataPosition = data.readUnsignedLong(cursor) ?: return null
        if (baseDataPosition < moofPosition) return null
        cursor = nextCursor
    }
    if (flags and TFHD_SAMPLE_DESCRIPTION_INDEX_PRESENT != 0L) cursor = checkedAdvance(cursor, INT_SIZE, tfhd.end) ?: return null

    var duration = track.defaultSampleDuration
    if (flags and TFHD_DEFAULT_SAMPLE_DURATION_PRESENT != 0L) {
        val nextCursor = checkedAdvance(cursor, INT_SIZE, tfhd.end) ?: return null
        duration = data.readNonNegativeInt(cursor) ?: return null
        cursor = nextCursor
    }
    var sampleSize = track.defaultSampleSize
    if (flags and TFHD_DEFAULT_SAMPLE_SIZE_PRESENT != 0L) {
        val nextCursor = checkedAdvance(cursor, INT_SIZE, tfhd.end) ?: return null
        sampleSize = data.readNonNegativeInt(cursor) ?: return null
        cursor = nextCursor
    }

    var sampleFlags = track.defaultSampleFlags
    if (flags and TFHD_DEFAULT_SAMPLE_FLAGS_PRESENT != 0L) {
        val nextCursor = checkedAdvance(cursor, INT_SIZE, tfhd.end) ?: return null
        sampleFlags = data.readUnsignedInt(cursor) ?: return null
        cursor = nextCursor
    }
    if (cursor > tfhd.end) return null
    return TrackFragmentHeader(track, duration, sampleSize, sampleFlags, baseDataPosition)
}

private fun parseBaseDecodeTime(data: ByteArray, tfdt: IsoBox): Long? {
    val payload = tfdt.payloadStart
    if (tfdt.end - payload < FULL_BOX_HEADER_SIZE + INT_SIZE) return null
    val fullBox = data.readUnsignedInt(payload) ?: return null
    val version = (fullBox ushr 24).toInt()
    return when (version) {
        0 -> data.readUnsignedInt(payload + INT_SIZE)
        1 -> if (tfdt.end - payload >= FULL_BOX_HEADER_SIZE + LONG_SIZE) {
            data.readUnsignedLong(payload + INT_SIZE)
        } else {
            null
        }
        else -> null
    }
}

private data class ParsedTrackRuns(
    val firstSyncPresentationTime: Long?,
    val sampleDataRanges: List<SampleDataRange>,
)

private class MoofSampleBudget {
    private var remaining = MAX_SAMPLES_PER_MOOF

    fun consume(sampleCount: Long): Boolean {
        if (sampleCount > remaining) return false
        remaining -= sampleCount
        return true
    }
}

private fun parseTrackRuns(
    data: ByteArray,
    truns: List<IsoBox>,
    baseDecodeTime: Long,
    header: TrackFragmentHeader,
    moofPosition: Long,
    sampleBudget: MoofSampleBudget,
): ParsedTrackRuns? {
    var decodeTime = baseDecodeTime
    var firstSyncPresentationTime: Long? = null
    var fragmentSampleIndex = 0L
    var previousRunDataEnd: Long? = null
    val sampleDataRanges = ArrayList<SampleDataRange>()
    for (trun in truns) {
        if (trun.end - trun.payloadStart < FULL_BOX_HEADER_SIZE + INT_SIZE) return null
        var cursor = trun.payloadStart
        val fullBox = data.readUnsignedInt(cursor) ?: return null
        cursor += INT_SIZE
        val flags = fullBox and FULL_BOX_FLAGS_MASK
        val sampleCount = data.readUnsignedInt(cursor) ?: return null
        cursor += INT_SIZE
        if (!sampleBudget.consume(sampleCount)) return null

        var sampleDataPosition = previousRunDataEnd ?: header.baseDataPosition
        if (flags and TRUN_DATA_OFFSET_PRESENT != 0L) {
            val nextCursor = checkedAdvance(cursor, INT_SIZE, trun.end) ?: return null
            val dataOffset = data.readSignedInt(cursor)?.toLong() ?: return null
            sampleDataPosition = checkedAdd(header.baseDataPosition, dataOffset) ?: return null
            cursor = nextCursor
        }
        if (sampleDataPosition < moofPosition) return null
        var firstSampleFlags: Long? = null
        if (flags and TRUN_FIRST_SAMPLE_FLAGS_PRESENT != 0L) {
            val nextCursor = checkedAdvance(cursor, INT_SIZE, trun.end) ?: return null
            firstSampleFlags = data.readUnsignedInt(cursor) ?: return null
            cursor = nextCursor
        }

        var sampleIndexInRun = 0L
        var sampleBytesInRun = 0L
        while (sampleIndexInRun < sampleCount) {
            val duration = if (flags and TRUN_SAMPLE_DURATION_PRESENT != 0L) {
                val nextCursor = checkedAdvance(cursor, INT_SIZE, trun.end) ?: return null
                val value = data.readNonNegativeInt(cursor) ?: return null
                cursor = nextCursor
                value
            } else {
                header.defaultSampleDuration
            }
            val sampleSize = if (flags and TRUN_SAMPLE_SIZE_PRESENT != 0L) {
                val nextCursor = checkedAdvance(cursor, INT_SIZE, trun.end) ?: return null
                val value = data.readNonNegativeInt(cursor) ?: return null
                cursor = nextCursor
                value
            } else {
                header.defaultSampleSize
            }
            sampleBytesInRun = checkedAdd(sampleBytesInRun, sampleSize) ?: return null
            val sampleFlags = if (flags and TRUN_SAMPLE_FLAGS_PRESENT != 0L) {
                val nextCursor = checkedAdvance(cursor, INT_SIZE, trun.end) ?: return null
                val value = data.readUnsignedInt(cursor) ?: return null
                cursor = nextCursor
                value
            } else if (fragmentSampleIndex == 0L && firstSampleFlags != null) {
                firstSampleFlags
            } else {
                header.defaultSampleFlags
            }
            val compositionOffset = if (flags and TRUN_COMPOSITION_TIME_OFFSET_PRESENT != 0L) {
                val nextCursor = checkedAdvance(cursor, INT_SIZE, trun.end) ?: return null
                val value = data.readSignedInt(cursor)?.toLong() ?: return null
                cursor = nextCursor
                value
            } else {
                0L
            }

            if (
                firstSyncPresentationTime == null &&
                sampleFlags and SAMPLE_IS_NON_SYNC_SAMPLE == 0L
            ) {
                firstSyncPresentationTime = checkedAdd(decodeTime, compositionOffset) ?: return null
            }
            decodeTime = checkedAdd(decodeTime, duration) ?: return null
            sampleIndexInRun++
            fragmentSampleIndex++
        }
        if (cursor > trun.end) return null
        val sampleDataEnd = checkedAdd(sampleDataPosition, sampleBytesInRun) ?: return null
        // ISO-BMFF makes a trun without data_offset continue at the end of the preceding run in
        // the same traf. Resetting every run to base_data_offset rejects valid multi-run fragments
        // and can force large-file seeking back onto the slow fallback path.
        previousRunDataEnd = sampleDataEnd
        if (sampleCount > 0L) {
            sampleDataRanges += SampleDataRange(sampleDataPosition, sampleDataEnd)
        }
    }
    return ParsedTrackRuns(firstSyncPresentationTime, sampleDataRanges)
}

private fun parseTrackId(data: ByteArray, tkhd: IsoBox): Int? {
    val payload = tkhd.payloadStart
    val version = data.getOrNull(payload)?.toInt()?.and(0xFF) ?: return null
    val offset = payload + if (version == 1) 20 else if (version == 0) 12 else return null
    if (offset > tkhd.end - INT_SIZE) return null
    return data.readPositiveInt(offset)
}

private fun parseTimescale(data: ByteArray, mdhd: IsoBox): Long? {
    val payload = mdhd.payloadStart
    val version = data.getOrNull(payload)?.toInt()?.and(0xFF) ?: return null
    val offset = payload + if (version == 1) 20 else if (version == 0) 12 else return null
    if (offset > mdhd.end - INT_SIZE) return null
    return data.readUnsignedInt(offset)?.takeIf { it > 0L }
}

private fun directChildren(data: ByteArray, container: IsoBox): List<IsoBox>? {
    val children = ArrayList<IsoBox>()
    var cursor = container.payloadStart
    while (cursor < container.end) {
        val child = readIsoBox(data, cursor, container.end) ?: return null
        children += child
        cursor = child.end
    }
    return children
}

private fun readIsoBox(data: ByteArray, offset: Int, limit: Int): IsoBox? {
    if (offset < 0 || limit < offset || limit > data.size || limit - offset < BOX_HEADER_SIZE) return null
    val compactSize = data.readUnsignedInt(offset) ?: return null
    val type = data.readInt(offset + INT_SIZE) ?: return null
    val headerSize: Int
    val size: Long
    when (compactSize) {
        0L -> {
            headerSize = BOX_HEADER_SIZE
            size = (limit - offset).toLong()
        }
        1L -> {
            if (limit - offset < LONG_BOX_HEADER_SIZE) return null
            headerSize = LONG_BOX_HEADER_SIZE
            size = data.readUnsignedLong(offset + BOX_HEADER_SIZE) ?: return null
        }
        else -> {
            headerSize = BOX_HEADER_SIZE
            size = compactSize
        }
    }
    val available = (limit - offset).toLong()
    if (size < headerSize.toLong() || size > available || size > Int.MAX_VALUE.toLong()) return null
    val end = offset + size.toInt()
    return IsoBox(offset, end, headerSize, type)
}

private fun readWindowBoxHeader(data: ByteArray, offset: Int): WindowBoxHeader? {
    if (offset < 0 || data.size - offset < BOX_HEADER_SIZE) return null
    val compactSize = data.readUnsignedInt(offset) ?: return null
    val type = data.readInt(offset + INT_SIZE) ?: return null
    return when (compactSize) {
        0L -> WindowBoxHeader(
            size = 0L,
            headerSize = BOX_HEADER_SIZE.toLong(),
            type = type,
            extendsToEnd = true,
        )
        1L -> {
            val size = data.readUnsignedLong(offset + BOX_HEADER_SIZE) ?: return null
            if (size < LONG_BOX_HEADER_SIZE) return null
            WindowBoxHeader(
                size = size,
                headerSize = LONG_BOX_HEADER_SIZE.toLong(),
                type = type,
                extendsToEnd = false,
            )
        }
        else -> {
            if (compactSize < BOX_HEADER_SIZE) return null
            WindowBoxHeader(
                size = compactSize,
                headerSize = BOX_HEADER_SIZE.toLong(),
                type = type,
                extendsToEnd = false,
            )
        }
    }
}

private fun checkedWindowEnd(start: Int, size: Long, limit: Int): Int? {
    if (size < 0L || size > Int.MAX_VALUE.toLong()) return null
    val intSize = size.toInt()
    return if (start >= 0 && start <= limit - intSize) start + intSize else null
}

private fun ByteArray.readUnsignedInt(offset: Int): Long? {
    if (offset < 0 || size - offset < INT_SIZE) return null
    return ((this[offset].toLong() and 0xFFL) shl 24) or
        ((this[offset + 1].toLong() and 0xFFL) shl 16) or
        ((this[offset + 2].toLong() and 0xFFL) shl 8) or
        (this[offset + 3].toLong() and 0xFFL)
}

private fun ByteArray.readNonNegativeInt(offset: Int): Long? =
    readUnsignedInt(offset)?.takeIf { it <= Int.MAX_VALUE.toLong() }

private fun ByteArray.readInt(offset: Int): Int? = readUnsignedInt(offset)?.toInt()

private fun ByteArray.readSignedInt(offset: Int): Int? = readInt(offset)

private fun ByteArray.readPositiveInt(offset: Int): Int? =
    readUnsignedInt(offset)?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()

private fun ByteArray.readUnsignedLong(offset: Int): Long? {
    if (offset < 0 || size - offset < LONG_SIZE || this[offset].toInt() and 0x80 != 0) return null
    var value = 0L
    repeat(LONG_SIZE) { index ->
        value = (value shl 8) or (this[offset + index].toLong() and 0xFFL)
    }
    return value
}

private fun checkedAdvance(position: Int, count: Int, limit: Int): Int? =
    if (count >= 0 && position >= 0 && position <= limit - count) position + count else null

private fun checkedAdd(left: Long, right: Long): Long? =
    if ((right > 0L && left > Long.MAX_VALUE - right) || (right < 0L && left < Long.MIN_VALUE - right)) {
        null
    } else {
        left + right
    }

private fun checkedSubtract(left: Long, right: Long): Long? =
    if ((right > 0L && left < Long.MIN_VALUE + right) ||
        (right < 0L && left > Long.MAX_VALUE + right)
    ) {
        null
    } else {
        left - right
    }

private fun scaleToMicroseconds(value: Long, timescale: Long): Long? {
    if (value < 0L || timescale <= 0L) return null
    val whole = value / timescale
    val remainder = value % timescale
    if (whole > Long.MAX_VALUE / MICROS_PER_SECOND) return null
    return whole * MICROS_PER_SECOND + (remainder * MICROS_PER_SECOND) / timescale
}

private const val BOX_HEADER_SIZE = 8
private const val LONG_BOX_HEADER_SIZE = 16
private const val FULL_BOX_HEADER_SIZE = 4
private const val INT_SIZE = 4
private const val LONG_SIZE = 8
private const val TREX_PAYLOAD_SIZE = 24
private const val MAX_SAMPLES_PER_MOOF = 250_000L
private const val MAX_VALIDATED_MOOF_SIZE = 4L * 1024L * 1024L
private const val MICROS_PER_SECOND = 1_000_000L
private const val FULL_BOX_FLAGS_MASK = 0x00FF_FFFFL

private const val TFHD_BASE_DATA_OFFSET_PRESENT = 0x01L
private const val TFHD_SAMPLE_DESCRIPTION_INDEX_PRESENT = 0x02L
private const val TFHD_DEFAULT_SAMPLE_DURATION_PRESENT = 0x08L
private const val TFHD_DEFAULT_SAMPLE_SIZE_PRESENT = 0x10L
private const val TFHD_DEFAULT_SAMPLE_FLAGS_PRESENT = 0x20L

private const val TRUN_DATA_OFFSET_PRESENT = 0x01L
private const val TRUN_FIRST_SAMPLE_FLAGS_PRESENT = 0x04L
private const val TRUN_SAMPLE_DURATION_PRESENT = 0x100L
private const val TRUN_SAMPLE_SIZE_PRESENT = 0x200L
private const val TRUN_SAMPLE_FLAGS_PRESENT = 0x400L
private const val TRUN_COMPOSITION_TIME_OFFSET_PRESENT = 0x800L
private const val SAMPLE_IS_NON_SYNC_SAMPLE = 0x0001_0000L

internal val TYPE_MOOV = fourCc("moov")
internal val TYPE_MOOF = fourCc("moof")
internal val TYPE_MDAT = fourCc("mdat")
private val TYPE_MVEX = fourCc("mvex")
private val TYPE_TREX = fourCc("trex")
private val TYPE_TRAK = fourCc("trak")
private val TYPE_TKHD = fourCc("tkhd")
private val TYPE_MDIA = fourCc("mdia")
private val TYPE_MDHD = fourCc("mdhd")
private val TYPE_HDLR = fourCc("hdlr")
private val TYPE_VIDE = fourCc("vide")
private val TYPE_SOUN = fourCc("soun")
private val TYPE_MFHD = fourCc("mfhd")
private val TYPE_TRAF = fourCc("traf")
private val TYPE_TFHD = fourCc("tfhd")
private val TYPE_TFDT = fourCc("tfdt")
private val TYPE_TRUN = fourCc("trun")

internal fun fourCc(value: String): Int {
    require(value.length == 4)
    return (value[0].code shl 24) or
        (value[1].code shl 16) or
        (value[2].code shl 8) or
        value[3].code
}
