package org.openlist.mobile.data.api.catalog

/** The seven task managers registered by OpenList v4.2.5. */
enum class TaskKind(val segment: String) {
    UPLOAD("upload"),
    COPY("copy"),
    MOVE("move"),
    OFFLINE_DOWNLOAD("offline_download"),
    OFFLINE_DOWNLOAD_TRANSFER("offline_download_transfer"),
    DECOMPRESS("decompress"),
    DECOMPRESS_UPLOAD("decompress_upload"),
}

enum class TaskRequestShape {
    NONE,
    TASK_ID_QUERY,
    TASK_IDS_BODY,
}

/** The same twelve operations are registered for every [TaskKind]. */
enum class TaskAction(
    val segment: String,
    val method: ApiHttpMethod,
    val requestShape: TaskRequestShape,
) {
    UNDONE("undone", ApiHttpMethod.GET, TaskRequestShape.NONE),
    DONE("done", ApiHttpMethod.GET, TaskRequestShape.NONE),
    INFO("info", ApiHttpMethod.POST, TaskRequestShape.TASK_ID_QUERY),
    CANCEL("cancel", ApiHttpMethod.POST, TaskRequestShape.TASK_ID_QUERY),
    DELETE("delete", ApiHttpMethod.POST, TaskRequestShape.TASK_ID_QUERY),
    RETRY("retry", ApiHttpMethod.POST, TaskRequestShape.TASK_ID_QUERY),
    CANCEL_SOME("cancel_some", ApiHttpMethod.POST, TaskRequestShape.TASK_IDS_BODY),
    DELETE_SOME("delete_some", ApiHttpMethod.POST, TaskRequestShape.TASK_IDS_BODY),
    RETRY_SOME("retry_some", ApiHttpMethod.POST, TaskRequestShape.TASK_IDS_BODY),
    CLEAR_DONE("clear_done", ApiHttpMethod.POST, TaskRequestShape.NONE),
    CLEAR_SUCCEEDED("clear_succeeded", ApiHttpMethod.POST, TaskRequestShape.NONE),
    RETRY_FAILED("retry_failed", ApiHttpMethod.POST, TaskRequestShape.NONE),
}
