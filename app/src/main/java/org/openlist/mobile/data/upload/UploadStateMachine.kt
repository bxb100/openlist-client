package org.openlist.mobile.data.upload

import org.openlist.mobile.data.api.dto.MultipartSession

sealed interface UploadAction {
    data object Complete : UploadAction
    data object Finished : UploadAction
    data class SendChunks(val indices: List<Int>) : UploadAction
    data class Fail(val reason: String) : UploadAction
}

object UploadStateMachine {
    fun next(session: MultipartSession): UploadAction {
        if (session.uploadId.isBlank()) return UploadAction.Fail("服务器未返回 upload_id")
        if (session.chunkSize <= 0 || session.totalChunks <= 0) {
            return UploadAction.Fail("服务器返回了无效的分片参数")
        }
        return when (session.state.lowercase()) {
            "completed" -> UploadAction.Finished
            "failed_permanent", "aborted" -> UploadAction.Fail(
                session.error?.takeIf(String::isNotBlank) ?: "上传会话状态为 ${session.state}",
            )
            // OpenList only accepts chunk zero in this state. Its response starts a fresh
            // receiving attempt; the next state-machine pass then uses the new missing ranges.
            "failed_retriable" -> UploadAction.SendChunks(listOf(0))
            "receiving" -> {
                val missing = ChunkRanges.missing(session.totalChunks, session.received)
                if (missing.isEmpty()) UploadAction.Complete else UploadAction.SendChunks(missing)
            }
            else -> UploadAction.Fail("未知的上传会话状态：${session.state}")
        }
    }
}
