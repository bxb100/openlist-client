package org.openlist.mobile.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.openlist.mobile.core.model.CachePolicy
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.account.AccountDraft
import org.openlist.mobile.data.account.AccountId
import org.openlist.mobile.data.account.AccountRecord
import org.openlist.mobile.data.account.AccountState
import org.openlist.mobile.data.account.AccountStateMachine
import org.openlist.mobile.data.account.AccountSummary
import org.openlist.mobile.data.account.LoginCompletion
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private val Context.openListDataStore by preferencesDataStore(name = "openlist_settings")

data class AppSettings(
    val server: ServerProfile = ServerProfile(),
    val token: String = "",
    val cachePolicy: CachePolicy = CachePolicy(),
    val dynamicColor: Boolean = true,
    val darkTheme: Boolean? = null,
    val sessionBindingKey: String = "",
) {
    /** Keep the compatibility token readable by network code without making logs disclose it. */
    override fun toString(): String =
        "AppSettings(server=$server, token=<redacted>, cachePolicy=$cachePolicy, " +
            "dynamicColor=$dynamicColor, darkTheme=$darkTheme, sessionBindingKey=<redacted>)"
}

private data class DecodedSession(
    val settings: AppSettings,
    val accountSummaries: List<AccountSummary>,
)

class SessionStore(
    context: Context,
    private val scope: CoroutineScope,
    private val accountIdGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val dataStore = context.openListDataStore
    private val current = AtomicReference(AppSettings())
    private val loadedCurrent = AtomicReference<AppSettings?>(null)
    private val currentAccounts = AtomicReference(AccountState())
    private val persisted = MutableStateFlow(DecodedSession(AppSettings(), emptyList()))
    private val mutableLoadedSettings = MutableStateFlow<AppSettings?>(null)
    private val loadLock = Any()
    private var loadStatus = LoadStatus.IDLE
    private var loadAttempt = CompletableDeferred<AppSettings>()

    /** Active-account compatibility surface used by existing API, upload, cache, and UI code. */
    val settings: StateFlow<AppSettings> = persisted
        .map { it.settings }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Token-free account-management projection. */
    val accountSummaries: StateFlow<List<AccountSummary>> = persisted
        .map { it.accountSummaries }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Settings that came from a completed DataStore read or write. Unlike [settings], this stream
     * has no in-memory placeholder, so destructive consumers such as cache trimming cannot mistake
     * the model defaults for the user's persisted policy during cold start.
     */
    val loadedSettings: Flow<AppSettings> = mutableLoadedSettings
        .filterNotNull()
        .distinctUntilChanged()

    init {
        synchronized(loadLock) { startObservationLocked() }
        // Decoding already exposes legacy data immediately. This one-time rewrite removes the
        // former standalone token and makes every credential account-scoped atomically.
        scope.launch {
            runCatching {
                dataStore.edit { preferences ->
                    val decoded = AccountPreferencesCodec.decode(preferences)
                    if (decoded.requiresMigrationWrite) {
                        AccountPreferencesCodec.write(preferences, decoded.state)
                    }
                }
            }
        }
    }

    fun snapshot(): AppSettings = current.get()

    /** Returns null until the first real DataStore value has been decoded. */
    fun loadedSnapshot(): AppSettings? = loadedCurrent.get()

    /** Atomic, token-free account view for validating serialized account mutations. */
    fun accountSnapshot(): List<AccountSummary> = currentAccounts.get().summaries()

    internal fun authenticationSnapshot(): AccountRecord? = currentAccounts.get().active

    /** Commits renewal only while the original active login is still current. */
    internal suspend fun replaceAuthentication(
        expected: AccountRecord,
        token: String,
        clearCredentials: Boolean = false,
    ): Boolean {
        require(clearCredentials || token.isNotBlank()) { "Renewed token must not be blank" }
        var replaced = false
        mutateAccounts { state ->
            val active = state.active
            if (state.activeId != expected.id || active == null ||
                active.id != expected.id || active.server != expected.server ||
                active.token != expected.token ||
                active.sessionBindingKey != expected.sessionBindingKey ||
                active.encryptedPasswordHash != expected.encryptedPasswordHash
            ) {
                state
            } else {
                replaced = true
                AccountState(
                    records = state.records.map { record ->
                        if (record.id == expected.id) {
                            record.updated(
                                token = if (clearCredentials) "" else token,
                                encryptedPasswordHash = if (clearCredentials) "" else record.encryptedPasswordHash,
                                sessionBindingKey = if (clearCredentials) "" else record.sessionBindingKey,
                            )
                        } else {
                            record
                        }
                    },
                    activeId = state.activeId,
                )
            }
        }
        return replaced
    }

    fun isActiveAccount(id: AccountId, expectedProfile: ServerProfile): Boolean {
        val state = currentAccounts.get()
        return state.activeId == id &&
            AccountStateMachine.matchesIdentity(state, id, expectedProfile)
    }

    /** Waits for DataStore's first persisted value instead of observing the StateFlow placeholder. */
    suspend fun awaitLoaded(): AppSettings {
        val attempt = synchronized(loadLock) {
            if (loadStatus == LoadStatus.IDLE) startObservationLocked() else loadAttempt
        }
        return attempt.await()
    }

    /**
     * Restarts the DataStore observation after an initial read failure. Concurrent retries share
     * one attempt, while a store that already loaded returns its current atomic snapshot.
     */
    suspend fun retryLoad(): AppSettings {
        val attempt = synchronized(loadLock) {
            when (loadStatus) {
                LoadStatus.READY -> null
                LoadStatus.LOADING -> loadAttempt
                LoadStatus.IDLE, LoadStatus.FAILED -> startObservationLocked()
            }
        }
        return attempt?.await() ?: current.get()
    }

    suspend fun addAccount(
        draft: AccountDraft,
        makeActive: Boolean = false,
    ): AccountId {
        val id = nextAccountId()
        mutateAccounts { state ->
            AccountStateMachine.add(state, id, draft, makeActive).state
        }
        return id
    }

    suspend fun editAccount(id: AccountId, draft: AccountDraft) {
        mutateAccounts { state -> AccountStateMachine.edit(state, id, draft) }
    }

    suspend fun switchAccount(id: AccountId) {
        mutateAccounts { state -> AccountStateMachine.switch(state, id) }
    }

    suspend fun deleteAccount(id: AccountId) {
        mutateAccounts { state -> AccountStateMachine.delete(state, id) }
    }

    /**
     * Atomically activates an existing server/username identity or creates a new account, while
     * clearing only that target account's stale token before authentication starts.
     */
    suspend fun beginLogin(profile: ServerProfile, displayName: String? = null): AccountId {
        val idForNewAccount = nextAccountId()
        var selectedId: AccountId? = null
        mutateAccounts { state ->
            AccountStateMachine.beginLogin(state, idForNewAccount, profile, displayName).also {
                selectedId = it.accountId
            }.state
        }
        return requireNotNull(selectedId)
    }

    /**
     * Writes a successful token only to [accountId] after checking that its server/username and
     * transport policy still equal [expectedProfile]. This method never changes the active id.
     */
    suspend fun completeLogin(
        accountId: AccountId,
        expectedProfile: ServerProfile,
        token: String,
        encryptedPasswordHash: String = "",
    ): LoginCompletion {
        var completion: LoginCompletion? = null
        mutateAccounts { state ->
            AccountStateMachine.completeLogin(
                state = state,
                id = accountId,
                expectedProfile = expectedProfile,
                token = token,
                encryptedPasswordHash = encryptedPasswordHash,
            ).also { completion = it.completion }.state
        }
        return requireNotNull(completion)
    }

    /** Clears only the original, still-matching login target. */
    suspend fun failLogin(accountId: AccountId, expectedProfile: ServerProfile): Boolean {
        var cleared = false
        mutateAccounts { state ->
            AccountStateMachine.failLogin(state, accountId, expectedProfile).also {
                cleared = it.cleared
            }.state
        }
        return cleared
    }

    /** Existing logout behavior now clears only the active account's token. */
    suspend fun clearSession() {
        mutateAccounts { state ->
            state.activeId?.let { AccountStateMachine.setToken(state, it, "") } ?: state
        }
    }

    suspend fun setCachePolicy(policy: CachePolicy) {
        val updated = dataStore.edit {
            it[Keys.CACHE_BYTES] = policy.maxBytes
            it[Keys.CACHE_AGE] = policy.maxAgeMillis
            it[Keys.CACHE_ENTRIES] = policy.maxEntries
        }
        publish(decodeSession(updated))
    }

    suspend fun setAppearance(dynamicColor: Boolean, darkTheme: Boolean?) {
        val updated = dataStore.edit {
            it[Keys.DYNAMIC_COLOR] = dynamicColor
            it[Keys.DARK_THEME] = when (darkTheme) {
                true -> "dark"
                false -> "light"
                null -> "system"
            }
        }
        publish(decodeSession(updated))
    }

    private suspend fun mutateAccounts(transform: (AccountState) -> AccountState): AccountState {
        lateinit var result: AccountState
        val updated = dataStore.edit { preferences ->
            val currentState = AccountPreferencesCodec.decode(preferences).state
            result = transform(currentState)
            AccountPreferencesCodec.write(preferences, result)
        }
        publish(decodeSession(updated))
        return result
    }

    /** Must be called with [loadLock] held. */
    private fun startObservationLocked(): CompletableDeferred<AppSettings> {
        check(loadStatus != LoadStatus.LOADING)
        val attempt = CompletableDeferred<AppSettings>()
        loadAttempt = attempt
        loadStatus = LoadStatus.LOADING
        scope.launch {
            try {
                dataStore.data
                    .map(::decodeSession)
                    .collect { decoded ->
                        publish(decoded)
                        synchronized(loadLock) {
                            if (loadAttempt === attempt && loadStatus == LoadStatus.LOADING) {
                                loadStatus = LoadStatus.READY
                                attempt.complete(decoded.settings)
                            }
                        }
                    }
            } catch (cancelled: CancellationException) {
                failLoadAttempt(attempt, cancelled)
                throw cancelled
            } catch (error: Exception) {
                // Keep the failure observable through awaitLoaded(), but do not crash the
                // application scope. retryLoad() installs a fresh DataStore collection.
                failLoadAttempt(attempt, error)
            }
        }
        return attempt
    }

    private fun failLoadAttempt(
        attempt: CompletableDeferred<AppSettings>,
        error: Throwable,
    ) {
        synchronized(loadLock) {
            if (loadAttempt === attempt && loadStatus == LoadStatus.LOADING) {
                loadStatus = LoadStatus.FAILED
                attempt.completeExceptionally(error)
            }
        }
    }

    private fun publish(decoded: DecodedSession) {
        current.set(decoded.settings)
        loadedCurrent.set(decoded.settings)
        persisted.value = decoded
        mutableLoadedSettings.value = decoded.settings
    }

    private fun nextAccountId(): AccountId = AccountId(accountIdGenerator())

    private fun decodeSession(preferences: Preferences): DecodedSession {
        val accounts = AccountPreferencesCodec.decode(preferences).state
        currentAccounts.set(accounts)
        val active = accounts.active
        return DecodedSession(
            settings = AppSettings(
                server = active?.server ?: ServerProfile(),
                token = active?.token.orEmpty(),
                sessionBindingKey = active?.sessionBindingKey.orEmpty(),
                cachePolicy = CachePolicy(
                    maxBytes = preferences[Keys.CACHE_BYTES] ?: CachePolicy().maxBytes,
                    maxAgeMillis = preferences[Keys.CACHE_AGE] ?: CachePolicy().maxAgeMillis,
                    maxEntries = preferences[Keys.CACHE_ENTRIES] ?: CachePolicy().maxEntries,
                ),
                dynamicColor = preferences[Keys.DYNAMIC_COLOR] ?: true,
                darkTheme = when (preferences[Keys.DARK_THEME]) {
                    "light" -> false
                    "dark" -> true
                    else -> null
                },
            ),
            accountSummaries = accounts.summaries(),
        )
    }

    private object Keys {
        val CACHE_BYTES = longPreferencesKey("cache_max_bytes")
        val CACHE_AGE = longPreferencesKey("cache_max_age_millis")
        val CACHE_ENTRIES = intPreferencesKey("cache_max_entries")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DARK_THEME = stringPreferencesKey("dark_theme")
    }

    private enum class LoadStatus {
        IDLE,
        LOADING,
        READY,
        FAILED,
    }
}
