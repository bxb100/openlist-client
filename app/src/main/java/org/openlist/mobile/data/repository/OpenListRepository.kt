package org.openlist.mobile.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.core.model.OpenListUser
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.api.OpenListApi
import org.openlist.mobile.data.api.OpenListApiException
import org.openlist.mobile.data.api.dto.SearchData
import org.openlist.mobile.data.account.AccountId
import org.openlist.mobile.data.auth.PasswordHasher
import org.openlist.mobile.data.auth.LoginCredentialCipher
import org.openlist.mobile.data.credentials.InMemoryPathCredentialStore
import org.openlist.mobile.data.preferences.AppSettings
import org.openlist.mobile.data.preferences.SessionStore

class OpenListRepository internal constructor(
    private val api: OpenListApi,
    private val sessionStore: SessionStore,
    private val pathCredentials: InMemoryPathCredentialStore,
    private val credentialCipher: LoginCredentialCipher = LoginCredentialCipher(),
) {
    val settings: StateFlow<AppSettings> = sessionStore.settings

    suspend fun login(
        profile: ServerProfile,
        password: String,
        otpCode: String = "",
        savePassword: Boolean = false,
    ): OpenListUser {
        val normalizedProfile = profile.copy(baseUrl = profile.normalizedBaseUrl())
        if (!sessionStore.snapshot().server.hasSameCredentialIdentity(normalizedProfile)) {
            pathCredentials.clear()
        }
        val targetAccountId = sessionStore.beginLogin(normalizedProfile, clearSavedPassword = !savePassword)
        val passwordHash = PasswordHasher.forOpenList(password)
        val login = try {
            api.loginWithHash(
                normalizedProfile.username,
                passwordHash,
                otpCode,
            )
        } catch (error: Throwable) {
            failLoginTarget(targetAccountId, normalizedProfile)
            throw error.asLoginAuthenticationException()
        }
        if (login.token.isBlank()) {
            failLoginTarget(targetAccountId, normalizedProfile)
            throw IllegalStateException("登录响应未包含 token")
        }

        val completion = try {
            val encryptedPasswordHash = if (savePassword) withContext(Dispatchers.IO) {
                credentialCipher.encrypt(passwordHash, targetAccountId)
            } else ""
            val encryptedPassword = if (savePassword) withContext(Dispatchers.IO) {
                credentialCipher.encryptPassword(password, targetAccountId)
            } else ""
            sessionStore.completeLogin(
                targetAccountId, normalizedProfile, login.token, encryptedPasswordHash, encryptedPassword,
            )
        } catch (error: Throwable) {
            failLoginTarget(targetAccountId, normalizedProfile)
            throw error
        }
        if (!completion.isActive ||
            !sessionStore.isActiveAccount(targetAccountId, normalizedProfile)
        ) {
            // Authentication succeeded and remains saved on its target, but the user selected a
            // different account before completion. Never reactivate the stale operation.
            throw LoginSupersededException(targetAccountId)
        }

        val user = try {
            api.me()
        } catch (cancelled: CancellationException) {
            // A successful token is already committed. UI disposal must not erase it.
            throw cancelled
        } catch (error: Throwable) {
            failLoginTarget(targetAccountId, normalizedProfile)
            throw error
        }
        if (!sessionStore.isActiveAccount(targetAccountId, normalizedProfile)) {
            throw LoginSupersededException(targetAccountId)
        }
        return user
    }

    internal suspend fun savedLoginCredential(profile: ServerProfile): SavedLoginCredential? {
        sessionStore.awaitLoaded()
        val account = sessionStore.accountForProfile(profile) ?: return null
        if (account.encryptedPassword.isBlank() && account.encryptedPasswordHash.isBlank()) return null
        // A pre-checkbox installation may only have a saved hash; it remains usable for renewal,
        // but hashes cannot recover the original password for the login field.
        val password = if (account.encryptedPassword.isNotBlank()) withContext(Dispatchers.IO) {
            credentialCipher.decryptPassword(account.encryptedPassword, account.id)
        } else ""
        val current = sessionStore.accountForProfile(profile)
        if (current?.id != account.id || current.encryptedPassword != account.encryptedPassword ||
            current.encryptedPasswordHash != account.encryptedPasswordHash
        ) return null
        return SavedLoginCredential(password)
    }

    internal suspend fun clearSavedLoginCredential(profile: ServerProfile) {
        sessionStore.awaitLoaded()
        val account = sessionStore.accountForProfile(profile) ?: return
        sessionStore.clearSavedLoginCredentials(account.id, profile)
    }

    private suspend fun failLoginTarget(accountId: AccountId, expectedProfile: ServerProfile) {
        withContext(NonCancellable) {
            sessionStore.failLogin(accountId, expectedProfile)
        }
    }

    suspend fun logout() {
        performBestEffortLogout(
            remoteLogout = api::logout,
            localCleanup = {
                try {
                    sessionStore.clearSession()
                } finally {
                    pathCredentials.clear()
                }
            },
        )
    }

    suspend fun list(path: String, refresh: Boolean = false): DirectoryListing {
        val profile = sessionStore.snapshot().server
        return api.listAll(
            path = path,
            password = pathCredentials.passwordFor(profile, path),
            refresh = refresh,
        )
    }

    /** Validates [password] against the server before retaining it for this directory tree. */
    suspend fun unlockDirectory(
        path: String,
        password: String,
        refresh: Boolean = false,
    ): DirectoryListing {
        val profile = sessionStore.snapshot().server
        val result = api.listAll(path = path, password = password, refresh = refresh)
        pathCredentials.remember(profile, path, password)
        return result
    }

    suspend fun details(path: String): FileDetails {
        val profile = sessionStore.snapshot().server
        return api.get(path, pathCredentials.passwordFor(profile, path))
    }

    suspend fun search(
        parent: String,
        keywords: String,
        scope: Int = 0,
        page: Int = 1,
        perPage: Int = 100,
    ): SearchData {
        val profile = sessionStore.snapshot().server
        return api.search(
            parent = parent,
            keywords = keywords,
            scope = scope,
            page = page,
            perPage = perPage,
            password = pathCredentials.passwordFor(profile, parent),
        )
    }
}

