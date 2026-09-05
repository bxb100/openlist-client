package org.openlist.mobile.ui.browser

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.data.api.OpenListApiException
import org.openlist.mobile.ui.BrowserEntry

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    @Test
    fun `cancelled old directory cannot reopen password challenge or finish the new loading state`() = runTest {
        val oldDirectory = CompletableDeferred<DirectoryListing>()
        val nextDirectory = CompletableDeferred<DirectoryListing>()
        val viewModel = browser(
            loadDirectory = { path, _ ->
                // Models a transport callback that delivers an error even after its request is cancelled.
                withContext(NonCancellable) {
                    if (path == "/") oldDirectory.await() else nextDirectory.await()
                }
            },
        )
        runCurrent()

        viewModel.navigate("/photos")
        runCurrent()
        oldDirectory.completeExceptionally(OpenListApiException(403, "Old directory is protected"))
        runCurrent()

        assertThat(viewModel.state.value.path).isEqualTo("/photos")
        assertThat(viewModel.state.value.loading).isTrue()
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.challenge).isNull()

        nextDirectory.complete(listing("photo.jpg"))
        runCurrent()
        assertThat(viewModel.state.value.listing?.content?.single()?.name).isEqualTo("photo.jpg")
        assertThat(viewModel.state.value.loading).isFalse()
    }

    @Test
    fun `password verification cannot replace a directory opened while verification was pending`() = runTest {
        val unlocked = CompletableDeferred<DirectoryListing>()
        val viewModel = browser(
            loadDirectory = { path, _ ->
                if (path == "/") throw OpenListApiException(403, "Password required")
                listing("public.txt")
            },
            unlockDirectory = { _, _ -> withContext(NonCancellable) { unlocked.await() } },
        )
        runCurrent()
        viewModel.submitPassword("temporary-password")
        runCurrent()
        assertThat(viewModel.state.value.challenge?.submitting).isTrue()

        viewModel.navigate("/public")
        runCurrent()
        unlocked.complete(listing("private.txt"))
        runCurrent()

        assertThat(viewModel.state.value.path).isEqualTo("/public")
        assertThat(viewModel.state.value.listing?.content?.single()?.name).isEqualTo("public.txt")
        assertThat(viewModel.state.value.challenge).isNull()
    }

    @Test
    fun `unlocking a restored search retries it and invalidates an old forbidden search response`() = runTest {
        val oldSearch = CompletableDeferred<BrowserSearchPage>()
        val matching = entry("/protected", "photo.jpg")
        var unlocked = false
        var searchCount = 0
        val viewModel = browser(
            initialPath = "/protected",
            initialQuery = "photo",
            initialSearchVisible = true,
            loadDirectory = { _, _ -> throw OpenListApiException(403, "Password required") },
            unlockDirectory = { _, _ -> unlocked = true; listing(matching.item.name) },
            search = { _, _, _ ->
                searchCount++
                if (unlocked) BrowserSearchPage(listOf(matching), 1)
                else withContext(NonCancellable) { oldSearch.await() }
            },
        )
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        viewModel.submitPassword("temporary-password")
        runCurrent()
        oldSearch.completeExceptionally(OpenListApiException(403, "Password required"))
        runCurrent()

        assertThat(searchCount).isEqualTo(2)
        assertThat(viewModel.state.value.challenge).isNull()
        assertThat(viewModel.state.value.searchVisible).isTrue()
        assertThat(viewModel.state.value.search.results).containsExactly(matching)
        assertThat(viewModel.state.value.search.error).isNull()
    }

    @Test
    fun `search replaces query results immediately and rejects late results from the prior query`() = runTest {
        val oldResults = CompletableDeferred<BrowserSearchPage>()
        val newResults = CompletableDeferred<BrowserSearchPage>()
        val requests = mutableListOf<Pair<String, String>>()
        val viewModel = browser(search = { path, query, _ ->
            requests += path to query
            withContext(NonCancellable) {
                if (query == "old") oldResults.await() else newResults.await()
            }
        })
        viewModel.showSearch()
        viewModel.updateQuery("old")
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        viewModel.updateQuery("  new  ")
        assertThat(viewModel.state.value.search.results).isEmpty()
        advanceTimeBy(350)
        runCurrent()

        oldResults.completeExceptionally(IllegalStateException("Old query failed"))
        runCurrent()
        assertThat(viewModel.state.value.search.loading).isTrue()
        assertThat(viewModel.state.value.search.error).isNull()
        val nestedResult = entry("/photos/nested", "new.jpg")
        newResults.complete(BrowserSearchPage(listOf(nestedResult), 1))
        runCurrent()

        assertThat(requests).containsExactly("/" to "old", "/" to "new").inOrder()
        assertThat(viewModel.state.value.search.results).containsExactly(nestedResult)
        assertThat(viewModel.state.value.search.searched).isTrue()
    }

    @Test
    fun `clearing query cancels search and an in flight response cannot resurrect results`() = runTest {
        val results = CompletableDeferred<BrowserSearchPage>()
        val viewModel = browser(search = { _, _, _ -> withContext(NonCancellable) { results.await() } })
        viewModel.showSearch()
        viewModel.updateQuery("photo")
        runCurrent()
        advanceTimeBy(350)
        runCurrent()

        viewModel.updateQuery("")
        results.complete(BrowserSearchPage(listOf(entry("/", "photo.jpg")), 1))
        runCurrent()

        assertThat(viewModel.state.value.query).isEmpty()
        assertThat(viewModel.state.value.search).isEqualTo(SearchUiState())
    }

    @Test
    fun `load more preserves results on failure and retries the same page without duplicate file keys`() = runTest {
        val first = entry("/", "first.jpg")
        val second = entry("/nested", "second.jpg")
        val third = entry("/other", "third.jpg")
        val requestedPages = mutableListOf<Int>()
        var failSecondPage = true
        val viewModel = browser(search = { _, _, page ->
            requestedPages += page
            when {
                page == 1 -> BrowserSearchPage(listOf(first, second), total = 3)
                failSecondPage -> throw IllegalStateException("Connection interrupted")
                else -> BrowserSearchPage(listOf(second, third), total = 3)
            }
        })
        viewModel.showSearch()
        viewModel.updateQuery("jpg")
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        assertThat(viewModel.state.value.search.hasMore).isTrue()

        viewModel.loadMoreSearch()
        viewModel.loadMoreSearch()
        runCurrent()
        assertThat(viewModel.state.value.search.results).containsExactly(first, second).inOrder()
        assertThat(viewModel.state.value.search.error).isNotEmpty()
        assertThat(viewModel.state.value.search.hasMore).isTrue()
        assertThat(viewModel.state.value.search.loadingMore).isFalse()

        failSecondPage = false
        viewModel.loadMoreSearch()
        runCurrent()

        assertThat(requestedPages).containsExactly(1, 2, 2).inOrder()
        assertThat(viewModel.state.value.search.results).containsExactly(first, second, third).inOrder()
        assertThat(viewModel.state.value.search.error).isNull()
        assertThat(viewModel.state.value.search.hasMore).isFalse()
        assertThat(viewModel.state.value.search.total).isEqualTo(3)
    }

    @Test
    fun `loading another search page cannot append files after query changes`() = runTest {
        val oldPage = CompletableDeferred<BrowserSearchPage>()
        val newest = entry("/", "new.jpg")
        val viewModel = browser(search = { _, query, page ->
            when {
                query == "new" -> BrowserSearchPage(listOf(newest), 1)
                page == 1 -> BrowserSearchPage(listOf(entry("/", "old.jpg")), 2)
                else -> withContext(NonCancellable) { oldPage.await() }
            }
        })
        viewModel.showSearch()
        viewModel.updateQuery("old")
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        viewModel.loadMoreSearch()
        runCurrent()
        viewModel.updateQuery("new")
        advanceTimeBy(350)
        runCurrent()
        oldPage.completeExceptionally(IllegalStateException("Stale page failed"))
        runCurrent()

        assertThat(viewModel.state.value.search.results).containsExactly(newest)
        assertThat(viewModel.state.value.search.error).isNull()
        assertThat(viewModel.state.value.search.loadingMore).isFalse()
        assertThat(viewModel.state.value.search.hasMore).isFalse()
    }

    @Test
    fun `a stalled directory becomes retryable and permission safe retry does not force refresh`() = runTest {
        var respond = false
        val refreshFlags = mutableListOf<Boolean>()
        val viewModel = browser(loadDirectory = { _, refresh ->
            refreshFlags += refresh
            if (!respond) awaitCancellation()
            listing("available.txt")
        })
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(viewModel.state.value.loading).isFalse()
        assertThat(viewModel.state.value.error).isNotEmpty()
        respond = true
        viewModel.refresh(forceRefresh = false)
        runCurrent()

        assertThat(refreshFlags).containsExactly(false, false)
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.listing?.content?.single()?.name).isEqualTo("available.txt")
    }

    @Test
    fun `closing details invalidates a late failure before another file is inspected`() = runTest {
        val oldDetails = CompletableDeferred<FileDetails>()
        val currentDetails = CompletableDeferred<FileDetails>()
        val first = entry("/", "old.txt")
        val second = entry("/", "new.txt")
        val viewModel = browser(loadDetails = { path ->
            withContext(NonCancellable) {
                if (path == first.path) oldDetails.await() else currentDetails.await()
            }
        })
        viewModel.showDetails(first)
        runCurrent()
        viewModel.closeDetails()
        viewModel.showDetails(second)
        runCurrent()
        oldDetails.completeExceptionally(IllegalStateException("Old metadata failed"))
        runCurrent()

        assertThat(viewModel.state.value.details?.entry).isEqualTo(second)
        assertThat(viewModel.state.value.details?.loading).isTrue()
        assertThat(viewModel.state.value.details?.error).isNull()
        currentDetails.complete(FileDetails(name = second.item.name, size = 42))
        runCurrent()
        assertThat(viewModel.state.value.details?.details?.size).isEqualTo(42)
    }

    @Test
    fun `rename submits once and refreshes both directory and visible search after success`() = runTest {
        val mutation = CompletableDeferred<Unit>()
        val renamed = mutableListOf<Pair<String, String>>()
        val directoryRefreshes = mutableListOf<Boolean>()
        var searchCount = 0
        val viewModel = browser(
            loadDirectory = { _, refresh -> directoryRefreshes += refresh; listing("file.txt") },
            search = { _, _, _ -> searchCount++; BrowserSearchPage(listOf(entry("/", "file.txt")), 1) },
            renameEntry = { path, name -> renamed += path to name; mutation.await() },
        )
        viewModel.showSearch()
        viewModel.updateQuery("file")
        runCurrent()
        advanceTimeBy(350)
        runCurrent()
        viewModel.showActions(entry("/", "file.txt"))
        viewModel.chooseAction(BrowserActionKind.Rename)
        viewModel.renameInput("  file-renamed.txt  ")
        viewModel.submitAction()
        viewModel.submitAction()
        viewModel.dismissAction()
        runCurrent()

        assertThat(viewModel.state.value.action?.busy).isTrue()
        assertThat(renamed).containsExactly("/file.txt" to "file-renamed.txt")
        mutation.complete(Unit)
        runCurrent()

        assertThat(viewModel.state.value.action).isNull()
        assertThat(viewModel.state.value.searchVisible).isTrue()
        assertThat(viewModel.state.value.query).isEqualTo("file")
        assertThat(searchCount).isEqualTo(2)
        assertThat(directoryRefreshes).containsExactly(false, true).inOrder()
        assertThat(viewModel.state.value.message).isNotEmpty()
    }

    @Test
    fun `rename rejects path traversal input and delete addresses the selected search result parent`() = runTest {
        val renameCalls = mutableListOf<String>()
        val deleteCalls = mutableListOf<Pair<String, List<String>>>()
        val viewModel = browser(
            renameEntry = { _, name -> renameCalls += name },
            removeEntry = { parent, names -> deleteCalls += parent to names },
        )
        viewModel.showActions(entry("/nested/private", "file.txt"))
        viewModel.chooseAction(BrowserActionKind.Rename)
        viewModel.renameInput("../file.txt")
        viewModel.submitAction()
        runCurrent()
        assertThat(renameCalls).isEmpty()

        viewModel.chooseAction(BrowserActionKind.Delete)
        viewModel.submitAction()
        runCurrent()
        assertThat(deleteCalls).containsExactly("/nested/private" to listOf("file.txt"))
    }

    @Test
    fun `account replacement clears metadata and late destructive operation does not refresh the new account`() = runTest {
        val mutation = CompletableDeferred<Unit>()
        var directoryLoads = 0
        val viewModel = browser(
            loadDirectory = { _, _ -> directoryLoads++; listing("private.txt") },
            removeEntry = { _, _ -> withContext(NonCancellable) { mutation.await() } },
        )
        runCurrent()
        viewModel.showActions(entry("/private", "file.txt"))
        viewModel.chooseAction(BrowserActionKind.Delete)
        viewModel.submitAction()
        runCurrent()

        viewModel.deactivate()
        mutation.complete(Unit)
        runCurrent()
        viewModel.refresh()
        runCurrent()

        assertThat(directoryLoads).isEqualTo(1)
        assertThat(viewModel.state.value).isEqualTo(BrowserUiState(loading = false))
    }

    @Test
    fun `inactive account is checked before a queued network request starts`() = runTest {
        var accountActive = true
        var calls = 0
        val viewModel = browser(
            loadDirectory = { _, _ -> calls++; DirectoryListing() },
            accountActive = { accountActive },
        )
        accountActive = false
        runCurrent()
        viewModel.refresh()
        runCurrent()

        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `restored search inputs request fresh directory metadata and the selected query`() = runTest {
        val restoredLoads = mutableListOf<String>()
        val restoredSearches = mutableListOf<Pair<String, String>>()
        val restored = browser(
            initialPath = "/photos",
            initialQuery = "summer",
            initialSearchVisible = true,
            loadDirectory = { path, _ -> restoredLoads += path; DirectoryListing() },
            search = { path, query, _ -> restoredSearches += path to query; BrowserSearchPage(emptyList(), 0) },
        )
        runCurrent()
        advanceTimeBy(350)
        runCurrent()

        assertThat(restored.state.value.path).isEqualTo("/photos")
        assertThat(restored.state.value.query).isEqualTo("summer")
        assertThat(restored.state.value.searchVisible).isTrue()
        assertThat(restored.state.value.details).isNull()
        assertThat(restoredLoads).containsExactly("/photos")
        assertThat(restoredSearches).containsExactly("/photos" to "summer")
    }

    private fun CoroutineScope.browser(
        loadDirectory: suspend (String, Boolean) -> DirectoryListing = { _, _ -> DirectoryListing() },
        search: suspend (String, String, Int) -> BrowserSearchPage = { _, _, _ -> BrowserSearchPage(emptyList(), 0) },
        unlockDirectory: suspend (String, String) -> DirectoryListing = { _, _ -> DirectoryListing() },
        loadDetails: suspend (String) -> FileDetails = { FileDetails(rawUrl = "https://example.com/private?sign=secret") },
        renameEntry: suspend (String, String) -> Unit = { _, _ -> },
        removeEntry: suspend (String, List<String>) -> Unit = { _, _ -> },
        initialPath: String = "/",
        initialQuery: String = "",
        initialSearchVisible: Boolean = false,
        accountActive: () -> Boolean = { true },
    ) = BrowserViewModel(
        loadDirectory = loadDirectory,
        search = search,
        unlockDirectory = unlockDirectory,
        loadDetails = loadDetails,
        renameEntry = renameEntry,
        removeEntry = removeEntry,
        initialPath = initialPath,
        initialQuery = initialQuery,
        initialSearchVisible = initialSearchVisible,
        accountActive = accountActive,
        scope = this,
    )

    private fun listing(name: String) = DirectoryListing(content = listOf(OpenListObject(name = name)), total = 1)

    private fun entry(parent: String, name: String) = BrowserEntry(
        path = "${parent.trimEnd('/')}/$name",
        parent = parent,
        item = OpenListObject(name = name),
    )
}
