@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.util.Log
import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import java.io.IOException

/**
 * Adds bounded, on-demand seeking to fragmented MP4 files that contain neither sidx nor mfra.
 *
 * The estimated positions published by [FragmentedMp4EstimatedSeekMap] are consumed only by this
 * wrapper. They are never passed directly to the FongMi extractor. A distant seek first performs a
 * small, bounded search around Media3's estimated byte position. With neither sidx nor mfra an
 * arbitrary offset cannot be proven top-level without walking from the file start, so the fast path
 * is explicitly best-effort for non-adversarial media: it requires three complete fragments with
 * matching tracks, sync samples, bounded media times, and consecutive mfhd sequence numbers. A
 * bounded proven-boundary walk remains the safe fallback instead of an unbounded whole-file scan.
 */
internal class OpenListFragmentedMp4Extractor(
    delegate: Extractor,
) : ForwardingExtractor(delegate) {
    private var activeInput: ExtractorInput? = null
    private var initializationInspected = false
    private var initializationMetadata: FragmentedMp4SeekMetadata? = null
    private var demandSeeker: FragmentedMp4DemandSeeker? = null
    private var pendingSeekTimeUs = C.TIME_UNSET

    override fun init(output: ExtractorOutput) {
        super.init(
            object : ForwardingExtractorOutput(output) {
                override fun seekMap(seekMap: SeekMap) {
                    super.seekMap(maybeCreateEstimatedSeekMap(seekMap))
                }
            },
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!initializationInspected && input.position == 0L) {
            initializationMetadata = input.peekFragmentedMp4SeekMetadata()
            initializationInspected = true
        }

        val seeker = demandSeeker
        if (seeker != null && pendingSeekTimeUs != C.TIME_UNSET) {
            if (seeker.isSeeking) {
                val result = seeker.continueSeek(input, seekPosition)
                if (seeker.isSeeking) return result
            }
            val resolvedPosition = seeker.consumeResolvedPosition()
            val seekTimeUs = pendingSeekTimeUs
            pendingSeekTimeUs = C.TIME_UNSET
            if (resolvedPosition != null && seekTimeUs != C.TIME_UNSET) {
                super.seek(resolvedPosition, seekTimeUs)
                if (input.position != resolvedPosition) {
                    seekPosition.position = resolvedPosition
                    return Extractor.RESULT_SEEK
                }
                input.resetPeekPosition()
            }
        }

        activeInput = input
        return try {
            super.read(input, seekPosition)
        } finally {
            activeInput = null
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        val seeker = demandSeeker
        if (
            seeker != null &&
            timeUs > 0L &&
            position >= seeker.firstMoofPosition &&
            position < seeker.inputLength
        ) {
            pendingSeekTimeUs = timeUs
            seeker.startSeek(timeUs, position)
        } else {
            pendingSeekTimeUs = C.TIME_UNSET
            seeker?.cancelSeek()
            super.seek(position, timeUs)
        }
    }

    override fun release() {
        pendingSeekTimeUs = C.TIME_UNSET
        demandSeeker?.cancelSeek()
        super.release()
    }

    private fun maybeCreateEstimatedSeekMap(original: SeekMap): SeekMap {
        if (original.isSeekable || original.durationUs <= 0L || original.durationUs == C.TIME_UNSET) {
            disableDemandSeeking()
            return original
        }
        val input = activeInput ?: return original
        val inputLength = input.length
        val metadata = initializationMetadata ?: return original
        val delegateStartPosition = original.getSeekPoints(0L).first.position
        if (
            inputLength <= metadata.firstMoofPosition ||
            delegateStartPosition != metadata.firstMoofPosition
        ) {
            disableDemandSeeking()
            return original
        }

        val seeker = FragmentedMp4DemandSeeker(
            metadata = metadata,
            durationUs = original.durationUs,
            inputLength = inputLength,
        )
        demandSeeker = seeker
        Log.d(
            FMP4_SEEK_LOG_TAG,
            "enabled=true, durationUs=${original.durationUs}, inputLength=$inputLength, " +
                "firstMoof=${metadata.firstMoofPosition}",
        )
        return seeker.seekMap
    }

    private fun disableDemandSeeking() {
        pendingSeekTimeUs = C.TIME_UNSET
        demandSeeker?.cancelSeek()
        demandSeeker = null
    }
}

/** Estimated map whose byte positions are valid only through [OpenListFragmentedMp4Extractor]. */
internal class FragmentedMp4EstimatedSeekMap(
    private val durationUs: Long,
    private val firstMoofPosition: Long,
    private val inputLength: Long,
) : SeekMap {
    override fun isSeekable(): Boolean = true

    override fun isEstimated(): Boolean = true

    override fun getDurationUs(): Long = durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val clampedTimeUs = timeUs.coerceIn(0L, durationUs)
        if (clampedTimeUs == 0L) {
            return SeekMap.SeekPoints(SeekPoint(0L, firstMoofPosition))
        }
        val mediaBytes = inputLength - firstMoofPosition
        val byteOffset = (mediaBytes.toDouble() * clampedTimeUs.toDouble() / durationUs.toDouble())
            .toLong()
            .coerceIn(0L, mediaBytes)
        val interpolated = firstMoofPosition + byteOffset
        val estimatedPosition = interpolated.coerceIn(firstMoofPosition, inputLength - 1L)
        return SeekMap.SeekPoints(SeekPoint(clampedTimeUs, estimatedPosition))
    }
}

/**
 * Reads just enough from a proven top-level boundary to validate one fragment. When a complete
 * short box chain fits in the batch limit, the extra bytes let one Range request validate several
 * adjacent fragments. Large mdats are represented only by their header and are jumped directly.
 */
