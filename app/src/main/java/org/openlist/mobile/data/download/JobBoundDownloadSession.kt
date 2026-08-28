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

/** Authentication and routing captured once for a single background download job. */
class JobBoundDownloadSession private constructor(
    val baseUrl: String,
    val username: String,
    private val allowInsecureHttp: Boolean,
    private val token: String,
    private val binding: DownloadSessionBinding,
) {
    fun newHttpClient(
        okHttpClient: OkHttpClient,
        gson: Gson,
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
        )
        return OpenListHttpClient(
            baseUrl = { fixed.baseUrl },
            token = { fixed.token },
            allowInsecureHttp = { fixed.allowInsecureHttp },
            okHttpClient = guardedClient,
            gson = gson,
            sessionSnapshot = { fixed },
        )
    }

    /** Fixed `/api/fs/get` resolver with one process-memory-only password for one exact path. */
    fun newResolver(
        okHttpClient: OkHttpClient,
        gson: Gson,
        remotePath: String,
        pathPassword: String,
        isSessionCurrent: () -> Boolean,
    ): MediaUrlResolver {
        val expectedPath = normalizeRemotePath(remotePath)
        val delegate = OpenListMediaUrlResolver(
            api = OpenListApi(newHttpClient(okHttpClient, gson, isSessionCurrent)),
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

    fun matchesCurrent(settings: AppSettings): Boolean = binding.matches(
        DownloadSessionBinding.create(settings.server, settings.token),
    )

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
            expectedBinding: DownloadSessionBinding,
            current: AppSettings,
        ): JobBoundDownloadSession {
            val normalizedBase = normalizeBaseUrl(current.server.baseUrl)
            if (normalizedBase.isBlank() || current.server.username.isBlank() || current.token.isBlank()) {
                throw DownloadSessionChangedException("登录凭据已失效，请重新发起下载")
            }
            val currentBinding = DownloadSessionBinding.create(current.server, current.token)
            if (!expectedBinding.matches(currentBinding)) {
                throw DownloadSessionChangedException()
            }
            return JobBoundDownloadSession(
                baseUrl = current.server.normalizedBaseUrl(),
                username = current.server.username,
                allowInsecureHttp = current.server.allowInsecureHttp,
                token = current.token,
                binding = expectedBinding,
            )
        }

        private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')
    }
}
