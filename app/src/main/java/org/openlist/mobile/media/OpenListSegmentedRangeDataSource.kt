@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.Locale

/**
 * Adds strict and resumable single-range semantics to an HTTP [DataSource].
 *
 * The upstream must implement [HttpDataSource], as [OkHttpDataSource][androidx.media3.datasource.okhttp.OkHttpDataSource]
 * does. A server is allowed to cap a `206` response to a smaller range than requested; this source
 * transparently opens the next range after that response ends. A non-zero request must never be
 * satisfied by `200`, because allowing OkHttpDataSource to skip from the beginning can block
 * playback while it downloads gigabytes that the caller did not request.
 */
internal class OpenListSegmentedRangeDataSource(
    private val upstream: DataSource,
) : DataSource {
    class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            OpenListSegmentedRangeDataSource(upstreamFactory.createDataSource())
    }

    private var originalDataSpec: DataSpec? = null
    private var upstreamNeedsClose = false
    private var opened = false
    private var responseMode = ResponseMode.NONE
    private var currentPosition = 0L
    private var bytesRead = 0L
    private var segmentBytesRemaining = LENGTH_UNSET
    private var segmentBytesRead = 0L
    private var knownResourceLength: Long? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(!opened) { "DataSource is already open" }
        validateRepresentableRange(dataSpec.position, dataSpec.length)

        originalDataSpec = dataSpec
        currentPosition = dataSpec.position
        bytesRead = 0L
        knownResourceLength = null
        responseMode = ResponseMode.NONE
        segmentBytesRemaining = LENGTH_UNSET
        segmentBytesRead = 0L
        opened = true

        return try {
            val upstreamLength = openUpstream(dataSpec)
            resolvedInitialLength(dataSpec, upstreamLength)
        } catch (error: IOException) {
            closeAfterFailure(error)
            throw error
        } catch (error: RuntimeException) {
            closeAfterFailure(error)
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(opened) { "DataSource is not open" }
        if (length == 0) return 0

        while (true) {
            val remaining = remainingToRead()
            if (remaining == 0L || responseMode == ResponseMode.EOF) {
                return C.RESULT_END_OF_INPUT
            }

            if (responseMode == ResponseMode.PARTIAL && segmentBytesRemaining == 0L) {
                openNextSegment()
                continue
            }

            var readLength = length
            if (remaining != LENGTH_UNSET) {
                readLength = minOf(readLength.toLong(), remaining).toInt()
            }
            if (segmentBytesRemaining != LENGTH_UNSET) {
                readLength = minOf(readLength.toLong(), segmentBytesRemaining).toInt()
            }
            if (readLength == 0) return C.RESULT_END_OF_INPUT

            val result = upstream.read(buffer, offset, readLength)
            when {
                result > 0 -> {
                    advance(result)
                    return result
                }

                result == 0 -> throw IOException("HTTP range response made no progress")

                responseMode == ResponseMode.FULL && remaining != LENGTH_UNSET -> {
                    throw IOException("HTTP response ended before the requested range")
                }

                responseMode != ResponseMode.PARTIAL -> {
                    responseMode = ResponseMode.EOF
                    return C.RESULT_END_OF_INPUT
                }

                segmentBytesRead == 0L -> {
                    throw IOException("HTTP range response made no progress")
                }

                else -> {
                    openNextSegment()
                }
            }
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        var closeError: IOException? = null
        if (upstreamNeedsClose) {
            upstreamNeedsClose = false
            try {
                upstream.close()
            } catch (error: IOException) {
                closeError = error
            }
        }
        clearState()
        closeError?.let { throw it }
    }

    private fun openNextSegment() {
        val dataSpec = checkNotNull(originalDataSpec)
        val remaining = remainingToRead()
        if (remaining == 0L) {
            responseMode = ResponseMode.EOF
            return
        }

        closeCurrentUpstream()
        val nextDataSpec = dataSpec.buildUpon()
            .setPosition(currentPosition)
            .setLength(remaining)
            .build()
        openUpstream(nextDataSpec)
    }

    private fun openUpstream(dataSpec: DataSpec): Long {
        validateRepresentableRange(dataSpec.position, dataSpec.length)
        upstreamNeedsClose = true
        val upstreamLength = upstream.open(dataSpec)
        val http = upstream as? HttpDataSource
            ?: throw IOException("Segmented range upstream does not expose HTTP status")
        val responseCode = http.getResponseCode()

        segmentBytesRead = 0L
        when (responseCode) {
            HTTP_PARTIAL_CONTENT -> {
                val contentRange = requireSatisfiedContentRange(upstream.responseHeaders)
                if (contentRange.start != currentPosition) {
                    throw IOException("HTTP range response started at an unexpected position")
                }
                contentRange.total?.let(::recordResourceLength)
                responseMode = ResponseMode.PARTIAL
                segmentBytesRemaining = contentRange.length
            }

            HTTP_RANGE_NOT_SATISFIABLE -> {
                val contentRange = requireUnsatisfiedContentRange(upstream.responseHeaders)
                if (contentRange.total != currentPosition) {
                    throw IOException("HTTP range response did not describe exact end of input")
                }
                recordResourceLength(contentRange.total)
                responseMode = ResponseMode.EOF
                segmentBytesRemaining = 0L
            }

            HTTP_OK -> {
                if (currentPosition != 0L) {
                    throw IOException("Server ignored a non-zero HTTP range request")
                }
                responseMode = ResponseMode.FULL
                segmentBytesRemaining = LENGTH_UNSET
            }

            else -> throw IOException("Unexpected successful HTTP range response")
        }
        return upstreamLength
    }

    private fun resolvedInitialLength(dataSpec: DataSpec, upstreamLength: Long): Long {
        val requestedLength = dataSpec.length.takeUnless { it == LENGTH_UNSET }
        val availableLength = knownResourceLength?.minus(dataSpec.position)
        return when {
            availableLength != null && requestedLength != null ->
                minOf(availableLength, requestedLength)

            availableLength != null -> availableLength
            requestedLength != null -> requestedLength
            responseMode == ResponseMode.PARTIAL -> LENGTH_UNSET
            else -> upstreamLength
        }
    }

    private fun remainingToRead(): Long {
        val dataSpec = checkNotNull(originalDataSpec)
        val requestedRemaining = dataSpec.length
            .takeUnless { it == LENGTH_UNSET }
            ?.let { requested -> (requested - bytesRead).coerceAtLeast(0L) }
        val resourceRemaining = knownResourceLength
            ?.let { resourceLength -> (resourceLength - currentPosition).coerceAtLeast(0L) }
        return when {
            requestedRemaining != null && resourceRemaining != null ->
                minOf(requestedRemaining, resourceRemaining)

            requestedRemaining != null -> requestedRemaining
            resourceRemaining != null -> resourceRemaining
            else -> LENGTH_UNSET
        }
    }

    private fun advance(count: Int) {
        if (currentPosition > Long.MAX_VALUE - count.toLong()) {
            throw IOException("HTTP range position overflow")
        }
        currentPosition += count
        bytesRead += count
        segmentBytesRead += count
        if (segmentBytesRemaining != LENGTH_UNSET) {
            if (count > segmentBytesRemaining) {
                throw IOException("HTTP range response exceeded its declared boundary")
            }
            segmentBytesRemaining -= count
        }
    }

    private fun recordResourceLength(resourceLength: Long) {
        if (resourceLength < currentPosition) {
            throw IOException("HTTP range response reported an invalid resource length")
        }
        val previous = knownResourceLength
        if (previous != null && previous != resourceLength) {
            throw IOException("HTTP resource length changed between range responses")
        }
        knownResourceLength = resourceLength
    }

    private fun closeCurrentUpstream() {
        if (!upstreamNeedsClose) return
        upstreamNeedsClose = false
        upstream.close()
    }

    private fun closeAfterFailure(error: Throwable) {
        if (upstreamNeedsClose) {
            upstreamNeedsClose = false
            try {
                upstream.close()
            } catch (closeError: IOException) {
                error.addSuppressed(closeError)
            }
        }
        clearState()
    }

    private fun clearState() {
        opened = false
        originalDataSpec = null
        responseMode = ResponseMode.NONE
        currentPosition = 0L
        bytesRead = 0L
        segmentBytesRemaining = LENGTH_UNSET
        segmentBytesRead = 0L
        knownResourceLength = null
    }

    private enum class ResponseMode {
        NONE,
        FULL,
        PARTIAL,
        EOF,
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val LENGTH_UNSET = -1L
    }
}

