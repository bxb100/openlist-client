package org.openlist.mobile.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.data.api.dto.SearchObject

class SearchObjectPathTest {
    @Test
    fun `documented remote path wins and supplies a consistent parent and name`() {
        val entry = SearchObject(
            parent = "/stale-parent",
            path = "/actual/subdirectory/report.pdf",
            name = "stale-name.pdf",
        ).toBrowserEntry()

        assertThat(entry.path).isEqualTo("/actual/subdirectory/report.pdf")
        assertThat(entry.parent).isEqualTo("/actual/subdirectory")
        assertThat(entry.item.name).isEqualTo("report.pdf")
        assertThat(entry.item.path).isEqualTo(entry.path)
    }

    @Test
    fun `physical URI and traversal paths fall back to upstream parent and name`() {
        val unsafePaths = listOf(
            "C:\\storage\\report.pdf",
            "https://files.example/report.pdf",
            "/https://files.example/report.pdf",
            "/safe/../private/report.pdf",
            "/safe/%2e%2e/private/report.pdf",
        )

        unsafePaths.forEach { unsafePath ->
            val entry = SearchObject(
                parent = "/search-root",
                path = unsafePath,
                name = "report.pdf",
            ).toBrowserEntry()

            assertThat(entry.path).isEqualTo("/search-root/report.pdf")
            assertThat(entry.parent).isEqualTo("/search-root")
            assertThat(entry.item.name).isEqualTo("report.pdf")
        }
    }
}
