package org.openlist.mobile.data.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.openlist.mobile.data.api.dto.MultipartSession

class UploadStateMachineTest {
    @Test
    fun `receiving session sends only missing chunks then completes`() {
        val sending = UploadStateMachine.next(session(received = listOf(listOf(0, 1), listOf(3, 3))))
        assertThat(sending).isEqualTo(UploadAction.SendChunks(listOf(2)))

        val complete = UploadStateMachine.next(session(received = listOf(listOf(0, 3))))
        assertThat(complete).isEqualTo(UploadAction.Complete)
    }

    @Test
    fun `failed retriable session sends only chunk zero to restart the pipeline`() {
        val action = UploadStateMachine.next(
            session(state = "failed_retriable", received = listOf(listOf(2, 3))),
        )

        assertThat(action).isEqualTo(UploadAction.SendChunks(listOf(0)))
    }

    @Test
    fun `completed and permanent states are terminal`() {
        assertThat(UploadStateMachine.next(session(state = "completed")))
            .isEqualTo(UploadAction.Finished)
        assertThat(UploadStateMachine.next(session(state = "failed_permanent", error = "denied")))
            .isEqualTo(UploadAction.Fail("denied"))
    }

    private fun session(
        state: String = "receiving",
        received: List<List<Int>> = emptyList(),
        error: String? = null,
    ) = MultipartSession(
        uploadId = "upload-1",
        state = state,
        size = 8,
        chunkSize = 2,
        totalChunks = 4,
        received = received,
        error = error,
    )
}
