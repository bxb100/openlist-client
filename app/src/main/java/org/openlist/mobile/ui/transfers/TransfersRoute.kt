package org.openlist.mobile.ui.transfers

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await as awaitOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import org.openlist.mobile.data.preferences.AppSettings
import org.openlist.mobile.worker.DownloadWorker
import org.openlist.mobile.worker.UploadWorker
import java.util.UUID

private data class TransferObservation(
    val identity: TransferIdentity,
    val workInfos: List<WorkInfo> = emptyList(),
    val loadFailed: Boolean = false,
)

/** Call in the app shell so its destination badge and transfer list use the same observation. */
@Composable
fun rememberTransferCenterState(settings: AppSettings): TransferCenterState {
    val context = LocalContext.current.applicationContext
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val identity = remember(settings.server, settings.sessionBindingKey, settings.token) {
        TransferIdentity.from(settings)
    }
    val observation by remember(workManager, identity) {
        combine(
            workManager.getWorkInfosByTagFlow(identity.uploadTag),
            workManager.getWorkInfosByTagFlow(identity.downloadTag),
        ) { uploads, downloads ->
            TransferObservation(identity, uploads + downloads)
        }.retryWhen { cause, _ ->
            if (cause is CancellationException) return@retryWhen false
            emit(TransferObservation(identity, loadFailed = true))
            delay(5_000)
            true
        }
    }.collectAsStateWithLifecycle(initialValue = null)
    return remember(observation, identity) {
        val current = observation?.takeIf { it.identity == identity }
        TransferCenterState(
            entries = current?.let { transferEntries(it.workInfos, identity) }.orEmpty(),
            isLoading = current == null,
            loadFailed = current?.loadFailed == true,
        )
    }
}

@Composable
fun TransfersRoute(
    state: TransferCenterState,
    onBrowseFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var cancelling by remember { mutableStateOf(emptySet<UUID>()) }
    TransfersScreen(
        state = state,
        onBrowseFiles = onBrowseFiles,
        onCancel = { entry ->
            if (entry.status.isActive && entry.id !in cancelling) {
                cancelling = cancelling + entry.id
                scope.launch {
                    val failed = try {
                        when (entry.direction) {
                            TransferDirection.UPLOAD -> UploadWorker.cancel(context, entry.id)
                            TransferDirection.DOWNLOAD -> DownloadWorker.cancel(context, entry.id)
                        }.awaitOperation()
                        false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        true
                    } finally {
                        cancelling = cancelling - entry.id
                    }
                    if (failed) snackbar.showSnackbar("未能取消任务，请稍后重试。")
                }
            }
        },
        cancelling = cancelling,
        snackbarHost = { SnackbarHost(hostState = snackbar) },
        modifier = modifier,
    )
}
