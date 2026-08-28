package org.openlist.mobile.data.upload

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

interface RandomAccessUploadSource {
    val size: Long

    fun requestBody(
        offset: Long,
        byteCount: Long,
        mediaType: String = "application/octet-stream",
    ): RequestBody
}

class FileRandomAccessUploadSource(
    private val file: File,
    override val size: Long = file.length(),
) : RandomAccessUploadSource {
    init {
        require(size >= 0) { "size must not be negative" }
        require(file.isFile) { "source must be a regular file" }
        require(file.length() == size) { "source size changed before upload" }
    }

    override fun requestBody(offset: Long, byteCount: Long, mediaType: String): RequestBody {
        require(offset >= 0) { "offset must not be negative" }
        require(byteCount >= 0) { "byteCount must not be negative" }
        require(offset <= size && byteCount <= size - offset) { "Requested range is outside the source" }
        return RangedRequestBody(
            mediaType = mediaType.toMediaTypeOrNull(),
            length = byteCount,
            open = {
                FileInputStream(file).also { stream -> stream.channel.position(offset) }
            },
        )
    }
}

class ByteArrayUploadSource(
    private val bytes: ByteArray,
) : RandomAccessUploadSource {
    override val size: Long = bytes.size.toLong()

    override fun requestBody(offset: Long, byteCount: Long, mediaType: String): RequestBody {
        require(offset >= 0 && byteCount >= 0 && offset + byteCount <= size)
        val start = offset.toInt()
        val end = (offset + byteCount).toInt()
        return RangedRequestBody(
            mediaType = mediaType.toMediaTypeOrNull(),
            length = byteCount,
            open = { bytes.inputStream(start, end - start) },
        )
    }
}

private class RangedRequestBody(
    private val mediaType: MediaType?,
    private val length: Long,
    private val open: () -> InputStream,
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        open().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("源文件在读取 $length 字节之前结束")
                if (read == 0) continue
                sink.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}