/**
 * Rejects ignored or malformed ranges before OkHttpDataSource can manually skip a large response.
 *
 * Install this on the dedicated media [OkHttpClient] as well as wrapping its DataSource factory in
 * [OpenListSegmentedRangeDataSource.Factory]. Redirect responses are left untouched; OkHttp invokes
 * network interceptors again for the redirected exchange.
 */
internal fun OkHttpClient.withOpenListStrictRangeResponses(): OkHttpClient =
    newBuilder()
        .addNetworkInterceptor(OpenListStrictRangeNetworkInterceptor)
        .build()

private object OpenListStrictRangeNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val declaredRequestRange = originalRequest.header("Range")?.let { header ->
            parseOpenListRangeRequest(header)
                ?: throw IOException("Malformed HTTP range request")
        }
        // The FongMi OkHttpDataSource forces `bytes=0-` for progressive media even when the
        // caller requested the whole resource. Some storage backends cap that open-ended 206,
        // which makes Media3 mistake the first segment for the complete file. A normal initial
        // GET avoids that ambiguity. Explicit bounded ranges, including one starting at zero,
        // remain intact.
        val stripsForcedInitialRange = declaredRequestRange?.let { range ->
            range.start == 0L && range.endInclusive == null
        } == true
        val request = if (stripsForcedInitialRange) {
            originalRequest.newBuilder().removeHeader("Range").build()
        } else {
            originalRequest
        }
        val requestRange = declaredRequestRange.takeUnless { stripsForcedInitialRange }
        val response = chain.proceed(request)

        if (response.code in 300..399 || response.code >= 400) return response
        if (requestRange != null && requestRange.start > 0L && response.code != 206) {
            response.close()
            throw IOException("Server ignored a non-zero HTTP range request")
        }
        if (response.code == 206) {
            val contentRange = parseOpenListContentRange(response.header("Content-Range"))
            val valid = contentRange is OpenListContentRange.Satisfied &&
                (requestRange == null || contentRange.start == requestRange.start)
            if (!valid) {
                response.close()
                throw IOException("HTTP range response started at an unexpected position")
            }
        }
        return response
    }
}

