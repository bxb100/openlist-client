package org.openlist.mobile.media

import android.graphics.Color
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackFrameCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun captureReadsActualSurfacePixelsOnEverySupportedApi() {
        val surfaceReady = CompletableDeferred<SurfaceView>()
        compose.setContent {
            AndroidView(
                modifier = Modifier.size(width = 160.dp, height = 90.dp),
                factory = { context ->
                    SurfaceView(context).apply {
                        val view = this
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                val canvas = holder.lockCanvas()
                                try {
                                    canvas.drawColor(Color.GREEN)
                                } finally {
                                    holder.unlockCanvasAndPost(canvas)
                                }
                                surfaceReady.complete(view)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                        })
                    }
                },
            )
        }

        runBlocking {
            withTimeout(5_000L) {
                val surfaceView = surfaceReady.await()
                val frame = withContext(Dispatchers.Main) { copyPlaybackFrame(surfaceView) }
                try {
                    assertEquals(surfaceView.width, frame.width)
                    assertEquals(surfaceView.height, frame.height)
                    assertEquals(Color.GREEN, frame.getPixel(frame.width / 2, frame.height / 2))
                } finally {
                    frame.recycle()
                }
            }
        }
    }
}
