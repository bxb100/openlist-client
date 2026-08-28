package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChunkRangesTest {
    @Test
    fun `missing treats server ranges as inclusive and merges overlap`() {
        val missing = ChunkRanges.missing(
            totalChunks = 10,
            received = listOf(listOf(4, 6), listOf(0, 1), listOf(1, 3), listOf(8, 8)),
        )

        assertThat(missing).containsExactly(7, 9).inOrder()
    }

    @Test
    fun `missing clips malformed and out of bounds ranges`() {
        val missing = ChunkRanges.missing(
            totalChunks = 5,
            received = listOf(listOf(-3, 0), listOf(4, 99), listOf(3, 2), listOf(1)),
        )

        assertThat(missing).containsExactly(1, 2, 3).inOrder()
    }
}
