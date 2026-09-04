package org.openlist.mobile.data.download

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.openlist.mobile.data.api.HttpSessionSnapshot
import org.openlist.mobile.data.api.OpenListApi
import org.openlist.mobile.data.api.OpenListHttpClient
import org.openlist.mobile.data.preferences.AppSettings
import org.openlist.mobile.media.MediaUrlResolver
import org.openlist.mobile.media.OpenListMediaUrlResolver
import org.openlist.mobile.media.normalizeRemotePath

/** Fixed server and login identity for one download; tokens may renew within that login. */
class JobBoundDownloadSession private constructor(
    val baseUrl: String,
    val username: String,
    private val allowInsecureHttp: Boolean,
    private val token: String,
    private val sessionBindingKey: String,
    private val binding: DownloadSessionBinding,
) {
    fun newHttpClient(
        okHttpClient: OkHttpClient,
        gson: Gson,
        currentSnapshot: (() -> HttpSessionSnapshot)? = null,
        refreshSession: (suspend (HttpSessionSnapshot) -> HttpSessionSnapshot?)? = null,
        isSessionCurrent: () -> Boolean,
    ): OpenListHttpClient {
        val guard = Interceptor { chain ->
            if (!isSessionCurrent()) {
                throw DownloadSessionChangedException()
            }
            chain.proceed(chain.request())
        }
        val guardedClient = okHttpClient.newBuilder()
            .addInterceptor(guard)
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
                throw DownloadSessionChangedException()
            }
            return current
        }
        return OpenListHttpClient(
            baseUrl = { fixed.baseUrl },
            token = { fixed.token },
            allowInsecureHttp = { fixed.allowInsecureHttp },
            okHttpClient = guardedClient,
            gson = gson,
            sessionSnapshot = {
                if (currentSnapshot == null) fixed else {
                    if (!isSessionCurrent()) throw DownloadSessionChangedException()
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

    /** Fixed `/api/fs/get` resolver with one process-memory-only password for one exact path. */
    fun newResolver(
        okHttpClient: OkHttpClient,
        gson: Gson,
        remotePath: String,
        pathPassword: String,
        currentSnapshot: (() -> HttpSessionSnapshot)? = null,
        refreshSession: (suspend (HttpSessionSnapshot) -> HttpSessionSnapshot?)? = null,
        isSessionCurrent: () -> Boolean,
    ): MediaUrlResolver {
        val expectedPath = normalizeRemotePath(remotePath)
        val delegate = OpenListMediaUrlResolver(
            api = OpenListApi(
                newHttpClient(okHttpClient, gson, currentSnapshot, refreshSession, isSessionCurrent),
            ),
            passwordForPath = { requestedPath ->
                if (normalizeRemotePath(requestedPath) == expectedPath) pathPassword else ""
            },
        )
        return MediaUrlResolver { requestedPath ->
            if (!isSessionCurrent()) throw DownloadSessionChangedException()
            val resolved = delegate.resolve(requestedPath)
            if (!isSessionCurrent()) throw DownloadSessionChangedException()
            resolved
        }
    }

    fun matchesCurrent(settings: AppSettings): Boolean =
        settings.token.isNotBlank() &&
            settings.server.allowInsecureHttp == allowInsecureHttp &&
            binding.matches(
                DownloadSessionBinding.create(
                    settings.server,
                    settings.sessionBindingKey.ifBlank { settings.token },
                ),
            )

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
            expectedBinding: DownloadSessionBinding,
            current: AppSettings,
        ): JobBoundDownloadSession {
            val normalizedBase = normalizeBaseUrl(current.server.baseUrl)
            if (normalizedBase.isBlank() || current.server.username.isBlank() || current.token.isBlank()) {
                throw DownloadSessionChangedException("登录凭据已失效，请重新发起下载")
            }
            val sessionBindingKey = current.sessionBindingKey.ifBlank { current.token }
            val currentBinding = DownloadSessionBinding.create(current.server, sessionBindingKey)
            if (!expectedBinding.matches(currentBinding)) {
                throw DownloadSessionChangedException()
            }
            return JobBoundDownloadSession(
                baseUrl = current.server.normalizedBaseUrl(),
                username = current.server.username,
                allowInsecureHttp = current.server.allowInsecureHttp,
                token = current.token,
                sessionBindingKey = sessionBindingKey,
                binding = expectedBinding,
            )
        }

        private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')
    }
}
