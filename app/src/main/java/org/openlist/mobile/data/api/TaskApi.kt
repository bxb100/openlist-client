package org.openlist.mobile.data.api

import com.google.gson.JsonElement
import okhttp3.Response
import org.openlist.mobile.data.api.catalog.EndpointAliasKind
import org.openlist.mobile.data.api.catalog.EndpointCatalog
import org.openlist.mobile.data.api.catalog.GenericOpenListService
import org.openlist.mobile.data.api.catalog.TaskRequestShape
import org.openlist.mobile.data.api.dto.TaskInfo

typealias TaskKind = org.openlist.mobile.data.api.catalog.TaskKind
typealias TaskAction = org.openlist.mobile.data.api.catalog.TaskAction

/** Strongly typed task operations with a generic escape hatch for all 7 × 12 task routes. */
class TaskApi(val service: GenericOpenListService) {
    constructor(http: OpenListHttpClient) : this(GenericOpenListService(http))

    suspend fun list(kind: TaskKind, completed: Boolean): List<TaskInfo> =
        execute(kind, if (completed) TaskAction.DONE else TaskAction.UNDONE)

    suspend fun undone(kind: TaskKind): List<TaskInfo> = execute(kind, TaskAction.UNDONE)

    suspend fun done(kind: TaskKind): List<TaskInfo> = execute(kind, TaskAction.DONE)

    suspend fun info(kind: TaskKind, taskId: String): TaskInfo =
        execute(kind, TaskAction.INFO, taskId = taskId)

    suspend fun cancel(kind: TaskKind, taskId: String) =
        execute<Unit>(kind, TaskAction.CANCEL, taskId = taskId)

    suspend fun delete(kind: TaskKind, taskId: String) =
        execute<Unit>(kind, TaskAction.DELETE, taskId = taskId)

    suspend fun retry(kind: TaskKind, taskId: String) =
        execute<Unit>(kind, TaskAction.RETRY, taskId = taskId)

    suspend fun cancelSome(kind: TaskKind, taskIds: List<String>): Map<String, String> =
        execute(kind, TaskAction.CANCEL_SOME, taskIds = taskIds)

    suspend fun deleteSome(kind: TaskKind, taskIds: List<String>): Map<String, String> =
        execute(kind, TaskAction.DELETE_SOME, taskIds = taskIds)

    suspend fun retrySome(kind: TaskKind, taskIds: List<String>): Map<String, String> =
        execute(kind, TaskAction.RETRY_SOME, taskIds = taskIds)

    suspend fun clearDone(kind: TaskKind) = execute<Unit>(kind, TaskAction.CLEAR_DONE)

    suspend fun clearSucceeded(kind: TaskKind) = execute<Unit>(kind, TaskAction.CLEAR_SUCCEEDED)

    suspend fun retryFailed(kind: TaskKind) = execute<Unit>(kind, TaskAction.RETRY_FAILED)

    suspend inline fun <reified T> execute(
        kind: TaskKind,
        action: TaskAction,
        taskId: String? = null,
        taskIds: List<String>? = null,
        legacyAdminAlias: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): T {
        validateArguments(action, taskId, taskIds)
        return service.call(
            endpoint = EndpointCatalog.task(kind, action),
            body = if (action.requestShape == TaskRequestShape.TASK_IDS_BODY) taskIds else null,
            query = if (action.requestShape == TaskRequestShape.TASK_ID_QUERY) {
                mapOf("tid" to taskId)
            } else {
                emptyMap()
            },
            headers = headers,
            aliasKind = if (legacyAdminAlias) EndpointAliasKind.LEGACY_ADMIN_TASK else null,
        )
    }

    suspend fun executeJson(
        kind: TaskKind,
        action: TaskAction,
        taskId: String? = null,
        taskIds: List<String>? = null,
        legacyAdminAlias: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = execute(kind, action, taskId, taskIds, legacyAdminAlias, headers)

    /** Raw task response; the caller owns and must close it. */
    suspend fun raw(
        kind: TaskKind,
        action: TaskAction,
        taskId: String? = null,
        taskIds: List<String>? = null,
        legacyAdminAlias: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        validateArguments(action, taskId, taskIds)
        return service.raw(
            endpoint = EndpointCatalog.task(kind, action),
            body = if (action.requestShape == TaskRequestShape.TASK_IDS_BODY) {
                service.http.jsonBody(taskIds)
            } else {
                null
            },
            query = if (action.requestShape == TaskRequestShape.TASK_ID_QUERY) {
                mapOf("tid" to taskId)
            } else {
                emptyMap()
            },
            headers = headers,
            aliasKind = if (legacyAdminAlias) EndpointAliasKind.LEGACY_ADMIN_TASK else null,
        )
    }

    companion object {
        fun validateArguments(action: TaskAction, taskId: String?, taskIds: List<String>?) {
            when (action.requestShape) {
                TaskRequestShape.NONE -> {
                    require(taskId == null) { "${action.segment} does not accept taskId" }
                    require(taskIds == null) { "${action.segment} does not accept taskIds" }
                }

                TaskRequestShape.TASK_ID_QUERY -> {
                    require(!taskId.isNullOrBlank()) { "${action.segment} requires a non-blank taskId" }
                    require(taskIds == null) { "${action.segment} does not accept taskIds" }
                }

                TaskRequestShape.TASK_IDS_BODY -> {
                    require(taskId == null) { "${action.segment} does not accept taskId" }
                    requireNotNull(taskIds) { "${action.segment} requires taskIds" }
                    require(taskIds.none(String::isBlank)) { "taskIds must not contain blank ids" }
                }
            }
        }
    }
}

