package org.openlist.mobile.data.upload

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.api.OpenListHttpClient

/** Authentication and routing captured once for a single background upload job. */
class JobBoundUploadSession private constructor(
    val baseUrl: String,
    val username: String,
    private val allowInsecureHttp: Boolean,
    private val token: String,
) {
    val serverScope: String = "$baseUrl|$username"

    fun newHttpClient(
        okHttpClient: OkHttpClient,
        gson: Gson,
        isSessionCurrent: () -> Boolean,
    ): OpenListHttpClient {
        val guard = Interceptor { chain ->
            if (!isSessionCurrent()) {
                throw UploadPermanentException("登录服务器、账号或凭据已变化，上传已停止")
            }
            chain.proceed(chain.request())
        }
        val guardedClient = okHttpClient.newBuilder()
            // Fail before DNS/connect as well as before every physical redirect/retry exchange.
            .addInterceptor(guard)
            // A network interceptor runs once per physical request, including redirect hops.
            .addNetworkInterceptor(guard)
            .build()
        return OpenListHttpClient(
            baseUrl = { baseUrl },
            token = { token },
            allowInsecureHttp = { allowInsecureHttp },
            okHttpClient = guardedClient,
            gson = gson,
        )
    }

    fun matchesCurrent(
        baseUrl: String,
        username: String,
        allowInsecureHttp: Boolean,
        token: String,
    ): Boolean =
        normalizeBaseUrl(baseUrl) == this.baseUrl &&
            username == this.username &&
            allowInsecureHttp == this.allowInsecureHttp &&
            token == this.token

    companion object {
        fun capture(
            expectedBaseUrl: String,
            expectedUsername: String,
            expectedAllowInsecureHttp: Boolean,
            expectedSessionBinding: UploadSessionBinding,
            currentBaseUrl: String,
            currentUsername: String,
            currentAllowInsecureHttp: Boolean,
            currentToken: String,
        ): JobBoundUploadSession {
            val expectedBase = normalizeBaseUrl(expectedBaseUrl)
            val currentBase = normalizeBaseUrl(currentBaseUrl)
            if (expectedBase.isBlank()) {
                throw UploadPermanentException("上传任务缺少服务器地址")
            }
            if (currentBase != expectedBase ||
                currentUsername != expectedUsername ||
                currentAllowInsecureHttp != expectedAllowInsecureHttp
            ) {
                throw UploadPermanentException("登录服务器或账号已变化，请在当前目录重新选择文件")
            }
            if (currentBase.startsWith("http://", ignoreCase = true) &&
                !currentAllowInsecureHttp
            ) {
                throw UploadPermanentException("上传任务未获准连接局域网 HTTP 服务器")
            }
            if (currentToken.isBlank()) {
                throw UploadPermanentException("登录凭据已失效，请重新登录后选择文件")
            }
            val currentProfile = ServerProfile(
                baseUrl = currentBase,
                username = currentUsername,
                allowInsecureHttp = currentAllowInsecureHttp,
            )
            if (!expectedSessionBinding.matches(UploadSessionBinding.create(currentProfile, currentToken))) {
                throw UploadPermanentException("登录凭据已变化，请在当前目录重新选择文件")
            }
            return JobBoundUploadSession(
                baseUrl = currentBase,
                username = currentUsername,
                allowInsecureHttp = currentAllowInsecureHttp,
                token = currentToken,
            )
        }

        private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')
    }
}
