package org.openlist.mobile.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSessionOwnerTest {
    @Test
    fun `reselecting the same session after route recreation retains its child model`() = runTest {
        val invalidation = MutableStateFlow(0L)
        val busy = MutableStateFlow(false)
        val owner = BrowserSessionOwner(invalidation, busy, backgroundScope)
        val firstOwner = owner.select("account-a", 0)
        val child = ProbeViewModel()
        firstOwner.viewModelStore.put("browser", child)
        runCurrent()

        val recreatedOwner = owner.select("account-a", 0)
        runCurrent()

        assertThat(recreatedOwner).isSameInstanceAs(firstOwner)
        assertThat(recreatedOwner.viewModelStore["browser"]).isSameInstanceAs(child)
        assertThat(child.clearCount).isEqualTo(0)
    }

    @Test
    fun `session operation clears the child even with no browser route observing it`() = runTest {
        val invalidation = MutableStateFlow(0L)
        val busy = MutableStateFlow(false)
        val owner = BrowserSessionOwner(invalidation, busy, backgroundScope)
        val child = ProbeViewModel()
        owner.select("account-a", 0).viewModelStore.put("browser", child)
        runCurrent()

        busy.value = true
        runCurrent()

        assertThat(child.clearCount).isEqualTo(1)
        assertThat(owner.viewModelStore["browser"]).isNull()
        busy.value = false
        runCurrent()
        assertThat(owner.viewModelStore["browser"]).isNull()
    }

    @Test
    fun `queued invalidation cannot clear the new account that was already selected`() = runTest {
        val invalidation = MutableStateFlow(0L)
        val busy = MutableStateFlow(false)
        val owner = BrowserSessionOwner(invalidation, busy, backgroundScope)
        val oldChild = ProbeViewModel()
        owner.select("account-a", 0).viewModelStore.put("browser", oldChild)
        runCurrent()

        invalidation.value = 1L
        val newChild = ProbeViewModel()
        owner.select("account-b", 1).viewModelStore.put("browser", newChild)
        runCurrent()

        assertThat(oldChild.clearCount).isEqualTo(1)
        assertThat(newChild.clearCount).isEqualTo(0)
        assertThat(owner.viewModelStore["browser"]).isSameInstanceAs(newChild)
    }

    @Test
    fun `a new authentication generation replaces the child even when account identity is unchanged`() = runTest {
        val invalidation = MutableStateFlow(0L)
        val owner = BrowserSessionOwner(invalidation, MutableStateFlow(false), backgroundScope)
        val child = ProbeViewModel()
        owner.select("account-a", 0).viewModelStore.put("browser", child)
        runCurrent()

        invalidation.value = 1L
        owner.select("account-a", 1)
        runCurrent()

        assertThat(child.clearCount).isEqualTo(1)
        assertThat(owner.viewModelStore["browser"]).isNull()
    }

    @Test
    fun `activity owner removal clears its child and stops the session observer`() = runTest {
        val invalidation = MutableStateFlow(0L)
        val busy = MutableStateFlow(false)
        val owner = BrowserSessionOwner(invalidation, busy, this)
        val activityStore = ViewModelStore()
        activityStore.put("owner", owner)
        val child = ProbeViewModel()
        owner.select("account-a", 0).viewModelStore.put("browser", child)
        runCurrent()

        activityStore.clear()
        busy.value = true
        invalidation.value = 1L
        runCurrent()

        assertThat(child.clearCount).isEqualTo(1)
        assertThat(owner.viewModelStore["browser"]).isNull()
        // The observer uses this test's scope; runTest also verifies no child collector survives.
    }

    private class ProbeViewModel : ViewModel() {
        var clearCount = 0
            private set

        override fun onCleared() {
            clearCount++
        }
    }
}
