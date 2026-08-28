package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UploadHeadersTest {
    @Test
    fun `init headers match v4_2_5 protocol`() {
        val headers = UploadHeaders.init(
            MultipartInitRequest(
                remotePath = "/媒体/A B.mp4",
                fileSize = 12_345,
                mimeType = "video/mp4",
                modifiedAtMillis = 1_700_000_000_000,
                overwrite = false,
                preferredChunkSize = 1_048_576,
                hashes = UploadFileHashes(md5 = "md5", sha1 = "sha1", sha256 = "sha256"),
            ),
        )

        assertThat(headers).containsExactly(
            "File-Path", "%2F%E5%AA%92%E4%BD%93%2FA%20B.mp4",
            "X-File-Size", "12345",
            "Content-Type", "video/mp4",
            "Last-Modified", "1700000000000",
            "Overwrite", "false",
            "X-Chunk-Size", "1048576",
            "X-File-Md5", "md5",
            "X-File-Sha1", "sha1",
            "X-File-Sha256", "sha256",
        )
    }

    @Test
    fun `chunk and session headers use server upload id`() {
        assertThat(UploadHeaders.chunk("upload-id", 7)).containsExactly(
            "X-Upload-Id", "upload-id",
            "X-Chunk-Index", "7",
        )
        assertThat(UploadHeaders.session("upload-id"))
            .containsExactly("X-Upload-Id", "upload-id")
    }

    @Test
    fun `legacy put headers match documented openapi request`() {
        val headers = UploadHeaders.legacyPut(
            MultipartInitRequest(
                remotePath = "/媒体/A B.mp4",
                fileSize = 12_345,
                mimeType = "video/mp4",
                modifiedAtMillis = 1_700_000_000_000,
                overwrite = false,
                preferredChunkSize = 1_048_576,
                hashes = UploadFileHashes(md5 = "md5", sha1 = "sha1", sha256 = "sha256"),
            ),
        )

        assertThat(headers).containsExactly(
            "File-Path", "%2F%E5%AA%92%E4%BD%93%2FA%20B.mp4",
            "Content-Type", "application/octet-stream",
            "Last-Modified", "1700000000000",
            "Overwrite", "false",
            "X-File-Md5", "md5",
            "X-File-Sha1", "sha1",
            "X-File-Sha256", "sha256",
        )
        assertThat(headers).doesNotContainKey("X-File-Size")
        assertThat(headers).doesNotContainKey("X-Chunk-Size")
    }
}
