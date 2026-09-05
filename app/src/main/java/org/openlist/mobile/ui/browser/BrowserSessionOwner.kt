package org.openlist.mobile.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Retains the active browser through rotation and clears it even when another tab is visible. */
internal class BrowserSessionOwner(
    private val invalidation: StateFlow<Long>,
    private val busy: StateFlow<Boolean>,
    scope: CoroutineScope? = null,
) : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private var identity: String? = null
    private var selectedGeneration = invalidation.value

    private val sessionObserver = (scope ?: viewModelScope).launch {
        combine(invalidation, busy) { _, _ -> Unit }.collect {
            // Read current values because a queued transition may be delivered after the
            // new account's browser has already selected its generation during composition.
            if (busy.value || identity != null && invalidation.value != selectedGeneration) {
                clearSession()
            }
        }
    }

    fun select(identity: String, generation: Long): ViewModelStoreOwner {
        if (this.identity != identity || selectedGeneration != generation) {
            clearSession()
            this.identity = identity
        }
        selectedGeneration = generation
        return this
    }

    fun clearSession() {
        viewModelStore.clear()
        identity = null
    }

    override fun onCleared() {
        sessionObserver.cancel()
        clearSession()
    }
}
