package eu.kanade.domain.manga.model

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.ui.reader.setting.OrientationType
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import eu.kanade.tachiyomi.widget.ExtendedNavigationView
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.Serializable

data class Manga(
    val id: Long,
    val source: Long,
    val favorite: Boolean,
    val lastUpdate: Long,
    val dateAdded: Long,
    val viewerFlags: Long,
    val chapterFlags: Long,
    val coverLastModified: Long,
    val url: String,
    // SY -->
    val ogTitle: String,
    val ogArtist: String?,
    val ogAuthor: String?,
    val ogDescription: String?,
    val ogGenre: List<String>?,
    val ogStatus: Long,
    // SY <--
    val thumbnailUrl: String?,
    val updateStrategy: UpdateStrategy,
    val initialized: Boolean,
    // SY -->
    val filteredScanlators: List<String>?,
    // SY <--
) : Serializable {

    // SY -->
    private val customMangaInfo = if (favorite) {
        customMangaManager.getManga(this)
    } else {
        null
    }

    val title: String
        get() = customMangaInfo?.title ?: ogTitle

    val author: String?
        get() = customMangaInfo?.author ?: ogAuthor

    val artist: String?
        get() = customMangaInfo?.artist ?: ogArtist

    val description: String?
        get() = customMangaInfo?.description ?: ogDescription

    val genre: List<String>?
        get() = customMangaInfo?.genre ?: ogGenre

    val status: Long
        get() = customMangaInfo?.status ?: ogStatus
    // SY <--

    val sorting: Long
        get() = chapterFlags and CHAPTER_SORTING_MASK

    val displayMode: Long
        get() = chapterFlags and CHAPTER_DISPLAY_MASK

    val unreadFilterRaw: Long
        get() = chapterFlags and CHAPTER_UNREAD_MASK

    val downloadedFilterRaw: Long
        get() = chapterFlags and CHAPTER_DOWNLOADED_MASK

    val bookmarkedFilterRaw: Long
        get() = chapterFlags and CHAPTER_BOOKMARKED_MASK

    val readingModeType: Long
        get() = viewerFlags and ReadingModeType.MASK.toLong()

    val orientationType: Long
        get() = viewerFlags and OrientationType.MASK.toLong()

    val unreadFilter: TriStateFilter
        get() = when (unreadFilterRaw) {
            CHAPTER_SHOW_UNREAD -> TriStateFilter.ENABLED_IS
            CHAPTER_SHOW_READ -> TriStateFilter.ENABLED_NOT
            else -> TriStateFilter.DISABLED
        }

    val downloadedFilter: TriStateFilter
        get() {
            if (forceDownloaded()) return TriStateFilter.ENABLED_IS
            return when (downloadedFilterRaw) {
                CHAPTER_SHOW_DOWNLOADED -> TriStateFilter.ENABLED_IS
                CHAPTER_SHOW_NOT_DOWNLOADED -> TriStateFilter.ENABLED_NOT
                else -> TriStateFilter.DISABLED
            }
        }

    val bookmarkedFilter: TriStateFilter
        get() = when (bookmarkedFilterRaw) {
            CHAPTER_SHOW_BOOKMARKED -> TriStateFilter.ENABLED_IS
            CHAPTER_SHOW_NOT_BOOKMARKED -> TriStateFilter.ENABLED_NOT
            else -> TriStateFilter.DISABLED
        }

    fun chaptersFiltered(): Boolean {
        return unreadFilter != TriStateFilter.DISABLED ||
            downloadedFilter != TriStateFilter.DISABLED ||
            bookmarkedFilter != TriStateFilter.DISABLED
    }

    fun forceDownloaded(): Boolean {
        return favorite && Injekt.get<BasePreferences>().downloadedOnly().get()
    }

    fun sortDescending(): Boolean {
        return chapterFlags and CHAPTER_SORT_DIR_MASK == CHAPTER_SORT_DESC
    }

    fun toSManga(): SManga = SManga.create().also {
        it.url = url
        // SY -->
        it.title = ogTitle
        it.artist = ogArtist
        it.author = ogAuthor
        it.description = ogDescription
        it.genre = ogGenre.orEmpty().joinToString()
        it.status = ogStatus.toInt()
        // SY <--
        it.thumbnail_url = thumbnailUrl
        it.initialized = initialized
    }

    fun copyFrom(other: SManga): Manga {
        // SY -->
        val author = other.author ?: ogAuthor
        val artist = other.artist ?: ogArtist
        val description = other.description ?: ogDescription
        val genres = if (other.genre != null) {
            other.getGenres()
        } else {
            ogGenre
        }
        // SY <--
        val thumbnailUrl = other.thumbnail_url ?: thumbnailUrl
        return this.copy(
            // SY -->
            ogAuthor = author,
            ogArtist = artist,
            ogDescription = description,
            ogGenre = genres,
            // SY <--
            thumbnailUrl = thumbnailUrl,
            // SY -->
            ogStatus = other.status.toLong(),
            // SY <--
            updateStrategy = other.update_strategy,
            initialized = other.initialized && initialized,
        )
    }

    companion object {
        // Generic filter that does not filter anything
        const val SHOW_ALL = 0x00000000L

        const val CHAPTER_SORT_DESC = 0x00000000L
        const val CHAPTER_SORT_ASC = 0x00000001L
        const val CHAPTER_SORT_DIR_MASK = 0x00000001L

        const val CHAPTER_SHOW_UNREAD = 0x00000002L
        const val CHAPTER_SHOW_READ = 0x00000004L
        const val CHAPTER_UNREAD_MASK = 0x00000006L

        const val CHAPTER_SHOW_DOWNLOADED = 0x00000008L
        const val CHAPTER_SHOW_NOT_DOWNLOADED = 0x00000010L
        const val CHAPTER_DOWNLOADED_MASK = 0x00000018L

        const val CHAPTER_SHOW_BOOKMARKED = 0x00000020L
        const val CHAPTER_SHOW_NOT_BOOKMARKED = 0x00000040L
        const val CHAPTER_BOOKMARKED_MASK = 0x00000060L

        const val CHAPTER_SORTING_SOURCE = 0x00000000L
        const val CHAPTER_SORTING_NUMBER = 0x00000100L
        const val CHAPTER_SORTING_UPLOAD_DATE = 0x00000200L
        const val CHAPTER_SORTING_MASK = 0x00000300L

        const val CHAPTER_DISPLAY_NAME = 0x00000000L
        const val CHAPTER_DISPLAY_NUMBER = 0x00100000L
        const val CHAPTER_DISPLAY_MASK = 0x00100000L

        fun create() = Manga(
            id = -1L,
            url = "",
            // Sy -->
            ogTitle = "",
            // SY <--
            source = -1L,
            favorite = false,
            lastUpdate = 0L,
            dateAdded = 0L,
            viewerFlags = 0L,
            chapterFlags = 0L,
            coverLastModified = 0L,
            // SY -->
            ogArtist = null,
            ogAuthor = null,
            ogDescription = null,
            ogGenre = null,
            ogStatus = 0L,
            // SY <--
            thumbnailUrl = null,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            initialized = false,
            // SY -->
            filteredScanlators = null,
            // SY <--
        )

        // SY -->
        private val customMangaManager: CustomMangaManager by injectLazy()
        // SY <--
    }
}

