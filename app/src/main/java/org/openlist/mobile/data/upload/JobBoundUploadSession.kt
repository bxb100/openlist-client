package org.openlist.mobile.data.upload

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.api.HttpSessionSnapshot
import org.openlist.mobile.data.api.OpenListHttpClient

/** Fixed server and login identity for one upload; tokens may renew within that login. */
class JobBoundUploadSession private constructor(
    val baseUrl: String,
    val username: String,
    private val allowInsecureHttp: Boolean,
    private val token: String,
    private val sessionBindingKey: String,
) {
    val serverScope: String = "$baseUrl|$username"

    fun newHttpClient(
        okHttpClient: OkHttpClient,
        gson: Gson,
        currentSnapshot: (() -> HttpSessionSnapshot)? = null,
        refreshSession: (suspend (HttpSessionSnapshot) -> HttpSessionSnapshot?)? = null,
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
        val fixed = HttpSessionSnapshot(
            baseUrl = baseUrl,
            token = token,
            allowInsecureHttp = allowInsecureHttp,
            sessionBindingKey = sessionBindingKey,
        )
        fun validateSnapshot(current: HttpSessionSnapshot): HttpSessionSnapshot {
            if (!isSessionCurrent() ||
                normalizeBaseUrl(current.baseUrl) != baseUrl ||
                current.allowInsecureHttp != allowInsecureHttp ||
                current.token.isNullOrBlank() ||
                current.sessionBindingKey.ifBlank { current.token.orEmpty() } != sessionBindingKey
            ) {
                throw UploadPermanentException("登录服务器、账号或凭据已变化，上传已停止")
            }
            return current
        }
        return OpenListHttpClient(
            baseUrl = { baseUrl },
            token = { token },
            allowInsecureHttp = { allowInsecureHttp },
            okHttpClient = guardedClient,
            gson = gson,
            sessionSnapshot = {
                if (currentSnapshot == null) fixed else {
                    if (!isSessionCurrent()) {
                        throw UploadPermanentException("登录服务器、账号或凭据已变化，上传已停止")
                    }
                    validateSnapshot(currentSnapshot())
                }
            },
            refreshSession = if (refreshSession == null) {
                null
            } else {
                { expired ->
                    validateSnapshot(expired)
                    refreshSession(expired)?.let(::validateSnapshot)
                }
            },
        )
    }

    fun matchesCurrent(
        baseUrl: String,
        username: String,
        allowInsecureHttp: Boolean,
        token: String,
        sessionBindingKey: String = token,
    ): Boolean =
        normalizeBaseUrl(baseUrl) == this.baseUrl &&
            username == this.username &&
            allowInsecureHttp == this.allowInsecureHttp &&
            token.isNotBlank() && sessionBindingKey == this.sessionBindingKey

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
            currentSessionBindingKey: String = currentToken,
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
            if (!expectedSessionBinding.matches(UploadSessionBinding.create(currentProfile, currentSessionBindingKey))) {
                throw UploadPermanentException("登录凭据已变化，请在当前目录重新选择文件")
            }
            return JobBoundUploadSession(
                baseUrl = currentBase,
                username = currentUsername,
                allowInsecureHttp = currentAllowInsecureHttp,
                token = currentToken,
                sessionBindingKey = currentSessionBindingKey,
            )
        }

        private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')
    }
}
