package org.openlist.mobile.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.OpenListObject

class BrowserScreenStateTest {
    @Test
    fun `directory summary hides placeholder providers but keeps real provider names`() {
        assertThat(browserDirectorySummary(total = 12, provider = "unknown"))
            .isEqualTo("12 项")
        assertThat(browserDirectorySummary(total = 12, provider = "  UNKNOWN  "))
            .isEqualTo("12 项")
        assertThat(browserDirectorySummary(total = 12, provider = "  WebDav  "))
            .isEqualTo("12 项 · WebDav")
        assertThat(visibleStorageProvider("   ")).isNull()
    }

    @Test
    fun `name sorting keeps directories first and uses a stable path tie break`() {
        val entries = listOf(
            browserEntry("/files/beta.txt", "/files", objectOf("beta.txt")),
            browserEntry("/files/folder-b", "/files", objectOf("same", isDirectory = true)),
            browserEntry("/files/Alpha.txt", "/files", objectOf("Alpha.txt")),
            browserEntry("/files/folder-a", "/files", objectOf("same", isDirectory = true)),
        )

        val ascending = sortBrowserEntries(entries, BrowserSort())
        val descending = sortBrowserEntries(
            entries,
            BrowserSort(direction = BrowserSortDirection.Descending),
        )

        assertThat(ascending.map(BrowserEntry::path)).containsExactly(
            "/files/folder-a",
            "/files/folder-b",
            "/files/Alpha.txt",
            "/files/beta.txt",
        ).inOrder()
        assertThat(descending.map(BrowserEntry::path)).containsExactly(
            "/files/folder-a",
            "/files/folder-b",
            "/files/beta.txt",
            "/files/Alpha.txt",
        ).inOrder()
    }

    @Test
    fun `modified sorting compares instants and keeps missing values last in both directions`() {
        val entries = listOf(
            browserEntry(
                "/files/earlier.txt",
                "/files",
                objectOf("earlier.txt", modified = "2026-08-27T23:00:00+12:00"),
            ),
            browserEntry(
                "/files/unknown.txt",
                "/files",
                objectOf("unknown.txt", modified = ""),
            ),
            browserEntry(
                "/files/later.txt",
                "/files",
                objectOf("later.txt", modified = "2026-08-27T12:00:00Z"),
            ),
        )

        val ascending = sortBrowserEntries(
            entries,
            BrowserSort(BrowserSortField.Modified, BrowserSortDirection.Ascending),
        )
        val descending = sortBrowserEntries(
            entries,
            BrowserSort(BrowserSortField.Modified, BrowserSortDirection.Descending),
        )

        assertThat(ascending.map { it.item.name })
            .containsExactly("earlier.txt", "later.txt", "unknown.txt").inOrder()
        assertThat(descending.map { it.item.name })
            .containsExactly("later.txt", "earlier.txt", "unknown.txt").inOrder()
    }

    @Test
    fun `size sorting treats zero as valid and negative size as missing`() {
        val entries = listOf(
            browserEntry("/files/large.bin", "/files", objectOf("large.bin", size = 50)),
            browserEntry("/files/unknown.bin", "/files", objectOf("unknown.bin", size = -1)),
            browserEntry("/files/empty.bin", "/files", objectOf("empty.bin", size = 0)),
        )

        val ascending = sortBrowserEntries(
            entries,
            BrowserSort(BrowserSortField.Size, BrowserSortDirection.Ascending),
        )
        val descending = sortBrowserEntries(
            entries,
            BrowserSort(BrowserSortField.Size, BrowserSortDirection.Descending),
        )

        assertThat(ascending.map { it.item.name })
            .containsExactly("empty.bin", "large.bin", "unknown.bin").inOrder()
        assertThat(descending.map { it.item.name })
            .containsExactly("large.bin", "empty.bin", "unknown.bin").inOrder()
    }

    @Test
    fun `type sorting uses media categories and keeps directories first`() {
        val entries = listOf(
            browserEntry("/files/readme.txt", "/files", objectOf("readme.txt", type = 4)),
            browserEntry("/files/movie.mp4", "/files", objectOf("movie.mp4", type = 2)),
            browserEntry("/files/photo.jpg", "/files", objectOf("photo.jpg", type = 5)),
            browserEntry("/files/track.mp3", "/files", objectOf("track.mp3", type = 3)),
            browserEntry("/files/archive.zip", "/files", objectOf("archive.zip")),
            browserEntry("/files/folder", "/files", objectOf("folder", isDirectory = true)),
        )

        val sorted = sortBrowserEntries(
            entries,
            BrowserSort(BrowserSortField.Type, BrowserSortDirection.Ascending),
        )

        assertThat(sorted.map { it.item.name }).containsExactly(
            "folder",
            "photo.jpg",
            "movie.mp4",
            "track.mp3",
            "readme.txt",
            "archive.zip",
        ).inOrder()
    }

    @Test
    fun `stable directory siblings keep first order filter other parents and replace selected metadata`() {
        val selected = browserEntry(
            path = "/photos/current.jpg",
            parent = "/photos",
            item = objectOf("current.jpg", size = 42),
        )
        val siblings = stableDirectorySiblingEntries(
            selected = selected,
            candidates = listOf(
                browserEntry("/photos/cover.jpg", "/photos", objectOf("cover.jpg")),
                browserEntry("/photos/current.jpg", "/photos", objectOf("current.jpg", size = 1)),
                browserEntry("/archive/current.jpg", "/archive", objectOf("current.jpg", size = 7)),
                browserEntry("/photos/movie.mp4", "/photos", objectOf("movie.mp4", type = 3)),
                browserEntry("/photos/current.jpg", "/photos", objectOf("current.jpg", size = 99)),
            ),
        )

        assertThat(siblings.map(BrowserEntry::path))
            .containsExactly("/photos/cover.jpg", "/photos/current.jpg", "/photos/movie.mp4")
            .inOrder()
        assertThat(siblings[1].item.size).isEqualTo(42)
    }

    private fun browserEntry(
        path: String,
        parent: String,
        item: OpenListObject,
    ) = BrowserEntry(
        path = path,
        parent = parent,
        item = item,
    )

    private fun objectOf(
        name: String,
        type: Int = 0,
        size: Long = 1,
        modified: String = "revision",
        isDirectory: Boolean = false,
    ) = OpenListObject(
        name = name,
        type = type,
        size = size,
        modified = modified,
        isDirectory = isDirectory,
    )
}
