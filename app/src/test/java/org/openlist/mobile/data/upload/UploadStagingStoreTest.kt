package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadStagingStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stage publishes immutable content with content-derived identity`() = runTest {
        val directory = temporaryFolder.newFolder("staging")
        val bytes = "immutable source".encodeToByteArray()
        val store = UploadStagingStore(directory)

        val staged = store.stage("work-1", bytes.size.toLong()) { bytes.inputStream() }

        assertThat(staged.file.readBytes()).isEqualTo(bytes)
        assertThat(staged.sha256)
            .isEqualTo("fc17afe4af56fca9d2943b7901e7517611b37a36db7a7775b3e341e7d20a6ba0")
        assertThat(staged.sourceIdentity)
            .isEqualTo("sha256:${staged.sha256}:${bytes.size}")
        assertThat(directory.listFiles().orEmpty().none { it.name.endsWith(".part") }).isTrue()
    }

    @Test
    fun `retry reuses completed staging without reopening changed provider`() = runTest {
        val directory = temporaryFolder.newFolder("reuse")
        val original = "AAAA".encodeToByteArray()
        var openCount = 0

        val first = UploadStagingStore(directory).stage("same-work", 4) {
            openCount++
            original.inputStream()
        }
        val restored = UploadStagingStore(directory).stage("same-work", 4) {
            openCount++
            "BBBB".byteInputStream()
        }

        assertThat(openCount).isEqualTo(1)
        assertThat(restored).isEqualTo(first)
        val sink = Buffer()
        FileRandomAccessUploadSource(restored.file).requestBody(0, 4).writeTo(sink)
        assertThat(sink.readUtf8()).isEqualTo("AAAA")
    }

    @Test
    fun `same-size changed content gets a different identity in a new job`() = runTest {
        val directory = temporaryFolder.newFolder("identity")
        val store = UploadStagingStore(directory)

        val first = store.stage("work-a", 4) { "AAAA".byteInputStream() }
        val second = store.stage("work-b", 4) { "BBBB".byteInputStream() }

        assertThat(first.sha256).isNotEqualTo(second.sha256)
        assertThat(first.sourceIdentity).isNotEqualTo(second.sourceIdentity)
    }

    @Test
    fun `unknown provider size is counted while staging`() = runTest {
        val directory = temporaryFolder.newFolder("unknown-size")
        val store = UploadStagingStore(directory)

        val staged = store.stage("work-unknown", expectedSize = null) {
            "provider does not report size".byteInputStream()
        }

        assertThat(staged.size).isEqualTo(29)
        assertThat(staged.file.length()).isEqualTo(29)
        assertThat(staged.sourceIdentity).endsWith(":29")
    }

    @Test
    fun `short provider read is permanent and leaves no published or partial file`() = runTest {
        val directory = temporaryFolder.newFolder("short")

        val error = runCatching {
            UploadStagingStore(directory).stage("work-short", 5) { "four".byteInputStream() }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(UploadPermanentException::class.java)
        assertThat(directory.listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun `remove clears blob metadata and partial remnants`() = runTest {
        val directory = temporaryFolder.newFolder("cleanup")
        val store = UploadStagingStore(directory)
        val staged = store.stage("work-clean", 4) { "data".byteInputStream() }

        store.remove("work-clean")

        assertThat(staged.file.exists()).isFalse()
        assertThat(directory.listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun `stale staging pruning is age bounded and excludes active work`() = runTest {
        val directory = temporaryFolder.newFolder("stale-staging")
        val store = UploadStagingStore(directory)
        val old = store.stage("old-work", 3) { "old".byteInputStream() }
        val active = store.stage("active-work", 6) { "active".byteInputStream() }
        directory.listFiles().orEmpty().forEach { it.setLastModified(10) }

        val removed = store.pruneOlderThan(
            cutoffMillis = 50,
            maxEntries = 32,
            excludingKeys = setOf("active-work"),
        )

        assertThat(removed).isEqualTo(1)
        assertThat(old.file.exists()).isFalse()
        assertThat(active.file.exists()).isTrue()
    }
}
