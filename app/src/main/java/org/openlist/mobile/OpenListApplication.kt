package org.openlist.mobile

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import org.openlist.mobile.data.api.OpenListApi
import org.openlist.mobile.data.api.OpenListHttpClient
import org.openlist.mobile.data.auth.LoginCredentialCipher
import org.openlist.mobile.data.auth.SessionAuthenticator
import org.openlist.mobile.data.api.AdminApi
import org.openlist.mobile.data.api.TaskApi
import org.openlist.mobile.data.api.catalog.GenericOpenListService
import org.openlist.mobile.data.account.AccountDraft
import org.openlist.mobile.data.account.AccountId
import org.openlist.mobile.data.account.AccountSummary
import org.openlist.mobile.data.cache.CacheStartupPolicy
import org.openlist.mobile.data.cache.UnifiedCacheManager
import org.openlist.mobile.data.download.DownloadGrantLifecycle
import org.openlist.mobile.data.credentials.InMemoryPathCredentialStore
import org.openlist.mobile.data.preferences.SessionStore
import org.openlist.mobile.data.repository.OpenListRepository
import org.openlist.mobile.data.repository.SecondFactorRequiredException
import org.openlist.mobile.data.logging.AppLogger
import org.openlist.mobile.data.logging.PersistentAppLogger
import org.openlist.mobile.data.upload.UploadGrantLifecycle
import org.openlist.mobile.core.model.OpenListUser
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.media.MediaDataSourceDecorator
import org.openlist.mobile.media.MediaSequenceBuilder
import org.openlist.mobile.media.OpenListMediaUrlResolver
import org.openlist.mobile.media.OpenListPlaybackRuntime
import org.openlist.mobile.media.OpenListPlaybackService
import org.openlist.mobile.media.PlaybackServiceDependencies
import org.openlist.mobile.media.gallery.GalleryImageRepository
import java.io.File

class OpenListApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DownloadGrantLifecycle.initialize(this)
        UploadGrantLifecycle.initialize(this)
    }
}

internal const val SESSION_LOAD_TIMEOUT_MILLIS = 10_000L
internal const val SESSION_LOAD_TIMEOUT_MESSAGE = "读取本机会话超时，请点击重试"

internal sealed interface SessionLoadState {
    data object Loading : SessionLoadState
    data object Ready : SessionLoadState
    data class Failed(val message: String) : SessionLoadState
}

/**
 * Owns the one cold-start session read attempt visible to the UI. A retry cannot overlap an
 * existing attempt, and every attempt has the same total deadline so a stalled DataStore never
 * leaves the application on an indefinite loading screen.
 */
internal class SessionLoadCoordinator(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = SESSION_LOAD_TIMEOUT_MILLIS,
    private val load: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var attemptInFlight = false
    private var attemptGeneration = 0L
    private val mutableState = MutableStateFlow<SessionLoadState>(SessionLoadState.Loading)

    val state: StateFlow<SessionLoadState> = mutableState.asStateFlow()

    init {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    }

    /** Returns false when the session is ready or another tap already owns the active attempt. */
    fun retry(): Boolean {
        val generation = synchronized(lock) {
            if (attemptInFlight || mutableState.value == SessionLoadState.Ready) return false
            attemptInFlight = true
            attemptGeneration += 1L
            mutableState.value = SessionLoadState.Loading
            attemptGeneration
        }

        scope.launch {
            val outcome = try {
                withTimeout(timeoutMillis) { load() }
                AttemptOutcome(SessionLoadState.Ready)
            } catch (timeout: TimeoutCancellationException) {
                AttemptOutcome(
                    state = SessionLoadState.Failed(SESSION_LOAD_TIMEOUT_MESSAGE),
                    error = timeout,
                )
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    if (attemptGeneration == generation) attemptInFlight = false
                }
                throw cancelled
            } catch (error: Exception) {
                AttemptOutcome(
                    state = SessionLoadState.Failed(
                        error.message?.takeIf(String::isNotBlank) ?: "无法读取本机会话，请点击重试",
                    ),
                    error = error,
                )
            }

            val publishFailure = synchronized(lock) {
                if (attemptGeneration != generation || !attemptInFlight) {
                    false
                } else {
                    // Clear the gate before notifying collectors. A retry triggered by the error
                    // state therefore always starts a fresh bounded UI attempt.
                    attemptInFlight = false
                    mutableState.value = outcome.state
                    outcome.error != null
                }
            }
            if (publishFailure) onFailure(requireNotNull(outcome.error))
        }
        return true
    }

    private data class AttemptOutcome(
        val state: SessionLoadState,
        val error: Throwable? = null,
    )
}