/** Decrypted form state is deliberately absent from saved-instance state and diagnostics. */
internal class SavedLoginCredential(val password: String) {
    override fun toString(): String = "SavedLoginCredential(password=<redacted>)"
}

/**
 * Gives the server a small opportunity to invalidate its session while guaranteeing that local
 * credentials are removed even when the caller is cancelled or the network fails. External
 * cancellation is still propagated after cleanup; only a remote failure/timeout is best effort.
 */
internal suspend fun performBestEffortLogout(
    remoteTimeoutMillis: Long = REMOTE_LOGOUT_TIMEOUT_MILLIS,
    remoteLogout: suspend () -> Unit,
    localCleanup: suspend () -> Unit,
) {
    require(remoteTimeoutMillis > 0) { "Remote logout timeout must be positive" }
    try {
        try {
            // OpenListHttpClient propagates coroutine cancellation to OkHttp Call.cancel(), so
            // this timeout is also a socket-level boundary rather than only a UI timeout.
            withTimeoutOrNull(remoteTimeoutMillis) { remoteLogout() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Local revocation is authoritative for the app and must not depend on connectivity.
        }
    } finally {
        withContext(NonCancellable) { localCleanup() }
    }
}

private const val REMOTE_LOGOUT_TIMEOUT_MILLIS = 3_000L

class LoginSupersededException(accountId: AccountId) :
    IllegalStateException("登录完成前已切换到其他账户：$accountId")

class SecondFactorRequiredException(cause: OpenListApiException) :
    IllegalStateException("需要两步验证", cause)

internal fun Throwable.asLoginAuthenticationException(): Throwable =
    if (this is OpenListApiException && apiCode == 402) {
        SecondFactorRequiredException(this)
    } else {
        this
    }

private fun ServerProfile.hasSameCredentialIdentity(other: ServerProfile): Boolean =
    username == other.username &&
        runCatching { normalizedBaseUrl() }.getOrElse { baseUrl.trim().trimEnd('/') } ==
        runCatching { other.normalizedBaseUrl() }.getOrElse { other.baseUrl.trim().trimEnd('/') }
