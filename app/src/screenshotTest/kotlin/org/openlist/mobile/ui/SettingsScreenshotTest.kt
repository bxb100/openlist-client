package org.openlist.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import org.openlist.mobile.core.model.CachePolicy
import org.openlist.mobile.data.cache.CacheStats
import org.openlist.mobile.ui.theme.OpenListTheme

@PreviewTest
@Preview(name = "Settings", widthDp = 390, heightDp = 980)
@Composable
fun SettingsScreenshot() = SettingsSample(false)

@PreviewTest
@Preview(name = "Settings dark", widthDp = 390, heightDp = 980)
@Composable
fun SettingsDarkScreenshot() = SettingsSample(true)

@PreviewTest
@Preview(name = "Settings large text", widthDp = 360, heightDp = 1000, fontScale = 2f)
@Composable
fun SettingsLargeTextScreenshot() = SettingsSample(false)

@PreviewTest
@Preview(name = "Settings expanded", widthDp = 1000, heightDp = 1000)
@Composable
fun SettingsExpandedScreenshot() = SettingsSample(false)

@Composable
private fun SettingsSample(dark: Boolean) {
    OpenListTheme(darkTheme = dark) {
        SettingsPageContent(
            serverBaseUrl = "https://files.example.com/openlist",
            username = "我的家庭文件库",
            darkTheme = null,
            dynamicColor = false,
            dynamicColorAvailable = true,
            cachePolicy = CachePolicy(),
            operationBusy = false,
            onAccountsRequested = {},
            onAppearanceChanged = { _, _ -> },
            onCachePolicySave = {},
            onClearCacheRequested = {},
            onLogoutRequested = {},
            cacheStats = CacheStats(
                totalBytes = 458_752_000L,
                entryCount = 186,
                activeLeaseCount = 1,
                inProgressWriteCount = 0,
                expiredEntryCount = 0,
            ),
        )
    }
}
