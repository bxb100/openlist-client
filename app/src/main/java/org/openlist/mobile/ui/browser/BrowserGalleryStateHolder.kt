package org.openlist.mobile.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.openlist.mobile.media.MediaSequence
import org.openlist.mobile.ui.BrowserEntry

internal data class BrowserGalleryState(
    val sequence: MediaSequence? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selectedIndex: Int? = null,
)

/** Holds a gallery and its pending request through rotation, only for the current account session. */
internal class BrowserGalleryViewModel(
    private val loadSequence: suspend (BrowserEntry, List<BrowserEntry>) -> MediaSequence,
    private val accountActive: () -> Boolean = { true },
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val requestScope = scope ?: viewModelScope
    private val mutableState = MutableStateFlow(BrowserGalleryState())
    val state: StateFlow<BrowserGalleryState> = mutableState.asStateFlow()
    private var generation = 0L
    private var preparation: Job? = null
    private var disposed = false

    fun open(entry: BrowserEntry, completeSiblings: List<BrowserEntry>) {
        if (!active()) return
        val request = ++generation
        preparation?.cancel()
        val siblings = completeSiblings.toList()
        mutableState.value = state.value.copy(loading = true, error = null)
        preparation = requestScope.launch {
            try {
                if (!current(request)) return@launch
                val sequence = withTimeout(30_000L) { loadSequence(entry, siblings) }
                if (current(request)) {
                    mutableState.value = BrowserGalleryState(
                        sequence = sequence,
                        selectedIndex = sequence.currentIndex,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                if (current(request)) {
                    mutableState.value = state.value.copy(error = "读取同目录图片超时，请重试")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (current(request)) {
                    mutableState.value = state.value.copy(error = throwable.message ?: "无法打开图片")
                }
            } finally {
                if (current(request)) {
                    mutableState.value = state.value.copy(loading = false)
                }
            }
        }
    }

    fun show(index: Int) {
        if (!active()) return
        val sequence = state.value.sequence ?: return
        if (index in sequence.items.indices) {
            mutableState.value = state.value.copy(selectedIndex = index)
        }
    }

    /** A newer file action cancels opening an image without closing an already visible gallery. */
    fun cancelPreparation() {
        ++generation
        preparation?.cancel()
        preparation = null
        mutableState.value = state.value.copy(loading = false, error = null)
    }

    fun close() {
        cancelPreparation()
        mutableState.value = BrowserGalleryState()
    }

    fun consumeError() {
        mutableState.value = state.value.copy(error = null)
    }

    override fun onCleared() {
        disposed = true
        close()
    }

    private fun active(): Boolean = !disposed && accountActive()
    private fun current(request: Long): Boolean = active() && generation == request
}
