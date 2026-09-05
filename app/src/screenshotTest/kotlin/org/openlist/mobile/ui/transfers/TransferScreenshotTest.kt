package org.openlist.mobile.ui.transfers

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import org.openlist.mobile.data.download.DownloadFailureCode
import org.openlist.mobile.ui.theme.OpenListTheme
import java.util.UUID

@PreviewTest
@Preview(name = "TransfersPhone", widthDp = 360, heightDp = 800)
@Composable
fun TransfersPhonePreview() {
    TransferPreview(darkTheme = false, entries = sampleTransfers)
}

@PreviewTest
@Preview(name = "TransfersFinishedDark", widthDp = 360, heightDp = 800)
@Composable
fun TransfersFinishedDarkPreview() {
    TransferPreview(darkTheme = true, entries = sampleTransfers.filterNot { it.status.isActive })
}

@PreviewTest
@Preview(name = "TransfersLargeFont", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
fun TransfersLargeFontPreview() {
    TransferPreview(darkTheme = false, entries = sampleTransfers)
}

@PreviewTest
@Preview(name = "TransfersRecoveryLargeFontDark", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
fun TransfersRecoveryLargeFontDarkPreview() {
    TransferPreview(darkTheme = true, entries = sampleTransfers.filterNot { it.status.isActive })
}

@PreviewTest
@Preview(name = "TransfersEmpty", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
fun TransfersEmptyPreview() {
    TransferPreview(darkTheme = false, entries = emptyList())
}

@PreviewTest
@Preview(name = "TransfersEmptyLargeFontDark", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
fun TransfersEmptyLargeFontDarkPreview() {
    TransferPreview(darkTheme = true, entries = emptyList())
}

@PreviewTest
@Preview(name = "TransfersWide", widthDp = 1000, heightDp = 800)
@Composable
fun TransfersWidePreview() {
    TransferPreview(darkTheme = false, entries = sampleTransfers)
}

@Composable
private fun TransferPreview(darkTheme: Boolean, entries: List<TransferEntry>) {
    OpenListTheme(darkTheme = darkTheme, dynamicColor = false) {
        TransfersScreen(
            state = TransferCenterState(entries = entries),
            onCancel = {},
            onBrowseFiles = {},
        )
    }
}

private val sampleTransfers = listOf(
    TransferEntry(
        id = UUID(0L, 1L),
        name = "山间徒步 · 第一天.mp4",
        direction = TransferDirection.DOWNLOAD,
        status = TransferStatus.RUNNING,
        transferredBytes = 64L * 1024 * 1024,
        totalBytes = 256L * 1024 * 1024,
        createdAtMillis = 1_788_588_000_000L,
    ),
    TransferEntry(
        id = UUID(0L, 2L),
        name = "旅行照片.zip",
        direction = TransferDirection.UPLOAD,
        status = TransferStatus.WAITING,
        isRetrying = true,
        createdAtMillis = 1_788_587_000_000L,
    ),
    TransferEntry(
        id = UUID(0L, 3L),
        name = "新家的设计方案.pdf",
        direction = TransferDirection.DOWNLOAD,
        status = TransferStatus.FAILED,
        failureCode = DownloadFailureCode.TARGET_UNAVAILABLE,
        createdAtMillis = 1_788_586_000_000L,
    ),
    TransferEntry(
        id = UUID(0L, 4L),
        name = "秋日笔记.md",
        direction = TransferDirection.UPLOAD,
        status = TransferStatus.SUCCEEDED,
        transferredBytes = 2048L,
        totalBytes = 2048L,
        createdAtMillis = 1_788_585_000_000L,
    ),
)
