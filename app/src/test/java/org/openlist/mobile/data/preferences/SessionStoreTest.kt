package org.openlist.mobile.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.account.AccountId
import org.openlist.mobile.data.account.AccountRecord
import org.openlist.mobile.data.account.AccountState
import org.openlist.mobile.data.upload.JobBoundUploadSession
import org.openlist.mobile.data.upload.UploadPermanentException
import org.openlist.mobile.data.upload.UploadSessionBinding

class SessionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `upload starting after first login uses the completed login instead of the cold start state`() = runTest {
        val store = createStore()
        assertThat(store.awaitLoaded().token).isEmpty()

        val profile = ServerProfile("https://files.example", "alice")
        val accountId = store.beginLogin(profile)
        store.completeLogin(accountId, profile, "first-login-token")
        val queued = store.snapshot()

        val workerSettings = store.awaitLoaded()
        val session = captureUpload(queued, workerSettings)

        assertThat(workerSettings.server).isEqualTo(profile)
        assertThat(uploadAuthorization(session)).isEqualTo("first-login-token")
    }

    @Test
    fun `upload queued before token renewal starts with the current token for the same login`() = runTest {
        val account = account("alice", "original-token")
        val store = createStore(AccountState(listOf(account), account.id))
        val queued = store.awaitLoaded()

        assertThat(store.replaceAuthentication(account, "renewed-token")).isTrue()
        val workerSettings = store.awaitLoaded()
        val session = captureUpload(queued, workerSettings)

        assertThat(workerSettings.token).isEqualTo("renewed-token")
        assertThat(uploadAuthorization(session)).isEqualTo("renewed-token")
        assertThat(uploadAuthorization(captureUpload(queued, store.retryLoad())))
            .isEqualTo("renewed-token")
    }

    @Test
    fun `switching account exposes the new account and rejects the previous account upload`() = runTest {
        val alice = account("alice", "alice-token")
        val bob = account("bob", "bob-token")
        val store = createStore(AccountState(listOf(alice, bob), alice.id))
        val queued = store.awaitLoaded()

        store.switchAccount(bob.id)
        val workerSettings = store.awaitLoaded()

        assertThat(workerSettings.server).isEqualTo(bob.server)
        assertThat(workerSettings.token).isEqualTo("bob-token")
        assertThat(runCatching { captureUpload(queued, workerSettings) }.exceptionOrNull())
            .isInstanceOf(UploadPermanentException::class.java)
        assertThat(uploadAuthorization(captureUpload(workerSettings, workerSettings)))
            .isEqualTo("bob-token")
    }

    @Test
    fun `logout and a new login cannot authorize work queued by the previous login`() = runTest {
        val account = account("alice", "old-login-token")
        val store = createStore(AccountState(listOf(account), account.id))
        val queued = store.awaitLoaded()

        store.clearSession()
        val signedOut = store.awaitLoaded()

        assertThat(signedOut.token).isEmpty()
        assertThat(runCatching { captureUpload(queued, signedOut) }.exceptionOrNull())
            .isInstanceOf(UploadPermanentException::class.java)

        val accountId = store.beginLogin(account.server)
        store.completeLogin(accountId, account.server, "new-login-token")
        val signedIn = store.awaitLoaded()

        assertThat(signedIn.token).isEqualTo("new-login-token")
        assertThat(runCatching { captureUpload(queued, signedIn) }.exceptionOrNull())
            .isInstanceOf(UploadPermanentException::class.java)
        assertThat(uploadAuthorization(captureUpload(signedIn, signedIn)))
            .isEqualTo("new-login-token")
    }

    private suspend fun TestScope.createStore(initialAccounts: AccountState? = null): SessionStore {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        if (initialAccounts != null) {
            dataStore.edit { AccountPreferencesCodec.write(it, initialAccounts) }
        }
        var nextId = 0
        return SessionStore(dataStore, backgroundScope) { "test-account-${++nextId}" }
    }

    private fun account(username: String, token: String) = AccountRecord(
        id = AccountId(username),
        displayName = username,
        server = ServerProfile("https://files.example", username),
        token = token,
        sessionBindingKey = token,
    )

    private fun captureUpload(expected: AppSettings, current: AppSettings) = JobBoundUploadSession.capture(
        expectedBaseUrl = expected.server.baseUrl,
        expectedUsername = expected.server.username,
        expectedAllowInsecureHttp = expected.server.allowInsecureHttp,
        expectedSessionBinding = UploadSessionBinding.create(expected.server, expected.sessionBindingKey),
        currentBaseUrl = current.server.baseUrl,
        currentUsername = current.server.username,
        currentAllowInsecureHttp = current.server.allowInsecureHttp,
        currentToken = current.token,
        currentSessionBindingKey = current.sessionBindingKey,
    )

    private fun uploadAuthorization(session: JobBoundUploadSession): String? =
        session.newHttpClient(OkHttpClient(), Gson()) { true }
            .requestBuilder("/api/fs/put")
            .build()
            .header("Authorization")
}
