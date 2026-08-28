package org.openlist.mobile.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ChunkIndex
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

class OpenListFragmentedMp4ExtractorTest {
    @Test
    fun `estimated position is resolved to validated moof before delegate seek`() {
        val fixture = sparseFragmentedFile(
            firstMdatSize = 16L * 1024L * 1024L,
            secondMdatSize = 16L * 1024L * 1024L,
        )
        val delegate = UnseekableDelegate(fixture.firstMoofPosition, DURATION_US)
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)

        extractor.read(sparseInput(fixture, 0L), holder)
        val seekMap = checkNotNull(output.seekMap)
        val estimatedPosition = seekMap.getSeekPoints(60_000_000L).first.position
        assertThat(estimatedPosition - fixture.firstMoofPosition)
            .isGreaterThan(8L * 1024L * 1024L)
        extractor.seek(estimatedPosition, 60_000_000L)

        val visitedPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture, it) },
            isResolved = { delegate.hasReopenedSeek },
        )

        assertThat(seekMap.isSeekable).isTrue()
        assertThat(seekMap.isEstimated).isTrue()
        assertThat(visitedPositions).containsAtLeast(
            fixture.firstMoofPosition,
            fixture.secondMoofPosition,
        )
        assertThat(delegate.seeks).containsExactly(
            SeekCall(fixture.secondMoofPosition, 60_000_000L),
        )
    }

    @Test
    fun `invalid top level chain falls back to first moof never arbitrary payload`() {
        val fixture = fragmentedFile(includeSecondFragment = false)
        val delegate = UnseekableDelegate(fixture.firstMoofPosition, DURATION_US)
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(input(fixture.bytes, 0L), holder)
        val seekMap = checkNotNull(output.seekMap)
        val estimatedPosition = seekMap.getSeekPoints(100_000_000L).first.position
        extractor.seek(estimatedPosition, 100_000_000L)

        driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { input(fixture.bytes, it) },
            isResolved = { delegate.hasReopenedSeek },
        )

        assertThat(delegate.seeks).containsExactly(
            SeekCall(fixture.firstMoofPosition, 100_000_000L),
        )
        assertThat(delegate.seeks.single().position).isNotEqualTo(estimatedPosition)
    }

    @Test
    fun `seek near start resolves immediately to first validated moof`() {
        val fixture = fragmentedFile(includeSecondFragment = true)
        val delegate = UnseekableDelegate(fixture.firstMoofPosition, DURATION_US)
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(input(fixture.bytes, 0L), holder)
        val seekMap = checkNotNull(output.seekMap)
        val position = seekMap.getSeekPoints(10_000_000L).first.position

        extractor.seek(position, 10_000_000L)
        driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = position,
            inputAt = { input(fixture.bytes, it) },
            isResolved = { delegate.hasReopenedSeek },
        )

        assertThat(delegate.seeks).containsExactly(
            SeekCall(fixture.firstMoofPosition, 10_000_000L),
        )
    }

    @Test
    fun `real seekable map cancels stale demand seeker and pending seek`() {
        val fixture = fragmentedFile(includeSecondFragment = true)
        val delegate = UpgradingSeekMapDelegate(fixture.firstMoofPosition, DURATION_US)
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(input(fixture.bytes, 0L), holder)
        val estimatedMap = checkNotNull(output.seekMap)
        val estimatedPosition = estimatedMap.getSeekPoints(60_000_000L).first.position
        extractor.seek(estimatedPosition, 60_000_000L)

        val chunkIndex = ChunkIndex(
            intArrayOf(1),
            longArrayOf(fixture.secondMoofPosition),
            longArrayOf(DURATION_US),
            longArrayOf(0L),
        )
        delegate.publishSeekMap(chunkIndex)
        val result = extractor.read(input(fixture.bytes, estimatedPosition), holder)

        assertThat(output.seekMap).isSameInstanceAs(chunkIndex)
        assertThat(result).isEqualTo(Extractor.RESULT_CONTINUE)
        assertThat(delegate.readPositions).contains(estimatedPosition)
        assertThat(delegate.seeks).isEmpty()

        extractor.seek(fixture.secondMoofPosition, 60_000_000L)
        assertThat(delegate.seeks).containsExactly(
            SeekCall(fixture.secondMoofPosition, 60_000_000L),
        )
    }

    @Test
    fun `top level chain jumps across large mdat and resolves real moof`() {
        val fixture = sparseLargeFragmentedFile()
        val delegate = UnseekableDelegate(fixture.firstMoofPosition, DURATION_US)
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture, 0L), holder)
        val seekMap = checkNotNull(output.seekMap)
        val estimatedPosition = seekMap.getSeekPoints(60_000_000L).first.position

        extractor.seek(estimatedPosition, 60_000_000L)

        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture, it) },
            isResolved = { delegate.hasReopenedSeek },
        )

        assertThat(inputPositions).containsAtLeast(
            fixture.firstMoofPosition,
            fixture.secondMoofPosition,
        )
        assertThat(delegate.seeks).containsExactly(
            SeekCall(fixture.secondMoofPosition, 60_000_000L),
        )
    }

    @Test
    fun `batch ending after next moof header reopens at proven boundary`() {
        val firstMoofSize = moof(baseDecodeTime = 0L).size.toLong()
        val fixture = sparseFragmentedFile(
            firstMdatSize = 2L * 1024L * 1024L - firstMoofSize - 8L,
            secondMdatSize = 16L * 1024L * 1024L,
        )
        val delegate = UnseekableDelegate(fixture.firstMoofPosition, DURATION_US)
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture, 0L), holder)
        val seekMap = checkNotNull(output.seekMap)
        val estimatedPosition = seekMap.getSeekPoints(60_000_000L).first.position

        extractor.seek(estimatedPosition, 60_000_000L)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture, it) },
            isResolved = { delegate.hasReopenedSeek },
        )

        assertThat(inputPositions).contains(fixture.secondMoofPosition)
        assertThat(delegate.seeks).containsExactly(
            SeekCall(fixture.secondMoofPosition, 60_000_000L),
        )
    }

    @Test
    fun `complete fake moof mdat chain inside payload is never visited`() {
        val baseFixture = sparseFragmentedFile(
            firstMdatSize = 16L * 1024L * 1024L,
            secondMdatSize = 16L * 1024L * 1024L,
        )
        val fakeMoofPosition = baseFixture.firstMdatPosition + 4L * 1024L * 1024L
        val fakeBytes = moof(baseDecodeTime = 30_000L) + boxHeader("mdat", 4_096L)
        val fixture = baseFixture.copy(
            segments = baseFixture.segments + SparseSegment(fakeMoofPosition, fakeBytes),
        )
        val delegate = UnseekableDelegate(fixture.firstMoofPosition, DURATION_US)
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture, 0L), holder)
        val seekMap = checkNotNull(output.seekMap)
        val estimatedPosition = seekMap.getSeekPoints(60_000_000L).first.position

        extractor.seek(estimatedPosition, 60_000_000L)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture, it) },
            isResolved = { delegate.hasReopenedSeek },
        )

        assertThat(inputPositions).doesNotContain(fakeMoofPosition)
        assertThat(delegate.seeks).containsExactly(
            SeekCall(fixture.secondMoofPosition, 60_000_000L),
        )
    }

    @Test
    fun `far seek uses validated best effort chain within total range budget`() {
        val fixture = manySparseFragmentedFile(fragmentCount = 4_200)
        val targetTimeUs = 7_953_000_000L
        val delegate = UnseekableDelegate(
            fixture.sparse.firstMoofPosition,
            fixture.durationUs,
        )
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture.sparse, 0L), holder)
        val seekMap = checkNotNull(output.seekMap)
        val estimatedPosition = seekMap.getSeekPoints(targetTimeUs).first.position

        extractor.seek(estimatedPosition, targetTimeUs)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture.sparse, it) },
            isResolved = { delegate.hasReopenedSeek },
            maxReads = FMP4_MAX_RANGE_REQUESTS_PER_SEEK + 1,
        )

        assertThat(inputPositions.first()).isEqualTo(estimatedPosition)
        assertThat(inputPositions.size).isAtMost(FMP4_MAX_RANGE_REQUESTS_PER_SEEK)
        val resolvedPosition = delegate.seeks.single().position
        val resolvedIndex = fixture.moofPositions.indexOf(resolvedPosition)
        assertThat(resolvedIndex).isAtLeast(0)
        val resolvedTimeUs = resolvedIndex * fixture.fragmentDurationUs
        assertThat(kotlin.math.abs(resolvedTimeUs - targetTimeUs)).isAtMost(6_000_000L)
        assertThat(inputPositions).doesNotContain(fixture.sparse.firstMoofPosition)
        assertThat(inputPositions.last()).isEqualTo(resolvedPosition)
    }

    @Test
    fun `late VBR estimate self corrects by anchor interpolation within range budget`() {
        val fixture = manySparseFragmentedFile(
            fragmentCount = 4_500,
            mdatSize = 640L * 1024L,
        )
        val targetTimeUs = 6_681_598_000L
        val lateFragmentIndex = 3_390
        val estimatedPosition = fixture.moofPositions[lateFragmentIndex] - 256L * 1024L
        val delegate = UnseekableDelegate(
            fixture.sparse.firstMoofPosition,
            fixture.durationUs,
        )
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture.sparse, 0L), holder)
        checkNotNull(output.seekMap)

        extractor.seek(estimatedPosition, targetTimeUs)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture.sparse, it) },
            isResolved = { delegate.hasReopenedSeek },
            maxReads = FMP4_MAX_RANGE_REQUESTS_PER_SEEK + 1,
        )

        assertThat(fixture.sparse.inputLength).isGreaterThan(2_900_000_000L)
        assertThat(inputPositions.first()).isEqualTo(estimatedPosition)
        assertThat(inputPositions.size).isAtMost(3)
        assertThat(inputPositions).doesNotContain(fixture.sparse.firstMoofPosition)
        val expectedTargetIndex = (targetTimeUs / fixture.fragmentDurationUs).toInt()
        val correctionPosition = inputPositions[1]
        assertThat(
            kotlin.math.abs(correctionPosition - fixture.moofPositions[expectedTargetIndex]),
        ).isAtMost(2L * 1024L * 1024L)
        val resolvedIndex = fixture.moofPositions.indexOf(delegate.seeks.single().position)
        assertThat(resolvedIndex).isAtLeast(0)
        assertThat(
            kotlin.math.abs(resolvedIndex * fixture.fragmentDurationUs - targetTimeUs),
        ).isAtMost(6_000_000L)
        assertThat(inputPositions.last()).isEqualTo(delegate.seeks.single().position)
    }

    @Test
    fun `calibration chain may start 374 seconds past target and still self correct`() {
        val fixture = manySparseFragmentedFile(
            fragmentCount = 2_250,
            mdatSize = 1_280L * 1024L,
            fragmentDurationUs = 4_000_000L,
        )
        val targetTimeUs = 3_493_770_000L
        val lateFragmentIndex = 967
        val estimatedPosition = fixture.moofPositions[lateFragmentIndex] - 256L * 1024L
        val lateAnchorTimeUs = lateFragmentIndex * fixture.fragmentDurationUs
        val delegate = UnseekableDelegate(
            fixture.sparse.firstMoofPosition,
            fixture.durationUs,
        )
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture.sparse, 0L), holder)
        checkNotNull(output.seekMap)

        extractor.seek(estimatedPosition, targetTimeUs)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture.sparse, it) },
            isResolved = { delegate.hasReopenedSeek },
            maxReads = FMP4_MAX_RANGE_REQUESTS_PER_SEEK + 1,
        )

        assertThat(fixture.sparse.inputLength).isGreaterThan(2_900_000_000L)
        assertThat(lateAnchorTimeUs - targetTimeUs).isGreaterThan(374_000_000L)
        assertThat(inputPositions.first()).isEqualTo(estimatedPosition)
        assertThat(inputPositions.size).isAtMost(3)
        assertThat(inputPositions).doesNotContain(fixture.sparse.firstMoofPosition)
        val expectedTargetIndex = (targetTimeUs / fixture.fragmentDurationUs).toInt()
        assertThat(
            kotlin.math.abs(inputPositions[1] - fixture.moofPositions[expectedTargetIndex]),
        ).isAtMost(2L * 1024L * 1024L)
        val resolvedIndex = fixture.moofPositions.indexOf(delegate.seeks.single().position)
        assertThat(resolvedIndex).isAtLeast(0)
        assertThat(
            kotlin.math.abs(resolvedIndex * fixture.fragmentDurationUs - targetTimeUs),
        ).isAtMost(8_000_000L)
        assertThat(inputPositions.last()).isEqualTo(delegate.seeks.single().position)
    }

    @Test
    fun `piecewise VBR uses second correction and resolves within eight seconds`() {
        val fixture = manySparseFragmentedFile(
            fragmentCount = 2_250,
            mdatSize = 1L * 1024L * 1024L,
            fragmentDurationUs = 4_000_000L,
            mdatSizeAt = { index ->
                if (index < 500) 1L * 1024L * 1024L else 1_200L * 1024L
            },
        )
        val targetTimeUs = 3_493_770_000L
        val lateFragmentIndex = 967
        val estimatedPosition = fixture.moofPositions[lateFragmentIndex] - 256L * 1024L
        val delegate = UnseekableDelegate(
            fixture.sparse.firstMoofPosition,
            fixture.durationUs,
        )
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture.sparse, 0L), holder)
        checkNotNull(output.seekMap)

        extractor.seek(estimatedPosition, targetTimeUs)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture.sparse, it) },
            isResolved = { delegate.hasReopenedSeek },
            maxReads = FMP4_MAX_RANGE_REQUESTS_PER_SEEK + 1,
        )

        assertThat(inputPositions).hasSize(4)
        assertThat(inputPositions).doesNotContain(fixture.sparse.firstMoofPosition)
        assertThat(inputPositions[1]).isNotEqualTo(inputPositions[2])
        val resolvedIndex = fixture.moofPositions.indexOf(delegate.seeks.single().position)
        assertThat(resolvedIndex).isAtLeast(0)
        assertThat(
            kotlin.math.abs(resolvedIndex * fixture.fragmentDurationUs - targetTimeUs),
        ).isAtMost(8_000_000L)
        assertThat(inputPositions.last()).isEqualTo(delegate.seeks.single().position)
    }

    @Test
    fun `piecewise VBR may use third correction while discovery budget remains`() {
        val fixture = manySparseFragmentedFile(
            fragmentCount = 2_250,
            mdatSize = 1L * 1024L * 1024L,
            fragmentDurationUs = 4_000_000L,
            mdatSizeAt = { index ->
                if (index < 500) 1L * 1024L * 1024L else 1_536L * 1024L
            },
        )
        val targetTimeUs = 3_113_195_000L
        val lateFragmentIndex = 885
        val estimatedPosition = fixture.moofPositions[lateFragmentIndex] - 256L * 1024L
        val delegate = UnseekableDelegate(
            fixture.sparse.firstMoofPosition,
            fixture.durationUs,
        )
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture.sparse, 0L), holder)
        checkNotNull(output.seekMap)

        extractor.seek(estimatedPosition, targetTimeUs)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture.sparse, it) },
            isResolved = { delegate.hasReopenedSeek },
            maxReads = FMP4_MAX_RANGE_REQUESTS_PER_SEEK + 1,
        )

        // Four distinct discovery positions prove that a third correction was allowed.
        assertThat(inputPositions).hasSize(5)
        assertThat(inputPositions.size).isAtMost(FMP4_MAX_RANGE_REQUESTS_PER_SEEK)
        assertThat(inputPositions.dropLast(1).toSet()).hasSize(4)
        assertThat(inputPositions).doesNotContain(fixture.sparse.firstMoofPosition)
        val resolvedIndex = fixture.moofPositions.indexOf(delegate.seeks.single().position)
        assertThat(resolvedIndex).isAtLeast(0)
        assertThat(
            kotlin.math.abs(resolvedIndex * fixture.fragmentDurationUs - targetTimeUs),
        ).isAtMost(8_000_000L)
        assertThat(inputPositions.last()).isEqualTo(delegate.seeks.single().position)
    }

    @Test
    fun `bounded probe rejects a three fragment fake chain inside mdat payload`() {
        val base = manySparseFragmentedFile(fragmentCount = 4_200)
        val targetTimeUs = 7_953_000_000L
        val containingFragment = (targetTimeUs / base.fragmentDurationUs).toInt()
        val fakeMoofPosition = base.moofPositions[containingFragment] + 700L * 1024L
        val fakeMdatSize = 4_096L
        val firstFakeMoof = moof(
            baseDecodeTime = targetTimeUs * TIMESCALE / 1_000_000L,
            sequenceNumber = 100,
        )
        val secondFakePosition = fakeMoofPosition + firstFakeMoof.size + fakeMdatSize
        val secondFakeMoof = moof(
            baseDecodeTime = (targetTimeUs + base.fragmentDurationUs) * TIMESCALE / 1_000_000L,
            sequenceNumber = 101,
        )
        val thirdFakePosition = secondFakePosition + secondFakeMoof.size + fakeMdatSize
        val thirdFakeMoof = moof(
            baseDecodeTime = (targetTimeUs + 2L * base.fragmentDurationUs) *
                TIMESCALE / 1_000_000L,
            sequenceNumber = 777,
        )
        val fixture = base.copy(
            sparse = base.sparse.copy(
                segments = base.sparse.segments + listOf(
                    SparseSegment(
                        position = fakeMoofPosition,
                        bytes = firstFakeMoof + boxHeader("mdat", fakeMdatSize),
                    ),
                    SparseSegment(
                        position = secondFakePosition,
                        bytes = secondFakeMoof + boxHeader("mdat", fakeMdatSize),
                    ),
                    SparseSegment(
                        position = thirdFakePosition,
                        bytes = thirdFakeMoof + boxHeader("mdat", fakeMdatSize),
                    ),
                ),
            ),
        )
        val delegate = UnseekableDelegate(
            fixture.sparse.firstMoofPosition,
            fixture.durationUs,
        )
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture.sparse, 0L), holder)
        val estimatedPosition = checkNotNull(output.seekMap).getSeekPoints(targetTimeUs).first.position

        extractor.seek(estimatedPosition, targetTimeUs)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture.sparse, it) },
            isResolved = { delegate.hasReopenedSeek },
            maxReads = FMP4_MAX_RANGE_REQUESTS_PER_SEEK + 1,
        )

        assertThat(inputPositions.first()).isEqualTo(estimatedPosition)
        assertThat(inputPositions.size).isAtMost(FMP4_MAX_RANGE_REQUESTS_PER_SEEK)
        // The seed is deliberately plausible and B is sequence-contiguous. C breaks the mfhd
        // progression, so the full three-fragment chain must not become a delegate position.
        assertThat(inputPositions).doesNotContain(secondFakePosition)
        assertThat(inputPositions).contains(thirdFakePosition)
        assertThat(delegate.seeks.single().position).isIn(fixture.moofPositions)
        assertThat(delegate.seeks.single().position).isNotEqualTo(fakeMoofPosition)
        assertThat(delegate.seeks.single().position).isNotEqualTo(secondFakePosition)
        assertThat(delegate.seeks.single().position).isNotEqualTo(thirdFakePosition)
        assertThat(inputPositions.last()).isEqualTo(delegate.seeks.single().position)
    }

    @Test
    fun `bounded probe rejects fake chain whose second mdat extends to eof`() {
        val base = manySparseFragmentedFile(fragmentCount = 4_200)
        val targetTimeUs = 7_953_000_000L
        val containingFragment = (targetTimeUs / base.fragmentDurationUs).toInt()
        val firstFakePosition = base.moofPositions[containingFragment] + 700L * 1024L
        val firstFakeMdatSize = 4_096L
        val firstFakeMoof = moof(
            baseDecodeTime = targetTimeUs * TIMESCALE / 1_000_000L,
            sequenceNumber = 100,
        )
        val secondFakePosition = firstFakePosition + firstFakeMoof.size + firstFakeMdatSize
        val secondFakeMoof = moof(
            baseDecodeTime = (targetTimeUs + base.fragmentDurationUs) * TIMESCALE / 1_000_000L,
            sequenceNumber = 101,
        )
        val fixture = base.copy(
            sparse = base.sparse.copy(
                segments = base.sparse.segments + listOf(
                    SparseSegment(
                        position = firstFakePosition,
                        bytes = firstFakeMoof + boxHeader("mdat", firstFakeMdatSize),
                    ),
                    SparseSegment(
                        position = secondFakePosition,
                        bytes = secondFakeMoof + extendsToEndBoxHeader("mdat"),
                    ),
                ),
            ),
        )
        val delegate = UnseekableDelegate(
            fixture.sparse.firstMoofPosition,
            fixture.durationUs,
        )
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture.sparse, 0L), holder)
        val estimatedPosition = checkNotNull(output.seekMap).getSeekPoints(targetTimeUs).first.position

        extractor.seek(estimatedPosition, targetTimeUs)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture.sparse, it) },
            isResolved = { delegate.hasReopenedSeek },
            maxReads = FMP4_MAX_RANGE_REQUESTS_PER_SEEK + 1,
        )

        assertThat(inputPositions.size).isAtMost(FMP4_MAX_RANGE_REQUESTS_PER_SEEK)
        assertThat(inputPositions).doesNotContain(secondFakePosition)
        assertThat(delegate.seeks.single().position).isIn(fixture.moofPositions)
        assertThat(inputPositions.last()).isEqualTo(delegate.seeks.single().position)
    }

    @Test
    fun `fragment anchor beyond declared duration is never handed to delegate`() {
        val fixture = fragmentedFile(
            includeSecondFragment = true,
            secondBaseDecodeTime = 180_000L,
        )
        val delegate = UnseekableDelegate(fixture.firstMoofPosition, DURATION_US)
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(input(fixture.bytes, 0L), holder)
        val seekMap = checkNotNull(output.seekMap)
        val targetTimeUs = 100_000_000L
        val estimatedPosition = seekMap.getSeekPoints(targetTimeUs).first.position

        extractor.seek(estimatedPosition, targetTimeUs)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { input(fixture.bytes, it) },
            isResolved = { delegate.hasReopenedSeek },
            maxReads = FMP4_MAX_RANGE_REQUESTS_PER_SEEK + 1,
        )

        assertThat(inputPositions.size).isAtMost(FMP4_MAX_RANGE_REQUESTS_PER_SEEK)
        assertThat(delegate.seeks).containsExactly(
            SeekCall(fixture.firstMoofPosition, targetTimeUs),
        )
        assertThat(inputPositions.last()).isEqualTo(fixture.firstMoofPosition)
    }

    @Test
    fun `validated moof larger than probe stride is found across window boundary`() {
        val fixture = crossWindowLargeMoofFixture()
        val delegate = UnseekableDelegate(
            fixture.sparse.firstMoofPosition,
            fixture.durationUs,
        )
        val extractor = OpenListFragmentedMp4Extractor(delegate)
        val output = CapturingOutput()
        val holder = PositionHolder()
        extractor.init(output)
        extractor.read(sparseInput(fixture.sparse, 0L), holder)
        checkNotNull(output.seekMap)
        val secondMoofPosition = fixture.moofPositions[1]
        val estimatedPosition = secondMoofPosition - (2L * 1024L * 1024L - 64L)
        val targetTimeUs = 120_000_000L
        extractor.seek(estimatedPosition, targetTimeUs)
        val inputPositions = driveSeek(
            extractor = extractor,
            holder = holder,
            initialPosition = estimatedPosition,
            inputAt = { sparseInput(fixture.sparse, it) },
            isResolved = { delegate.hasReopenedSeek },
            maxReads = FMP4_MAX_RANGE_REQUESTS_PER_SEEK + 1,
        )

        assertThat(inputPositions).hasSize(2)
        assertThat(inputPositions[1]).isEqualTo(fixture.moofPositions[2])
        assertThat(inputPositions).doesNotContain(fixture.moofPositions[3])
        assertThat(inputPositions).doesNotContain(secondMoofPosition)
        assertThat(delegate.seeks.single().position).isEqualTo(fixture.moofPositions[2])
        assertThat(inputPositions.last()).isEqualTo(fixture.moofPositions[2])
    }

    private fun fragmentedFile(
        includeSecondFragment: Boolean,
        secondBaseDecodeTime: Long = 60_000L,
    ): Fixture {
        val ftyp = box("ftyp", ints(fourCc("iso6"), 0))
        val moov = moov()
        val firstMoof = moof(baseDecodeTime = 0L, sequenceNumber = 1)
        val firstMoofPosition = (ftyp.size + moov.size).toLong()
        val firstMdat = box(
            "mdat",
            ByteArray(if (includeSecondFragment) 2_048 else 12 * 1024 * 1024),
        )
        if (!includeSecondFragment) {
            return Fixture(
                bytes = ftyp + moov + firstMoof + firstMdat,
                firstMoofPosition = firstMoofPosition,
                secondMoofPosition = -1L,
            )
        }
        val secondMoof = moof(baseDecodeTime = secondBaseDecodeTime, sequenceNumber = 2)
        val secondMoofPosition = firstMoofPosition + firstMoof.size + firstMdat.size
        val secondMdat = box("mdat", ByteArray(2_048))
        return Fixture(
            bytes = ftyp + moov + firstMoof + firstMdat + secondMoof + secondMdat,
            firstMoofPosition = firstMoofPosition,
            secondMoofPosition = secondMoofPosition,
        )
    }

    private fun input(bytes: ByteArray, startPosition: Long): DefaultExtractorInput {
        var sourcePosition = startPosition.toInt()
        return DefaultExtractorInput(
            DataReader { target, offset, length ->
                if (length == 0) {
                    0
                } else if (sourcePosition >= bytes.size) {
                    C.RESULT_END_OF_INPUT
                } else {
                    val readLength = minOf(length, bytes.size - sourcePosition)
                    bytes.copyInto(target, offset, sourcePosition, sourcePosition + readLength)
                    sourcePosition += readLength
                    readLength
                }
            },
            startPosition,
            bytes.size.toLong(),
        )
    }

    private fun sparseLargeFragmentedFile(): SparseFixture {
        return sparseFragmentedFile(
            firstMdatSize = 80L * 1024L * 1024L,
            secondMdatSize = 16L * 1024L * 1024L,
        )
    }

    private fun sparseFragmentedFile(
        firstMdatSize: Long,
        secondMdatSize: Long,
    ): SparseFixture {
        val ftyp = box("ftyp", ints(fourCc("iso6"), 0))
        val moov = moov()
        val firstMoof = moof(baseDecodeTime = 0L, sequenceNumber = 1)
        val firstMoofPosition = (ftyp.size + moov.size).toLong()
        val firstMdatPosition = firstMoofPosition + firstMoof.size
        val secondMoofPosition = firstMdatPosition + firstMdatSize
        val secondMoof = moof(baseDecodeTime = 60_000L, sequenceNumber = 2)
        val secondMdatPosition = secondMoofPosition + secondMoof.size
        return SparseFixture(
            inputLength = secondMdatPosition + secondMdatSize,
            segments = listOf(
                SparseSegment(
                    position = 0L,
                    bytes = ftyp + moov + firstMoof + boxHeader("mdat", firstMdatSize),
                ),
                SparseSegment(
                    position = secondMoofPosition,
                    bytes = secondMoof + boxHeader("mdat", secondMdatSize),
                ),
            ),
            firstMoofPosition = firstMoofPosition,
            firstMdatPosition = firstMdatPosition,
            secondMoofPosition = secondMoofPosition,
        )
    }

    private fun manySparseFragmentedFile(
        fragmentCount: Int,
        mdatSize: Long = 1L * 1024L * 1024L,
        fragmentDurationUs: Long = 2_000_000L,
        mdatSizeAt: ((Int) -> Long)? = null,
    ): ManySparseFixture {
        require(fragmentCount >= 4)
        require(mdatSize >= 8L)
        require(fragmentDurationUs > 0L)
        val decodeTimeStep = fragmentDurationUs * TIMESCALE / 1_000_000L
        require(decodeTimeStep > 0L && decodeTimeStep * 1_000_000L == fragmentDurationUs * TIMESCALE)
        val ftyp = box("ftyp", ints(fourCc("iso6"), 0))
        val moov = moov()
        val moofPositions = ArrayList<Long>(fragmentCount)
        val segments = ArrayList<SparseSegment>(fragmentCount)
        var position = (ftyp.size + moov.size).toLong()
        var firstMdatPosition = -1L

        repeat(fragmentCount) { index ->
            val fragmentMdatSize = mdatSizeAt?.invoke(index) ?: mdatSize
            require(fragmentMdatSize >= 8L)
            val fragment = moof(
                baseDecodeTime = index * decodeTimeStep,
                sequenceNumber = index + 1,
            )
            moofPositions += position
            val prefix = fragment + boxHeader("mdat", fragmentMdatSize)
            segments += if (index == 0) {
                SparseSegment(position = 0L, bytes = ftyp + moov + prefix)
            } else {
                SparseSegment(position = position, bytes = prefix)
            }
            if (index == 0) firstMdatPosition = position + fragment.size
            position += fragment.size + fragmentMdatSize
        }

        return ManySparseFixture(
            sparse = SparseFixture(
                inputLength = position,
                segments = segments,
                firstMoofPosition = moofPositions.first(),
                firstMdatPosition = firstMdatPosition,
                secondMoofPosition = moofPositions[1],
            ),
            moofPositions = moofPositions,
            durationUs = fragmentCount * fragmentDurationUs,
            fragmentDurationUs = fragmentDurationUs,
        )
    }

    private fun crossWindowLargeMoofFixture(): LargeMoofFixture {
        val ftyp = box("ftyp", ints(fourCc("iso6"), 0))
        val moov = moov()
        val fragments = listOf(
            moof(baseDecodeTime = 0L, sequenceNumber = 1),
            moof(
                baseDecodeTime = 60_000L,
                paddingBytes = 3 * 1024 * 1024,
                sequenceNumber = 2,
            ),
            moof(baseDecodeTime = 120_000L, sequenceNumber = 3),
            moof(baseDecodeTime = 180_000L, sequenceNumber = 4),
        )
        val mdatSizes = listOf(
            3L * 1024L * 1024L,
            128L * 1024L,
            128L * 1024L,
            128L * 1024L,
        )
        val positions = ArrayList<Long>(fragments.size)
        val segments = ArrayList<SparseSegment>(fragments.size)
        var position = (ftyp.size + moov.size).toLong()
        fragments.forEachIndexed { index, fragment ->
            positions += position
            val prefix = fragment + boxHeader("mdat", mdatSizes[index])
            segments += if (index == 0) {
                SparseSegment(0L, ftyp + moov + prefix)
            } else {
                SparseSegment(position, prefix)
            }
            position += fragment.size + mdatSizes[index]
        }
        return LargeMoofFixture(
            sparse = SparseFixture(
                inputLength = position,
                segments = segments,
                firstMoofPosition = positions.first(),
                firstMdatPosition = positions.first() + fragments.first().size,
                secondMoofPosition = positions[1],
            ),
            moofPositions = positions,
            durationUs = 240_000_000L,
        )
    }

    private fun sparseInput(fixture: SparseFixture, startPosition: Long): DefaultExtractorInput {
        var sourcePosition = startPosition
        return DefaultExtractorInput(
            DataReader { target, offset, length ->
                if (length == 0) {
                    0
                } else if (sourcePosition >= fixture.inputLength) {
                    C.RESULT_END_OF_INPUT
                } else {
                    val readLength = minOf(length.toLong(), fixture.inputLength - sourcePosition)
                        .toInt()
                    target.fill(0, offset, offset + readLength)
                    val readEnd = sourcePosition + readLength
                    fixture.segments.forEach { segment ->
                        val overlapStart = maxOf(sourcePosition, segment.position)
                        val overlapEnd = minOf(readEnd, segment.position + segment.bytes.size)
                        if (overlapStart < overlapEnd) {
                            segment.bytes.copyInto(
                                destination = target,
                                destinationOffset = offset + (overlapStart - sourcePosition).toInt(),
                                startIndex = (overlapStart - segment.position).toInt(),
                                endIndex = (overlapEnd - segment.position).toInt(),
                            )
                        }
                    }
                    sourcePosition = readEnd
                    readLength
                }
            },
            startPosition,
            fixture.inputLength,
        )
    }

    private fun driveSeek(
        extractor: OpenListFragmentedMp4Extractor,
        holder: PositionHolder,
        initialPosition: Long,
        inputAt: (Long) -> ExtractorInput,
        isResolved: () -> Boolean,
        maxReads: Int = 12,
    ): List<Long> {
        val visitedPositions = ArrayList<Long>()
        var nextPosition = initialPosition
        repeat(maxReads) {
            if (isResolved()) return visitedPositions
            visitedPositions += nextPosition
            val result = extractor.read(inputAt(nextPosition), holder)
            if (!isResolved()) {
                assertThat(result).isEqualTo(Extractor.RESULT_SEEK)
                nextPosition = holder.position
            }
        }
        assertThat(isResolved()).isTrue()
        return visitedPositions
    }

    private fun moov(): ByteArray {
        val tkhd = fullBox("tkhd", payload = ints(0, 0, TRACK_ID, 0))
        val mdhd = fullBox("mdhd", payload = ints(0, 0, TIMESCALE, 0))
        val hdlr = fullBox("hdlr", payload = ints(0, fourCc("vide"), 0, 0))
        val trak = box("trak", tkhd + box("mdia", mdhd + hdlr))
        val trex = fullBox("trex", payload = ints(TRACK_ID, 1, 1_000, 0, 0))
        return box("moov", trak + box("mvex", trex))
    }

    private fun moof(
        baseDecodeTime: Long,
        paddingBytes: Int = 0,
        sequenceNumber: Int = 1,
    ): ByteArray {
        val padding = if (paddingBytes == 0) ByteArray(0) else box("free", ByteArray(paddingBytes))
        fun build(dataOffset: Int): ByteArray {
            val tfhd = fullBox("tfhd", payload = ints(TRACK_ID))
            val tfdt = fullBox("tfdt", payload = ints(baseDecodeTime.toInt()))
            val trun = fullBox(
                "trun",
                flags = 0x01 or 0x100 or 0x200 or 0x400,
                payload = ints(1, dataOffset, 1_000, 1, 0),
            )
            return box(
                "moof",
                fullBox("mfhd", payload = ints(sequenceNumber)) +
                    padding + box("traf", tfhd + tfdt + trun),
            )
        }

        val placeholder = build(dataOffset = 0)
        return build(dataOffset = placeholder.size + 8)
    }

    private fun fullBox(type: String, flags: Int = 0, payload: ByteArray): ByteArray =
        box(
            type,
            ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(flags)
                put(payload)
            }.array(),
        )

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(8 + payload.size)
            putInt(fourCc(type))
            put(payload)
        }.array()

    private fun boxHeader(type: String, size: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            require(size in 8..0xFFFF_FFFFL)
            putInt(size.toInt())
            putInt(fourCc(type))
        }.array()

    private fun extendsToEndBoxHeader(type: String): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0)
            putInt(fourCc(type))
        }.array()

    private fun ints(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN).apply {
            values.forEach { putInt(it) }
        }.array()

    private data class Fixture(
        val bytes: ByteArray,
        val firstMoofPosition: Long,
        val secondMoofPosition: Long,
    )

    private data class SparseFixture(
        val inputLength: Long,
        val segments: List<SparseSegment>,
        val firstMoofPosition: Long,
        val firstMdatPosition: Long,
        val secondMoofPosition: Long,
    )

    private data class ManySparseFixture(
        val sparse: SparseFixture,
        val moofPositions: List<Long>,
        val durationUs: Long,
        val fragmentDurationUs: Long,
    )

    private data class LargeMoofFixture(
        val sparse: SparseFixture,
        val moofPositions: List<Long>,
        val durationUs: Long,
    )

    private data class SparseSegment(
        val position: Long,
        val bytes: ByteArray,
    )

    private data class SeekCall(val position: Long, val timeUs: Long)

    private class UpgradingSeekMapDelegate(
        private val firstMoofPosition: Long,
        private val durationUs: Long,
    ) : Extractor {
        private lateinit var output: ExtractorOutput
        private var initialMapSent = false
        val readPositions = mutableListOf<Long>()
        val seeks = mutableListOf<SeekCall>()

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            readPositions += input.position
            if (!initialMapSent) {
                output.seekMap(SeekMap.Unseekable(durationUs, firstMoofPosition))
                initialMapSent = true
            }
            return Extractor.RESULT_CONTINUE
        }

        fun publishSeekMap(seekMap: SeekMap) {
            output.seekMap(seekMap)
        }

        override fun seek(position: Long, timeUs: Long) {
            seeks += SeekCall(position, timeUs)
        }

        override fun release() = Unit
    }

    private class UnseekableDelegate(
        private val firstMoofPosition: Long,
        private val durationUs: Long,
    ) : Extractor {
        private lateinit var output: ExtractorOutput
        private var mapSent = false
        private var pendingReopenPosition: Long? = null
        val seeks = mutableListOf<SeekCall>()
        var hasReopenedSeek = false
            private set

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            if (pendingReopenPosition == input.position) {
                hasReopenedSeek = true
                pendingReopenPosition = null
            }
            if (!mapSent) {
                output.seekMap(SeekMap.Unseekable(durationUs, firstMoofPosition))
                mapSent = true
            }
            return Extractor.RESULT_CONTINUE
        }

        override fun seek(position: Long, timeUs: Long) {
            seeks += SeekCall(position, timeUs)
            pendingReopenPosition = position
        }

        override fun release() = Unit
    }

    private class CapturingOutput : ExtractorOutput {
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput = error("No tracks in fake delegate")

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private companion object {
        const val TRACK_ID = 1
        const val TIMESCALE = 1_000
        const val DURATION_US = 120_000_000L
    }
}
