package org.openlist.mobile.media.gallery

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.media.ContentKey
import org.openlist.mobile.media.MediaEntry

class GalleryStateTest {
    @Test
    fun `navigation remains within gallery bounds`() {
        val state = GalleryState(
            items = listOf(image("one"), image("two"), image("three")),
            initialIndex = 1,
        )

        assertThat(state.current.name).isEqualTo("two.jpg")
        assertThat(state.previous()).isTrue()
        assertThat(state.previous()).isFalse()
        assertThat(state.currentIndex).isEqualTo(0)
        assertThat(state.next()).isTrue()
        assertThat(state.next()).isTrue()
        assertThat(state.next()).isFalse()
        assertThat(state.currentIndex).isEqualTo(2)
    }

    private fun image(name: String) = MediaEntry(
        remotePath = "/$name.jpg",
        name = "$name.jpg",
        kind = MediaKind.IMAGE,
        size = 1,
        modified = "revision",
        contentKey = ContentKey("key-$name"),
    )
}