private fun ExtractorInput.peekFragmentedMp4TopLevelWindow(inputLength: Long): ByteArray? {
    resetPeekPosition()
    return try {
        val startPosition = position
        if (startPosition < 0L || startPosition >= inputLength) return null
        val remaining = inputLength - startPosition
        if (remaining < FMP4_BOX_HEADER_BYTES) return null

        val topHeader = ByteArray(FMP4_LONG_BOX_HEADER_BYTES)
        if (!peekFully(topHeader, 0, FMP4_BOX_HEADER_BYTES, true)) return null
        val compactSize = topHeader.readUnsignedIntAt(0) ?: return null
        val type = topHeader.readIntAt(4) ?: return null
        var topHeaderSize = FMP4_BOX_HEADER_BYTES.toLong()
        val topBoxSize = when (compactSize) {
            0L -> remaining
            1L -> {
                if (!peekFully(
                        topHeader,
                        FMP4_BOX_HEADER_BYTES,
                        FMP4_LONG_BOX_HEADER_BYTES - FMP4_BOX_HEADER_BYTES,
                        true,
                    )
                ) {
                    return null
                }
                topHeaderSize = FMP4_LONG_BOX_HEADER_BYTES.toLong()
                topHeader.readUnsignedLongAt(FMP4_BOX_HEADER_BYTES) ?: return null
            }

            else -> compactSize
        }
        if (topBoxSize < topHeaderSize || topBoxSize > remaining) return null

        var minimumBytes = topHeaderSize
        var completeStepBytes = topBoxSize
        if (type == TYPE_MOOF) {
            if (compactSize == 0L || topBoxSize > FMP4_MAX_MOOF_BYTES) return null
            val compactMdatEnd = checkedAddFmp4(topBoxSize, FMP4_BOX_HEADER_BYTES.toLong())
                ?: return null
            if (compactMdatEnd > remaining || compactMdatEnd > Int.MAX_VALUE) return null

            val prefix = ByteArray((topBoxSize + FMP4_LONG_BOX_HEADER_BYTES).toInt())
            resetPeekPosition()
            peekFully(prefix, 0, compactMdatEnd.toInt())
            val mdatOffset = topBoxSize.toInt()
            val mdatCompactSize = prefix.readUnsignedIntAt(mdatOffset) ?: return null
            if (prefix.readIntAt(mdatOffset + 4) != TYPE_MDAT) return null
            var mdatHeaderSize = FMP4_BOX_HEADER_BYTES.toLong()
            val mdatSize = when (mdatCompactSize) {
                0L -> remaining - topBoxSize
                1L -> {
                    val longMdatEnd = checkedAddFmp4(
                        topBoxSize,
                        FMP4_LONG_BOX_HEADER_BYTES.toLong(),
                    ) ?: return null
                    if (longMdatEnd > remaining) return null
                    resetPeekPosition()
                    peekFully(prefix, 0, longMdatEnd.toInt())
                    mdatHeaderSize = FMP4_LONG_BOX_HEADER_BYTES.toLong()
                    prefix.readUnsignedLongAt(mdatOffset + FMP4_BOX_HEADER_BYTES) ?: return null
                }

                else -> mdatCompactSize
            }
            if (mdatSize < mdatHeaderSize || mdatSize > remaining - topBoxSize) return null
            minimumBytes = checkedAddFmp4(topBoxSize, mdatHeaderSize) ?: return null
            completeStepBytes = checkedAddFmp4(topBoxSize, mdatSize) ?: return null
        }

        val desiredBytes = if (completeStepBytes <= FMP4_CHAIN_BATCH_BYTES) {
            minOf(FMP4_CHAIN_BATCH_BYTES, remaining)
        } else {
            minimumBytes
        }
        if (desiredBytes < FMP4_BOX_HEADER_BYTES || desiredBytes > Int.MAX_VALUE) return null
        val buffer = ByteArray(desiredBytes.toInt())
        resetPeekPosition()
        peekFully(buffer, 0, buffer.size)
        buffer
    } catch (_: IOException) {
        null
    } finally {
        resetPeekPosition()
    }
}

/** Reads one bounded window at an estimated byte position, which may be inside mdat payload. */
private fun ExtractorInput.peekFragmentedMp4EstimatedWindow(inputLength: Long): ByteArray? {
    resetPeekPosition()
    return try {
        val startPosition = position
        if (startPosition < 0L || startPosition >= inputLength) return null
        val bytes = minOf(FMP4_ESTIMATED_PROBE_BYTES, inputLength - startPosition)
        if (bytes < FMP4_BOX_HEADER_BYTES || bytes > Int.MAX_VALUE) return null
        ByteArray(bytes.toInt()).also { peekFully(it, 0, it.size) }
    } catch (_: IOException) {
        null
    } finally {
        resetPeekPosition()
    }
}

private data class FragmentedMp4ProbeCandidate(
    val anchor: FragmentedMp4SeekAnchor,
    val position: Long,
    val nextPosition: Long,
    val sequenceNumber: Long,
)

private data class FragmentedMp4ProbeScanStats(
    val moofTags: Int,
    val sizeRejected: Int,
    val truncated: Int,
    val positionRejected: Int,
    val sequenceRejected: Int,
    val stepRejected: Int,
    val anchorRejected: Int,
    val nextRejected: Int,
)

private data class FragmentedMp4ProbeScanResult(
    val candidates: List<FragmentedMp4ProbeCandidate>,
    val stats: FragmentedMp4ProbeScanStats,
)

/**
 * Finds structurally valid moof/mdat pairs without treating the scan itself as top-level proof.
 * This is a best-effort locator, not an adversarial payload authenticator. Callers must still apply
 * the multi-fragment sequence/time/track validation before handing any position to the delegate.
 */
private fun findFragmentedMp4ProbeCandidates(
    data: ByteArray,
    absoluteStart: Long,
    inputLength: Long,
    metadata: FragmentedMp4SeekMetadata,
    targetTimeUs: Long,
): FragmentedMp4ProbeScanResult {
    val candidates = ArrayList<FragmentedMp4ProbeCandidate>()
    var moofTags = 0
    var sizeRejected = 0
    var truncated = 0
    var positionRejected = 0
    var sequenceRejected = 0
    var stepRejected = 0
    var anchorRejected = 0
    var nextRejected = 0
    val searchEndExclusive = minOf(data.size, FMP4_ESTIMATED_SEARCH_BYTES.toInt())
    var offset = 0
    while (
        offset < searchEndExclusive &&
        offset <= data.size - FMP4_BOX_HEADER_BYTES &&
        candidates.size < FMP4_MAX_CANDIDATES_PER_PROBE
    ) {
        if (data.readIntAt(offset + 4) != TYPE_MOOF) {
            offset++
            continue
        }
        moofTags++
        val compactSize = data.readUnsignedIntAt(offset)
        var headerSize = FMP4_BOX_HEADER_BYTES
        val moofSize = when (compactSize) {
            1L -> {
                headerSize = FMP4_LONG_BOX_HEADER_BYTES
                data.readUnsignedLongAt(offset + FMP4_BOX_HEADER_BYTES)
            }
            null, 0L -> null
            else -> compactSize
        }
        if (
            moofSize == null ||
            moofSize < headerSize ||
            moofSize > FMP4_MAX_MOOF_BYTES
        ) {
            sizeRejected++
            offset++
            continue
        }
        val requiredSize = checkedAddFmp4(moofSize, FMP4_LONG_BOX_HEADER_BYTES.toLong())
        if (
            requiredSize == null ||
            requiredSize > Int.MAX_VALUE ||
            requiredSize > data.size - offset
        ) {
            truncated++
            offset++
            continue
        }
        val candidatePosition = checkedAddFmp4(absoluteStart, offset.toLong())
        if (candidatePosition == null || candidatePosition >= inputLength) {
            positionRejected++
            offset++
            continue
        }
        val candidateBytes = data.copyOfRange(offset, offset + requiredSize.toInt())
        val sequenceNumber = readFragmentSequenceNumber(
            candidateBytes.copyOfRange(0, moofSize.toInt()),
        )
        val step = parseFragmentedMp4TopLevelStep(
            data = candidateBytes,
            absoluteStart = candidatePosition,
            inputLength = inputLength,
            metadata = metadata,
        )
        val anchor = step?.anchor
        if (anchor != null && sequenceNumber != null && step.nextPosition > candidatePosition) {
            candidates += FragmentedMp4ProbeCandidate(
                anchor = anchor,
                position = candidatePosition,
                nextPosition = step.nextPosition,
                sequenceNumber = sequenceNumber,
            )
            offset += moofSize.toInt().coerceAtLeast(1)
        } else {
            when {
                sequenceNumber == null -> sequenceRejected++
                step == null -> stepRejected++
                anchor == null -> anchorRejected++
                else -> nextRejected++
            }
            offset++
        }
    }
    return FragmentedMp4ProbeScanResult(
        candidates = candidates.sortedWith(
            compareBy<FragmentedMp4ProbeCandidate> {
                timeDistanceUs(it.anchor.timeUs, targetTimeUs)
            }.thenBy { positionDistance(it.position, absoluteStart) },
        ),
        stats = FragmentedMp4ProbeScanStats(
            moofTags = moofTags,
            sizeRejected = sizeRejected,
            truncated = truncated,
            positionRejected = positionRejected,
            sequenceRejected = sequenceRejected,
            stepRejected = stepRejected,
            anchorRejected = anchorRejected,
            nextRejected = nextRejected,
        ),
    )
}

