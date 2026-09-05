package org.openlist.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import org.openlist.mobile.core.model.FileVisibilityAction
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.FileVisibilityTarget
import org.openlist.mobile.ui.filter.FileVisibilityRulesEditor
import org.openlist.mobile.ui.filter.rememberFileVisibilityEditorState
import org.openlist.mobile.ui.theme.OpenListTheme

@PreviewTest
@Preview(name = "File visibility · Light", widthDp = 390, heightDp = 860)
@Composable
fun FileVisibilityScreenshot() = FileVisibilitySample(false)

@PreviewTest
@Preview(name = "File visibility · Dark", widthDp = 390, heightDp = 860)
@Composable
fun FileVisibilityDarkScreenshot() = FileVisibilitySample(true)

@PreviewTest
@Preview(name = "File visibility · Large text", widthDp = 320, heightDp = 960, fontScale = 2f)
@Composable
fun FileVisibilityLargeTextScreenshot() = FileVisibilitySample(false)

@PreviewTest
@Preview(name = "File visibility · Expanded", widthDp = 1000, heightDp = 900)
@Composable
fun FileVisibilityExpandedScreenshot() = FileVisibilitySample(false)

@Composable
private fun FileVisibilitySample(dark: Boolean) {
    OpenListTheme(darkTheme = dark) {
        FileVisibilityRulesEditor(
            state = rememberFileVisibilityEditorState(listOf(
                FileVisibilityRule("temporary", "*.tmp", FileVisibilityAction.Hide, FileVisibilityTarget.Files),
                FileVisibilityRule("exception", "important.tmp", FileVisibilityAction.Show, FileVisibilityTarget.Files),
            )),
            onSave = {},
            onDismiss = {},
        )
    }
}
