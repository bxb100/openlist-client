package org.openlist.mobile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackControllerDisposalTest {
    @Test
    fun `deferred disposal runs only after the scheduler executes it`() {
        val calls = mutableListOf<String>()
        var scheduled: Runnable? = null

        disposePlaybackControllerAfterUiDetach(
            deferDisposal = true,
            scheduleDisposal = { action ->
                calls += "schedule"
                scheduled = action
                true
            },
            dispose = { calls += "dispose" },
        )

        assertThat(calls).containsExactly("schedule").inOrder()

        scheduled?.run()

        assertThat(calls).containsExactly("schedule", "dispose").inOrder()
    }

    @Test
    fun `disposal falls back to immediate execution when scheduling is unavailable`() {
        val calls = mutableListOf<String>()

        disposePlaybackControllerAfterUiDetach(
            deferDisposal = true,
            scheduleDisposal = {
                calls += "schedule"
                false
            },
            dispose = { calls += "dispose" },
        )

        assertThat(calls).containsExactly("schedule", "dispose").inOrder()
    }

    @Test
    fun `disposal runs immediately when deferral is disabled`() {
        val calls = mutableListOf<String>()

        disposePlaybackControllerAfterUiDetach(
            deferDisposal = false,
            scheduleDisposal = {
                calls += "schedule"
                true
            },
            dispose = { calls += "dispose" },
        )

        assertThat(calls).containsExactly("dispose").inOrder()
    }
}