/** Parses one exact moof start already reached from a candidate's declared nextPosition. */
private fun parseFragmentedMp4ProbeCandidateAt(
    data: ByteArray,
    absoluteWindowStart: Long,
    candidatePosition: Long,
    inputLength: Long,
    metadata: FragmentedMp4SeekMetadata,
): FragmentedMp4ProbeCandidate? {
    val relativeOffset = candidatePosition - absoluteWindowStart
    if (relativeOffset < 0L || relativeOffset > Int.MAX_VALUE) return null
    val offset = relativeOffset.toInt()
    if (offset > data.size - FMP4_BOX_HEADER_BYTES || data.readIntAt(offset + 4) != TYPE_MOOF) {
        return null
    }
    val compactSize = data.readUnsignedIntAt(offset) ?: return null
    var headerSize = FMP4_BOX_HEADER_BYTES
    val moofSize = when (compactSize) {
        0L -> return null
        1L -> {
            headerSize = FMP4_LONG_BOX_HEADER_BYTES
            data.readUnsignedLongAt(offset + FMP4_BOX_HEADER_BYTES) ?: return null
        }
        else -> compactSize
    }
    if (moofSize < headerSize || moofSize > FMP4_MAX_MOOF_BYTES) return null
    val requiredSize = checkedAddFmp4(moofSize, FMP4_LONG_BOX_HEADER_BYTES.toLong())
        ?: return null
    if (requiredSize > Int.MAX_VALUE || requiredSize > data.size - offset) return null
    val candidateBytes = data.copyOfRange(offset, offset + requiredSize.toInt())
    val sequenceNumber = readFragmentSequenceNumber(
        candidateBytes.copyOfRange(0, moofSize.toInt()),
    ) ?: return null
    val step = parseFragmentedMp4TopLevelStep(
        data = candidateBytes,
        absoluteStart = candidatePosition,
        inputLength = inputLength,
        metadata = metadata,
    ) ?: return null
    val anchor = step.anchor ?: return null
    if (step.nextPosition <= candidatePosition) return null
    return FragmentedMp4ProbeCandidate(
        anchor = anchor,
        position = candidatePosition,
        nextPosition = step.nextPosition,
        sequenceNumber = sequenceNumber,
    )
}

private fun timeDistanceUs(left: Long, right: Long): Long =
    if (left >= right) left - right else right - left

private fun positionDistance(left: Long, right: Long): Long =
    if (left >= right) left - right else right - left

/** Reads the mandatory mfhd sequence number without trusting any non-moof payload structure. */
private fun readFragmentSequenceNumber(moof: ByteArray): Long? {
    if (moof.size < FMP4_BOX_HEADER_BYTES) return null
    val compactRootSize = moof.readUnsignedIntAt(0) ?: return null
    if (moof.readIntAt(4) != TYPE_MOOF || compactRootSize == 0L) return null
    val rootHeaderSize: Int
    val rootSize = if (compactRootSize == 1L) {
        rootHeaderSize = FMP4_LONG_BOX_HEADER_BYTES
        moof.readUnsignedLongAt(FMP4_BOX_HEADER_BYTES) ?: return null
    } else {
        rootHeaderSize = FMP4_BOX_HEADER_BYTES
        compactRootSize
    }
    if (rootSize < rootHeaderSize || rootSize > moof.size || rootSize > Int.MAX_VALUE) return null
    val rootEnd = rootSize.toInt()

    var cursor = rootHeaderSize
    var sequenceNumber: Long? = null
    var boxCount = 0
    while (cursor < rootEnd && boxCount++ < FMP4_MAX_MOOF_CHILD_BOXES) {
        val compactSize = moof.readUnsignedIntAt(cursor) ?: return null
        val type = moof.readIntAt(cursor + 4) ?: return null
        var headerSize = FMP4_BOX_HEADER_BYTES
        val childSize = when (compactSize) {
            0L -> (rootEnd - cursor).toLong()
            1L -> {
                headerSize = FMP4_LONG_BOX_HEADER_BYTES
                moof.readUnsignedLongAt(cursor + FMP4_BOX_HEADER_BYTES) ?: return null
            }
            else -> compactSize
        }
        if (
            childSize < headerSize ||
            childSize > rootEnd - cursor ||
            childSize > Int.MAX_VALUE
        ) {
            return null
        }
        if (type == FMP4_TYPE_MFHD) {
            if (sequenceNumber != null || childSize < headerSize + 8L) return null
            val payloadStart = cursor + headerSize
            if (moof.readUnsignedIntAt(payloadStart) != 0L) return null
            sequenceNumber = moof.readUnsignedIntAt(payloadStart + 4) ?: return null
        }
        cursor += childSize.toInt()
    }
    return sequenceNumber.takeIf { cursor == rootEnd }
}

private fun isNextFragmentSequence(previous: Long, current: Long): Boolean =
    current == ((previous + 1L) and 0xFFFF_FFFFL)

private enum class FragmentedMp4SeekPhase {
    ESTIMATED_PROBE,
    VERIFY_FORWARD_CHAIN,
    PROVEN_CHAIN_FALLBACK,
}

