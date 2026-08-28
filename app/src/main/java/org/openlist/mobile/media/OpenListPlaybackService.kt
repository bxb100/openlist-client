@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import org.openlist.mobile.OpenListApplication

fun interface MediaDataSourceDecorator {
    /** For example, wrap the secure upstream in a CacheDataSource.Factory. */
    fun decorate(upstream: DataSource.Factory): DataSource.Factory
}

data class PlaybackServiceDependencies(
    val urlResolver: MediaUrlResolver,
    val downloadClient: OkHttpClient = OkHttpClient(),
    val dataSourceDecorator: MediaDataSourceDecorator = MediaDataSourceDecorator { it },
    val customizePlayer: (ExoPlayer.Builder) -> Unit = {},
)

/**
 * Injection point for the framework-created service. Install during Application.onCreate when a
 * custom cache/client is needed. The default uses the app's existing OpenList API client.
 */
object OpenListPlaybackRuntime {
    @Volatile
    private var dependencyProvider: ((Context) -> PlaybackServiceDependencies)? = null

    fun install(provider: (Context) -> PlaybackServiceDependencies) {
        dependencyProvider = provider
    }

    internal fun dependencies(context: Context): PlaybackServiceDependencies {
        dependencyProvider?.let { return it(context.applicationContext) }
        val application = context.applicationContext as? OpenListApplication
            ?: error("OpenListPlaybackRuntime.install must be called before playback starts")
        return PlaybackServiceDependencies(
            urlResolver = OpenListMediaUrlResolver(application.container.api),
            downloadClient = application.container.httpClient.okHttpClient,
        )
    }
}

/** Restricts the exported MediaSession to this app and controllers trusted by Android/Media3. */
@Suppress("DEPRECATION")
internal object PlaybackControllerTrust {
    private val queueMutationCommands = setOf(
        Player.COMMAND_SET_MEDIA_ITEM,
        Player.COMMAND_CHANGE_MEDIA_ITEMS,
        Player.COMMAND_SET_MEDIA_ITEMS_METADATA,
        Player.COMMAND_SET_PLAYLIST_METADATA,
    )

    fun isAllowed(appUid: Int, controllerUid: Int, isTrusted: Boolean): Boolean =
        controllerUid == appUid || isTrusted

    fun isAllowed(appUid: Int, controllerInfo: MediaSession.ControllerInfo): Boolean =
        isAllowed(appUid, controllerInfo.uid, controllerInfo.isTrusted)

    fun transportCommands(commands: Player.Commands): Player.Commands = commands.buildUpon().apply {
        queueMutationCommands.forEach(::remove)
    }.build()

    fun mayTrustedExternalControllerIssue(command: Int): Boolean = command !in queueMutationCommands
}

