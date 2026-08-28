package org.openlist.mobile

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLoadCoordinatorTest {
    @Test
    fun `stalled first load becomes a recoverable timeout instead of loading forever`() = runTest {
        val failures = mutableListOf<Throwable>()
        val coordinator = SessionLoadCoordinator(
            scope = this,
            timeoutMillis = 1_000L,
            load = { awaitCancellation() },
            onFailure = failures::add,
        )

        assertThat(coordinator.retry()).isTrue()
        runCurrent()
        assertThat(coordinator.state.value).isEqualTo(SessionLoadState.Loading)

        advanceTimeBy(1_000L)
        runCurrent()

        assertThat(coordinator.state.value).isEqualTo(
            SessionLoadState.Failed(SESSION_LOAD_TIMEOUT_MESSAGE),
        )
        assertThat(failures).hasSize(1)
    }

    @Test
    fun `repeated taps share one bounded attempt and retry can recover`() = runTest {
        var loadCount = 0
        var nextAttemptSucceeds = false
        val coordinator = SessionLoadCoordinator(
            scope = this,
            timeoutMillis = 500L,
            load = {
                loadCount += 1
                if (!nextAttemptSucceeds) awaitCancellation()
            },
        )

        assertThat(coordinator.retry()).isTrue()
        runCurrent()
        assertThat(coordinator.retry()).isFalse()
        assertThat(coordinator.retry()).isFalse()
        assertThat(loadCount).isEqualTo(1)

        advanceTimeBy(500L)
        runCurrent()
        assertThat(coordinator.state.value).isInstanceOf(SessionLoadState.Failed::class.java)

        nextAttemptSucceeds = true
        assertThat(coordinator.retry()).isTrue()
        runCurrent()

        assertThat(loadCount).isEqualTo(2)
        assertThat(coordinator.state.value).isEqualTo(SessionLoadState.Ready)
        assertThat(coordinator.retry()).isFalse()
    }

    @Test
    fun `every retry receives a fresh timeout deadline`() = runTest {
        var loadCount = 0
        val coordinator = SessionLoadCoordinator(
            scope = this,
            timeoutMillis = 250L,
            load = {
                loadCount += 1
                awaitCancellation()
            },
        )

        repeat(2) {
            assertThat(coordinator.retry()).isTrue()
            runCurrent()
            advanceTimeBy(250L)
            runCurrent()
            assertThat(coordinator.state.value).isEqualTo(
                SessionLoadState.Failed(SESSION_LOAD_TIMEOUT_MESSAGE),
            )
        }

        assertThat(loadCount).isEqualTo(2)
    }
}