/** Bounded demand-seek state. Every final position is a validated sync moof. */
internal class FragmentedMp4DemandSeeker(
    private val metadata: FragmentedMp4SeekMetadata,
    private val durationUs: Long,
    val inputLength: Long,
) {
    val firstMoofPosition: Long = metadata.firstMoofPosition
    val seekMap: SeekMap = FragmentedMp4EstimatedSeekMap(
        durationUs = durationUs,
        firstMoofPosition = firstMoofPosition,
        inputLength = inputLength,
    )

    private val knownAnchors = mutableListOf(FragmentedMp4SeekAnchor(0L, firstMoofPosition))
    private val knownNextPositions = HashMap<Long, Long>()
    private val visitedProbePositions = HashSet<Long>()
    private var targetTimeUs = C.TIME_UNSET
    private var nextReadPosition = FMP4_POSITION_UNSET
    private var estimatedPosition = FMP4_POSITION_UNSET
    private var nextProbeOffsetIndex = 1
    private var pendingCandidates: List<FragmentedMp4ProbeCandidate> = emptyList()
    private var nextCandidateIndex = 0
    private var probeWindowStart = FMP4_POSITION_UNSET
    private var probeWindowData: ByteArray? = null
    private val verificationChain = ArrayList<FragmentedMp4ProbeCandidate>()
    private var phase = FragmentedMp4SeekPhase.ESTIMATED_PROBE
    private var rangeRequests = 0
    private var chainSteps = 0
    private var correctionAttempts = 0
    private var resolvedPosition: Long? = null
    private var usedFallback = false

    val isSeeking: Boolean get() = targetTimeUs != C.TIME_UNSET

    fun startSeek(timeUs: Long, media3EstimatedPosition: Long) {
        targetTimeUs = timeUs.coerceIn(0L, durationUs)
        resolvedPosition = null
        usedFallback = false
        rangeRequests = 0
        chainSteps = 0
        correctionAttempts = 0
        pendingCandidates = emptyList()
        nextCandidateIndex = 0
        probeWindowStart = FMP4_POSITION_UNSET
        probeWindowData = null
        verificationChain.clear()
        visitedProbePositions.clear()
        nextProbeOffsetIndex = 1

        val floor = floorAnchor(targetTimeUs)
        if (timeDistanceUs(floor.timeUs, targetTimeUs) <= FMP4_CACHED_ANCHOR_MAX_LAG_US) {
            Log.d(
                FMP4_SEEK_LOG_TAG,
                "seekStart targetUs=$targetTimeUs estimate=$media3EstimatedPosition " +
                    "inputLength=$inputLength durationUs=$durationUs cached=1 " +
                    "floorPosition=${floor.position} floorTimeUs=${floor.timeUs}",
            )
            finishAt(floor, fallback = false)
            return
        }

        val maximumStart = (inputLength - FMP4_BOX_HEADER_BYTES)
            .coerceAtLeast(firstMoofPosition)
        estimatedPosition = media3EstimatedPosition.coerceIn(firstMoofPosition, maximumStart)
        visitedProbePositions += estimatedPosition
        phase = FragmentedMp4SeekPhase.ESTIMATED_PROBE
        nextReadPosition = estimatedPosition
        Log.d(
            FMP4_SEEK_LOG_TAG,
            "seekStart targetUs=$targetTimeUs estimate=$estimatedPosition inputLength=$inputLength " +
                "durationUs=$durationUs cached=0 firstMoof=$firstMoofPosition",
        )
    }

    fun cancelSeek() {
        targetTimeUs = C.TIME_UNSET
        nextReadPosition = FMP4_POSITION_UNSET
        resolvedPosition = null
        pendingCandidates = emptyList()
        probeWindowStart = FMP4_POSITION_UNSET
        probeWindowData = null
        verificationChain.clear()
    }

    fun consumeResolvedPosition(): Long? {
        val result = resolvedPosition
        resolvedPosition = null
        return result
    }

    fun continueSeek(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!isSeeking) return Extractor.RESULT_CONTINUE
        val requiredPosition = nextReadPosition
        if (requiredPosition == FMP4_POSITION_UNSET) {
            finishAt(nearestAnchor(targetTimeUs), fallback = true)
            return Extractor.RESULT_CONTINUE
        }
        if (input.position != requiredPosition) {
            seekPosition.position = requiredPosition
            return Extractor.RESULT_SEEK
        }
        if (rangeRequests >= FMP4_MAX_DISCOVERY_READS_PER_SEEK) {
            finishAt(nearestAnchor(targetTimeUs), fallback = true)
            return Extractor.RESULT_CONTINUE
        }
        rangeRequests++

        return when (phase) {
            FragmentedMp4SeekPhase.ESTIMATED_PROBE -> continueEstimatedProbe(input, seekPosition)
            FragmentedMp4SeekPhase.VERIFY_FORWARD_CHAIN ->
                continueForwardVerification(input, seekPosition)
            FragmentedMp4SeekPhase.PROVEN_CHAIN_FALLBACK ->
                continueProvenChain(input, seekPosition)
        }
    }

    private fun continueEstimatedProbe(
        input: ExtractorInput,
        seekPosition: PositionHolder,
    ): Int {
        val probeStart = input.position
        val buffer = input.peekFragmentedMp4EstimatedWindow(inputLength)
        val scan = if (buffer == null) {
            FragmentedMp4ProbeScanResult(
                candidates = emptyList(),
                stats = FragmentedMp4ProbeScanStats(
                    moofTags = 0,
                    sizeRejected = 0,
                    truncated = 0,
                    positionRejected = 0,
                    sequenceRejected = 0,
                    stepRejected = 0,
                    anchorRejected = 0,
                    nextRejected = 0,
                ),
            )
        } else {
            findFragmentedMp4ProbeCandidates(
                data = buffer,
                absoluteStart = probeStart,
                inputLength = inputLength,
                metadata = metadata,
                targetTimeUs = targetTimeUs,
            )
        }
        pendingCandidates = scan.candidates
        nextCandidateIndex = 0
        probeWindowStart = probeStart
        probeWindowData = buffer
        val stats = scan.stats
        Log.d(
            FMP4_SEEK_LOG_TAG,
            "probe read=$rangeRequests start=$probeStart bytes=${buffer?.size ?: 0} " +
                "readFailed=${if (buffer == null) 1 else 0} candidates=${scan.candidates.size} " +
                "moofTags=${stats.moofTags} sizeRejected=${stats.sizeRejected} " +
                "truncated=${stats.truncated} positionRejected=${stats.positionRejected} " +
                "sequenceRejected=${stats.sequenceRejected} stepRejected=${stats.stepRejected} " +
                "anchorRejected=${stats.anchorRejected} nextRejected=${stats.nextRejected}",
        )
        scan.candidates.forEachIndexed { index, candidate ->
            Log.d(
                FMP4_SEEK_LOG_TAG,
                "candidate index=$index position=${candidate.position} " +
                    "timeUs=${candidate.anchor.timeUs} sequence=${candidate.sequenceNumber} " +
                    "next=${candidate.nextPosition} targetDeltaUs=" +
                    timeDistanceUs(candidate.anchor.timeUs, targetTimeUs),
            )
        }
        return scheduleNextCandidateOrProbe(seekPosition)
    }

    private fun continueForwardVerification(
        input: ExtractorInput,
        seekPosition: PositionHolder,
    ): Int {
        val previous = verificationChain.lastOrNull()
            ?: return rejectVerification(
                reason = "EMPTY_CHAIN",
                inputPosition = input.position,
                bufferBytes = 0,
                previous = null,
                anchor = null,
                sequenceNumber = null,
                nextPosition = null,
                seekPosition = seekPosition,
            )
        val buffer = input.peekFragmentedMp4TopLevelWindow(inputLength)
            ?: return rejectVerification(
                reason = "WINDOW_READ_FAILED",
                inputPosition = input.position,
                bufferBytes = 0,
                previous = previous,
                anchor = null,
                sequenceNumber = null,
                nextPosition = null,
                seekPosition = seekPosition,
            )
        if (buffer.readIntAt(4) != TYPE_MOOF) {
            return rejectVerification(
                reason = "HEADER_NOT_MOOF",
                inputPosition = input.position,
                bufferBytes = buffer.size,
                previous = previous,
                anchor = null,
                sequenceNumber = null,
                nextPosition = null,
                seekPosition = seekPosition,
            )
        }
        val step = parseFragmentedMp4TopLevelStep(
            data = buffer,
            absoluteStart = input.position,
            inputLength = inputLength,
            metadata = metadata,
        ) ?: return rejectVerification(
            reason = "STRUCTURE_INVALID",
            inputPosition = input.position,
            bufferBytes = buffer.size,
            previous = previous,
            anchor = null,
            sequenceNumber = null,
            nextPosition = null,
            seekPosition = seekPosition,
        )
        val anchor = step.anchor ?: return rejectVerification(
            reason = "SYNC_ANCHOR_MISSING",
            inputPosition = input.position,
            bufferBytes = buffer.size,
            previous = previous,
            anchor = null,
            sequenceNumber = null,
            nextPosition = step.nextPosition,
            seekPosition = seekPosition,
        )
        val sequenceNumber = readFragmentSequenceNumber(buffer) ?: return rejectVerification(
            reason = "MFHD_INVALID",
            inputPosition = input.position,
            bufferBytes = buffer.size,
            previous = previous,
            anchor = anchor,
            sequenceNumber = null,
            nextPosition = step.nextPosition,
            seekPosition = seekPosition,
        )
        val rejectionReason = when {
            input.position != previous.nextPosition -> "POSITION_MISMATCH"
            anchor.timeUs <= previous.anchor.timeUs -> "TIME_NOT_INCREASING"
            anchor.timeUs - previous.anchor.timeUs > FMP4_MAX_FRAGMENT_TIME_STEP_US ->
                "TIME_STEP_TOO_LARGE"
            !isNextFragmentSequence(previous.sequenceNumber, sequenceNumber) ->
                "SEQUENCE_MISMATCH"
            !isValidAnchor(anchor) -> "ANCHOR_OUT_OF_RANGE"
            step.nextPosition <= input.position -> "NEXT_NOT_FORWARD"
            else -> null
        }
        if (rejectionReason != null) {
            return rejectVerification(
                reason = rejectionReason,
                inputPosition = input.position,
                bufferBytes = buffer.size,
                previous = previous,
                anchor = anchor,
                sequenceNumber = sequenceNumber,
                nextPosition = step.nextPosition,
                seekPosition = seekPosition,
            )
        }

        verificationChain += FragmentedMp4ProbeCandidate(
            anchor = anchor,
            position = input.position,
            nextPosition = step.nextPosition,
            sequenceNumber = sequenceNumber,
        )
        Log.d(
            FMP4_SEEK_LOG_TAG,
            "verificationAccept depth=${verificationChain.size} position=${input.position} " +
                "timeUs=${anchor.timeUs} sequence=$sequenceNumber next=${step.nextPosition} " +
                "bytes=${buffer.size}",
        )
        if (verificationChain.size >= FMP4_MIN_CONFIRMED_PROBE_CHAIN) {
            return acceptVerifiedProbeChain(seekPosition)
        }
        if (step.nextPosition >= inputLength) return scheduleNextCandidateOrProbe(seekPosition)
        phase = FragmentedMp4SeekPhase.VERIFY_FORWARD_CHAIN
        return scheduleRead(step.nextPosition, seekPosition)
    }

    private fun rejectVerification(
        reason: String,
        inputPosition: Long,
        bufferBytes: Int,
        previous: FragmentedMp4ProbeCandidate?,
        anchor: FragmentedMp4SeekAnchor?,
        sequenceNumber: Long?,
        nextPosition: Long?,
        seekPosition: PositionHolder,
    ): Int {
        Log.d(
            FMP4_SEEK_LOG_TAG,
            "verificationReject reason=$reason read=$rangeRequests position=$inputPosition " +
                "bytes=$bufferBytes expected=${previous?.nextPosition ?: FMP4_POSITION_UNSET} " +
                "previousTimeUs=${previous?.anchor?.timeUs ?: C.TIME_UNSET} " +
                "timeUs=${anchor?.timeUs ?: C.TIME_UNSET} " +
                "previousSequence=${previous?.sequenceNumber ?: FMP4_POSITION_UNSET} " +
                "sequence=${sequenceNumber ?: FMP4_POSITION_UNSET} " +
                "next=${nextPosition ?: FMP4_POSITION_UNSET}",
        )
        return scheduleNextCandidateOrProbe(seekPosition)
    }

    private fun acceptVerifiedProbeChain(seekPosition: PositionHolder): Int {
        val confirmed = verificationChain.toList()
        if (!canMergeAnchors(confirmed.map { it.anchor })) {
            Log.d(
                FMP4_SEEK_LOG_TAG,
                "chainReject reason=ANCHOR_MERGE depth=${confirmed.size} read=$rangeRequests",
            )
            return scheduleNextCandidateOrProbe(seekPosition)
        }

        confirmed.forEach { candidate ->
            if (knownAnchors.none { it.position == candidate.position } && !addAnchor(candidate.anchor)) {
                Log.d(
                    FMP4_SEEK_LOG_TAG,
                    "chainReject reason=ANCHOR_ADD position=${candidate.position} " +
                        "timeUs=${candidate.anchor.timeUs} read=$rangeRequests",
                )
                return scheduleNextCandidateOrProbe(seekPosition)
            }
            knownNextPositions[candidate.position] = candidate.nextPosition
        }
        val chosen = confirmed
            .filter { timeDistanceUs(it.anchor.timeUs, targetTimeUs) <= FMP4_PROBE_MAX_TIME_DISTANCE_US }
            .let { nearby ->
                nearby.filter { it.anchor.timeUs <= targetTimeUs }.maxByOrNull { it.anchor.timeUs }
                    ?: nearby.minByOrNull { it.anchor.timeUs }
            }
        if (chosen != null) {
            finishAt(chosen.anchor, fallback = false)
            return Extractor.RESULT_CONTINUE
        }

        val correctedPosition = estimateTargetPositionFromKnownAnchors()
        if (correctedPosition != null) {
            val corrected = scheduleCorrectionProbe(correctedPosition, seekPosition)
            if (corrected != null) return corrected
        }
        return finishAtNearestAnchorOrContinue(seekPosition)
    }

    /** Interpolates to the next accepted anchor, or the declared timeline end; never extrapolates. */
    private fun estimateTargetPositionFromKnownAnchors(): Long? {
        val lower = knownAnchors.lastOrNull { it.timeUs <= targetTimeUs } ?: return null
        val knownUpper = knownAnchors.firstOrNull { it.timeUs > targetTimeUs }
        val upperTimeUs = knownUpper?.timeUs ?: durationUs
        val upperPosition = knownUpper?.position ?: (inputLength - 1L)
        val timeSpan = upperTimeUs - lower.timeUs
        val positionSpan = upperPosition - lower.position
        if (timeSpan <= 0L || positionSpan <= 0L) return null
        val fraction = (targetTimeUs - lower.timeUs).toDouble() / timeSpan.toDouble()
        if (!fraction.isFinite() || fraction !in 0.0..1.0) return null
        val interpolated = lower.position.toDouble() + positionSpan.toDouble() * fraction
        if (!interpolated.isFinite()) return null
        val maximumStart = (inputLength - FMP4_BOX_HEADER_BYTES)
            .coerceAtLeast(firstMoofPosition)
        val position = interpolated.toLong().coerceIn(firstMoofPosition, maximumStart)
        Log.d(
            FMP4_SEEK_LOG_TAG,
            "correctionEstimate attempt=${correctionAttempts + 1} lowerPosition=${lower.position} " +
                "lowerTimeUs=${lower.timeUs} upperPosition=$upperPosition " +
                "upperTimeUs=$upperTimeUs upperKnown=${if (knownUpper == null) 0 else 1} " +
                "targetUs=$targetTimeUs position=$position " +
                "remaining=${FMP4_MAX_DISCOVERY_READS_PER_SEEK - rangeRequests}",
        )
        return position
    }

    /** Returns null when the remaining discovery budget cannot validate another three-box chain. */
    private fun scheduleCorrectionProbe(
        position: Long,
        seekPosition: PositionHolder,
    ): Int? {
        if (
            FMP4_MAX_DISCOVERY_READS_PER_SEEK - rangeRequests <
            FMP4_MIN_DISCOVERY_READS_FOR_PROBE_CHAIN
        ) {
            Log.d(
                FMP4_SEEK_LOG_TAG,
                "correctionReject reason=BUDGET attempts=$correctionAttempts " +
                    "read=$rangeRequests position=$position",
            )
            return null
        }
        if (!visitedProbePositions.add(position)) {
            Log.d(
                FMP4_SEEK_LOG_TAG,
                "correctionReject reason=POSITION_VISITED attempts=$correctionAttempts " +
                    "read=$rangeRequests position=$position",
            )
            return null
        }
        correctionAttempts++
        estimatedPosition = position
        nextProbeOffsetIndex = 1
        pendingCandidates = emptyList()
        nextCandidateIndex = 0
        probeWindowStart = FMP4_POSITION_UNSET
        probeWindowData = null
        verificationChain.clear()
        phase = FragmentedMp4SeekPhase.ESTIMATED_PROBE
        Log.d(
            FMP4_SEEK_LOG_TAG,
            "correctionStart attempt=$correctionAttempts read=$rangeRequests position=$position",
        )
        return scheduleRead(position, seekPosition)
    }

    private fun finishAtNearestAnchorOrContinue(seekPosition: PositionHolder): Int {
        if (knownAnchors.size > 1) {
            finishAt(nearestAnchor(targetTimeUs), fallback = true)
            return Extractor.RESULT_CONTINUE
        }
        return scheduleNextCandidateOrProbe(seekPosition)
    }

    private fun scheduleNextCandidateOrProbe(seekPosition: PositionHolder): Int {
        while (nextCandidateIndex < pendingCandidates.size) {
            val candidate = pendingCandidates[nextCandidateIndex++]
            val rejectionReason = probeAnchorRejectionReason(candidate.anchor)
            if (rejectionReason != null || candidate.nextPosition >= inputLength) {
                Log.d(
                    FMP4_SEEK_LOG_TAG,
                    "candidateReject reason=${rejectionReason ?: "NEXT_AT_EOF"} " +
                        "position=${candidate.position} timeUs=${candidate.anchor.timeUs} " +
                        "sequence=${candidate.sequenceNumber} next=${candidate.nextPosition}",
                )
                continue
            }
            verificationChain.clear()
            verificationChain += candidate
            extendVerificationChainFromProbeWindow()
            if (verificationChain.size >= FMP4_MIN_CONFIRMED_PROBE_CHAIN) {
                Log.d(
                    FMP4_SEEK_LOG_TAG,
                    "candidateReuseComplete depth=${verificationChain.size} " +
                        "read=$rangeRequests position=${candidate.position}",
                )
                return acceptVerifiedProbeChain(seekPosition)
            }
            val nextPosition = verificationChain.last().nextPosition
            if (nextPosition >= inputLength) {
                Log.d(
                    FMP4_SEEK_LOG_TAG,
                    "candidateReject reason=REUSED_CHAIN_AT_EOF position=${candidate.position} " +
                        "depth=${verificationChain.size} next=$nextPosition",
                )
                continue
            }
            phase = FragmentedMp4SeekPhase.VERIFY_FORWARD_CHAIN
            Log.d(
                FMP4_SEEK_LOG_TAG,
                "candidateSelect position=${candidate.position} timeUs=${candidate.anchor.timeUs} " +
                    "sequence=${candidate.sequenceNumber} next=$nextPosition " +
                    "reusedDepth=${verificationChain.size}",
            )
            return scheduleRead(nextPosition, seekPosition)
        }
        verificationChain.clear()
        while (nextProbeOffsetIndex < FMP4_ESTIMATED_PROBE_OFFSETS.size) {
            val multiplier = FMP4_ESTIMATED_PROBE_OFFSETS[nextProbeOffsetIndex++]
            val distance = FMP4_ESTIMATED_SEARCH_BYTES * kotlin.math.abs(multiplier)
            val rawPosition = if (multiplier < 0L) {
                (estimatedPosition - distance).coerceAtLeast(firstMoofPosition)
            } else {
                checkedAddFmp4(estimatedPosition, distance) ?: inputLength
            }
            val maximumStart = (inputLength - FMP4_BOX_HEADER_BYTES)
                .coerceAtLeast(firstMoofPosition)
            val probePosition = rawPosition.coerceIn(firstMoofPosition, maximumStart)
            if (!visitedProbePositions.add(probePosition)) continue
            phase = FragmentedMp4SeekPhase.ESTIMATED_PROBE
            return scheduleRead(probePosition, seekPosition)
        }
        return startProvenChainFallback(seekPosition)
    }

    /** Reuses bytes already fetched by the 6 MiB probe before issuing another Range request. */
    private fun extendVerificationChainFromProbeWindow() {
        val data = probeWindowData ?: return
        val windowStart = probeWindowStart
        if (windowStart == FMP4_POSITION_UNSET) return
        while (verificationChain.size < FMP4_MIN_CONFIRMED_PROBE_CHAIN) {
            val previous = verificationChain.last()
            val candidate = parseFragmentedMp4ProbeCandidateAt(
                data = data,
                absoluteWindowStart = windowStart,
                candidatePosition = previous.nextPosition,
                inputLength = inputLength,
                metadata = metadata,
            )
            if (candidate == null) {
                Log.d(
                    FMP4_SEEK_LOG_TAG,
                    "candidateReuseStop reason=NOT_COMPLETE_OR_INVALID depth=${verificationChain.size} " +
                        "windowStart=$windowStart windowBytes=${data.size} next=${previous.nextPosition}",
                )
                return
            }
            val rejectionReason = when {
                candidate.position != previous.nextPosition -> "POSITION_MISMATCH"
                candidate.anchor.timeUs <= previous.anchor.timeUs -> "TIME_NOT_INCREASING"
                candidate.anchor.timeUs - previous.anchor.timeUs > FMP4_MAX_FRAGMENT_TIME_STEP_US ->
                    "TIME_STEP_TOO_LARGE"
                !isNextFragmentSequence(previous.sequenceNumber, candidate.sequenceNumber) ->
                    "SEQUENCE_MISMATCH"
                !isValidAnchor(candidate.anchor) -> "ANCHOR_OUT_OF_RANGE"
                candidate.nextPosition <= candidate.position -> "NEXT_NOT_FORWARD"
                else -> null
            }
            if (rejectionReason != null) {
                Log.d(
                    FMP4_SEEK_LOG_TAG,
                    "candidateReuseStop reason=$rejectionReason depth=${verificationChain.size} " +
                        "position=${candidate.position} timeUs=${candidate.anchor.timeUs} " +
                        "sequence=${candidate.sequenceNumber} next=${candidate.nextPosition}",
                )
                return
            }
            verificationChain += candidate
            Log.d(
                FMP4_SEEK_LOG_TAG,
                "candidateReuseAccept depth=${verificationChain.size} position=${candidate.position} " +
                    "timeUs=${candidate.anchor.timeUs} sequence=${candidate.sequenceNumber} " +
                    "next=${candidate.nextPosition}",
            )
        }
    }

    private fun startProvenChainFallback(seekPosition: PositionHolder): Int {
        usedFallback = true
        phase = FragmentedMp4SeekPhase.PROVEN_CHAIN_FALLBACK
        if (knownAnchors.size > 1) {
            finishAt(nearestAnchor(targetTimeUs), fallback = true)
            return Extractor.RESULT_CONTINUE
        }
        val floor = floorAnchor(targetTimeUs)
        val position = knownNextPositions[floor.position] ?: floor.position
        Log.d(
            FMP4_SEEK_LOG_TAG,
            "fallbackStart read=$rangeRequests floorPosition=${floor.position} " +
                "floorTimeUs=${floor.timeUs} next=$position candidates=${pendingCandidates.size}",
        )
        if (position >= inputLength) {
            finishAt(floor, fallback = true)
            return Extractor.RESULT_CONTINUE
        }
        return scheduleRead(position, seekPosition)
    }

    private fun continueProvenChain(
        input: ExtractorInput,
        seekPosition: PositionHolder,
    ): Int {
        val requiredPosition = input.position
        val buffer = input.peekFragmentedMp4TopLevelWindow(inputLength)
        if (buffer == null) {
            finishAt(nearestAnchor(targetTimeUs), fallback = true)
            return Extractor.RESULT_CONTINUE
        }

        var localOffset = 0
        var stepsInRead = 0
        while (localOffset <= buffer.size - FMP4_BOX_HEADER_BYTES) {
            val stepPosition = requiredPosition + localOffset.toLong()
            val step = parseFragmentedMp4TopLevelStep(
                data = if (localOffset == 0) buffer else buffer.copyOfRange(localOffset, buffer.size),
                absoluteStart = stepPosition,
                inputLength = inputLength,
                metadata = metadata,
            )
            if (step == null && localOffset > 0) {
                return scheduleRead(stepPosition, seekPosition)
            }
            if (step == null || step.nextPosition <= stepPosition) {
                finishAt(nearestAnchor(targetTimeUs), fallback = true)
                return Extractor.RESULT_CONTINUE
            }
            knownNextPositions[stepPosition] = step.nextPosition
            chainSteps++
            stepsInRead++
            step.anchor?.let(::addAnchor)

            val floor = floorAnchor(targetTimeUs)
            val ceiling = ceilingAnchor(targetTimeUs)
            if (floor.timeUs == targetTimeUs || ceiling != null) {
                finishAt(floor, fallback = usedFallback)
                return Extractor.RESULT_CONTINUE
            }
            if (step.nextPosition >= inputLength) {
                finishAt(floor, fallback = usedFallback)
                return Extractor.RESULT_CONTINUE
            }
            if (chainSteps >= FMP4_MAX_CHAIN_STEPS_PER_SEEK) {
                finishAt(nearestAnchor(targetTimeUs), fallback = true)
                return Extractor.RESULT_CONTINUE
            }
            if (stepsInRead >= FMP4_MAX_CHAIN_STEPS_PER_READ) {
                return scheduleRead(step.nextPosition, seekPosition)
            }

            val nextLocalOffset = step.nextPosition - requiredPosition
            if (
                nextLocalOffset >= 0L &&
                nextLocalOffset <= buffer.size - FMP4_BOX_HEADER_BYTES
            ) {
                localOffset = nextLocalOffset.toInt()
                continue
            }
            return scheduleRead(step.nextPosition, seekPosition)
        }

        finishAt(nearestAnchor(targetTimeUs), fallback = true)
        return Extractor.RESULT_CONTINUE
    }

    private fun scheduleRead(position: Long, seekPosition: PositionHolder): Int {
        if (rangeRequests >= FMP4_MAX_DISCOVERY_READS_PER_SEEK) {
            finishAt(nearestAnchor(targetTimeUs), fallback = true)
            return Extractor.RESULT_CONTINUE
        }
        nextReadPosition = position
        seekPosition.position = position
        return Extractor.RESULT_SEEK
    }

    private fun canMergeAnchors(candidates: List<FragmentedMp4SeekAnchor>): Boolean {
        if (candidates.any { !isValidAnchor(it) }) return false
        val combined = (knownAnchors + candidates).sortedBy { it.position }
        for (index in 1 until combined.size) {
            val previous = combined[index - 1]
            val current = combined[index]
            if (previous.position == current.position) {
                if (previous.timeUs != current.timeUs) return false
            } else if (previous.timeUs >= current.timeUs) {
                return false
            }
        }
        return true
    }

    private fun addAnchor(anchor: FragmentedMp4SeekAnchor): Boolean {
        if (!isValidAnchor(anchor)) return false
        val insertion = knownAnchors.binarySearchBy(anchor.position) { it.position }
        if (insertion >= 0) return false
        val index = -insertion - 1
        val previous = knownAnchors.getOrNull(index - 1)
        val next = knownAnchors.getOrNull(index)
        if (
            (previous != null && previous.timeUs >= anchor.timeUs) ||
            (next != null && next.timeUs <= anchor.timeUs)
        ) {
            return false
        }
        knownAnchors.add(index, anchor)
        return true
    }

    private fun isValidAnchor(anchor: FragmentedMp4SeekAnchor): Boolean =
        anchor.timeUs in 0L..durationUs &&
            anchor.position in firstMoofPosition until inputLength

    private fun probeAnchorRejectionReason(anchor: FragmentedMp4SeekAnchor): String? = when {
        anchor.timeUs !in 0L..durationUs -> "TIME_OUT_OF_RANGE"
        anchor.position !in firstMoofPosition until inputLength -> "POSITION_OUT_OF_RANGE"
        else -> null
    }

    private fun floorAnchor(timeUs: Long): FragmentedMp4SeekAnchor =
        knownAnchors.lastOrNull { it.timeUs <= timeUs } ?: knownAnchors.first()

    private fun ceilingAnchor(timeUs: Long): FragmentedMp4SeekAnchor? =
        knownAnchors.firstOrNull { it.timeUs > timeUs }

    private fun nearestAnchor(timeUs: Long): FragmentedMp4SeekAnchor =
        knownAnchors.minByOrNull { timeDistanceUs(it.timeUs, timeUs) } ?: knownAnchors.first()

    private fun finishAt(anchor: FragmentedMp4SeekAnchor, fallback: Boolean) {
        val trustedAnchor = knownAnchors.firstOrNull {
            it.position == anchor.position && it.timeUs == anchor.timeUs && isValidAnchor(it)
        } ?: floorAnchor(targetTimeUs.coerceIn(0L, durationUs))
        resolvedPosition = trustedAnchor.position
        usedFallback = fallback
        Log.d(
            FMP4_SEEK_LOG_TAG,
            "targetUs=$targetTimeUs, resolvedUs=${trustedAnchor.timeUs}, " +
                "position=${trustedAnchor.position}, discoveryReads=$rangeRequests, " +
                "totalRangeBudget=$FMP4_MAX_RANGE_REQUESTS_PER_SEEK, " +
                "chainSteps=$chainSteps, fallback=$usedFallback, " +
                "anchors=${knownAnchors.size}",
        )
        targetTimeUs = C.TIME_UNSET
        nextReadPosition = FMP4_POSITION_UNSET
        probeWindowStart = FMP4_POSITION_UNSET
        probeWindowData = null
    }
}

