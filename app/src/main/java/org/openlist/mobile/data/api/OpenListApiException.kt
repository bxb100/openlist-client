package org.openlist.mobile.data.api

import com.google.gson.JsonElement
import java.io.IOException

class OpenListApiException(
    val apiCode: Int,
    override val message: String,
    val httpStatus: Int? = null,
    val responseData: JsonElement? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

