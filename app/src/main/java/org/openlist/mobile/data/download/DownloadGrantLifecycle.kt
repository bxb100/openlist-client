package org.openlist.mobile.data.download

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** A durable ownership record for the persistable write grant of one download work request. */
internal data class DownloadGrantLease(
    val workId: String,
    val targetUri: String,
    val targetOpened: Boolean = false,
)

internal interface DownloadGrantLeaseStore {
    fun entries(): List<DownloadGrantLease>
    fun put(lease: DownloadGrantLease)
    fun remove(workId: String)
}

internal enum class DownloadCancelledTargetAction {
    WAIT_FOR_WORKER,
    CLEAR_THEN_RELEASE,
    RELEASE_ONLY,
}

internal fun cancelledTargetAction(
    workerActive: Boolean,
    targetOpened: Boolean,
): DownloadCancelledTargetAction = when {
    workerActive -> DownloadCancelledTargetAction.WAIT_FOR_WORKER
    targetOpened -> DownloadCancelledTargetAction.CLEAR_THEN_RELEASE
    else -> DownloadCancelledTargetAction.RELEASE_ONLY
}

/**
 * Serializes grant ownership independently from WorkManager so terminal cleanup is idempotent.
 *
 * Persisted URI grants are not reference counted per work request. If KEEP rejects a duplicate
 * request, releasing that request's grant could revoke the grant still needed by the accepted
 * request. Consequently, the last tracked lease is the only lease allowed to release the URI.
 */
internal class DownloadGrantLedger(
    private val store: DownloadGrantLeaseStore,
    private val releasePersistedWriteGrant: (String) -> Unit,
) {
    @Synchronized
    fun track(lease: DownloadGrantLease) {
        val existing = store.entries().firstOrNull { it.workId == lease.workId }
        require(existing == null || existing == lease) {
            "Download work ${lease.workId} already owns another SAF target"
        }
        store.put(lease)
    }

    @Synchronized
    fun entries(): List<DownloadGrantLease> = store.entries()

    @Synchronized
    fun contains(workId: String): Boolean = store.entries().any { it.workId == workId }

    @Synchronized
    fun hasTarget(targetUri: String): Boolean = store.entries().any {
        it.targetUri == targetUri
    }

    @Synchronized
    fun markTargetOpened(workId: String) {
        val lease = store.entries().firstOrNull { it.workId == workId } ?: return
        if (!lease.targetOpened) store.put(lease.copy(targetOpened = true))
    }

    @Synchronized
    fun targetWasOpened(workId: String): Boolean = store.entries()
        .firstOrNull { it.workId == workId }
        ?.targetOpened == true

    fun onDisposition(
        workId: String,
        disposition: DownloadWorkDisposition,
        legacyTargetUri: String? = null,
    ) {
        if (!disposition.shouldReleasePersistedGrant) return
        releaseTerminal(workId, legacyTargetUri)
    }

    /** Releases a terminal lease, or the supplied URI for a work request created before leases. */
    @Synchronized
    fun releaseTerminal(workId: String, legacyTargetUri: String? = null) {
        val entries = store.entries()
        val lease = entries.firstOrNull { it.workId == workId }
        if (lease == null) {
            legacyTargetUri
                ?.takeUnless { target -> entries.any { it.targetUri == target } }
                ?.let(::releaseWithoutLease)
            return
        }

        val anotherOwnerExists = entries.any {
            it.workId != workId && it.targetUri == lease.targetUri
        }
        if (anotherOwnerExists) {
            runCatching { store.remove(workId) }
            return
        }

        if (releaseSafely(lease.targetUri)) runCatching { store.remove(workId) }
    }

    @Synchronized
    private fun releaseWithoutLease(targetUri: String) {
        releaseSafely(targetUri)
    }

    private fun releaseSafely(targetUri: String): Boolean = try {
        releasePersistedWriteGrant(targetUri)
        true
    } catch (_: SecurityException) {
        // A terminal callback can race another terminal callback that already released it.
        true
    } catch (_: IllegalArgumentException) {
        // Providers commonly use this when no matching persisted permission remains.
        true
    } catch (_: Exception) {
        // Keep the durable lease so cold-start reconciliation can retry provider cleanup.
        false
    }
}

