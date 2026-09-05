package org.openlist.mobile.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resumeWithException

/**
 * Captures the current Media3 [SurfaceView] frame with [PixelCopy] and saves it as a PNG.
 * Copying the video surface itself excludes controls and preserves the frame's aspect ratio.
 *
 * Returns a human-readable destination description on success.
 */
suspend fun captureAndSavePlaybackFrame(activity: Activity): Result<String> {
    val window = activity.window ?: return Result.failure(IllegalStateException("无法获取窗口"))
    val surfaceView = window.decorView.findPlaybackSurfaceView()
        ?: return Result.failure(IllegalStateException("视频画面尚未就绪"))
    return try {
        val bitmap = copyPlaybackFrame(surfaceView)
        try {
            withContext(Dispatchers.IO) { saveBitmapToPictures(activity, bitmap) }
        } finally {
            bitmap.recycle()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

private fun View.findPlaybackSurfaceView(): SurfaceView? {
    if (!isShown) return null
    if (this is SurfaceView && width > 0 && height > 0 && holder.surface.isValid) return this
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).findPlaybackSurfaceView()?.let { return it }
        }
    }
    return null
}

/** The caller owns a successful bitmap; cancellation leaves cleanup with PixelCopy's callback. */
internal suspend fun copyPlaybackFrame(surfaceView: SurfaceView): Bitmap =
    suspendCancellableCoroutine { continuation ->
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        // This four-argument SurfaceView overload is available on API 24. The Window and cropped
        // SurfaceView overloads require API 26; copying a Window also targets a different surface.
        try {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap) { _, unclaimedBitmap, _ -> unclaimedBitmap.recycle() }
                    } else {
                        bitmap.recycle()
                        continuation.resumeWithException(IllegalStateException("截图失败（$result）"))
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (error: Exception) {
            bitmap.recycle()
            continuation.resumeWithException(error)
        }
    }

private fun saveBitmapToPictures(context: Context, bitmap: Bitmap): Result<String> = runCatching {
    val name = "OpenList_${System.currentTimeMillis()}.png"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/OpenList",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建图片条目")
        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } ?: error("无法写入图片")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "图库 Pictures/OpenList"
    } else {
        // Pre-Q: keep it permission-free by writing to the app's external Pictures directory.
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "OpenList",
        ).apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        file.absolutePath
    }
}

/** True when the device advertises picture-in-picture support and the OS version allows it. */
fun Context.supportsPictureInPicture(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** Enters picture-in-picture with a 16:9 window. No-op when unsupported. */
fun Activity.enterPlaybackPictureInPicture() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    runCatching { enterPictureInPictureMode(params) }
}

/** Observes whether [activity] is currently in picture-in-picture mode. */
@Composable
fun rememberIsInPictureInPictureMode(activity: Activity?): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity !is ComponentActivity) {
        return false
    }
    var inPictureInPicture by remember(activity) {
        mutableStateOf(activity.isInPictureInPictureMode)
    }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            inPictureInPicture = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPictureInPicture
}
