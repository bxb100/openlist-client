package org.openlist.mobile.data.upload

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
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal const val UPLOAD_STAGING_DIRECTORY = "upload_staging"
internal const val UPLOAD_CHECKPOINT_DIRECTORY = "upload_checkpoints"

/** Durable ownership of one upload source grant by one WorkManager request. */
internal data class UploadGrantLease(
    val workId: String,
    val sourceUri: String,
    val uniqueWorkName: String,
    val sourceGrantRequired: Boolean = true,
    val checkpointKey: String? = null,
)

internal interface UploadGrantLeaseStore {
    fun entries(): List<UploadGrantLease>
    fun put(lease: UploadGrantLease)
    fun remove(workId: String)
}

/**
 * Reference-counts persisted grants by URI because Android grants are package-wide, not per work.
 */
internal class UploadGrantLedger(
    private val store: UploadGrantLeaseStore,
    private val releasePersistedReadGrant: (String) -> Unit,
) {
    @Synchronized
    fun track(lease: UploadGrantLease) {
        val existing = store.entries().firstOrNull { it.workId == lease.workId }
        require(existing == null || existing == lease) {
            "Upload work ${lease.workId} already owns another source grant"
        }
        store.put(lease)
    }

    @Synchronized
    fun entries(): List<UploadGrantLease> = store.entries()

    @Synchronized
    fun contains(workId: String): Boolean = store.entries().any { it.workId == workId }

    /** Unknown active work is treated conservatively as a legacy owner. */
    @Synchronized
    fun hasPotentialSourceOwner(activeWorkIds: Set<String>, sourceUri: String): Boolean {
        if (activeWorkIds.isEmpty()) return false
        val leases = store.entries().associateBy(UploadGrantLease::workId)
        return activeWorkIds.any { activeWorkId ->
            val owner = leases[activeWorkId]
            owner == null ||
                (owner.sourceUri == sourceUri && owner.sourceGrantRequired)
        }
    }

    @Synchronized
    fun registerCheckpoint(workId: String, checkpointKey: String) {
        require(checkpointKey.isNotBlank()) { "checkpointKey must not be blank" }
        val lease = store.entries().firstOrNull { it.workId == workId } ?: return
        require(lease.checkpointKey == null || lease.checkpointKey == checkpointKey) {
            "Upload work $workId already owns another checkpoint"
        }
        store.put(lease.copy(checkpointKey = checkpointKey))
    }

    /** Removes a lease whose platform grant was never successfully acquired. */
    @Synchronized
    fun discard(workId: String) {
        runCatching { store.remove(workId) }
    }

    /**
     * Marks the provider source unnecessary while retaining local-cleanup metadata until terminal.
     * The package permission is released only when no other active lease still requires this URI.
     */
    @Synchronized
    fun releaseSourceGrant(workId: String, legacySourceUri: String? = null) {
        val entries = store.entries()
        val lease = entries.firstOrNull { it.workId == workId }
        if (lease == null) {
            legacySourceUri
                ?.takeUnless { source -> entries.any {
                    it.sourceUri == source && it.sourceGrantRequired
                } }
                ?.let(::releaseWithoutLease)
            return
        }
        if (!lease.sourceGrantRequired) return

        val anotherOwnerExists = entries.any {
            it.workId != workId &&
                it.sourceUri == lease.sourceUri &&
                it.sourceGrantRequired
        }
        if (anotherOwnerExists) {
            runCatching { store.put(lease.copy(sourceGrantRequired = false)) }
            return
        }

        if (releaseSafely(lease.sourceUri)) {
            runCatching { store.put(lease.copy(sourceGrantRequired = false)) }
        }
    }

    /** Removes a fully cleaned terminal record after its source grant has been resolved. */
    @Synchronized
    fun removeTerminal(workId: String): Boolean {
        val lease = store.entries().firstOrNull { it.workId == workId } ?: return true
        if (lease.sourceGrantRequired) return false
        return runCatching { store.remove(workId) }.isSuccess
    }

    @Synchronized
    private fun releaseWithoutLease(sourceUri: String) {
        releaseSafely(sourceUri)
    }

    private fun releaseSafely(sourceUri: String): Boolean = try {
        releasePersistedReadGrant(sourceUri)
        true
    } catch (_: SecurityException) {
        // Another terminal callback already released the package-wide grant.
        true
    } catch (_: IllegalArgumentException) {
        // Some document providers report an absent persisted grant this way.
        true
    } catch (_: Exception) {
        // Keep the durable lease so reconciliation can retry provider cleanup.
        false
    }
}

