package org.openlist.mobile.ui.browser

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.media.ContentKey
import org.openlist.mobile.media.MediaEntry
import org.openlist.mobile.media.MediaSequence
import org.openlist.mobile.ui.BrowserEntry

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserGalleryViewModelTest {
    @Test
    fun `late gallery completion cannot cover a newer file action after cancellation`() = runTest {
        val response = CompletableDeferred<MediaSequence>()
        val model = BrowserGalleryViewModel(
            loadSequence = { _, _ -> withContext(NonCancellable) { response.await() } },
            scope = this,
        )
        model.open(entry("old.jpg"), emptyList())
        runCurrent()
        model.cancelPreparation()
        response.complete(sequence("old.jpg"))
        runCurrent()

        assertThat(model.state.value).isEqualTo(BrowserGalleryState())
    }

    @Test
    fun `a stale gallery failure cannot replace a newer request or stop its progress`() = runTest {
        val oldResponse = CompletableDeferred<MediaSequence>()
        val newResponse = CompletableDeferred<MediaSequence>()
        val model = BrowserGalleryViewModel(
            loadSequence = { entry, _ ->
                withContext(NonCancellable) {
                    if (entry.item.name == "old.jpg") oldResponse.await() else newResponse.await()
                }
            },
            scope = this,
        )
        model.open(entry("old.jpg"), emptyList())
        runCurrent()
        model.open(entry("new.jpg"), emptyList())
        runCurrent()
        oldResponse.completeExceptionally(IllegalStateException("Old image lookup failed"))
        runCurrent()

        assertThat(model.state.value.loading).isTrue()
        assertThat(model.state.value.error).isNull()
        val newest = sequence("new.jpg")
        newResponse.complete(newest)
        runCurrent()

        assertThat(model.state.value.sequence).isSameInstanceAs(newest)
        assertThat(model.state.value.selectedIndex).isEqualTo(newest.currentIndex)
        assertThat(model.state.value.loading).isFalse()
    }

    @Test
    fun `cancelling another preparation preserves the gallery sequence and currently selected image`() = runTest {
        val opened = sequence("first.jpg", "second.jpg")
        val pending = CompletableDeferred<MediaSequence>()
        val model = BrowserGalleryViewModel(
            loadSequence = { entry, _ ->
                if (entry.item.name == "first.jpg") opened
                else withContext(NonCancellable) { pending.await() }
            },
            scope = this,
        )
        model.open(entry("first.jpg"), emptyList())
        runCurrent()
        model.show(1)
        model.open(entry("pending.jpg"), emptyList())
        runCurrent()
        model.cancelPreparation()
        pending.complete(sequence("pending.jpg"))
        runCurrent()
        model.show(100)

        assertThat(model.state.value.sequence).isSameInstanceAs(opened)
        assertThat(model.state.value.selectedIndex).isEqualTo(1)
        assertThat(model.state.value.loading).isFalse()
    }

    @Test
    fun `retained session owner keeps the gallery and selected image across route recreation`() = runTest {
        val owner = BrowserSessionOwner(MutableStateFlow(0L), MutableStateFlow(false), backgroundScope)
        val model = BrowserGalleryViewModel(loadSequence = { _, _ -> sequence("one.jpg", "two.jpg") }, scope = this)
        owner.select("account-a", 0).viewModelStore.put("gallery", model)
        model.open(entry("one.jpg"), emptyList())
        runCurrent()
        model.show(1)

        val restored = owner.select("account-a", 0).viewModelStore["gallery"] as BrowserGalleryViewModel
        assertThat(restored).isSameInstanceAs(model)
        assertThat(restored.state.value.sequence).isSameInstanceAs(model.state.value.sequence)
        assertThat(restored.state.value.selectedIndex).isEqualTo(1)
    }

    @Test
    fun `account identity is checked before queued work starts and before its result is published`() = runTest {
        var active = true
        var calls = 0
        val response = CompletableDeferred<MediaSequence>()
        val model = BrowserGalleryViewModel(
            loadSequence = { _, _ -> calls++; response.await() },
            accountActive = { active },
            scope = this,
        )
        model.open(entry("never-started.jpg"), emptyList())
        active = false
        runCurrent()
        assertThat(calls).isEqualTo(0)

        active = true
        model.open(entry("old-account.jpg"), emptyList())
        runCurrent()
        active = false
        response.complete(sequence("old-account.jpg"))
        runCurrent()

        assertThat(calls).isEqualTo(1)
        assertThat(model.state.value.sequence).isNull()
        assertThat(model.state.value.error).isNull()
    }

    @Test
    fun `removing the account owner clears gallery memory and invalidates pending work`() = runTest {
        val response = CompletableDeferred<MediaSequence>()
        val store = ViewModelStore()
        val model = BrowserGalleryViewModel(
            loadSequence = { _, _ -> withContext(NonCancellable) { response.await() } },
            scope = this,
        )
        store.put("gallery", model)
        model.open(entry("private.jpg"), emptyList())
        runCurrent()
        store.clear()
        response.complete(sequence("private.jpg"))
        runCurrent()
        model.open(entry("another.jpg"), emptyList())
        runCurrent()

        assertThat(model.state.value).isEqualTo(BrowserGalleryState())
    }

    @Test
    fun `timeout ends gallery progress and a later open can recover`() = runTest {
        var respond = false
        val model = BrowserGalleryViewModel(
            loadSequence = { _, _ -> if (respond) sequence("photo.jpg") else awaitCancellation() },
            scope = this,
        )
        model.open(entry("photo.jpg"), emptyList())
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertThat(model.state.value.loading).isFalse()
        assertThat(model.state.value.error).isNotEmpty()
        respond = true
        model.open(entry("photo.jpg"), emptyList())
        runCurrent()

        assertThat(model.state.value.sequence?.current?.name).isEqualTo("photo.jpg")
        assertThat(model.state.value.error).isNull()
    }

    private fun entry(name: String) = BrowserEntry("/$name", "/", OpenListObject(name = name, type = 5))

    private fun sequence(vararg names: String): MediaSequence = MediaSequence(
        items = names.map { name ->
            MediaEntry(
                remotePath = "/$name",
                name = name,
                kind = MediaKind.IMAGE,
                size = 1,
                modified = "",
                contentKey = ContentKey("key:$name"),
            )
        },
        currentIndex = 0,
        kind = MediaKind.IMAGE,
    )
}
