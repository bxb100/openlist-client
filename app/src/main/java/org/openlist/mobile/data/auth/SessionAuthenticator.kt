package org.openlist.mobile.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openlist.mobile.data.account.AccountRecord
import org.openlist.mobile.data.api.HttpSessionSnapshot
import org.openlist.mobile.data.api.OpenListApi
import org.openlist.mobile.data.api.OpenListApiException
import org.openlist.mobile.data.api.OpenListHttpClient
import org.openlist.mobile.data.preferences.SessionStore
import java.security.GeneralSecurityException

/** Renews one login generation without changing accounts or interrupting signed-in navigation. */
internal class SessionAuthenticator(
    private val sessionStore: SessionStore,
    private val credentialCipher: LoginCredentialCipher,
    private val onAuthenticationRequired: (String) -> Unit,
) {
    private val refreshMutex = Mutex()

    fun snapshot(): HttpSessionSnapshot = sessionStore.authenticationSnapshot()?.httpSnapshot()
        ?: HttpSessionSnapshot("", null, false)

    suspend fun refresh(session: HttpSessionSnapshot): HttpSessionSnapshot? = refreshMutex.withLock {
        val account = sessionStore.authenticationSnapshot() ?: return@withLock null
        if (account.id != session.accountId ||
            account.server.baseUrl != session.baseUrl ||
            account.server.allowInsecureHttp != session.allowInsecureHttp ||
            account.sessionBindingKey != session.sessionBindingKey || account.token.isBlank()
        ) return@withLock null
        // Requests that failed together reuse the token committed by the first renewal.
        if (account.token != session.token) return@withLock account.httpSnapshot()
        if (account.encryptedPasswordHash.isBlank()) {
            requireLogin(account, "登录已过期，请重新登录一次以启用自动续期")
        }
        val passwordHash = try {
            withContext(Dispatchers.IO) {
                credentialCipher.decrypt(account.encryptedPasswordHash, account.id)
            }
        } catch (_: GeneralSecurityException) {
            requireLogin(account, "登录凭据无法读取，请重新登录")
        } catch (_: IllegalArgumentException) {
            requireLogin(account, "登录凭据无法读取，请重新登录")
        }
        // OpenList has no refresh endpoint. Keep reauthentication bound to the original server,
        // and omit both the expired token and a renewal callback to avoid recursive retries.
        val loginApi = OpenListApi(
            OpenListHttpClient(
                baseUrl = { account.server.baseUrl },
                token = { null },
                allowInsecureHttp = { account.server.allowInsecureHttp },
            ),
        )
        val login = try {
            loginApi.loginWithHash(account.server.username, passwordHash)
        } catch (error: OpenListApiException) {
            when (error.apiCode) {
                402 -> requireLogin(account, "登录已过期，请重新登录并输入两步验证码")
                400, 401, 403 -> requireLogin(account, "登录凭据已失效，请重新登录")
                else -> throw error
            }
        }
        if (login.token.isBlank()) throw OpenListApiException(401, "登录响应未包含 token")
        if (!sessionStore.replaceAuthentication(account, login.token)) return@withLock null
        account.updated(token = login.token).httpSnapshot()
    }

    private suspend fun requireLogin(account: AccountRecord, message: String): Nothing {
        if (sessionStore.replaceAuthentication(account, "", clearCredentials = true)) {
            onAuthenticationRequired(message)
        }
        throw OpenListApiException(401, message)
    }
}

private fun AccountRecord.httpSnapshot(): HttpSessionSnapshot = HttpSessionSnapshot(
    baseUrl = server.baseUrl,
    token = token,
    allowInsecureHttp = server.allowInsecureHttp,
    accountId = id,
    sessionBindingKey = sessionBindingKey,
)