enum class TriStateFilter {
    DISABLED, // Disable filter
    ENABLED_IS, // Enabled with "is" filter
    ENABLED_NOT, // Enabled with "not" filter
}

fun TriStateFilter.toTriStateGroupState(): ExtendedNavigationView.Item.TriStateGroup.State {
    return when (this) {
        TriStateFilter.DISABLED -> ExtendedNavigationView.Item.TriStateGroup.State.IGNORE
        TriStateFilter.ENABLED_IS -> ExtendedNavigationView.Item.TriStateGroup.State.INCLUDE
        TriStateFilter.ENABLED_NOT -> ExtendedNavigationView.Item.TriStateGroup.State.EXCLUDE
    }
}

fun Manga.toMangaUpdate(): MangaUpdate {
    return MangaUpdate(
        id = id,
        source = source,
        favorite = favorite,
        lastUpdate = lastUpdate,
        dateAdded = dateAdded,
        viewerFlags = viewerFlags,
        chapterFlags = chapterFlags,
        coverLastModified = coverLastModified,
        url = url,
        // SY -->
        title = ogTitle,
        artist = ogArtist,
        author = ogAuthor,
        description = ogDescription,
        genre = ogGenre,
        status = ogStatus,
        // SY <--
        thumbnailUrl = thumbnailUrl,
        updateStrategy = updateStrategy,
        initialized = initialized,
        // SY -->
        filteredScanlators = filteredScanlators,
        // SY <--
    )
}

fun SManga.toDomainManga(sourceId: Long): Manga {
    return Manga.create().copy(
        url = url,
        // SY -->
        ogTitle = title,
        ogArtist = artist,
        ogAuthor = author,
        ogDescription = description,
        ogGenre = getGenres(),
        ogStatus = status.toLong(),
        // SY <--
        thumbnailUrl = thumbnail_url,
        updateStrategy = update_strategy,
        initialized = initialized,
        source = sourceId,
    )
}

fun Manga.isLocal(): Boolean = source == LocalSource.ID

fun Manga.hasCustomCover(coverCache: CoverCache = Injekt.get()): Boolean {
    return coverCache.getCustomCoverFile(id).exists()
}