/** Background audio/video playback backed by Media3 ExoPlayer and MediaSession. */
open class OpenListPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var decodePreferenceController: MediaItemDecodePreferenceController? = null

    protected open fun createDependencies(): PlaybackServiceDependencies =
        OpenListPlaybackRuntime.dependencies(this)

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (!PlaybackControllerTrust.isAllowed(Process.myUid(), controller)) {
                return MediaSession.ConnectionResult.reject()
            }
            val defaultResult = super.onConnect(session, controller)
            if (controller.uid == Process.myUid()) return defaultResult

            // Trusted cross-process controllers (System UI, Bluetooth, Assistant) only need to
            // inspect and control the existing queue. Prevent them from injecting URLs or replacing
            // the app-owned opaque MediaItems while retaining transport and notification controls.
            val transportOnlyCommands = PlaybackControllerTrust.transportCommands(
                defaultResult.availablePlayerCommands,
            )
            return MediaSession.ConnectionResult.accept(
                defaultResult.availableSessionCommands,
                transportOnlyCommands,
            )
        }
    }
    private val playbackListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            val activePlayer = player ?: return
            decodePreferenceController?.update(activePlayer.currentMediaItem?.mediaId)
            updatePlaybackDiagnostics(activePlayer.currentMediaItem, forceReset = false)
            OpenListMediaRequestRegistry.retainOnly(
                buildSet {
                    repeat(activePlayer.mediaItemCount) { index ->
                        val mediaItem = activePlayer.getMediaItemAt(index)
                        add(mediaItem.mediaId)
                        mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
                            .mapNotNullTo(this) { subtitle ->
                                OpenListMediaRequestUri.mediaIdOrNull(subtitle.uri)
                            }
                    }
                },
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            decodePreferenceController?.update(mediaItem?.mediaId)
            updatePlaybackDiagnostics(mediaItem, forceReset = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            val activePlayer = player ?: return
            val mediaItem = activePlayer.currentMediaItem ?: return
            val preferenceController = decodePreferenceController ?: return
            preferenceController.update(mediaItem.mediaId)
            if (!shouldRetryWithSoftwareAudio(error)) return

            // Mark the item before prepare() so a subsequent FFmpeg failure cannot recurse.
            if (!preferenceController.enableSoftwareAudioFallback()) return
            Log.w(
                DECODE_FALLBACK_LOG_TAG,
                "Platform audio decoder failed; retrying current item with FFmpeg " +
                    "(errorCode=${error.errorCode})",
            )
            // A fatal renderer error keeps the playlist, position and playWhenReady. prepare()
            // clears the error and remaps tracks using the newly selected FFmpeg audio renderer.
            activePlayer.prepare()
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlaybackTransferDiagnostics.reset()
        val dependencies = createDependencies()
        val resolvingFactory = OpenListMediaDataSourceFactory(
            resolver = dependencies.urlResolver,
            downloadClient = dependencies.downloadClient,
        )
        val decoratedFactory = dependencies.dataSourceDecorator.decorate(resolvingFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            PlaybackTransferDiagnostics.decorate(decoratedFactory),
            OpenListExtractorsFactory(),
        )
        val trackSelector = OpenListMedia3RuntimeFactory.createTrackSelector(this)
        val playerBuilder = ExoPlayer.Builder(
            this,
            OpenListMedia3RuntimeFactory.createRenderersFactory(this),
            mediaSourceFactory,
        )
            .setTrackSelector(trackSelector)
            .setLoadControl(OpenListMedia3RuntimeFactory.createLoadControl())
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
        dependencies.customizePlayer(playerBuilder)
        val newPlayer = playerBuilder.build()
        decodePreferenceController = MediaItemDecodePreferenceController(
            trackSelector::setRendererDecodePreferences,
        ).also {
            it.update(newPlayer.currentMediaItem?.mediaId)
        }
        newPlayer.addListener(playbackListener)
        player = newPlayer
        session = MediaSession.Builder(this, newPlayer)
            .setId(SESSION_ID)
            .setCallback(sessionCallback)
            .build()
        mutableRunningInProcess.value = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val activePlayer = player
        if (activePlayer == null ||
            !activePlayer.playWhenReady ||
            activePlayer.mediaItemCount == 0 ||
            activePlayer.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mutableRunningInProcess.value = false
        session?.release()
        session = null
        player?.removeListener(playbackListener)
        player?.release()
        player = null
        decodePreferenceController = null
        OpenListMediaRequestRegistry.clear()
        PlaybackTransferDiagnostics.reset()
        super.onDestroy()
    }

    private fun updatePlaybackDiagnostics(mediaItem: MediaItem?, forceReset: Boolean) {
        val mediaId = mediaItem?.mediaId?.takeIf(String::isNotBlank)
        val knownSizeBytes = mediaId
            ?.let(OpenListMediaRequestRegistry::detailsOrNull)
            ?.knownSize
        val customCacheKey = mediaItem?.localConfiguration?.customCacheKey
        if (forceReset) {
            PlaybackTransferDiagnostics.activate(mediaId, customCacheKey, knownSizeBytes)
        } else {
            PlaybackTransferDiagnostics.activateIfChanged(mediaId, customCacheKey, knownSizeBytes)
        }
    }

    companion object {
        const val SESSION_ID = "openlist-playback"
        internal const val SEEK_INCREMENT_MS = 10_000L
        private val mutableRunningInProcess = MutableStateFlow(false)
        internal val runningInProcess = mutableRunningInProcess.asStateFlow()
        private const val DECODE_FALLBACK_LOG_TAG = "OpenListDecodeFallback"
    }
}