class AppContainer(private val application: Application) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionMutex = Mutex()
    private val mutableAuthenticating = MutableStateFlow(false)
    private val mutableSessionBusy = MutableStateFlow(false)
    private val mutableAuthenticationError = MutableStateFlow<String?>(null)
    private val mutablePlaybackInvalidation = MutableStateFlow(0L)
    val authenticating: StateFlow<Boolean> = mutableAuthenticating.asStateFlow()
    val sessionBusy: StateFlow<Boolean> = mutableSessionBusy.asStateFlow()
    val authenticationError: StateFlow<String?> = mutableAuthenticationError.asStateFlow()
    /** Changes before a session mutation commits so UI-scoped players can release immediately. */
    val playbackInvalidation: StateFlow<Long> = mutablePlaybackInvalidation.asStateFlow()
    /** Disk-backed services stay off the Application main-thread cold-start path. */
    val appLogger: AppLogger by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PersistentAppLogger(File(application.filesDir, "app-logs"))
    }
    val sessionStore = SessionStore(application, applicationScope)
    private val sessionLoadCoordinator = SessionLoadCoordinator(
        scope = applicationScope,
        load = { sessionStore.retryLoad() },
        onFailure = { error -> appLogger.error("Account", "无法读取本机会话", error) },
    )
    internal val sessionLoadState: StateFlow<SessionLoadState> = sessionLoadCoordinator.state
    private val credentialCipher = LoginCredentialCipher()
    internal val sessionAuthenticator = SessionAuthenticator(
        sessionStore = sessionStore,
        credentialCipher = credentialCipher,
        onAuthenticationRequired = { mutableAuthenticationError.value = it },
    )
    val httpClient = OpenListHttpClient(
        baseUrl = { sessionStore.snapshot().server.baseUrl },
        token = { sessionStore.snapshot().token },
        allowInsecureHttp = { sessionStore.snapshot().server.allowInsecureHttp },
        sessionSnapshot = sessionAuthenticator::snapshot,
        refreshSession = sessionAuthenticator::refresh,
    )
    val api = OpenListApi(httpClient)
    val genericApi = GenericOpenListService(httpClient)
    val adminApi = AdminApi(genericApi)
    val taskApi = TaskApi(genericApi)
    val pathCredentials = InMemoryPathCredentialStore()
    val repository = OpenListRepository(api, sessionStore, pathCredentials, credentialCipher)
    val mediaUrlResolver = OpenListMediaUrlResolver(api) { path ->
        pathCredentials.passwordFor(sessionStore.snapshot().server, path)
    }
    val mediaSequenceBuilder = MediaSequenceBuilder(repository)
    val cacheManager: UnifiedCacheManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UnifiedCacheManager.create(
            blobDirectory = File(application.cacheDir, "openlist-images"),
            mediaDirectory = File(application.cacheDir, "openlist-media"),
            policy = CacheStartupPolicy.initial(sessionStore.loadedSnapshot()?.cachePolicy),
        )
    }
    val galleryImageRepository: GalleryImageRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GalleryImageRepository(
            urlResolver = mediaUrlResolver,
            managedDiskCache = cacheManager.blobCache,
            downloadClient = httpClient.okHttpClient,
        )
    }

    init {
        applicationScope.launch(Dispatchers.IO) {
            appLogger.info("Application", "OpenList 已启动")
        }
        retrySessionLoad()
        OpenListPlaybackRuntime.install {
            PlaybackServiceDependencies(
                urlResolver = mediaUrlResolver,
                downloadClient = httpClient.okHttpClient,
                dataSourceDecorator = MediaDataSourceDecorator(cacheManager.mediaCache::decorate),
            )
        }
        applicationScope.launch(Dispatchers.IO) {
            sessionStore.loadedSettings
                .map { it.cachePolicy }
                .distinctUntilChanged()
                .collect { policy -> cacheManager.updatePolicy(policy) }
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheManager.clear()
        appLogger.info("Cache", "缓存已清空")
    }

    /** Authentication survives composition/activity recreation and gates token-driven navigation. */
    suspend fun login(
        profile: ServerProfile,
        password: String,
        otpCode: String = "",
    ): OpenListUser = sessionOperation("登录", authentication = true) {
        mutableAuthenticationError.value = null
        appLogger.info("Account", "开始登录")
        try {
            val normalizedProfile = profile.copy(baseUrl = profile.normalizedBaseUrl())
            stopPlaybackForSessionChange()
            repository.login(normalizedProfile, password, otpCode).also {
                appLogger.info("Account", "登录成功")
            }
        } catch (challenge: SecondFactorRequiredException) {
            mutableAuthenticationError.value = null
            appLogger.info("Account", "需要两步验证")
            throw challenge
        } catch (error: Throwable) {
            val message = error.message ?: "无法登录，请检查服务器和凭据"
            mutableAuthenticationError.value = message
            appLogger.warn("Account", "登录失败：$message")
            throw error
        }
    }

    fun clearAuthenticationError() {
        mutableAuthenticationError.value = null
    }

    /** Retries a failed first DataStore read; repeated taps share the same in-flight attempt. */
    fun retrySessionLoad() {
        sessionLoadCoordinator.retry()
    }

    suspend fun addAccount(draft: AccountDraft) = sessionOperation("新增账户") {
        val validated = validateAccountDraft(draft)
        stopPlaybackForSessionChange()
        sessionStore.addAccount(validated, makeActive = true).also {
            mutableAuthenticationError.value = null
        }
    }

    suspend fun editAccount(id: AccountId, draft: AccountDraft) = sessionOperation("编辑账户") {
        val before = activeIdentity()
        val validated = validateAccountDraft(draft, editingId = id)
        if (before.accountId == id && before.server != validated.server) {
            stopPlaybackForSessionChange()
        }
        sessionStore.editAccount(id, validated)
        mutableAuthenticationError.value = null
    }

    suspend fun switchAccount(id: AccountId) = sessionOperation("切换账户") {
        val before = activeIdentity()
        requireAccount(id)
        if (before.accountId != id) stopPlaybackForSessionChange()
        sessionStore.switchAccount(id)
        mutableAuthenticationError.value = null
    }

    suspend fun deleteAccount(id: AccountId) = sessionOperation("删除账户") {
        val before = activeIdentity()
        requireAccount(id)
        if (before.accountId == id) stopPlaybackForSessionChange()
        sessionStore.deleteAccount(id)
        mutableAuthenticationError.value = null
    }

    suspend fun logout() = sessionOperation("退出账户") {
        runCatching { stopPlaybackForSessionChange() }
            .onFailure { appLogger.error("Playback", "退出账户时无法停止播放", it) }
        try {
            repository.logout()
        } finally {
            withContext(NonCancellable) {
                runCatching { cacheManager.clear() }
                    .onFailure { appLogger.error("Cache", "退出账户时无法清空缓存", it) }
                mutableAuthenticationError.value = null
            }
        }
    }

    private suspend fun <T> sessionOperation(
        label: String,
        authentication: Boolean = false,
        block: suspend () -> T,
    ): T {
        check(sessionMutex.tryLock()) { "另一个账户操作正在进行，请稍候" }
        mutableSessionBusy.value = true
        if (authentication) mutableAuthenticating.value = true
        return applicationScope.async {
            try {
                sessionStore.awaitLoaded()
                block().also { appLogger.info("Account", label) }
            } finally {
                if (authentication) mutableAuthenticating.value = false
                mutableSessionBusy.value = false
                sessionMutex.unlock()
            }
        }.await()
    }

    private fun activeIdentity(): ActiveIdentity {
        val active = sessionStore.accountSnapshot().firstOrNull(AccountSummary::isActive)
        return ActiveIdentity(
            accountId = active?.id,
            server = active?.server,
        )
    }

    private fun validateAccountDraft(
        draft: AccountDraft,
        editingId: AccountId? = null,
    ): AccountDraft {
        val normalized = draft.copy(
            server = draft.server.copy(baseUrl = draft.server.normalizedBaseUrl()),
        )
        val accounts = sessionStore.accountSnapshot()
        if (editingId != null) {
            check(accounts.any { it.id == editingId }) { "账户不存在" }
        }
        check(
            accounts.none {
                it.id != editingId &&
                    it.server.username == normalized.server.username &&
                    runCatching { it.server.normalizedBaseUrl() }.getOrDefault(it.server.baseUrl) ==
                    normalized.server.baseUrl
            },
        ) { "该服务器和用户名的账户已存在" }
        return normalized
    }

    private fun requireAccount(id: AccountId): AccountSummary {
        return sessionStore.accountSnapshot().firstOrNull { it.id == id }
            ?: throw NoSuchElementException("账户不存在")
    }

    private fun stopPlaybackForSessionChange() {
        mutablePlaybackInvalidation.value += 1L
        try {
            application.stopService(Intent(application, OpenListPlaybackService::class.java))
        } finally {
            pathCredentials.clear()
        }
    }

    private data class ActiveIdentity(
        val accountId: AccountId?,
        val server: ServerProfile?,
    )
}
