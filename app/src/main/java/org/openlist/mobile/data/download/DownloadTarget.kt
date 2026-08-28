package org.openlist.mobile.data.download

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

interface DownloadTarget {
    /** Opens a brand-new logical file; any previous bytes must be removed before the first write. */
    fun openTruncated(): OutputStream

    /** Best-effort cleanup used after cancellation or failure so a partial file is not mistaken for success. */
    fun clear()
}

class SafDownloadTarget(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
) : DownloadTarget {
    override fun openTruncated(): OutputStream = openTruncatingStream()

    override fun clear() {
        openTruncatingStream().use { /* Opening with wt truncates before close. */ }
    }

    private fun openTruncatingStream(): OutputStream = try {
        contentResolver.openOutputStream(uri, TRUNCATE_WRITE_MODE)
            ?: throw FileNotFoundException("SAF provider returned no stream for $uri")
    } catch (error: SecurityException) {
        throw error
    } catch (error: IOException) {
        throw DownloadTargetException("无法打开目标文件", error)
    }

    companion object {
        /** ContentResolver's explicit write-and-truncate mode; plain "w" is provider-dependent. */
        const val TRUNCATE_WRITE_MODE = "wt"
    }
}