private fun checkedAddFmp4(left: Long, right: Long): Long? =
    if (right < 0L || left > Long.MAX_VALUE - right) null else left + right

private fun ExtractorInput.peekFragmentedMp4SeekMetadata(): FragmentedMp4SeekMetadata? {
    resetPeekPosition()
    return try {
        val inputLength = length
        if (position != 0L || inputLength <= 0L) return null
        var moov: ByteArray? = null
        var firstMoof: ByteArray? = null
        var firstMoofPosition = FMP4_POSITION_UNSET

        repeat(FMP4_MAX_INITIAL_BOXES) {
            val boxStart = peekPosition
            if (boxStart < 0L || boxStart >= inputLength || boxStart > FMP4_MAX_INITIAL_SCAN_BYTES) {
                return null
            }
            val header = ByteArray(FMP4_LONG_BOX_HEADER_BYTES)
            if (!peekFully(header, 0, FMP4_BOX_HEADER_BYTES, true)) return null
            val compactSize = header.readUnsignedIntAt(0) ?: return null
            val type = header.readIntAt(4) ?: return null
            val headerSize: Int
            val boxSize: Long
            when (compactSize) {
                0L -> {
                    headerSize = FMP4_BOX_HEADER_BYTES
                    boxSize = inputLength - boxStart
                }
                1L -> {
                    if (!peekFully(
                            header,
                            FMP4_BOX_HEADER_BYTES,
                            FMP4_LONG_BOX_HEADER_BYTES - FMP4_BOX_HEADER_BYTES,
                            true,
                        )
                    ) {
                        return null
                    }
                    headerSize = FMP4_LONG_BOX_HEADER_BYTES
                    boxSize = header.readUnsignedLongAt(FMP4_BOX_HEADER_BYTES) ?: return null
                }
                else -> {
                    headerSize = FMP4_BOX_HEADER_BYTES
                    boxSize = compactSize
                }
            }
            if (
                boxSize < headerSize ||
                boxStart > inputLength - boxSize ||
                boxStart + boxSize > FMP4_MAX_INITIAL_SCAN_BYTES
            ) {
                return null
            }
            val payloadSize = boxSize - headerSize
            if (payloadSize > Int.MAX_VALUE) return null

            when (type) {
                TYPE_MOOV, TYPE_MOOF -> {
                    val maximum = if (type == TYPE_MOOV) FMP4_MAX_MOOV_BYTES else FMP4_MAX_MOOF_BYTES
                    if (boxSize > maximum) return null
                    val box = ByteArray(boxSize.toInt())
                    header.copyInto(box, endIndex = headerSize)
                    if (payloadSize > 0L) {
                        peekFully(box, headerSize, payloadSize.toInt())
                    }
                    if (type == TYPE_MOOV) {
                        if (moov != null) return null
                        moov = box
                    } else {
                        firstMoof = box
                        firstMoofPosition = boxStart
                        return parseFragmentedMp4SeekMetadata(
                            moov = moov ?: return null,
                            firstMoof = firstMoof,
                            firstMoofPosition = firstMoofPosition,
                        )
                    }
                }
                TYPE_MDAT -> return null
                else -> if (payloadSize > 0L) advancePeekPosition(payloadSize.toInt())
            }
        }
        null
    } catch (_: IOException) {
        null
    } finally {
        resetPeekPosition()
    }
}