/** Idempotent terminal cleanup of private upload state, independently testable from WorkManager. */
internal class UploadTerminalLocalCleanup(
    private val removeStaging: suspend (String) -> Unit,
    private val removeCheckpoint: suspend (String) -> Unit,
) {
    suspend fun cleanup(workId: String, checkpointKey: String?): Boolean = try {
        removeStaging(workId)
        checkpointKey?.let { removeCheckpoint(it) }
        true
    } catch (_: Exception) {
        false
    }
}

/** Runs terminal mutation only after a concurrently cancelled Worker has finished unwinding. */
internal suspend fun runAfterUploadWorkerExit(
    workId: String,
    activeWorkers: Set<String>,
    waitForStateChange: suspend () -> Unit = { delay(25L) },
    action: suspend () -> Unit,
) {
    while (workId in activeWorkers) waitForStateChange()
    action()
}

@SuppressLint("ApplySharedPref") // Ownership must reach disk before WorkManager accepts the job.
private class SharedPreferencesUploadGrantLeaseStore(context: Context) : UploadGrantLeaseStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun entries(): List<UploadGrantLease> = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith(LEASE_KEY_PREFIX) || !key.endsWith(URI_KEY_SUFFIX) || value !is String) {
            return@mapNotNull null
        }
        val workId = key.removePrefix(LEASE_KEY_PREFIX).removeSuffix(URI_KEY_SUFFIX)
        runCatching { UUID.fromString(workId) }.getOrNull() ?: return@mapNotNull null
        val uniqueWorkName = preferences.getString(
            LEASE_KEY_PREFIX + workId + UNIQUE_NAME_KEY_SUFFIX,
            null,
        ) ?: return@mapNotNull null
        val sourceGrantRequired = preferences.getBoolean(
            LEASE_KEY_PREFIX + workId + GRANT_REQUIRED_KEY_SUFFIX,
            true,
        )
        val checkpointKey = preferences.getString(
            LEASE_KEY_PREFIX + workId + CHECKPOINT_KEY_SUFFIX,
            null,
        )
        UploadGrantLease(
            workId = workId,
            sourceUri = value,
            uniqueWorkName = uniqueWorkName,
            sourceGrantRequired = sourceGrantRequired,
            checkpointKey = checkpointKey,
        )
    }

    override fun put(lease: UploadGrantLease) {
        check(
            preferences.edit()
                .putString(LEASE_KEY_PREFIX + lease.workId + URI_KEY_SUFFIX, lease.sourceUri)
                .putString(
                    LEASE_KEY_PREFIX + lease.workId + UNIQUE_NAME_KEY_SUFFIX,
                    lease.uniqueWorkName,
                )
                .putBoolean(
                    LEASE_KEY_PREFIX + lease.workId + GRANT_REQUIRED_KEY_SUFFIX,
                    lease.sourceGrantRequired,
                )
                .apply {
                    val checkpointPreference =
                        LEASE_KEY_PREFIX + lease.workId + CHECKPOINT_KEY_SUFFIX
                    if (lease.checkpointKey == null) {
                        remove(checkpointPreference)
                    } else {
                        putString(checkpointPreference, lease.checkpointKey)
                    }
                }
                .commit(),
        ) { "Unable to persist the upload source grant owner" }
    }

    override fun remove(workId: String) {
        check(
            preferences.edit()
                .remove(LEASE_KEY_PREFIX + workId + URI_KEY_SUFFIX)
                .remove(LEASE_KEY_PREFIX + workId + UNIQUE_NAME_KEY_SUFFIX)
                .remove(LEASE_KEY_PREFIX + workId + GRANT_REQUIRED_KEY_SUFFIX)
                .remove(LEASE_KEY_PREFIX + workId + CHECKPOINT_KEY_SUFFIX)
                .commit(),
        ) { "Unable to remove the upload source grant owner" }
    }

    private companion object {
        const val PREFERENCES_NAME = "openlist_upload_grants"
        const val LEASE_KEY_PREFIX = "lease."
        const val URI_KEY_SUFFIX = ".uri"
        const val UNIQUE_NAME_KEY_SUFFIX = ".unique_name"
        const val GRANT_REQUIRED_KEY_SUFFIX = ".grant_required"
        const val CHECKPOINT_KEY_SUFFIX = ".checkpoint"
    }
}

/**
 * Owns upload source grants from enqueue until staging or a real WorkManager terminal state.
 *
 * Call [initialize] from `Application.onCreate` so an ENQUEUED request cancelled before its Worker
 * starts is reconciled after a cold process start.
 */
object UploadGrantLifecycle {
    private const val CONTENT_SCHEME = "content"
    private const val RECONCILE_RETRY_MILLIS = 5_000L