internal sealed interface OpenListContentRange {
    data class Satisfied(
        val start: Long,
        val endInclusive: Long,
        val total: Long?,
    ) : OpenListContentRange {
        val length: Long
            get() = endInclusive - start + 1L
    }

    data class Unsatisfied(val total: Long) : OpenListContentRange
}

/** Parses a single RFC 9110 byte Content-Range without narrowing values to [Int]. */
internal fun parseOpenListContentRange(value: String?): OpenListContentRange? {
    if (value == null) return null
    val match = CONTENT_RANGE_PATTERN.matchEntire(value.trim()) ?: return null
    val startValue = match.groups[1]?.value
    val endValue = match.groups[2]?.value
    val totalValue = match.groups[3]?.value ?: return null

    if (startValue == null || endValue == null) {
        val total = totalValue.toLongOrNull() ?: return null
        return OpenListContentRange.Unsatisfied(total)
    }

    val start = startValue.toLongOrNull() ?: return null
    val endInclusive = endValue.toLongOrNull() ?: return null
    if (endInclusive < start || endInclusive - start == Long.MAX_VALUE) return null
    val total = if (totalValue == "*") null else totalValue.toLongOrNull() ?: return null
    if (total != null && (total == 0L || endInclusive >= total)) return null
    return OpenListContentRange.Satisfied(start, endInclusive, total)
}

private data class OpenListRangeRequest(
    val start: Long,
    val endInclusive: Long?,
)

private fun parseOpenListRangeRequest(value: String): OpenListRangeRequest? {
    val match = RANGE_REQUEST_PATTERN.matchEntire(value.trim()) ?: return null
    val start = match.groups[1]?.value?.toLongOrNull() ?: return null
    val end = match.groups[2]?.value?.takeIf(String::isNotEmpty)?.toLongOrNull()
    if (end != null && end < start) return null
    return OpenListRangeRequest(start, end)
}

private fun requireSatisfiedContentRange(
    headers: Map<String, List<String>>,
): OpenListContentRange.Satisfied {
    return parseSingleContentRange(headers) as? OpenListContentRange.Satisfied
        ?: throw IOException("Missing or malformed HTTP Content-Range")
}

private fun requireUnsatisfiedContentRange(
    headers: Map<String, List<String>>,
): OpenListContentRange.Unsatisfied {
    return parseSingleContentRange(headers) as? OpenListContentRange.Unsatisfied
        ?: throw IOException("Missing or malformed HTTP Content-Range")
}

private fun parseSingleContentRange(
    headers: Map<String, List<String>>,
): OpenListContentRange? {
    val values = headers.entries
        .filter { (name, _) -> name.lowercase(Locale.US) == "content-range" }
        .flatMap { (_, values) -> values }
    return values.singleOrNull()?.let(::parseOpenListContentRange)
}

private fun validateRepresentableRange(position: Long, length: Long) {
    if (position < 0L || (length != C.LENGTH_UNSET.toLong() && length <= 0L)) {
        throw IOException("Invalid HTTP range")
    }
    if (length != C.LENGTH_UNSET.toLong() && length - 1L > Long.MAX_VALUE - position) {
        throw IOException("HTTP range exceeds supported positions")
    }
}

private val CONTENT_RANGE_PATTERN = Regex(
    pattern = "bytes\\s+(?:(\\d+)-(\\d+)|\\*)/(\\d+|\\*)",
    option = RegexOption.IGNORE_CASE,
)

private val RANGE_REQUEST_PATTERN = Regex(
    pattern = "bytes=(\\d+)-(\\d*)",
    option = RegexOption.IGNORE_CASE,
)
