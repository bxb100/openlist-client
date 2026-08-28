package org.openlist.mobile.media

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class FragmentedMp4SparseIndexTest {
    @Test
    fun `sync sample pts becomes a moof seek anchor`() {
        val firstMoof = moof(
            baseDecodeTime = 10_000L,
            samples = listOf(Sample(duration = 1_000, flags = 0, compositionOffset = 100)),
        )
        val metadata = parseFragmentedMp4SeekMetadata(
            moov = moov(defaultDuration = 1_000, defaultFlags = 0),
            firstMoof = firstMoof,
            firstMoofPosition = 4_096L,
        )
        val laterMoof = moof(
            baseDecodeTime = 20_000L,
            samples = listOf(
                Sample(duration = 1_000, flags = NON_SYNC, compositionOffset = 0),
                Sample(duration = 1_000, flags = 0, compositionOffset = 100),
            ),
        )

        val anchor = metadata?.parseSeekAnchor(laterMoof, 2_500_000_000L)

        assertThat(metadata).isNotNull()
        assertThat(anchor).isEqualTo(
            FragmentedMp4SeekAnchor(
                timeUs = 11_000_000L,
                position = 2_500_000_000L,
            ),
        )
    }

    @Test
    fun `tfhd duration override is used while advancing to first sync sample`() {
        val metadata = parseFragmentedMp4SeekMetadata(
            moov = moov(defaultDuration = 1_000, defaultFlags = 0),
            firstMoof = moof(0L, listOf(Sample(1_000, 0, 0))),
            firstMoofPosition = 1_024L,
        )
        val laterMoof = moof(
            baseDecodeTime = 5_000L,
            samples = listOf(
                Sample(duration = null, flags = NON_SYNC, compositionOffset = 0),
                Sample(duration = null, flags = 0, compositionOffset = 0),
            ),
            tfhdDefaultDuration = 500,
        )

        assertThat(metadata?.parseSeekAnchor(laterMoof, 9_000L)?.timeUs)
            .isEqualTo(5_500_000L)
    }

    @Test
    fun `fragment without explicit tfdt is never a random access anchor`() {
        val metadata = parseFragmentedMp4SeekMetadata(
            moov = moov(defaultDuration = 1_000, defaultFlags = 0),
            firstMoof = moof(0L, listOf(Sample(1_000, 0, 0))),
            firstMoofPosition = 1_024L,
        )

        val anchor = metadata?.parseSeekAnchor(
            moof = moof(
                baseDecodeTime = 5_000L,
                samples = listOf(Sample(1_000, 0, 0)),
                includeTfdt = false,
            ),
            moofPosition = 9_000L,
        )

        assertThat(anchor).isNull()
    }

    @Test
    fun `fragment containing no video sync sample is rejected`() {
        val metadata = parseFragmentedMp4SeekMetadata(
            moov = moov(defaultDuration = 1_000, defaultFlags = 0),
            firstMoof = moof(0L, listOf(Sample(1_000, 0, 0))),
            firstMoofPosition = 1_024L,
        )

        val anchor = metadata?.parseSeekAnchor(
            moof = moof(
                baseDecodeTime = 5_000L,
                samples = listOf(
                    Sample(1_000, NON_SYNC, 0),
                    Sample(1_000, NON_SYNC, 0),
                ),
            ),
            moofPosition = 9_000L,
        )

        assertThat(anchor).isNull()
    }

    @Test
    fun `proven top level boundary validates moof mdat chain above two gibibytes`() {
        val firstMoof = moof(0L, listOf(Sample(1_000, 0, 0)))
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = firstMoof,
                firstMoofPosition = 1_024L,
            ),
        )
        val laterMoof = selfContainedMoof(5_000L, listOf(Sample(1_000, 0, 0)))
        val declaredMdatSize = 3_000_000_000L
        val window = laterMoof + mdatHeader(declaredSize = declaredMdatSize)
        val absoluteStart = Int.MAX_VALUE.toLong() + 8_192L

        val step = parseFragmentedMp4TopLevelStep(
            data = window,
            absoluteStart = absoluteStart,
            inputLength = absoluteStart + laterMoof.size + declaredMdatSize,
            metadata = metadata,
        )

        assertThat(step?.anchor).isEqualTo(
            FragmentedMp4SeekAnchor(5_000_000L, absoluteStart),
        )
    }

    @Test
    fun `moof inside an unproven payload window is never searched`() {
        val firstMoof = moof(0L, listOf(Sample(1_000, 0, 0)))
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = firstMoof,
                firstMoofPosition = 1_024L,
            ),
        )
        val fakeMoof = selfContainedMoof(5_000L, listOf(Sample(1_000, 0, 0)))
        val payloadWindow = ByteArray(37) + fakeMoof + mdatHeader(declaredSize = 4_096L)

        assertThat(
            parseFragmentedMp4TopLevelStep(
                data = payloadWindow,
                absoluteStart = 10_000L,
                inputLength = 100_000L,
                metadata = metadata,
            )?.anchor,
        ).isNull()
    }

    @Test
    fun `sample data range outside following mdat rejects fragment`() {
        val firstMoof = moof(0L, listOf(Sample(1_000, 0, 0)))
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = firstMoof,
                firstMoofPosition = 1_024L,
            ),
        )
        val candidate = selfContainedMoof(
            baseDecodeTime = 5_000L,
            samples = listOf(Sample(duration = 1_000, flags = 0, compositionOffset = 0, size = 512)),
        )

        assertThat(
            parseFragmentedMp4TopLevelStep(
                data = candidate + mdatHeader(declaredSize = 64L),
                absoluteStart = 10_000L,
                inputLength = 10_000L + candidate.size + 64L,
                metadata = metadata,
            ),
        ).isNull()
    }

    @Test
    fun `trun without data offset continues after preceding run`() {
        val firstMoof = moof(0L, listOf(Sample(1_000, 0, 0)))
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = firstMoof,
                firstMoofPosition = 1_024L,
            ),
        )
        fun candidate(dataOffset: Int): ByteArray = moofFromTrafs(
            box(
                "traf",
                fullBox("tfhd", payload = ints(TRACK_ID)) +
                    fullBox("tfdt", payload = ints(5_000)) +
                    sampleTrun(
                        samples = listOf(Sample(1_000, 0, 0, size = 16)),
                        dataOffset = dataOffset,
                    ) +
                    sampleTrun(
                        samples = listOf(Sample(1_000, 0, 0, size = 16)),
                        dataOffset = null,
                    ),
            ),
        )
        val placeholder = candidate(dataOffset = 0)
        val moof = candidate(dataOffset = placeholder.size + 8)
        val absoluteStart = 10_000L

        val step = parseFragmentedMp4TopLevelStep(
            data = moof + mdatHeader(declaredSize = 8L + 32L),
            absoluteStart = absoluteStart,
            inputLength = absoluteStart + moof.size + 8L + 32L,
            metadata = metadata,
        )

        assertThat(step?.anchor).isEqualTo(
            FragmentedMp4SeekAnchor(timeUs = 5_000_000L, position = absoluteStart),
        )
    }

    @Test
    fun `moof-shaped payload without following mdat is ignored`() {
        val firstMoof = moof(0L, listOf(Sample(1_000, 0, 0)))
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = firstMoof,
                firstMoofPosition = 1_024L,
            ),
        )
        val fakeWindow = moof(5_000L, listOf(Sample(1_000, 0, 0))) + box("free", ByteArray(16))

        assertThat(
            parseFragmentedMp4TopLevelStep(
                data = fakeWindow,
                absoluteStart = 10_000L,
                inputLength = 100_000L,
                metadata = metadata,
            ),
        ).isNull()
    }

    @Test
    fun `anchor missing a declared audio track is rejected`() {
        val firstMoof = moofFromTrafs(
            traf(TRACK_ID, 0L, listOf(Sample(1_000, 0, 0))),
            traf(AUDIO_TRACK_ID, 0L, listOf(Sample(1_000, 0, 0))),
        )
        val metadata = parseFragmentedMp4SeekMetadata(
            moov = moov(includeAudio = true, defaultDuration = 1_000, defaultFlags = 0),
            firstMoof = firstMoof,
            firstMoofPosition = 1_024L,
        )

        val anchor = metadata?.parseSeekAnchor(
            moof = moofFromTrafs(
                traf(TRACK_ID, 5_000L, listOf(Sample(1_000, 0, 0))),
            ),
            moofPosition = 9_000L,
        )

        assertThat(metadata).isNotNull()
        assertThat(anchor).isNull()
    }

    @Test
    fun `anchor with audio track missing tfdt is rejected`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(includeAudio = true, defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = moofFromTrafs(
                    traf(TRACK_ID, 0L, listOf(Sample(1_000, 0, 0))),
                    traf(AUDIO_TRACK_ID, 0L, listOf(Sample(1_000, 0, 0))),
                ),
                firstMoofPosition = 1_024L,
            ),
        )

        val candidate = moofFromTrafs(
            traf(TRACK_ID, 5_000L, listOf(Sample(1_000, 0, 0))),
            traf(
                trackId = AUDIO_TRACK_ID,
                baseDecodeTime = 5_000L,
                samples = listOf(Sample(1_000, 0, 0)),
                includeTfdt = false,
            ),
        )

        assertThat(metadata.parseSeekAnchor(candidate, moofPosition = 9_000L)).isNull()
    }

    @Test
    fun `first sample flags is not reapplied to later truns`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = NON_SYNC),
                firstMoof = moof(0L, listOf(Sample(1_000, 0, 0))),
                firstMoofPosition = 1_024L,
            ),
        )
        val firstRun = firstSampleFlagsTrun(sampleCount = 1, firstSampleFlags = NON_SYNC)
        val secondRun = firstSampleFlagsTrun(sampleCount = 1, firstSampleFlags = 0)
        val candidate = moofFromTrafs(
            box(
                "traf",
                fullBox("tfhd", payload = ints(TRACK_ID)) +
                    fullBox("tfdt", payload = ints(5_000)) +
                    firstRun + secondRun,
            ),
        )

        assertThat(metadata.parseSeekAnchor(candidate, moofPosition = 9_000L)).isNull()
    }

    @Test
    fun `tfhd base offset before candidate moof is rejected`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = moof(0L, listOf(Sample(1_000, 0, 0))),
                firstMoofPosition = 1_024L,
            ),
        )
        val unsafeTraf = traf(
            trackId = TRACK_ID,
            baseDecodeTime = 5_000L,
            samples = listOf(Sample(1_000, 0, 0)),
            baseDataOffset = 1_000L,
        )

        assertThat(
            metadata.parseSeekAnchor(moofFromTrafs(unsafeTraf), moofPosition = 9_000L),
        ).isNull()
    }

    @Test
    fun `trun offset before candidate moof is rejected`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = moof(0L, listOf(Sample(1_000, 0, 0))),
                firstMoofPosition = 1_024L,
            ),
        )
        val unsafeTraf = traf(
            trackId = TRACK_ID,
            baseDecodeTime = 5_000L,
            samples = listOf(Sample(1_000, 0, 0)),
            dataOffset = -10_000,
        )

        assertThat(
            metadata.parseSeekAnchor(moofFromTrafs(unsafeTraf), moofPosition = 9_000L),
        ).isNull()
    }

    @Test
    fun `tfhd base plus trun offset overflow is rejected`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = moof(0L, listOf(Sample(1_000, 0, 0))),
                firstMoofPosition = 1_024L,
            ),
        )
        val candidate = moofFromTrafs(
            traf(
                trackId = TRACK_ID,
                baseDecodeTime = 5_000L,
                samples = listOf(Sample(1_000, 0, 0)),
                baseDataOffset = Long.MAX_VALUE - 4L,
                dataOffset = 16,
            ),
        )

        assertThat(metadata.parseSeekAnchor(candidate, moofPosition = 9_000L)).isNull()
    }

    @Test
    fun `version one tfdt cannot borrow missing bytes from sibling box`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = moof(0L, listOf(Sample(1_000, 0, 0))),
                firstMoofPosition = 1_024L,
            ),
        )
        val truncatedTfdt = fullBox("tfdt", version = 1, payload = ints(5_000))
        val candidate = moofFromTrafs(
            box(
                "traf",
                fullBox("tfhd", payload = ints(TRACK_ID)) +
                    truncatedTfdt +
                    fullBox("trun", flags = 0x100 or 0x400, payload = ints(1, 1_000, 0)),
            ),
        )

        assertThat(metadata.parseSeekAnchor(candidate, moofPosition = 9_000L)).isNull()
    }

    @Test
    fun `truncated trun cannot borrow sample field from sibling box`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1_000, defaultFlags = 0),
                firstMoof = moof(0L, listOf(Sample(1_000, 0, 0))),
                firstMoofPosition = 1_024L,
            ),
        )
        val truncatedTrun = fullBox("trun", flags = 0x100, payload = ints(1))
        val candidate = moofFromTrafs(
            box(
                "traf",
                fullBox("tfhd", payload = ints(TRACK_ID)) +
                    fullBox("tfdt", payload = ints(5_000)) +
                    truncatedTrun + box("free", ints(1_000)),
            ),
        )

        assertThat(metadata.parseSeekAnchor(candidate, moofPosition = 9_000L)).isNull()
    }

    @Test
    fun `sample budget applies across the whole moof`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1, defaultFlags = NON_SYNC),
                firstMoof = moof(0L, listOf(Sample(1, 0, 0))),
                firstMoofPosition = 1_024L,
            ),
        )
        val oversizedTrun = fullBox(
            "trun",
            payload = ints(250_001),
        )
        val candidate = moofFromTrafs(
            box(
                "traf",
                fullBox("tfhd", payload = ints(TRACK_ID)) +
                    fullBox("tfdt", payload = ints(5_000)) +
                    oversizedTrun,
            ),
        )

        assertThat(metadata.parseSeekAnchor(candidate, moofPosition = 9_000L)).isNull()
    }

    @Test
    fun `sample budget validates primary truns after the first sync sample`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(defaultDuration = 1, defaultFlags = 0),
                firstMoof = moof(0L, listOf(Sample(1, 0, 0))),
                firstMoofPosition = 1_024L,
            ),
        )
        val candidate = moofFromTrafs(
            traf(
                trackId = TRACK_ID,
                baseDecodeTime = 5_000L,
                samples = listOf(Sample(1, 0, 0)),
                additionalTruns = listOf(sampleCountOnlyTrun(250_000)),
            ),
        )

        assertThat(metadata.parseSeekAnchor(candidate, moofPosition = 9_000L)).isNull()
    }

    @Test
    fun `sample budget validates secondary av track truns`() {
        val metadata = checkNotNull(
            parseFragmentedMp4SeekMetadata(
                moov = moov(includeAudio = true, defaultDuration = 1, defaultFlags = 0),
                firstMoof = moofFromTrafs(
                    traf(TRACK_ID, 0L, listOf(Sample(1, 0, 0))),
                    traf(AUDIO_TRACK_ID, 0L, listOf(Sample(1, 0, 0))),
                ),
                firstMoofPosition = 1_024L,
            ),
        )
        val candidate = moofFromTrafs(
            traf(TRACK_ID, 5_000L, listOf(Sample(1, 0, 0))),
            traf(
                trackId = AUDIO_TRACK_ID,
                baseDecodeTime = 5_000L,
                samples = emptyList(),
                additionalTruns = listOf(sampleCountOnlyTrun(250_000)),
            ),
        )

        assertThat(metadata.parseSeekAnchor(candidate, moofPosition = 9_000L)).isNull()
    }

    @Test
    fun `estimated map preserves long byte offsets`() {
        val map = FragmentedMp4EstimatedSeekMap(
            durationUs = 10_000_000L,
            firstMoofPosition = 1_024L,
            inputLength = 5_000_000_000L,
        )

        val position = map.getSeekPoints(9_000_000L).first.position

        assertThat(position).isGreaterThan(Int.MAX_VALUE.toLong())
        assertThat(position).isLessThan(5_000_000_000L)
        assertThat(map.isEstimated).isTrue()
    }

    private data class Sample(
        val duration: Int?,
        val flags: Int,
        val compositionOffset: Int,
        val size: Int = 1,
    )

    private fun moov(
        defaultDuration: Int,
        defaultFlags: Int,
        includeAudio: Boolean = false,
    ): ByteArray {
        val videoTrak = trak(TRACK_ID, "vide")
        val audioTrak = if (includeAudio) trak(AUDIO_TRACK_ID, "soun") else ByteArray(0)
        val videoTrex = trex(TRACK_ID, defaultDuration, defaultFlags)
        val audioTrex = if (includeAudio) {
            trex(AUDIO_TRACK_ID, defaultDuration, defaultFlags)
        } else {
            ByteArray(0)
        }
        return box("moov", videoTrak + audioTrak + box("mvex", videoTrex + audioTrex))
    }

    private fun trak(trackId: Int, handler: String): ByteArray {
        val tkhd = fullBox(
            "tkhd",
            payload = ints(0, 0, trackId, 0),
        )
        val mdhd = fullBox(
            "mdhd",
            payload = ints(0, 0, TIMESCALE, 0),
        )
        val hdlr = fullBox(
            "hdlr",
            payload = ints(0, fourCc(handler), 0, 0),
        )
        val mdia = box("mdia", mdhd + hdlr)
        return box("trak", tkhd + mdia)
    }

    private fun trex(trackId: Int, defaultDuration: Int, defaultFlags: Int): ByteArray =
        fullBox(
            "trex",
            payload = ints(trackId, 1, defaultDuration, 0, defaultFlags),
        )

    private fun moof(
        baseDecodeTime: Long,
        samples: List<Sample>,
        tfhdDefaultDuration: Int? = null,
        includeTfdt: Boolean = true,
        dataOffset: Int? = null,
    ): ByteArray = moofFromTrafs(
        traf(
            trackId = TRACK_ID,
            baseDecodeTime = baseDecodeTime,
            samples = samples,
            tfhdDefaultDuration = tfhdDefaultDuration,
            includeTfdt = includeTfdt,
            dataOffset = dataOffset,
        ),
    )

    private fun selfContainedMoof(
        baseDecodeTime: Long,
        samples: List<Sample>,
    ): ByteArray {
        val placeholder = moof(baseDecodeTime, samples, dataOffset = 0)
        return moof(
            baseDecodeTime = baseDecodeTime,
            samples = samples,
            dataOffset = placeholder.size + 8,
        )
    }

    private fun traf(
        trackId: Int,
        baseDecodeTime: Long,
        samples: List<Sample>,
        tfhdDefaultDuration: Int? = null,
        includeTfdt: Boolean = true,
        baseDataOffset: Long? = null,
        dataOffset: Int? = null,
        additionalTruns: List<ByteArray> = emptyList(),
    ): ByteArray {
        val tfhdFlags = if (tfhdDefaultDuration != null) 0x08 else 0
        val resolvedTfhdFlags = tfhdFlags or if (baseDataOffset != null) 0x01 else 0
        val tfhdPayload = ints(trackId) +
            (baseDataOffset?.let(::longs) ?: ByteArray(0)) +
            (tfhdDefaultDuration?.let(::ints) ?: ByteArray(0))
        val tfhd = fullBox("tfhd", flags = resolvedTfhdFlags, payload = tfhdPayload)
        val tfdt = if (includeTfdt) {
            fullBox("tfdt", payload = ints(baseDecodeTime.toInt()))
        } else {
            ByteArray(0)
        }
        val durationsPresent = samples.all { it.duration != null }
        val trunFlags = 0x200 or 0x400 or 0x800 or
            (if (durationsPresent) 0x100 else 0) or
            (if (dataOffset != null) 0x01 else 0)
        val trunPayload = ByteBuffer.allocate(
            4 + (if (dataOffset != null) 4 else 0) +
                samples.sumOf { (if (durationsPresent) 4 else 0) + 4 + 4 + 4 },
        ).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(samples.size)
            if (dataOffset != null) putInt(dataOffset)
            samples.forEach { sample ->
                if (durationsPresent) putInt(checkNotNull(sample.duration))
                putInt(sample.size)
                putInt(sample.flags)
                putInt(sample.compositionOffset)
            }
        }.array()
        val trun = fullBox("trun", flags = trunFlags, payload = trunPayload)
        val trailingTruns = additionalTruns.fold(ByteArray(0), ByteArray::plus)
        return box("traf", tfhd + tfdt + trun + trailingTruns)
    }

    private fun sampleCountOnlyTrun(sampleCount: Int): ByteArray =
        fullBox("trun", payload = ints(sampleCount))

    private fun sampleTrun(samples: List<Sample>, dataOffset: Int?): ByteArray {
        val flags = 0x100 or 0x200 or 0x400 or 0x800 or
            (if (dataOffset != null) 0x01 else 0)
        val payload = ByteBuffer.allocate(
            4 + (if (dataOffset != null) 4 else 0) + samples.size * 16,
        ).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(samples.size)
            if (dataOffset != null) putInt(dataOffset)
            samples.forEach { sample ->
                putInt(checkNotNull(sample.duration))
                putInt(sample.size)
                putInt(sample.flags)
                putInt(sample.compositionOffset)
            }
        }.array()
        return fullBox("trun", flags = flags, payload = payload)
    }

    private fun firstSampleFlagsTrun(sampleCount: Int, firstSampleFlags: Int): ByteArray =
        fullBox(
            "trun",
            flags = 0x04,
            payload = ints(sampleCount, firstSampleFlags),
        )

    private fun moofFromTrafs(vararg trafs: ByteArray): ByteArray =
        box("moof", fullBox("mfhd", payload = ints(1)) + trafs.fold(ByteArray(0), ByteArray::plus))

    private fun fullBox(
        type: String,
        version: Int = 0,
        flags: Int = 0,
        payload: ByteArray,
    ): ByteArray = box(
        type,
        ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt((version shl 24) or flags)
            put(payload)
        }.array(),
    )

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(8 + payload.size)
            putInt(fourCc(type))
            put(payload)
        }.array()

    private fun mdatHeader(declaredSize: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(declaredSize.toInt())
            putInt(fourCc("mdat"))
        }.array()

    private fun ints(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN).apply {
            values.forEach(::putInt)
        }.array()

    private fun longs(vararg values: Long): ByteArray =
        ByteBuffer.allocate(values.size * 8).order(ByteOrder.BIG_ENDIAN).apply {
            values.forEach(::putLong)
        }.array()

    private companion object {
        const val TRACK_ID = 1
        const val AUDIO_TRACK_ID = 2
        const val TIMESCALE = 1_000
        const val NON_SYNC = 0x0001_0000
    }
}
