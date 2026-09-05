package org.openlist.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.core.model.joinRemotePath
import org.openlist.mobile.core.network.LocalNetworkPermissionController
import org.openlist.mobile.media.MediaSequence
import org.openlist.mobile.media.MediaTypeDetector
import org.openlist.mobile.media.OpenListPlaybackController
import org.openlist.mobile.media.OpenListPlaybackService
import org.openlist.mobile.media.PlaybackOverlay
import org.openlist.mobile.ui.OpenListApp
import org.openlist.mobile.ui.theme.OpenListTheme
import org.openlist.mobile.worker.DownloadWorker
import org.openlist.mobile.worker.UploadWorker
import org.openlist.mobile.data.download.DownloadSessionBinding
import org.openlist.mobile.data.download.DownloadUniqueEnqueueResult
import org.openlist.mobile.data.upload.UploadSessionBinding
import org.openlist.mobile.data.upload.UploadUniqueEnqueueResult
import org.openlist.mobile.media.isVideo
import org.openlist.mobile.media.playbackShowsPause
import org.openlist.mobile.media.performPlaybackToggle
import androidx.media3.common.Player

class MainActivity : ComponentActivity(), LocalNetworkPermissionController {
    private var localNetworkPermissionResult: ((Boolean) -> Unit)? = null
    private var pendingUpload: PendingUpload? = null
    private var pendingDownload: PendingDownload? = null
    private var playbackControllerFuture: ListenableFuture<OpenListPlaybackController>? = null
    private var playbackController by mutableStateOf<OpenListPlaybackController?>(null)
    private var pendingPlaybackSequence: MediaSequence? = null
    private var mediaOpenGeneration = 0L
    private var showPlaybackOverlay by mutableStateOf(false)
    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetworkPermissionResult?.invoke(granted)
        localNetworkPermissionResult = null
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Uploads remain available when notifications are denied. */ }
    private val uploadDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val upload = pendingUpload
        pendingUpload = null
        if (uri != null && upload != null) enqueueUpload(uri, upload)
    }
    private val downloadDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        val download = pendingDownload
        pendingDownload = null
        if (uri != null && download != null) enqueueDownload(uri, download)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingUpload = savedInstanceState?.getString(STATE_UPLOAD_DIRECTORY)?.let { directory ->
            val baseUrl = savedInstanceState.getString(STATE_UPLOAD_SERVER_BASE_URL)
                ?: return@let null
            val username = savedInstanceState.getString(STATE_UPLOAD_SERVER_USERNAME)
                ?: return@let null
            val binding = savedInstanceState.getString(STATE_UPLOAD_SESSION_BINDING)
                ?: return@let null
            PendingUpload(
                directory = directory,
                serverBaseUrl = baseUrl,
                serverUsername = username,
                serverAllowInsecureHttp = savedInstanceState.getBoolean(
                    STATE_UPLOAD_SERVER_ALLOW_INSECURE_HTTP,
                ),
                sessionBinding = binding,
            )
        }
        pendingDownload = savedInstanceState?.getString(STATE_DOWNLOAD_PATH)?.let { path ->
            val binding = savedInstanceState.getString(STATE_DOWNLOAD_SESSION_BINDING)
                ?: return@let null
            PendingDownload(
                remotePath = path,
                fileName = savedInstanceState.getString(STATE_DOWNLOAD_NAME).orEmpty(),
                expectedBytes = savedInstanceState.getLong(STATE_DOWNLOAD_BYTES, -1L)
                    .takeIf { it >= 0L },
                mimeType = savedInstanceState.getString(STATE_DOWNLOAD_MIME)
                    ?: "application/octet-stream",
                sessionBinding = binding,
            )
        }
        enableEdgeToEdge()
        val container = (application as OpenListApplication).container
        setContent {
            val settings by container.sessionStore.settings.collectAsStateWithLifecycle()
            val sessionLoadState by container.sessionLoadState.collectAsStateWithLifecycle()
            val playbackInvalidation by container.playbackInvalidation.collectAsStateWithLifecycle()
            val playbackServiceRunning by OpenListPlaybackService.runningInProcess
                .collectAsStateWithLifecycle()
            val connectedPlaybackController = playbackController
            val playbackState = if (connectedPlaybackController == null) {
                null
            } else {
                connectedPlaybackController.state.collectAsStateWithLifecycle().value
            }
            val hasPlaybackQueue = playbackState?.queue?.isNotEmpty() == true
            val playbackConnectionSnapshot = PlaybackConnectionSnapshot(
                identity = PlaybackSessionIdentity(
                    baseUrl = settings.server.baseUrl,
                    username = settings.server.username,
                    authenticated = settings.token.isNotBlank(),
                ),
                serviceRunning = playbackServiceRunning,
            )
            var observedPlaybackConnection by remember {
                mutableStateOf<PlaybackConnectionSnapshot?>(null)
            }
            LaunchedEffect(playbackInvalidation) {
                if (playbackInvalidation > 0L) {
                    releasePlaybackController(stopPlayback = true)
                }
            }
            LaunchedEffect(sessionLoadState, playbackConnectionSnapshot) {
                if (sessionLoadState != SessionLoadState.Ready) return@LaunchedEffect
                when (
                    playbackConnectionAction(
                        previous = observedPlaybackConnection,
                        current = playbackConnectionSnapshot,
                    )
                ) {
                    PlaybackConnectionAction.NONE -> Unit
                    PlaybackConnectionAction.ATTACH -> connectPlaybackController()
                    PlaybackConnectionAction.RELEASE -> {
                        releasePlaybackController(stopPlayback = true)
                    }
                }
                observedPlaybackConnection = playbackConnectionSnapshot
            }
            OpenListTheme(sessionStore = container.sessionStore) {
                Box(Modifier.fillMaxSize()) {
                    OpenListApp(
                        container = container,
                        onOpenMedia = { path, item, siblings ->
                            openMedia(container, path, item, siblings)
                        },
                        hasPlaybackQueue = hasPlaybackQueue,
                        playbackTitle = playbackState?.currentItem?.mediaMetadata?.title?.toString(),
                        playbackIsPlaying = playbackState?.let {
                            playbackShowsPause(it.playWhenReady, it.playbackState)
                        } == true,
                        playbackIsVideo = playbackState?.currentItem?.isVideo == true,
                        playbackStatusLabel = playbackState?.let { state ->
                            when {
                                state.playbackState == Player.STATE_ENDED -> "播放已结束"
                                state.playbackState == Player.STATE_IDLE -> "待播放"
                                state.playbackState == Player.STATE_BUFFERING && state.playWhenReady -> "正在缓冲"
                                state.isPlaying -> "正在播放"
                                state.playWhenReady -> "等待播放"
                                else -> "已暂停"
                            }
                        },
                        playbackActionLabel = if (playbackState?.playbackState == Player.STATE_ENDED) "重新播放" else "继续播放",
                        onPlaybackToggle = {
                            playbackController?.let { controller ->
                                val state = controller.state.value
                                performPlaybackToggle(
                                    playWhenReady = state.playWhenReady,
                                    playbackState = state.playbackState,
                                    pause = controller::pause,
                                    seekToStart = { controller.seekTo(0L) },
                                    play = {
                                        if (state.currentItem?.isVideo == true) showPlaybackOverlay = true
                                        controller.play()
                                    },
                                )
                            }
                        },
                        onPlaybackQueueRequested = {
                            if (playbackController?.state?.value?.queue?.isNotEmpty() == true) {
                                showPlaybackOverlay = true
                            }
                        },
                        onUploadRequested = ::selectFileForUpload,
                        onDownloadRequested = ::selectDownloadDestination,
                        onClearCacheRequested = { container.clearCache() },
                        onCacheUsageRequested = {
                            withContext(Dispatchers.IO) { container.cacheManager.stats() }
                        },
                    )
                    if (showPlaybackOverlay) {
                        playbackController?.let { controller ->
                            PlaybackOverlay(
                                controller = controller,
                                onDismiss = { showPlaybackOverlay = false },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingUpload?.let { upload ->
            outState.putString(STATE_UPLOAD_DIRECTORY, upload.directory)
            outState.putString(STATE_UPLOAD_SERVER_BASE_URL, upload.serverBaseUrl)
            outState.putString(STATE_UPLOAD_SERVER_USERNAME, upload.serverUsername)
            outState.putBoolean(
                STATE_UPLOAD_SERVER_ALLOW_INSECURE_HTTP,
                upload.serverAllowInsecureHttp,
            )
            outState.putString(STATE_UPLOAD_SESSION_BINDING, upload.sessionBinding)
        }
        pendingDownload?.let { download ->
            outState.putString(STATE_DOWNLOAD_PATH, download.remotePath)
            outState.putString(STATE_DOWNLOAD_NAME, download.fileName)
            outState.putLong(STATE_DOWNLOAD_BYTES, download.expectedBytes ?: -1L)
            outState.putString(STATE_DOWNLOAD_MIME, download.mimeType)
            outState.putString(STATE_DOWNLOAD_SESSION_BINDING, download.sessionBinding)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // The window is already leaving; posting to its decor view could strand the controller.
        releasePlaybackController(stopPlayback = false, deferUntilUiDetached = false)
        super.onDestroy()
    }

    override fun hasLocalNetworkPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED

    override fun requestLocalNetworkPermission(onResult: (Boolean) -> Unit) {
        if (hasLocalNetworkPermission()) {
            onResult(true)
            return
        }
        localNetworkPermissionResult = onResult
        localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun selectFileForUpload(directory: String) {
        if (pendingUpload != null) {
            Toast.makeText(this, "请先完成当前文件选择", Toast.LENGTH_SHORT).show()
            return
        }
        val settings = (application as OpenListApplication).container.sessionStore.snapshot()
        if (settings.token.isBlank()) {
            Toast.makeText(this, "登录凭据已失效，请重新登录", Toast.LENGTH_LONG).show()
            return
        }
        pendingUpload = PendingUpload(
            directory = directory,
            serverBaseUrl = settings.server.baseUrl,
            serverUsername = settings.server.username,
            serverAllowInsecureHttp = settings.server.allowInsecureHttp,
            sessionBinding = UploadSessionBinding.create(
                settings.server,
                settings.sessionBindingKey.ifBlank { settings.token },
            ).value,
        )
        requestNotificationPermissionIfNeeded()
        uploadDocumentLauncher.launch(arrayOf("*/*"))
    }

    private fun selectDownloadDestination(path: String, item: OpenListObject) {
        if (item.isDirectory) return
        if (pendingDownload != null) {
            Toast.makeText(this, "请先完成当前保存位置选择", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = item.name
            .replace('/', '_')
            .replace('\\', '_')
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "download-${System.currentTimeMillis()}"
        val settings = (application as OpenListApplication).container.sessionStore.snapshot()
        val mimeType = MediaTypeDetector.mimeType(item.name, MediaTypeDetector.kind(item))
            ?.takeUnless { it.endsWith("/*") }
            ?: "application/octet-stream"
        pendingDownload = PendingDownload(
            remotePath = path,
            fileName = fileName,
            expectedBytes = item.size.takeIf { it >= 0L },
            mimeType = mimeType,
            sessionBinding = DownloadSessionBinding.create(
                settings.server,
                settings.sessionBindingKey.ifBlank { settings.token },
            ).value,
        )
        requestNotificationPermissionIfNeeded()
        downloadDocumentLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, fileName),
        )
    }

    private fun openMedia(
        container: AppContainer,
        path: String,
        item: OpenListObject,
        siblings: List<OpenListObject>,
    ) {
        val sessionGeneration = container.playbackInvalidation.value
        val requestGeneration = ++mediaOpenGeneration
        container.appLogger.info("Media", "准备打开 ${item.name}")
        lifecycleScope.launch {
            val preparation = runCatching {
                try {
                    withTimeout(MEDIA_PREPARATION_TIMEOUT_MS) {
                        if (siblings.isEmpty()) {
                            container.mediaSequenceBuilder.build(path, item)
                        } else {
                            container.mediaSequenceBuilder.build(path, item, siblings)
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    throw IllegalStateException("读取同目录媒体超时，请重试")
                }
            }
            preparation.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            preparation
                .onSuccess { sequence ->
                    if (
                        requestGeneration != mediaOpenGeneration ||
                        sessionGeneration != container.playbackInvalidation.value
                    ) {
                        container.appLogger.info("Media", "已有更新的打开请求或账户变化")
                        return@onSuccess
                    }
                    requestNotificationPermissionIfNeeded()
                    pendingPlaybackSequence = sequence
                    val controller = playbackController
                    if (controller != null) {
                        runCatching { controller.setQueue(sequence) }
                            .onSuccess {
                                pendingPlaybackSequence = null
                                showPlaybackOverlay = true
                            }
                            .onFailure { error ->
                                container.appLogger.warn(
                                    "Media",
                                    "播放器连接已失效，正在重新连接：${error.message.orEmpty()}",
                                )
                                closePlaybackController(controller)
                                connectPlaybackController()
                            }
                    } else {
                        connectPlaybackController()
                    }
                }
                .onFailure { error ->
                    if (
                        requestGeneration != mediaOpenGeneration ||
                        sessionGeneration != container.playbackInvalidation.value
                    ) return@onFailure
                    container.appLogger.warn("Media", "无法准备 ${item.name}：${error.message.orEmpty()}")
                    showLongToast(error.message ?: "无法准备媒体播放")
                }
        }
    }

    private fun connectPlaybackController() {
        if (playbackController != null || playbackControllerFuture != null) return
        val future = OpenListPlaybackController.connect(this)
        playbackControllerFuture = future
        future.addListener(
            {
                // A cancelled connection may still finish after release/reconnect. It must not
                // clear or replace the newer attempt with a controller that is already stale.
                if (playbackControllerFuture !== future) {
                    runCatching { future.get() }.getOrNull()?.let { staleController ->
                        closePlaybackController(
                            controller = staleController,
                            detachFromUi = false,
                        )
                    }
                    return@addListener
                }
                playbackControllerFuture = null
                val connected = runCatching { future.get() }.getOrElse { error ->
                    pendingPlaybackSequence = null
                    showLongToast(error.message ?: "无法连接媒体服务")
                    return@addListener
                }
                if (isFinishing || isDestroyed) {
                    closePlaybackController(
                        controller = connected,
                        detachFromUi = false,
                        deferUntilUiDetached = false,
                    )
                    return@addListener
                }
                val sequence = pendingPlaybackSequence
                if (sequence != null) {
                    val startError = runCatching { connected.setQueue(sequence) }.exceptionOrNull()
                    if (startError != null) {
                        pendingPlaybackSequence = null
                        closePlaybackController(
                            controller = connected,
                            detachFromUi = false,
                        )
                        showLongToast(startError.message ?: "无法启动媒体播放")
                        return@addListener
                    }
                    pendingPlaybackSequence = null
                    showPlaybackOverlay = true
                }
                playbackController = connected
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun releasePlaybackController(
        stopPlayback: Boolean,
        deferUntilUiDetached: Boolean = true,
    ) {
        mediaOpenGeneration += 1L
        playbackControllerFuture?.cancel(false)
        playbackControllerFuture = null
        pendingPlaybackSequence = null
        playbackController?.let { controller ->
            playbackController = null
            showPlaybackOverlay = false
            closePlaybackController(
                controller = controller,
                stopPlayback = stopPlayback,
                detachFromUi = false,
                deferUntilUiDetached = deferUntilUiDetached,
            )
            return
        }
        showPlaybackOverlay = false
    }

    private fun closePlaybackController(
        controller: OpenListPlaybackController,
        stopPlayback: Boolean = false,
        detachFromUi: Boolean = playbackController === controller,
        deferUntilUiDetached: Boolean = detachFromUi,
    ) {
        if (detachFromUi) {
            if (playbackController === controller) {
                playbackController = null
            }
            showPlaybackOverlay = false
        }
        val closeAction: () -> Unit = {
            runCatching {
                if (stopPlayback) {
                    controller.mediaController.stop()
                    controller.clear()
                }
            }.onFailure { error ->
                (application as OpenListApplication).container.appLogger.warn(
                    "Media",
                    "释放失效播放器连接时忽略异常：${error.message.orEmpty()}",
                )
            }
            runCatching { controller.close() }
        }
        disposePlaybackControllerAfterUiDetach(
            deferDisposal = deferUntilUiDetached,
            scheduleDisposal = { action -> window.decorView.post(action) },
            dispose = closeAction,
        )
    }

    private fun showLongToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun enqueueUpload(uri: Uri, upload: PendingUpload) {
        val currentSettings = (application as OpenListApplication).container.sessionStore.snapshot()
        val originalBinding = runCatching {
            UploadSessionBinding.parse(upload.sessionBinding)
        }.getOrNull()
        val currentBinding = UploadSessionBinding.create(
            currentSettings.server,
            currentSettings.sessionBindingKey.ifBlank { currentSettings.token },
        )
        if (
            originalBinding == null ||
            !originalBinding.matches(currentBinding) ||
            upload.serverAllowInsecureHttp != currentSettings.server.allowInsecureHttp
        ) {
            Toast.makeText(
                this,
                "文件选择期间账户已变化，请重新发起上传",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        lifecycleScope.launch {
            val prepared = try {
                withContext(Dispatchers.IO) {
                    val metadata = queryUploadMetadata(uri)
                    val fileName = metadata.name
                        .replace('/', '_')
                        .replace('\\', '_')
                        .takeIf { it.isNotBlank() && it != "." && it != ".." }
                        ?: "upload-${System.currentTimeMillis()}"
                    val remotePath = joinRemotePath(upload.directory, fileName)
                    val request = UploadWorker.request(
                        sourceUri = uri,
                        remotePath = remotePath,
                        fileSize = metadata.size,
                        mimeType = contentResolver.getType(uri),
                        modifiedAtMillis = metadata.modifiedAtMillis,
                        serverBaseUrl = upload.serverBaseUrl,
                        serverUsername = upload.serverUsername,
                        serverAllowInsecureHttp = upload.serverAllowInsecureHttp,
                        sessionBinding = originalBinding,
                    )
                    Triple(fileName, remotePath, request)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "无法创建上传任务，请重新选择文件",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val (fileName, remotePath, request) = prepared
            val result = try {
                UploadWorker.enqueueUnique(
                    context = applicationContext,
                    sourceUri = uri,
                    remotePath = remotePath,
                    serverAllowInsecureHttp = upload.serverAllowInsecureHttp,
                    sessionBinding = originalBinding,
                    request = request,
                )
            } catch (_: SecurityException) {
                Toast.makeText(
                    this@MainActivity,
                    "无法保留文件读取权限，请从支持文档访问的来源重新选择",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            } catch (_: IllegalArgumentException) {
                Toast.makeText(
                    this@MainActivity,
                    "无法保留文件读取权限，请从支持文档访问的来源重新选择",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "无法加入上传队列，请重试",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            when (result) {
                UploadUniqueEnqueueResult.ENQUEUED -> {
                    (application as OpenListApplication).container.appLogger.info(
                        "Upload",
                        "已加入上传队列：$fileName",
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "已加入上传队列：$fileName",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                UploadUniqueEnqueueResult.KEPT_EXISTING -> {
                    Toast.makeText(
                        this@MainActivity,
                        "同一目标已有上传任务，未重复加入",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun enqueueDownload(uri: Uri, download: PendingDownload) {
        val container = (application as OpenListApplication).container
        lifecycleScope.launch {
            val settings = runCatching { container.sessionStore.awaitLoaded() }.getOrElse { error ->
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "无法读取当前账户，请重试",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val originalBinding = runCatching {
                DownloadSessionBinding.parse(download.sessionBinding)
            }.getOrNull()
            val currentBinding = DownloadSessionBinding.create(
                settings.server,
                settings.sessionBindingKey.ifBlank { settings.token },
            )
            if (originalBinding == null || !originalBinding.matches(currentBinding)) {
                Toast.makeText(
                    this@MainActivity,
                    "保存位置选择期间账户已变化，请重新发起下载",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val result = try {
                val request = DownloadWorker.request(
                    context = this@MainActivity,
                    remotePath = download.remotePath,
                    targetUri = uri,
                    sessionBinding = originalBinding,
                    expectedBytes = download.expectedBytes,
                )
                DownloadWorker.enqueueUnique(this@MainActivity, uri, request)
            } catch (_: SecurityException) {
                Toast.makeText(
                    this@MainActivity,
                    "无法保留下载位置，请重新选择",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            } catch (_: IllegalArgumentException) {
                Toast.makeText(
                    this@MainActivity,
                    "无法保留下载位置，请重新选择",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "无法加入下载队列，请重试",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            when (result) {
                DownloadUniqueEnqueueResult.ENQUEUED -> {
                    container.appLogger.info("Download", "已加入下载队列：${download.fileName}")
                    Toast.makeText(
                        this@MainActivity,
                        "已加入下载队列：${download.fileName}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                DownloadUniqueEnqueueResult.KEPT_EXISTING -> {
                    Toast.makeText(
                        this@MainActivity,
                        "同一保存位置已有下载任务，未重复加入",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun queryUploadMetadata(uri: Uri): UploadMetadata {
        var name: String? = null
        var size: Long? = null
        var modifiedAtMillis: Long? = null
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                    }
                    val modifiedIndex = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    )
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        modifiedAtMillis = cursor.getLong(modifiedIndex).takeIf { it >= 0 }
                    }
                }
            }
        }
        return UploadMetadata(
            name = name?.takeIf(String::isNotBlank)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                ?: "upload-${System.currentTimeMillis()}",
            size = size,
            modifiedAtMillis = modifiedAtMillis,
        )
    }

    private data class UploadMetadata(
        val name: String,
        val size: Long?,
        val modifiedAtMillis: Long?,
    )

    private data class PendingUpload(
        val directory: String,
        val serverBaseUrl: String,
        val serverUsername: String,
        val serverAllowInsecureHttp: Boolean,
        val sessionBinding: String,
    )

    private data class PendingDownload(
        val remotePath: String,
        val fileName: String,
        val expectedBytes: Long?,
        val mimeType: String,
        val sessionBinding: String,
    )

    private companion object {
        const val MEDIA_PREPARATION_TIMEOUT_MS = 30_000L
        const val STATE_UPLOAD_DIRECTORY = "pending_upload_directory"
        const val STATE_UPLOAD_SERVER_BASE_URL = "pending_upload_server_base_url"
        const val STATE_UPLOAD_SERVER_USERNAME = "pending_upload_server_username"
        const val STATE_UPLOAD_SERVER_ALLOW_INSECURE_HTTP =
            "pending_upload_server_allow_insecure_http"
        const val STATE_UPLOAD_SESSION_BINDING = "pending_upload_session_binding"
        const val STATE_DOWNLOAD_PATH = "pending_download_path"
        const val STATE_DOWNLOAD_NAME = "pending_download_name"
        const val STATE_DOWNLOAD_BYTES = "pending_download_bytes"
        const val STATE_DOWNLOAD_MIME = "pending_download_mime"
        const val STATE_DOWNLOAD_SESSION_BINDING = "pending_download_session_binding"
    }
}

internal fun disposePlaybackControllerAfterUiDetach(
    deferDisposal: Boolean,
    scheduleDisposal: (Runnable) -> Boolean,
    dispose: () -> Unit,
) {
    val action = Runnable(dispose)
    if (!deferDisposal || !scheduleDisposal(action)) {
        action.run()
    }
}

internal data class PlaybackSessionIdentity(
    val baseUrl: String,
    val username: String,
    val authenticated: Boolean,
)

internal data class PlaybackConnectionSnapshot(
    val identity: PlaybackSessionIdentity,
    val serviceRunning: Boolean,
)

internal enum class PlaybackConnectionAction {
    NONE,
    ATTACH,
    RELEASE,
}

/**
 * A recreated Activity attaches only when a playback service already exists. A cold app launch
 * must not create ExoPlayer just to inspect an empty queue. A real account transition clears the
 * previous session; the next media request will create a service for the new account on demand.
 */
internal fun playbackConnectionAction(
    previous: PlaybackConnectionSnapshot?,
    current: PlaybackConnectionSnapshot,
): PlaybackConnectionAction = when {
    previous == null && current.identity.authenticated && current.serviceRunning ->
        PlaybackConnectionAction.ATTACH
    previous == null -> PlaybackConnectionAction.NONE
    previous.identity != current.identity -> PlaybackConnectionAction.RELEASE
    !previous.serviceRunning && current.serviceRunning && current.identity.authenticated ->
        PlaybackConnectionAction.ATTACH
    else -> PlaybackConnectionAction.NONE
}