    private data class Runtime(
        val context: Context,
        val workManager: WorkManager,
        val ledger: UploadGrantLedger,
        val terminalCleanup: UploadTerminalLocalCleanup,
        val activeWorkers: MutableSet<String>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbackExecutor = Executor { callback -> scope.launch { callback.run() } }
    private val initialized = AtomicBoolean(false)
    private val reconciliationJobs = ConcurrentHashMap<String, Job>()
    private val runtimeLock = Any()
    private val acquisitionLock = Any()

    @Volatile
    private var installedRuntime: Runtime? = null

    /** Reconciles durable leases left by terminal or cancelled work after process restart. */
    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val applicationContext = context.applicationContext
        scope.launch {
            val runtime = runtime(applicationContext)
            runtime.ledger.entries().forEach { lease ->
                scheduleReconciliation(runtime, lease.workId)
            }
        }
    }

    /** Persists ownership before taking the platform grant, closing the process-death gap. */
    internal fun acquire(
        context: Context,
        workId: UUID,
        sourceUri: Uri,
        uniqueWorkName: String,
    ) = synchronized(acquisitionLock) {
        require(sourceUri.scheme.equals(CONTENT_SCHEME, ignoreCase = true)) {
            "sourceUri must be a SAF content URI"
        }
        val runtime = runtime(context)
        val id = workId.toString()
        runtime.ledger.track(
            UploadGrantLease(
                workId = id,
                sourceUri = sourceUri.toString(),
                uniqueWorkName = uniqueWorkName,
            ),
        )
        try {
            runtime.context.contentResolver.takePersistableUriPermission(
                sourceUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (error: Exception) {
            runtime.ledger.discard(id)
            throw error
        }
    }

    /** Starts terminal observation after the request id is confirmed in WorkManager. */
    internal fun onEnqueued(context: Context, workId: UUID) {
        val runtime = runtime(context)
        scheduleReconciliation(runtime, workId.toString())
    }

    /** Prevents CANCELLED WorkInfo from racing a CoroutineWorker that is still unwinding. */
    internal fun onWorkerStarted(context: Context, workId: UUID) {
        runtime(context).activeWorkers += workId.toString()
    }

    internal fun onWorkerFinished(context: Context, workId: UUID) {
        val runtime = runtime(context)
        val id = workId.toString()
        runtime.activeWorkers -= id
        if (runtime.ledger.contains(id)) scheduleReconciliation(runtime, id)
    }

    /** Persists the checkpoint identity before multipart state can be written. */
    internal fun registerCheckpoint(context: Context, workId: UUID, checkpointKey: String) {
        runtime(context).ledger.registerCheckpoint(workId.toString(), checkpointKey)
    }

    /**
     * Resolves an enqueue failure or KEEP rejection before returning to the caller. If another
     * active unique request may share the URI, the durable lease remains until that owner stops.
     */
    internal suspend fun resolveAfterUnacceptedEnqueue(context: Context, workId: UUID) {
        val runtime = runtime(context)
        val id = workId.toString()
        runCatching { reconcileOnce(runtime, id) }
        if (runtime.ledger.contains(id)) scheduleReconciliation(runtime, id)
    }

    /** Called once private staging has made the document provider grant unnecessary. */
    internal fun onSourceNoLongerNeeded(context: Context, workId: UUID, sourceUri: Uri?) {
        val runtime = runtime(context)
        val id = workId.toString()
        releaseSourceGrant(runtime, id, sourceUri?.toString())
        if (runtime.ledger.contains(id)) {
            scheduleReconciliation(runtime, id)
        } else {
            stopReconciliation(id)
        }
    }

    internal suspend fun onWorkDisposition(
        context: Context,
        workId: UUID,
        sourceUri: Uri?,
        disposition: UploadWorkDisposition,
    ) {
        val runtime = runtime(context)
        val id = workId.toString()
        if (disposition.shouldReleaseSourceGrant) {
            cleanupTerminal(runtime, id, sourceUri?.toString())
            if (runtime.ledger.contains(id)) {
                scheduleReconciliation(runtime, id)
            } else {
                stopReconciliation(id)
            }
        }
    }

    /** Preferred cancellation path for upload UI controls. */
    fun cancel(context: Context, workId: UUID): Operation {
        initialize(context)
        val runtime = runtime(context)
        return runtime.workManager.cancelWorkById(workId).also { operation ->
            operation.result.addListener(
                { scheduleReconciliation(runtime, workId.toString()) },
                callbackExecutor,
            )
        }
    }

    private fun runtime(context: Context): Runtime {
        installedRuntime?.let { return it }
        return synchronized(runtimeLock) {
            installedRuntime ?: context.applicationContext.let { applicationContext ->
                val ledger = UploadGrantLedger(
                    store = SharedPreferencesUploadGrantLeaseStore(applicationContext),
                    releasePersistedReadGrant = { rawUri ->
                        applicationContext.contentResolver.releasePersistableUriPermission(
                            Uri.parse(rawUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    },
                )
                Runtime(
                    context = applicationContext,
                    workManager = WorkManager.getInstance(applicationContext),
                    ledger = ledger,
                    terminalCleanup = UploadTerminalLocalCleanup(
                        removeStaging = UploadStagingStore(
                            File(applicationContext.filesDir, UPLOAD_STAGING_DIRECTORY),
                        )::remove,
                        removeCheckpoint = JsonUploadCheckpointStore(
                            File(applicationContext.filesDir, UPLOAD_CHECKPOINT_DIRECTORY),
                        )::remove,
                    ),
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
                val info = runtime.workManager.getWorkInfoByIdFlow(UUID.fromString(workId)).first()
                when {
                    info == null -> waitForPotentialSharedOwner(runtime, workId)
                    info.state.isFinished -> cleanupObservedTerminal(runtime, workId)
                    else -> {
                        val terminal = runtime.workManager
                            .getWorkInfoByIdFlow(UUID.fromString(workId))
                            .first { current -> current == null || current.state.isFinished }
                        if (terminal == null) {
                            waitForPotentialSharedOwner(runtime, workId)
                        } else {
                            cleanupObservedTerminal(runtime, workId)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // WorkManager/provider state is retried without dropping durable ownership.
            }
            if (runtime.ledger.contains(workId)) delay(RECONCILE_RETRY_MILLIS)
        }
    }

    private suspend fun reconcileOnce(runtime: Runtime, workId: String) {
        val info = runtime.workManager.getWorkInfoByIdFlow(UUID.fromString(workId)).first()
        when {
            info == null -> releaseUnlessSharedOwnerIsActive(runtime, workId)
            info.state.isFinished -> cleanupObservedTerminal(runtime, workId)
        }
    }

    private suspend fun waitForPotentialSharedOwner(runtime: Runtime, workId: String) {
        val lease = runtime.ledger.entries().firstOrNull { it.workId == workId } ?: return
        val currentlyShared = runtime.workManager
            .getWorkInfosForUniqueWorkFlow(lease.uniqueWorkName)
            .first()
            .hasPotentialSourceOwner(runtime.ledger, lease.sourceUri)
        if (!currentlyShared) {
            cleanupTerminal(runtime, workId)
            return
        }
        runtime.workManager.getWorkInfosForUniqueWorkFlow(lease.uniqueWorkName).first { infos ->
            !infos.hasPotentialSourceOwner(runtime.ledger, lease.sourceUri)
        }
        cleanupTerminal(runtime, workId)
    }

    private suspend fun releaseUnlessSharedOwnerIsActive(runtime: Runtime, workId: String) {
        val lease = runtime.ledger.entries().firstOrNull { it.workId == workId } ?: return
        val sharedOwnerActive = runtime.workManager
            .getWorkInfosForUniqueWorkFlow(lease.uniqueWorkName)
            .first()
            .hasPotentialSourceOwner(runtime.ledger, lease.sourceUri)
        if (!sharedOwnerActive) cleanupTerminal(runtime, workId)
    }

    private fun List<WorkInfo>.hasPotentialSourceOwner(
        ledger: UploadGrantLedger,
        sourceUri: String,
    ): Boolean = ledger.hasPotentialSourceOwner(
        activeWorkIds = asSequence()
            .filterNot { info -> info.state.isFinished }
            .mapTo(mutableSetOf()) { info -> info.id.toString() },
        sourceUri = sourceUri,
    )

    private suspend fun cleanupTerminal(
        runtime: Runtime,
        workId: String,
        legacySourceUri: String? = null,
    ) {
        val lease = runtime.ledger.entries().firstOrNull { it.workId == workId }
        if (!runtime.terminalCleanup.cleanup(workId, lease?.checkpointKey)) {
            // Keep the durable record so terminal reconciliation can retry local cleanup.
            return
        }
        releaseSourceGrant(runtime, workId, legacySourceUri)
        runtime.ledger.removeTerminal(workId)
    }

    private suspend fun cleanupObservedTerminal(runtime: Runtime, workId: String) {
        // WorkManager can publish CANCELLED before CoroutineWorker has stopped writing its final
        // checkpoint. Wait for the worker's finally block so cleanup is the last file mutation.
        runAfterUploadWorkerExit(workId, runtime.activeWorkers) {
            cleanupTerminal(runtime, workId)
        }
    }

    private fun releaseSourceGrant(
        runtime: Runtime,
        workId: String,
        legacySourceUri: String?,
    ) = synchronized(acquisitionLock) {
        runtime.ledger.releaseSourceGrant(workId, legacySourceUri)
    }

    private fun stopReconciliation(workId: String) {
        reconciliationJobs.remove(workId)?.cancel()
    }
}
