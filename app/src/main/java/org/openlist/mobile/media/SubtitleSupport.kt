package org.openlist.mobile.media

import androidx.media3.common.MimeTypes
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.core.model.joinRemotePath
import java.util.Locale

/** A subtitle before its expiring raw URL is resolved. The path never enters MediaItem metadata. */
data class SubtitleEntry(
    val remotePath: String,
    val name: String,
    val mimeType: String,
) {
    init {
        require(remotePath.startsWith('/')) { "remotePath must be absolute" }
        require(name.isNotBlank() && '/' !in name && '\\' !in name) { "name must be a safe child name" }
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
    }
}

object SubtitleTypeDetector {
    fun mimeType(name: String): String? = when (extension(name)) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    private fun extension(name: String): String = name
        .substringAfterLast('/', name)
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
}

/** Matches exact basenames plus the common `video.language.ext` subtitle convention. */
internal object DirectorySubtitleMatcher {
    fun match(
        videoName: String,
        directory: String,
        candidates: List<OpenListObject>,
    ): List<SubtitleEntry> {
        val videoBase = basenameWithoutExtension(videoName)
        if (videoBase.isBlank()) return emptyList()
        return candidates.mapNotNull { candidate ->
            val name = candidate.name
            if (candidate.isDirectory || candidate.type == 1 || !isSafeChildName(name)) return@mapNotNull null
            val mimeType = SubtitleTypeDetector.mimeType(name) ?: return@mapNotNull null
            val subtitleBase = basenameWithoutExtension(name)
            val isMatch = subtitleBase.equals(videoBase, ignoreCase = true) ||
                subtitleBase.startsWith("$videoBase.", ignoreCase = true)
            if (!isMatch) return@mapNotNull null
            SubtitleEntry(
                remotePath = normalizeRemotePath(joinRemotePath(directory, name)),
                name = name,
                mimeType = mimeType,
            )
        }
    }
}

/**
 * Selects an unambiguous sidecar to enable automatically.
 *
 * A plain `video.srt` wins over language-qualified alternatives. If there is no plain sidecar,
 * one sole match is safe to enable; multiple language variants stay available without guessing
 * the user's language from directory ordering.
 */
internal fun defaultSubtitleIndex(videoName: String, subtitles: List<SubtitleEntry>): Int? {
    val videoBase = basenameWithoutExtension(videoName)
    val exactMatches = subtitles.indices.filter { index ->
        basenameWithoutExtension(subtitles[index].name).equals(videoBase, ignoreCase = true)
    }
    return exactMatches.singleOrNull() ?: subtitles.indices.singleOrNull()
}

private fun basenameWithoutExtension(name: String): String {
    val safeName = name.substringAfterLast('/').substringAfterLast('\\')
    return safeName.substringBeforeLast('.', missingDelimiterValue = safeName)
}

internal fun isSafeChildName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name
