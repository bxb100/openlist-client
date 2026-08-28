package org.openlist.mobile.ui

import androidx.compose.material3.TopAppBarState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserHeaderScrollLayoutTest {
    @Test
    fun `every partial official state offset produces a matching visible height`() {
        val state = topAppBarState()
        updateBrowserHeaderHeight(state, fullHeightPx = 120f)

        state.heightOffset = -18f
        assertThat(browserHeaderVisibleHeight(120, state.heightOffset)).isEqualTo(102)

        state.heightOffset = -40f
        assertThat(browserHeaderVisibleHeight(120, state.heightOffset)).isEqualTo(80)

        state.heightOffset = -91.5f
        assertThat(browserHeaderVisibleHeight(120, state.heightOffset)).isEqualTo(29)
    }

    @Test
    fun `official state collapses through only the measured app bar height`() {
        val state = topAppBarState()

        updateBrowserHeaderHeight(state, fullHeightPx = 64f)
        state.heightOffset = -500f

        assertThat(state.heightOffsetLimit).isEqualTo(-64f)
        assertThat(state.heightOffset).isEqualTo(-64f)
        assertThat(browserHeaderVisibleHeight(64, state.heightOffset)).isEqualTo(0)
    }

    @Test
    fun `height changes preserve a partially collapsed fraction`() {
        val state = topAppBarState()
        updateBrowserHeaderHeight(state, fullHeightPx = 100f)
        state.heightOffset = -25f

        updateBrowserHeaderHeight(state, fullHeightPx = 200f)

        assertThat(state.heightOffsetLimit).isEqualTo(-200f)
        assertThat(state.heightOffset).isEqualTo(-50f)
        assertThat(browserHeaderVisibleHeight(200, state.heightOffset)).isEqualTo(150)
    }

    @Test
    fun `reset expands a half collapsed header and clears official content offset`() {
        val state = topAppBarState()
        updateBrowserHeaderHeight(state, fullHeightPx = 100f)
        state.heightOffset = -50f
        state.contentOffset = -340f

        resetBrowserHeaderScroll(state)

        assertThat(state.heightOffset).isEqualTo(0f)
        assertThat(state.contentOffset).isEqualTo(0f)
        assertThat(browserHeaderVisibleHeight(100, state.heightOffset)).isEqualTo(100)
    }

    @Test
    fun `visible height is clamped to valid layout bounds`() {
        assertThat(browserHeaderVisibleHeight(100, heightOffsetPx = 40f)).isEqualTo(100)
        assertThat(browserHeaderVisibleHeight(100, heightOffsetPx = -140f)).isEqualTo(0)
    }

    @Test
    fun `invalid measured heights do not corrupt official state`() {
        val state = topAppBarState()

        updateBrowserHeaderHeight(state, Float.NaN)
        updateBrowserHeaderHeight(state, -1f)

        assertThat(state.heightOffsetLimit).isEqualTo(-Float.MAX_VALUE)
        assertThat(state.heightOffset).isEqualTo(0f)
    }

    private fun topAppBarState(): TopAppBarState = TopAppBarState(
        initialHeightOffsetLimit = -Float.MAX_VALUE,
        initialHeightOffset = 0f,
        initialContentOffset = 0f,
    )
}