@SuppressLint("ApplySharedPref") // Ownership must reach disk before WorkManager accepts the job.
private class SharedPreferencesDownloadGrantLeaseStore(context: Context) : DownloadGrantLeaseStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun entries(): List<DownloadGrantLease> = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith(LEASE_KEY_PREFIX) || value !is String) return@mapNotNull null
        val workId = key.removePrefix(LEASE_KEY_PREFIX)
        runCatching { UUID.fromString(workId) }.getOrNull() ?: return@mapNotNull null
        when {
            value.startsWith(OPENED_VALUE_PREFIX) -> DownloadGrantLease(
                workId,
                value.removePrefix(OPENED_VALUE_PREFIX),
                targetOpened = true,
            )
            value.startsWith(PENDING_VALUE_PREFIX) -> DownloadGrantLease(
                workId,
                value.removePrefix(PENDING_VALUE_PREFIX),
                targetOpened = false,
            )
            else -> DownloadGrantLease(workId, value, targetOpened = false)
        }
    }

    override fun put(lease: DownloadGrantLease) {
        check(
            preferences.edit()
                .putString(
                    LEASE_KEY_PREFIX + lease.workId,
                    (if (lease.targetOpened) OPENED_VALUE_PREFIX else PENDING_VALUE_PREFIX) +
                        lease.targetUri,
                )
                .commit(),
        ) { "Unable to persist the download SAF grant owner" }
    }

    override fun remove(workId: String) {
        check(preferences.edit().remove(LEASE_KEY_PREFIX + workId).commit()) {
            "Unable to remove the download SAF grant owner"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "openlist_download_grants"
        const val LEASE_KEY_PREFIX = "lease."
        const val PENDING_VALUE_PREFIX = "pending:"
        const val OPENED_VALUE_PREFIX = "opened:"
    }
}

/**
 * Owns download SAF grants until WorkManager reaches a real terminal state.
 *
 * Call [initialize] once from `Application.onCreate`. This is required for a work request that is
 * cancelled while still ENQUEUED: its Worker never starts, so only cold-start reconciliation can
 * release a grant after the process has been recreated.
 */
object DownloadGrantLifecycle {
    private const val CONTENT_SCHEME = "content"
    private const val RECONCILE_RETRY_MILLIS = 5_000L

