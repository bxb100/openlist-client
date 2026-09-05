package org.openlist.mobile.ui

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import org.openlist.mobile.ui.designsystem.OpenListComponentCatalog
import org.openlist.mobile.ui.designsystem.OpenListErrorState
import org.openlist.mobile.ui.theme.OpenListTheme

@PreviewTest
@Preview(name = "Design system · Compact", widthDp = 360, heightDp = 1000)
@Preview(
    name = "Design system · Dark",
    widthDp = 360,
    heightDp = 1000,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Design system · Large text", widthDp = 360, heightDp = 1700, fontScale = 2f)
@Preview(name = "Design system · Expanded", widthDp = 840, heightDp = 1000)
@Composable
fun DesignSystemCatalogScreenshot() {
    OpenListTheme { OpenListComponentCatalog() }
}

@PreviewTest
@Preview(name = "Connection error · Large text", widthDp = 320, heightDp = 700, fontScale = 2f)
@Composable
fun ConnectionErrorScreenshot() {
    OpenListTheme {
        Surface {
            OpenListErrorState(
                title = "暂时无法连接到服务器",
                description = "检查网络连接后重试。你仍然可以切换到其他账户。",
                onRetry = {},
            )
        }
    }
}