private fun ByteArray.readUnsignedIntAt(offset: Int): Long? {
    if (offset < 0 || size - offset < 4) return null
    return ((this[offset].toLong() and 0xFFL) shl 24) or
        ((this[offset + 1].toLong() and 0xFFL) shl 16) or
        ((this[offset + 2].toLong() and 0xFFL) shl 8) or
        (this[offset + 3].toLong() and 0xFFL)
}

private fun ByteArray.readIntAt(offset: Int): Int? = readUnsignedIntAt(offset)?.toInt()

private fun ByteArray.readUnsignedLongAt(offset: Int): Long? {
    if (offset < 0 || size - offset < 8 || this[offset].toInt() and 0x80 != 0) return null
    var value = 0L
    repeat(8) { index -> value = (value shl 8) or (this[offset + index].toLong() and 0xFFL) }
    return value
}

private const val FMP4_SEEK_LOG_TAG = "OpenListFmp4Seek"
private const val FMP4_POSITION_UNSET = -1L
private const val FMP4_BOX_HEADER_BYTES = 8
private const val FMP4_LONG_BOX_HEADER_BYTES = 16
private const val FMP4_MAX_INITIAL_BOXES = 64
private const val FMP4_MAX_INITIAL_SCAN_BYTES = 16L * 1024L * 1024L
private const val FMP4_MAX_MOOV_BYTES = 12L * 1024L * 1024L
private const val FMP4_MAX_MOOF_BYTES = 4L * 1024L * 1024L
private const val FMP4_CHAIN_BATCH_BYTES = 2L * 1024L * 1024L
private const val FMP4_MAX_CHAIN_STEPS_PER_READ = 64
private const val FMP4_MAX_CHAIN_STEPS_PER_SEEK = 50_000
private const val FMP4_ESTIMATED_SEARCH_BYTES = 2L * 1024L * 1024L
private const val FMP4_ESTIMATED_PROBE_BYTES =
    FMP4_ESTIMATED_SEARCH_BYTES + FMP4_MAX_MOOF_BYTES + FMP4_LONG_BOX_HEADER_BYTES
private const val FMP4_MAX_CANDIDATES_PER_PROBE = 8
private const val FMP4_CACHED_ANCHOR_MAX_LAG_US = 8_000_000L
private const val FMP4_PROBE_MAX_TIME_DISTANCE_US = 8_000_000L
private const val FMP4_MIN_CONFIRMED_PROBE_CHAIN = 3
private const val FMP4_MIN_DISCOVERY_READS_FOR_PROBE_CHAIN = 3
private const val FMP4_MAX_FRAGMENT_TIME_STEP_US = 120_000_000L
private const val FMP4_MAX_MOOF_CHILD_BOXES = 1_024
internal const val FMP4_MAX_RANGE_REQUESTS_PER_SEEK = 10
private const val FMP4_MAX_DISCOVERY_READS_PER_SEEK =
    FMP4_MAX_RANGE_REQUESTS_PER_SEEK - 1

/** Starts of adjacent search regions; each read has extra tail bytes for a maximum-size moof. */
private val FMP4_ESTIMATED_PROBE_OFFSETS = longArrayOf(0L, 1L, -1L, 2L, -2L)
private val FMP4_TYPE_MFHD = fourCc("mfhd")
