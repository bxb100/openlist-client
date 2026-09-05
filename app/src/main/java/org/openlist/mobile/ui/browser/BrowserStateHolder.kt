package org.openlist.mobile.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.ui.BrowserEntry
import org.openlist.mobile.ui.isForbiddenAccess
import org.openlist.mobile.ui.normalizedRemotePath

internal data class SearchUiState(
    val results: List<BrowserEntry> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
    val total: Long = 0,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
)

internal data class BrowserSearchPage(val entries: List<BrowserEntry>, val total: Long)

internal data class BrowserDetailsState(
    val entry: BrowserEntry,
    val details: FileDetails? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

internal enum class BrowserActionKind { Menu, Rename, Delete }

internal data class BrowserActionState(
    val entry: BrowserEntry,
    val kind: BrowserActionKind = BrowserActionKind.Menu,
    val name: String = entry.item.name,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canRename: Boolean
        get() = validRemoteName(name) && name.trim() != entry.item.name && !busy
}

internal data class DirectoryChallenge(
    val path: String,
    val submitting: Boolean = false,
    val error: String? = null,
)

internal data class BrowserUiState(
    val path: String = "/",
    val listing: DirectoryListing? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val searchVisible: Boolean = false,
    val query: String = "",
    val search: SearchUiState = SearchUiState(),
    val details: BrowserDetailsState? = null,
    val action: BrowserActionState? = null,
    val challenge: DirectoryChallenge? = null,
    val message: String? = null,
)

/**
 * Owns server requests across configuration changes. The route supplies restored navigation and
 * search input; server metadata and signed media URLs remain only in this model's memory.
 *
 * Every request has a generation in addition to coroutine cancellation. A transport that finishes
 * after cancellation cannot replace a newer directory, query, dialog, or account's visible state.
 */
internal class BrowserViewModel(
    private val loadDirectory: suspend (String, Boolean) -> DirectoryListing,
    private val search: suspend (String, String, Int) -> BrowserSearchPage,
    private val unlockDirectory: suspend (String, String) -> DirectoryListing,
    private val loadDetails: suspend (String) -> FileDetails,
    private val renameEntry: suspend (String, String) -> Unit,
    private val removeEntry: suspend (String, List<String>) -> Unit,
    initialPath: String = "/",
    initialQuery: String = "",
    initialSearchVisible: Boolean = false,
    private val accountActive: () -> Boolean = { true },
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val requestScope = scope ?: viewModelScope
    private val mutableState = MutableStateFlow(
        BrowserUiState(
            path = normalizedRemotePath(initialPath),
            query = initialQuery,
            searchVisible = initialSearchVisible,
        ),
    )
    val state: StateFlow<BrowserUiState> = mutableState.asStateFlow()

    private var disposed = false
    private var directoryGeneration = 0L
    private var searchGeneration = 0L
    private var detailsGeneration = 0L
    private var actionGeneration = 0L
    private var directoryJob: Job? = null
    private var searchJob: Job? = null
    private var detailsJob: Job? = null
    private var actionJob: Job? = null
    private var nextSearchPage = 2
    private var receivedSearchEntries = 0L

    init {
        refresh(forceRefresh = false)
        if (state.value.searchVisible) requestSearch(debounce = true)
    }

    fun navigate(path: String) {
        if (!active() || state.value.action?.busy == true) return
        val nextPath = normalizedRemotePath(path)
        closeDetails()
        dismissAction()
        invalidateSearch()
        mutableState.value = state.value.copy(
            path = nextPath,
            listing = if (nextPath == state.value.path) state.value.listing else null,
            searchVisible = false,
            search = SearchUiState(),
            challenge = null,
        )
        refresh(forceRefresh = false)
    }

    fun refresh(forceRefresh: Boolean = true) {
        if (!active()) return
        val generation = ++directoryGeneration
        directoryJob?.cancel()
        val path = state.value.path
        mutableState.value = state.value.copy(loading = true, error = null, challenge = null)
        directoryJob = requestScope.launch {
            try {
                if (!currentDirectory(generation)) return@launch
                val listing = withTimeout(REQUEST_TIMEOUT_MS) { loadDirectory(path, forceRefresh) }
                if (currentDirectory(generation)) {
                    mutableState.value = state.value.copy(listing = listing, error = null)
                }
            } catch (_: TimeoutCancellationException) {
                if (currentDirectory(generation)) {
                    mutableState.value = state.value.copy(error = "目录加载超时，请检查服务器连接后重试")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (currentDirectory(generation)) {
                    mutableState.value = state.value.copy(
                        error = throwable.message ?: "无法加载此目录",
                        challenge = if (throwable.isForbiddenAccess()) DirectoryChallenge(path) else null,
                    )
                }
            } finally {
                if (currentDirectory(generation)) {
                    mutableState.value = state.value.copy(loading = false)
                }
            }
        }
    }

    fun showSearch() {
        if (!active() || state.value.searchVisible) return
        mutableState.value = state.value.copy(searchVisible = true)
        requestSearch(debounce = true)
    }

    fun closeSearch() {
        if (!active()) return
        invalidateSearch()
        mutableState.value = state.value.copy(searchVisible = false, search = SearchUiState())
    }

    fun updateQuery(query: String) {
        if (!active() || state.value.query == query) return
        mutableState.value = state.value.copy(query = query)
        requestSearch(debounce = true)
    }

    fun retrySearch() = requestSearch(debounce = false)

    private fun requestSearch(debounce: Boolean) {
        if (!active()) return
        invalidateSearch()
        val generation = searchGeneration
        val path = state.value.path
        val query = state.value.query.trim()
        if (!state.value.searchVisible || query.isEmpty()) {
            mutableState.value = state.value.copy(search = SearchUiState())
            return
        }
        // Results belong to the submitted query; do not leave an older query's files actionable.
        mutableState.value = state.value.copy(search = SearchUiState(loading = true))
        searchJob = requestScope.launch {
            try {
                if (debounce) delay(SEARCH_DEBOUNCE_MS)
                if (!currentSearch(generation)) return@launch
                val page = withTimeout(REQUEST_TIMEOUT_MS) { search(path, query, 1) }
                if (currentSearch(generation)) {
                    receivedSearchEntries = page.entries.size.toLong()
                    val results = page.entries.distinctBy(BrowserEntry::path)
                    mutableState.value = state.value.copy(
                        search = SearchUiState(
                            results = results,
                            searched = true,
                            total = maxOf(page.total, results.size.toLong()),
                            hasMore = page.entries.isNotEmpty() && receivedSearchEntries < page.total,
                        ),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                if (currentSearch(generation)) {
                    mutableState.value = state.value.copy(
                        search = SearchUiState(searched = true, error = "搜索超时，请检查服务器连接后重试"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (currentSearch(generation)) {
                    mutableState.value = state.value.copy(
                        search = SearchUiState(searched = true, error = throwable.message ?: "搜索失败"),
                    )
                }
            } finally {
                if (currentSearch(generation)) {
                    mutableState.value = state.value.copy(search = state.value.search.copy(loading = false))
                }
            }
        }
    }

    fun loadMoreSearch() {
        if (!active()) return
        val current = state.value
        if (!current.searchVisible || current.search.loading || current.search.loadingMore || !current.search.hasMore) return
        val generation = searchGeneration
        val pageNumber = nextSearchPage
        mutableState.value = current.copy(search = current.search.copy(loadingMore = true, error = null))
        searchJob = requestScope.launch {
            try {
                if (!currentSearch(generation)) return@launch
                val page = withTimeout(REQUEST_TIMEOUT_MS) { search(current.path, current.query.trim(), pageNumber) }
                if (currentSearch(generation)) {
                    nextSearchPage = pageNumber + 1
                    receivedSearchEntries += page.entries.size
                    val results = (state.value.search.results + page.entries).distinctBy(BrowserEntry::path)
                    mutableState.value = state.value.copy(
                        search = state.value.search.copy(
                            results = results,
                            total = maxOf(page.total, results.size.toLong()),
                            hasMore = page.entries.isNotEmpty() && receivedSearchEntries < page.total,
                            loadingMore = false,
                        ),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                if (currentSearch(generation)) {
                    mutableState.value = state.value.copy(
                        search = state.value.search.copy(error = "加载更多结果超时，请重试"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (currentSearch(generation)) {
                    mutableState.value = state.value.copy(
                        search = state.value.search.copy(error = throwable.message ?: "无法加载更多结果，请重试"),
                    )
                }
            } finally {
                if (currentSearch(generation)) {
                    mutableState.value = state.value.copy(search = state.value.search.copy(loadingMore = false))
                }
            }
        }
    }

    fun showDetails(entry: BrowserEntry) {
        if (!active()) return
        val generation = ++detailsGeneration
        detailsJob?.cancel()
        mutableState.value = state.value.copy(details = BrowserDetailsState(entry))
        detailsJob = requestScope.launch {
            try {
                if (!currentDetails(generation)) return@launch
                val details = withTimeout(REQUEST_TIMEOUT_MS) { loadDetails(entry.path) }
                if (currentDetails(generation)) {
                    mutableState.value = state.value.copy(
                        details = BrowserDetailsState(entry, details, loading = false),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                if (currentDetails(generation)) {
                    mutableState.value = state.value.copy(
                        details = BrowserDetailsState(entry, loading = false, error = "读取详细信息超时，请重试"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (currentDetails(generation)) {
                    mutableState.value = state.value.copy(
                        details = BrowserDetailsState(
                            entry, loading = false, error = throwable.message ?: "无法读取详细信息",
                        ),
                    )
                }
            }
        }
    }

    fun closeDetails() {
        ++detailsGeneration
        detailsJob?.cancel()
        mutableState.value = state.value.copy(details = null)
    }

    fun retryDetails() {
        state.value.details?.entry?.let(::showDetails)
    }

    fun showActions(entry: BrowserEntry) {
        if (!active() || state.value.action?.busy == true) return
        ++actionGeneration
        actionJob?.cancel()
        closeDetails()
        mutableState.value = state.value.copy(action = BrowserActionState(entry))
    }

    fun chooseAction(kind: BrowserActionKind) {
        if (!active()) return
        val action = state.value.action ?: return
        if (!action.busy) {
            mutableState.value = state.value.copy(action = action.copy(kind = kind, error = null))
        }
    }

    fun renameInput(name: String) {
        if (!active()) return
        val action = state.value.action ?: return
        if (!action.busy) {
            mutableState.value = state.value.copy(action = action.copy(name = name, error = null))
        }
    }

    fun submitAction() {
        if (!active()) return
        val action = state.value.action ?: return
        if (action.busy || action.kind == BrowserActionKind.Menu) return
        if (action.kind == BrowserActionKind.Rename && !action.canRename) return
        val generation = ++actionGeneration
        val name = action.name.trim()
        mutableState.value = state.value.copy(action = action.copy(busy = true, error = null))
        actionJob = requestScope.launch {
            try {
                if (!currentAction(generation)) return@launch
                withTimeout(REQUEST_TIMEOUT_MS) {
                    when (action.kind) {
                        BrowserActionKind.Rename -> renameEntry(action.entry.path, name)
                        BrowserActionKind.Delete -> removeEntry(action.entry.parent, listOf(action.entry.item.name))
                        BrowserActionKind.Menu -> Unit
                    }
                }
                if (currentAction(generation)) {
                    mutableState.value = state.value.copy(
                        action = null,
                        message = when (action.kind) {
                            BrowserActionKind.Rename -> "已重命名为 $name"
                            BrowserActionKind.Delete -> "已删除 ${action.entry.item.name}"
                            BrowserActionKind.Menu -> null
                        },
                    )
                    refresh()
                    if (state.value.searchVisible) retrySearch()
                }
            } catch (_: TimeoutCancellationException) {
                if (currentAction(generation)) {
                    mutableState.value = state.value.copy(
                        action = action.copy(error = "请求超时，操作结果尚未确认。请刷新检查后再决定是否重试。"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (currentAction(generation)) {
                    mutableState.value = state.value.copy(
                        action = action.copy(error = throwable.message ?: "操作失败，请重试"),
                    )
                }
            } finally {
                if (currentAction(generation)) {
                    mutableState.value = state.value.copy(action = state.value.action?.copy(busy = false))
                }
            }
        }
    }

    fun dismissAction() {
        if (state.value.action?.busy == true) return
        ++actionGeneration
        actionJob?.cancel()
        mutableState.value = state.value.copy(action = null)
    }

    fun submitPassword(password: String) {
        if (!active() || password.isEmpty()) return
        val challenge = state.value.challenge ?: return
        if (challenge.submitting) return
        val generation = ++directoryGeneration
        directoryJob?.cancel()
        mutableState.value = state.value.copy(challenge = challenge.copy(submitting = true, error = null))
        directoryJob = requestScope.launch {
            try {
                if (!currentDirectory(generation)) return@launch
                // A 403 may mean missing refresh permission, so password validation uses a normal list.
                val listing = withTimeout(REQUEST_TIMEOUT_MS) { unlockDirectory(challenge.path, password) }
                if (currentDirectory(generation)) {
                    mutableState.value = state.value.copy(
                        listing = listing, loading = false, error = null, challenge = null,
                    )
                    if (state.value.searchVisible) retrySearch()
                }
            } catch (_: TimeoutCancellationException) {
                if (currentDirectory(generation)) {
                    mutableState.value = state.value.copy(
                        challenge = challenge.copy(error = "验证目录密码超时，请检查连接后重试"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (currentDirectory(generation)) {
                    mutableState.value = state.value.copy(
                        challenge = challenge.copy(
                            error = if (throwable.isForbiddenAccess()) {
                                "密码不正确，或当前账号无权访问。请修改后重试。"
                            } else {
                                throwable.message ?: "验证目录密码失败"
                            },
                        ),
                    )
                }
            } finally {
                if (currentDirectory(generation)) {
                    mutableState.value = state.value.copy(challenge = state.value.challenge?.copy(submitting = false))
                }
            }
        }
    }

    fun dismissPassword() {
        if (state.value.challenge?.submitting != true) {
            mutableState.value = state.value.copy(challenge = null)
        }
    }

    fun consumeMessage() {
        mutableState.value = state.value.copy(message = null)
    }

    /** The route calls this when its account is replaced, even if the Activity retains this model. */
    fun deactivate() {
        disposed = true
        ++directoryGeneration
        ++searchGeneration
        ++detailsGeneration
        ++actionGeneration
        directoryJob?.cancel()
        searchJob?.cancel()
        detailsJob?.cancel()
        actionJob?.cancel()
        // A retained, inactive account must not keep signed file metadata reachable through its UI.
        mutableState.value = BrowserUiState(loading = false)
    }

    override fun onCleared() {
        deactivate()
    }

    private fun active(): Boolean = !disposed && accountActive()
    private fun currentDirectory(generation: Long): Boolean = active() && directoryGeneration == generation
    private fun currentSearch(generation: Long): Boolean = active() && searchGeneration == generation
    private fun currentDetails(generation: Long): Boolean = active() && detailsGeneration == generation
    private fun currentAction(generation: Long): Boolean = active() && actionGeneration == generation

    private fun invalidateSearch() {
        ++searchGeneration
        searchJob?.cancel()
        nextSearchPage = 2
        receivedSearchEntries = 0L
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

internal fun validRemoteName(name: String): Boolean = name.trim().let { value ->
    value.isNotEmpty() && value != "." && value != ".." && '/' !in value && '\\' !in value
}
