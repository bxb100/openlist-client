package org.openlist.mobile.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.core.model.joinRemotePath
import org.openlist.mobile.core.model.parentRemotePath
import org.openlist.mobile.core.util.FileVisibilityMatcher
import org.openlist.mobile.data.repository.OpenListRepository

fun interface DirectoryMediaSource {
    suspend fun list(directory: String): DirectoryListing
}

/** Builds a queue/gallery from media of the same kind in the current item's directory. */
class MediaSequenceBuilder(
    private val directorySource: DirectoryMediaSource,
    private val serverIdentity: () -> String,
    private val accountIdentity: () -> String = { "" },
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val visibilityRules: () -> List<FileVisibilityRule> = { emptyList() },
) {
    constructor(repository: OpenListRepository) : this(
        directorySource = DirectoryMediaSource { directory -> repository.list(directory) },
        serverIdentity = { repository.settings.value.server.baseUrl },
        accountIdentity = { repository.settings.value.server.username },
        visibilityRules = { repository.settings.value.fileVisibilityRules },
    )

    suspend fun build(currentPath: String, current: FileDetails): MediaSequence =
        build(currentPath, current.asObject())

    suspend fun build(
        currentPath: String,
        current: FileDetails,
        siblings: List<OpenListObject>,
    ): MediaSequence = build(currentPath, current.asObject(), siblings)

    suspend fun build(currentPath: String, current: OpenListObject): MediaSequence {
        val request = SequenceBuildRequest(
            currentPath = currentPath,
            current = current,
            sourceIdentity = serverIdentity(),
            sourceAccountIdentity = accountIdentity(),
        )
        val listing = try {
            directorySource.list(request.parent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return request.fallback()
        }
        return withContext(computationDispatcher) {
            request.buildFromSiblings(listing.content)
        }
    }

    suspend fun build(
        currentPath: String,
        current: OpenListObject,
        siblings: List<OpenListObject>,
    ): MediaSequence {
        val request = SequenceBuildRequest(
            currentPath = currentPath,
            current = current,
            sourceIdentity = serverIdentity(),
            sourceAccountIdentity = accountIdentity(),
        )
        return withContext(computationDispatcher) {
            request.buildFromSiblings(siblings)
        }
    }

    private inner class SequenceBuildRequest(
        val currentPath: String,
        val current: OpenListObject,
        val sourceIdentity: String,
        val sourceAccountIdentity: String,
    ) {
        val normalizedCurrentPath: String = normalizeRemotePath(currentPath)
        val parent: String = parentRemotePath(normalizedCurrentPath)
        val currentName: String = current.name.ifBlank { normalizedCurrentPath.substringAfterLast('/') }
        val currentKind: MediaKind = MediaTypeDetector.kind(current, currentName)
        private val visibilityMatcher = FileVisibilityMatcher.compile(visibilityRules())

        init {
            require(currentKind in supportedMediaKinds) { "The selected object is not supported media" }
        }

        fun fallback(): MediaSequence {
            val currentEntry = current.toMediaEntry(
                remotePath = normalizedCurrentPath,
                sourceIdentity = sourceIdentity,
                sourceAccountIdentity = sourceAccountIdentity,
            )
            return MediaSequence(
                items = listOf(currentEntry),
                currentIndex = 0,
                kind = currentEntry.kind,
                isDirectoryFallback = true,
            )
        }

        fun buildFromSiblings(siblings: List<OpenListObject>): MediaSequence {
            val currentEntry = current.toMediaEntry(
                remotePath = normalizedCurrentPath,
                sourceIdentity = sourceIdentity,
                sourceAccountIdentity = sourceAccountIdentity,
                subtitles = subtitlesFor(currentName, siblings),
            )
            val byPath = LinkedHashMap<String, MediaEntry>()
            siblings.forEach { sibling ->
                if (sibling.isDirectory || sibling.name.isBlank()) return@forEach
                if (!isSafeChildName(sibling.name)) return@forEach
                val siblingKind = MediaTypeDetector.kind(sibling)
                if (siblingKind != currentEntry.kind) return@forEach
                val siblingPath = normalizeRemotePath(joinRemotePath(parent, sibling.name))
                if (siblingPath != normalizedCurrentPath &&
                    !visibilityMatcher.isPathVisible(siblingPath, isDirectory = false)
                ) return@forEach
                byPath.putIfAbsent(
                    siblingPath,
                    sibling.toMediaEntry(
                        remotePath = siblingPath,
                        sourceIdentity = sourceIdentity,
                        sourceAccountIdentity = sourceAccountIdentity,
                        subtitles = subtitlesFor(sibling.name, siblings),
                    ),
                )
            }

            // The snapshot may be stale, paginated or hide the selected item under new rules.
            // Explicit selection remains playable. Replacing an existing entry keeps the caller's
            // ordering while the tapped item's metadata stays authoritative.
            byPath[normalizedCurrentPath] = currentEntry

            val items = byPath.values.toList()
            val currentIndex = items.indexOfFirst { it.remotePath == normalizedCurrentPath }
            return MediaSequence(
                items = items,
                currentIndex = currentIndex,
                kind = currentEntry.kind,
            )
        }

        private fun subtitlesFor(
            name: String,
            siblings: List<OpenListObject>,
        ): List<SubtitleEntry> = if (currentKind == MediaKind.VIDEO) {
            DirectorySubtitleMatcher.match(name, parent, siblings)
        } else {
            emptyList()
        }
    }

}

private fun OpenListObject.toMediaEntry(
    remotePath: String,
    sourceIdentity: String,
    sourceAccountIdentity: String,
    subtitles: List<SubtitleEntry> = emptyList(),
): MediaEntry {
    val effectiveName = name.ifBlank { remotePath.substringAfterLast('/') }
    val kind = MediaTypeDetector.kind(this, effectiveName)
    return MediaEntry(
        remotePath = remotePath,
        name = effectiveName,
        kind = kind,
        size = size,
        modified = modified,
        contentKey = ContentKeyFactory.forObject(
            serverIdentity = sourceIdentity,
            accountIdentity = sourceAccountIdentity,
            remotePath = remotePath,
            item = this,
        ),
        mimeType = MediaTypeDetector.mimeType(effectiveName, kind),
        subtitles = subtitles,
    )
}