    private data class Runtime(
        val context: Context,
        val workManager: WorkManager,
        val ledger: DownloadGrantLedger,
        val activeWorkers: MutableSet<String>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbackExecutor = Executor { callback -> scope.launch { callback.run() } }
    private val initialized = AtomicBoolean(false)
    private val reconciliationJobs = ConcurrentHashMap<String, Job>()
    private val sharedOwnerJobs = ConcurrentHashMap<String, Job>()
    private val runtimeLock = Any()
    private val acquisitionLock = Any()

    @Volatile
    private var installedRuntime: Runtime? = null

    /** Reconciles grants left by terminal or cancelled work after a process restart. */
    fun initialize(context: Context) {
        val runtime = runtime(context)
        if (!initialized.compareAndSet(false, true)) return
        scope.launch {
            runtime.ledger.entries().forEach { scheduleReconciliation(runtime, it.workId) }
        }
    }

    /** Acquires and durably records the grant immediately before WorkManager enqueue. */
    internal fun acquire(
        context: Context,
        workId: UUID,
        targetUri: Uri,
    ) = synchronized(acquisitionLock) {
        require(targetUri.scheme.equals(CONTENT_SCHEME, ignoreCase = true)) {
            "targetUri must be a SAF content URI"
        }
        val runtime = runtime(context)
        val resolver = runtime.context.contentResolver
        val alreadyPersisted = runCatching {
            resolver.persistedUriPermissions.any { permission ->
                permission.isWritePermission && permission.uri == targetUri
            }
        }.getOrDefault(false)

        resolver.takePersistableUriPermission(
            targetUri,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        try {
            runtime.ledger.track(DownloadGrantLease(workId.toString(), targetUri.toString()))
        } catch (error: Exception) {
            if (!alreadyPersisted && !runtime.ledger.hasTarget(targetUri.toString())) {
                runCatching {
                    resolver.releasePersistableUriPermission(
                        targetUri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            throw error
        }
    }

    /** Adopts a request created by an older app version that did not persist a lease record. */
    internal fun adoptLegacy(context: Context, workId: UUID, targetUri: Uri) {
        val runtime = runtime(context)
        synchronized(acquisitionLock) {
            if (!runtime.ledger.contains(workId.toString())) {
                runCatching {
                    runtime.ledger.track(
                        DownloadGrantLease(workId.toString(), targetUri.toString()),
                    )
                }
            }
        }
        if (runtime.ledger.contains(workId.toString())) {
            scheduleReconciliation(runtime, workId.toString())
        }
    }

    /** Prevents terminal WorkInfo observation from revoking a grant before partial-file cleanup. */
    internal fun onWorkerStarted(context: Context, workId: UUID) {
        runtime(context).activeWorkers += workId.toString()
    }

    internal fun onTargetOpened(context: Context, workId: UUID) {
        val runtime = runtime(context)
        synchronized(acquisitionLock) {
            runtime.ledger.markTargetOpened(workId.toString())
        }
    }

    internal fun onWorkerFinished(context: Context, workId: UUID) {
        val runtime = runtime(context)
        runtime.activeWorkers -= workId.toString()
        if (runtime.ledger.contains(workId.toString())) {
            scheduleReconciliation(runtime, workId.toString())
        }
    }

    /** Starts durable terminal observation after the unique-work enqueue operation settles. */
    internal fun onEnqueued(context: Context, workId: UUID) {
        val runtime = runtime(context)
        scheduleReconciliation(runtime, workId.toString())
    }

    internal fun resolveAfterUnacceptedEnqueue(context: Context, workId: UUID) {
        val runtime = runtime(context)
        // The URI may already belong to an older active KEEP owner. Resolve WorkManager state
        // before releasing instead of revoking a process-wide grant synchronously.
        scheduleReconciliation(runtime, workId.toString())
    }

    internal fun onWorkDisposition(
        context: Context,
        workId: UUID,
        targetUri: Uri?,
        disposition: DownloadWorkDisposition,
    ) {
        val runtime = runtime(context)
        synchronized(acquisitionLock) {
            runtime.ledger.onDisposition(
                workId = workId.toString(),
                disposition = disposition,
                legacyTargetUri = targetUri?.toString(),
            )
        }
        if (disposition.shouldReleasePersistedGrant) {
            stopReconciliation(workId.toString())
            if (runtime.ledger.contains(workId.toString())) {
                scheduleReconciliation(runtime, workId.toString())
            }
        }
    }

    /** API 31+ exposes explicit app cancellation before WorkInfo publishes its terminal state. */
    internal fun onCancelledByApp(context: Context, workId: UUID, targetUri: Uri?) {
        onWorkDisposition(
            context = context,
            workId = workId,
            targetUri = targetUri,
            disposition = DownloadWorkDisposition.CANCELLED,
        )
    }

    /**
     * Preferred cancellation API for any future download UI. The terminal observer, rather than
     * the act of requesting cancellation, releases the grant only after cancellation succeeds.
     */
    fun cancel(context: Context, workId: UUID): Operation {
        initialize(context)
        val runtime = runtime(context)
        val operation = runtime.workManager.cancelWorkById(workId)
        operation.result.addListener(
            { scheduleReconciliation(runtime, workId.toString()) },
            callbackExecutor,
        )
        return operation
    }

    private fun runtime(context: Context): Runtime {
        installedRuntime?.let { return it }
        return synchronized(runtimeLock) {
            installedRuntime ?: context.applicationContext.let { applicationContext ->
                val ledger = DownloadGrantLedger(
                    store = SharedPreferencesDownloadGrantLeaseStore(applicationContext),
                    releasePersistedWriteGrant = { rawUri ->
                        applicationContext.contentResolver.releasePersistableUriPermission(
                            Uri.parse(rawUri),
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    },
                )
                Runtime(
                    context = applicationContext,
                    workManager = WorkManager.getInstance(applicationContext),
                    ledger = ledger,
                    activeWorkers = ConcurrentHashMap.newKeySet(),
                ).also { installedRuntime = it }
            }
        }
    }

    private fun scheduleReconciliation(runtime: Runtime, workId: String) {
        if (!runtime.ledger.contains(workId)) return
        lateinit var candidate: Job
        candidate = scope.launch(start = CoroutineStart.LAZY) {
            try {
                reconcileUntilResolved(runtime, workId)
            } finally {
                reconciliationJobs.remove(workId, candidate)
            }
        }
        val existing = reconciliationJobs.putIfAbsent(workId, candidate)
        if (existing == null) candidate.start() else candidate.cancel()
    }

    private suspend fun reconcileUntilResolved(runtime: Runtime, workId: String) {
        while (runtime.ledger.contains(workId)) {
            try {
                val id = UUID.fromString(workId)
                val initial = runtime.workManager.getWorkInfoByIdFlow(id).first()
                val delegated = when {
                    initial == null -> reconcileMissingWork(runtime, workId)
                    initial.state.isFinished -> {
                        prepareCancelledTargetForRelease(runtime, workId, initial)
                        releaseTerminal(runtime, workId)
                        false
                    }
                    else -> {
                        val terminal = runtime.workManager.getWorkInfoByIdFlow(id).first { workInfo ->
                            workInfo == null || workInfo.state.isFinished
                        }
                        terminal?.let {
                            prepareCancelledTargetForRelease(runtime, workId, it)
                        }
                        releaseTerminal(runtime, workId)
                        false
                    }
                }
                if (delegated || !runtime.ledger.contains(workId)) return
                delay(RECONCILE_RETRY_MILLIS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                delay(RECONCILE_RETRY_MILLIS)
            }
        }
    }

    private suspend fun reconcileMissingWork(runtime: Runtime, workId: String): Boolean {
        val lease = runtime.ledger.entries().firstOrNull { it.workId == workId } ?: return false
        val uniqueName = DownloadTargetWork.uniqueName(lease.targetUri)
        val activeOwner = runtime.workManager.getWorkInfosForUniqueWorkFlow(uniqueName)
            .first()
            .any { workInfo -> !workInfo.state.isFinished }
        if (!activeOwner) {
            releaseTerminal(runtime, workId)
            return false
        }

        // KEEP rejected this work. Keep its lease durable while the accepted work is active: the
        // accepted work can finish concurrently and defer its release to this last lease.
        scheduleSharedOwnerReconciliation(runtime, uniqueName, workId)
        return true
    }

    private fun scheduleSharedOwnerReconciliation(
        runtime: Runtime,
        uniqueName: String,
        workId: String,
    ) {
        val key = "$uniqueName\u0000$workId"
        lateinit var candidate: Job
        candidate = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (runtime.ledger.contains(workId)) {
                    try {
                        runtime.workManager.getWorkInfosForUniqueWorkFlow(uniqueName).first { infos ->
                            infos.none { workInfo -> !workInfo.state.isFinished }
                        }
                        releaseTerminal(runtime, workId)
                        if (runtime.ledger.contains(workId)) delay(RECONCILE_RETRY_MILLIS)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        delay(RECONCILE_RETRY_MILLIS)
                    }
                }
            } finally {
                sharedOwnerJobs.remove(key, candidate)
            }
        }
        val existing = sharedOwnerJobs.putIfAbsent(key, candidate)
        if (existing == null) candidate.start() else candidate.cancel()
    }

    private fun releaseTerminal(
        runtime: Runtime,
        workId: String,
        legacyTargetUri: String? = null,
    ) = synchronized(acquisitionLock) {
        runtime.ledger.releaseTerminal(workId, legacyTargetUri)
    }

    private suspend fun prepareCancelledTargetForRelease(
        runtime: Runtime,
        workId: String,
        terminal: WorkInfo,
    ) {
        if (terminal.state != WorkInfo.State.CANCELLED) return

        // WorkManager publishes CANCELLED before a running CoroutineWorker necessarily finishes
        // unwinding. Keep the grant until DownloadEngine has had the chance to truncate partial
        // output and the Worker leaves its finally block.
        while (
            cancelledTargetAction(
                workerActive = workId in runtime.activeWorkers,
                targetOpened = runtime.ledger.targetWasOpened(workId),
            ) == DownloadCancelledTargetAction.WAIT_FOR_WORKER
        ) {
            delay(25L)
        }
        if (
            cancelledTargetAction(
                workerActive = false,
                targetOpened = runtime.ledger.targetWasOpened(workId),
            ) != DownloadCancelledTargetAction.CLEAR_THEN_RELEASE
        ) {
            return
        }
        val targetUri = runtime.ledger.entries().firstOrNull { it.workId == workId }?.targetUri
            ?: return
        runCatching {
            SafDownloadTarget(runtime.context.contentResolver, Uri.parse(targetUri)).clear()
        }
    }

    private fun stopReconciliation(workId: String) {
        reconciliationJobs.remove(workId)?.cancel()
    }
}
