package eu.kanade.tachiyomi.ui.library

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.coroutineScope
import eu.kanade.core.prefs.CheckboxState
import eu.kanade.core.prefs.PreferenceMutableState
import eu.kanade.core.prefs.asState
import eu.kanade.core.util.fastDistinctBy
import eu.kanade.core.util.fastFilter
import eu.kanade.core.util.fastFilterNot
import eu.kanade.core.util.fastMapNotNull
import eu.kanade.core.util.fastPartition
import eu.kanade.domain.UnsortedPreferences
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.category.interactor.GetCategories
import eu.kanade.domain.category.interactor.SetMangaCategories
import eu.kanade.domain.category.model.Category
import eu.kanade.domain.chapter.interactor.GetChapterByMangaId
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.model.Chapter
import eu.kanade.domain.history.interactor.GetNextChapters
import eu.kanade.domain.library.model.LibraryDisplayMode
import eu.kanade.domain.library.model.LibraryGroup
import eu.kanade.domain.library.model.LibraryManga
import eu.kanade.domain.library.model.LibrarySort
import eu.kanade.domain.library.model.sort
import eu.kanade.domain.library.service.LibraryPreferences
import eu.kanade.domain.manga.interactor.GetIdsOfFavoriteMangaWithMetadata
import eu.kanade.domain.manga.interactor.GetLibraryManga
import eu.kanade.domain.manga.interactor.GetMergedMangaById
import eu.kanade.domain.manga.interactor.GetSearchTags
import eu.kanade.domain.manga.interactor.GetSearchTitles
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.Manga
import eu.kanade.domain.manga.model.MangaUpdate
import eu.kanade.domain.manga.model.isLocal
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.GetTracks
import eu.kanade.domain.track.interactor.GetTracksPerManga
import eu.kanade.domain.track.model.Track
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.launchNonCancellable
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.preference.asHotFlow
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup
import exh.favorites.FavoritesSyncHelper
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import exh.search.Namespace
import exh.search.QueryComponent
import exh.search.SearchEngine
import exh.search.Text
import exh.source.EH_SOURCE_ID
import exh.source.MERGED_SOURCE_ID
import exh.source.isEhBasedManga
import exh.source.isMetadataSource
import exh.source.mangaDexSourceIds
import exh.source.nHentaiSourceIds
import exh.util.cancellable
import exh.util.isLewd
import exh.util.nullIfBlank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.Collator
import java.util.Collections
import java.util.Locale

/**
 * Typealias for the library manga, using the category as keys, and list of manga as values.
 */
typealias LibraryMap = Map<Category, List<LibraryItem>>

class LibraryScreenModel(
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getTracksPerManga: GetTracksPerManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getChaptersByMangaId: GetChapterByMangaId = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val trackManager: TrackManager = Injekt.get(),
    // SY -->
    private val unsortedPreferences: UnsortedPreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val getMergedMangaById: GetMergedMangaById = Injekt.get(),
    private val customMangaManager: CustomMangaManager = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val getIdsOfFavoriteMangaWithMetadata: GetIdsOfFavoriteMangaWithMetadata = Injekt.get(),
    private val getSearchTags: GetSearchTags = Injekt.get(),
    private val getSearchTitles: GetSearchTitles = Injekt.get(),
    private val searchEngine: SearchEngine = Injekt.get(),
    // SY <--
) : StateScreenModel<LibraryScreenModel.State>(State()) {

    // This is active category INDEX NUMBER
    var activeCategory: Int by libraryPreferences.lastUsedCategory().asState(coroutineScope)

    val isDownloadOnly: Boolean by preferences.downloadedOnly().asState(coroutineScope)
    val isIncognitoMode: Boolean by preferences.incognitoMode().asState(coroutineScope)

    // SY -->
    val favoritesSync = FavoritesSyncHelper(preferences.context)

    private val services by lazy {
        trackManager.services.associate { service ->
            service.id to preferences.context.getString(service.nameRes())
        }
    }
    // SY <--

    init {
        coroutineScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged(),
                getLibraryFlow(),
                getTracksPerManga.subscribe(),
                getTrackingFilterFlow(),
                // SY -->
                combine(state.map { it.groupType }.distinctUntilChanged(), libraryPreferences.libraryDisplayMode().changes()) { a, b -> a to b },
                // SY <--
            ) { searchQuery, library, tracks, loggedInTrackServices, (groupType, displayMode) ->
                library
                    .applyFilters(tracks, loggedInTrackServices)
                    .applySort(/* SY --> */groupType/* SY <-- */)
                    // SY -->
                    .applyGrouping(groupType, displayMode)
                    // SY <--
                    .mapValues { (_, value) ->
                        if (searchQuery != null) {
                            // Filter query
                            // SY -->
                            filterLibrary(value, searchQuery, loggedInTrackServices)
                            // SY <--
                        } else {
                            // Don't do anything
                            value
                        }
                    }
            }
                .collectLatest {
                    mutableState.update { state ->
                        state.copy(
                            isLoading = false,
                            library = it,
                        )
                    }
                }
        }

        combine(
            libraryPreferences.categoryTabs().changes(),
            libraryPreferences.categoryNumberOfItems().changes(),
            libraryPreferences.showContinueReadingButton().changes(),
        ) { a, b, c -> arrayOf(a, b, c) }
            .onEach { (showCategoryTabs, showMangaCount, showMangaContinueButton) ->
                mutableState.update { state ->
                    state.copy(
                        showCategoryTabs = showCategoryTabs,
                        showMangaCount = showMangaCount,
                        showMangaContinueButton = showMangaContinueButton,
                    )
                }
            }
            .launchIn(coroutineScope)

        combine(
            getLibraryItemPreferencesFlow(),
            getTrackingFilterFlow(),
        ) { prefs, trackFilter ->
            val a = (
                prefs.filterDownloaded or
                    prefs.filterUnread or
                    prefs.filterStarted or
                    prefs.filterBookmarked or
                    prefs.filterCompleted
                ) != TriStateGroup.State.IGNORE.value
            val b = trackFilter.values.any { it != TriStateGroup.State.IGNORE.value }
            a || b
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(coroutineScope)

        // SY -->
        combine(
            unsortedPreferences.isHentaiEnabled().changes(),
            sourcePreferences.disabledSources().changes(),
            unsortedPreferences.enableExhentai().changes(),
        ) { isHentaiEnabled, disabledSources, enableExhentai ->
            isHentaiEnabled && (EH_SOURCE_ID.toString() !in disabledSources || enableExhentai)
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(showSyncExh = it)
                }
            }
            .launchIn(coroutineScope)

        libraryPreferences.groupLibraryBy().asHotFlow {
            mutableState.update { state ->
                state.copy(groupType = it)
            }
        }.launchIn(coroutineScope)
        // SY <--
    }

    /**
     * Applies library filters to the given map of manga.
     */
    private suspend fun LibraryMap.applyFilters(
        trackMap: Map<Long, List<Long>>,
        loggedInTrackServices: Map<Long, Int>,
    ): LibraryMap {
        val prefs = getLibraryItemPreferencesFlow().first()
        val downloadedOnly = prefs.globalFilterDownloaded
        val filterDownloaded = prefs.filterDownloaded
        val filterUnread = prefs.filterUnread
        val filterStarted = prefs.filterStarted
        val filterBookmarked = prefs.filterBookmarked
        val filterCompleted = prefs.filterCompleted

        val isNotLoggedInAnyTrack = loggedInTrackServices.isEmpty()

        val excludedTracks = loggedInTrackServices.mapNotNull { if (it.value == TriStateGroup.State.EXCLUDE.value) it.key else null }
        val includedTracks = loggedInTrackServices.mapNotNull { if (it.value == TriStateGroup.State.INCLUDE.value) it.key else null }
        val trackFiltersIsIgnored = includedTracks.isEmpty() && excludedTracks.isEmpty()

        // SY -->
        val filterLewd = prefs.filterLewd
        // SY <--

        val filterFnDownloaded: (LibraryItem) -> Boolean = downloaded@{ item ->
            if (!downloadedOnly && filterDownloaded == TriStateGroup.State.IGNORE.value) return@downloaded true
            val isDownloaded = when {
                item.libraryManga.manga.isLocal() -> true
                item.downloadCount != -1L -> item.downloadCount > 0
                else -> downloadManager.getDownloadCount(item.libraryManga.manga) > 0
            }

            return@downloaded if (downloadedOnly || filterDownloaded == TriStateGroup.State.INCLUDE.value) {
                isDownloaded
            } else {
                !isDownloaded
            }
        }

        val filterFnUnread: (LibraryItem) -> Boolean = unread@{ item ->
            if (filterUnread == TriStateGroup.State.IGNORE.value) return@unread true
            val isUnread = item.libraryManga.unreadCount > 0

            return@unread if (filterUnread == TriStateGroup.State.INCLUDE.value) {
                isUnread
            } else {
                !isUnread
            }
        }

        val filterFnStarted: (LibraryItem) -> Boolean = started@{ item ->
            if (filterStarted == TriStateGroup.State.IGNORE.value) return@started true
            val hasStarted = item.libraryManga.hasStarted

            return@started if (filterStarted == TriStateGroup.State.INCLUDE.value) {
                hasStarted
            } else {
                !hasStarted
            }
        }

        val filterFnBookmarked: (LibraryItem) -> Boolean = bookmarked@{ item ->
            if (filterBookmarked == TriStateGroup.State.IGNORE.value) return@bookmarked true

            val hasBookmarks = item.libraryManga.hasBookmarks

            return@bookmarked if (filterBookmarked == TriStateGroup.State.INCLUDE.value) {
                hasBookmarks
            } else {
                !hasBookmarks
            }
        }

        val filterFnCompleted: (LibraryItem) -> Boolean = completed@{ item ->
            if (filterCompleted == TriStateGroup.State.IGNORE.value) return@completed true
            val isCompleted = item.libraryManga.manga.status.toInt() == SManga.COMPLETED

            return@completed if (filterCompleted == TriStateGroup.State.INCLUDE.value) {
                isCompleted
            } else {
                !isCompleted
            }
        }

        val filterFnTracking: (LibraryItem) -> Boolean = tracking@{ item ->
            if (isNotLoggedInAnyTrack || trackFiltersIsIgnored) return@tracking true

            val mangaTracks = trackMap[item.libraryManga.id].orEmpty()

            val exclude = mangaTracks.fastFilter { it in excludedTracks }
            val include = mangaTracks.fastFilter { it in includedTracks }

            // TODO: Simplify the filter logic
            if (includedTracks.isNotEmpty() && excludedTracks.isNotEmpty()) {
                return@tracking if (exclude.isNotEmpty()) false else include.isNotEmpty()
            }

            if (excludedTracks.isNotEmpty()) return@tracking exclude.isEmpty()

            if (includedTracks.isNotEmpty()) return@tracking include.isNotEmpty()

            return@tracking false
        }

        // SY -->
        val filterFnLewd: (LibraryItem) -> Boolean = lewd@{ item ->
            if (filterLewd == TriStateGroup.State.IGNORE.value) return@lewd true
            val isLewd = item.libraryManga.manga.isLewd()

            return@lewd if (filterLewd == TriStateGroup.State.INCLUDE.value) {
                isLewd
            } else {
                !isLewd
            }
        }
        // SY <--

        val filterFn: (LibraryItem) -> Boolean = filter@{ item ->
            return@filter !(
                !filterFnDownloaded(item) ||
                    !filterFnUnread(item) ||
                    !filterFnStarted(item) ||
                    !filterFnBookmarked(item) ||
                    !filterFnCompleted(item) ||
                    !filterFnTracking(item) ||
                    // SY -->
                    !filterFnLewd(item)
                // SY <--
                )
        }

        return this.mapValues { entry -> entry.value.fastFilter(filterFn) }
    }

    /**
     * Applies library sorting to the given map of manga.
     */
    private fun LibraryMap.applySort(/* SY --> */ groupType: Int /* SY <-- */): LibraryMap {
        // SY -->
        val listOfTags by lazy {
            libraryPreferences.sortTagsForLibrary().get()
                .asSequence()
                .mapNotNull {
                    val list = it.split("|")
                    (list.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to (list.getOrNull(1) ?: return@mapNotNull null)
                }
                .sortedBy { it.first }
                .map { it.second }
                .toList()
        }
        val groupSort = libraryPreferences.librarySortingMode().get()
        // SY <--

        val locale = Locale.getDefault()
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
        }
        val sortAlphabetically: (LibraryItem, LibraryItem) -> Int = { i1, i2 ->
            collator.compare(i1.libraryManga.manga.title.lowercase(locale), i2.libraryManga.manga.title.lowercase(locale))
        }

        val sortFn: (LibraryItem, LibraryItem) -> Int = { i1, i2 ->
            // SY -->
            val sort = when (groupType) {
                LibraryGroup.BY_DEFAULT -> keys.find { it.id == i1.libraryManga.category }!!.sort
                else -> groupSort
            }
            // SY <--
            when (sort.type) {
                LibrarySort.Type.Alphabetical -> {
                    sortAlphabetically(i1, i2)
                }
                LibrarySort.Type.LastRead -> {
                    i1.libraryManga.lastRead.compareTo(i2.libraryManga.lastRead)
                }
                LibrarySort.Type.LastUpdate -> {
                    i1.libraryManga.manga.lastUpdate.compareTo(i2.libraryManga.manga.lastUpdate)
                }
                LibrarySort.Type.UnreadCount -> when {
                    // Ensure unread content comes first
                    i1.libraryManga.unreadCount == i2.libraryManga.unreadCount -> 0
                    i1.libraryManga.unreadCount == 0L -> if (sort.isAscending) 1 else -1
                    i2.libraryManga.unreadCount == 0L -> if (sort.isAscending) -1 else 1
                    else -> i1.libraryManga.unreadCount.compareTo(i2.libraryManga.unreadCount)
                }
                LibrarySort.Type.TotalChapters -> {
                    i1.libraryManga.totalChapters.compareTo(i2.libraryManga.totalChapters)
                }
                LibrarySort.Type.LatestChapter -> {
                    i1.libraryManga.latestUpload.compareTo(i2.libraryManga.latestUpload)
                }
                LibrarySort.Type.ChapterFetchDate -> {
                    i1.libraryManga.chapterFetchedAt.compareTo(i2.libraryManga.chapterFetchedAt)
                }
                LibrarySort.Type.DateAdded -> {
                    i1.libraryManga.manga.dateAdded.compareTo(i2.libraryManga.manga.dateAdded)
                }
                // SY -->
                LibrarySort.Type.TagList -> {
                    val manga1IndexOfTag = listOfTags.indexOfFirst { i1.libraryManga.manga.genre?.contains(it) ?: false }
                    val manga2IndexOfTag = listOfTags.indexOfFirst { i2.libraryManga.manga.genre?.contains(it) ?: false }
                    manga1IndexOfTag.compareTo(manga2IndexOfTag)
                }
                // SY <--
            }
        }

        return this.mapValues { entry ->
            // SY -->
            val isAscending = if (groupType == LibraryGroup.BY_DEFAULT) {
                keys.find { it.id == entry.key.id }!!.sort.isAscending
            } else {
                groupSort.isAscending
            }
            // SY <--
            val comparator = if ( /* SY --> */ isAscending /* SY <-- */) {
                Comparator(sortFn)
            } else {
                Collections.reverseOrder(sortFn)
            }

            entry.value.sortedWith(comparator.thenComparator(sortAlphabetically))
        }
    }

    private fun getLibraryItemPreferencesFlow(): Flow<ItemPreferences> {
        return combine(
            libraryPreferences.downloadBadge().changes(),
            libraryPreferences.unreadBadge().changes(),
            libraryPreferences.localBadge().changes(),
            libraryPreferences.languageBadge().changes(),

            preferences.downloadedOnly().changes(),
            libraryPreferences.filterDownloaded().changes(),
            libraryPreferences.filterUnread().changes(),
            libraryPreferences.filterStarted().changes(),
            libraryPreferences.filterBookmarked().changes(),
            libraryPreferences.filterCompleted().changes(),
            // SY -->
            libraryPreferences.filterLewd().changes(),
            // SY <--
            transform = {
                ItemPreferences(
                    downloadBadge = it[0] as Boolean,
                    unreadBadge = it[1] as Boolean,
                    localBadge = it[2] as Boolean,
                    languageBadge = it[3] as Boolean,
                    globalFilterDownloaded = it[4] as Boolean,
                    filterDownloaded = it[5] as Int,
                    filterUnread = it[6] as Int,
                    filterStarted = it[7] as Int,
                    filterBookmarked = it[8] as Int,
                    filterCompleted = it[9] as Int,
                    // SY -->
                    filterLewd = it[10] as Int,
                    // SY <--
                )
            },
        )
    }

    /**
     * Get the categories and all its manga from the database.
     *
     * @return an observable of the categories and its manga.
     */
    private fun getLibraryFlow(): Flow<LibraryMap> {
        val libraryMangasFlow = combine(
            getLibraryManga.subscribe(),
            getLibraryItemPreferencesFlow(),
            downloadCache.changes,
        ) { libraryMangaList, prefs, _ ->
            libraryMangaList
                .map { libraryManga ->
                    val needsDownloadCounts = prefs.downloadBadge ||
                        prefs.filterDownloaded != TriStateGroup.State.IGNORE.value ||
                        prefs.globalFilterDownloaded

                    // Display mode based on user preference: take it from global library setting or category
                    LibraryItem(libraryManga).apply {
                        downloadCount = if (needsDownloadCounts) {
                            // SY -->
                            if (libraryManga.manga.source == MERGED_SOURCE_ID) {
                                runBlocking {
                                    getMergedMangaById.await(libraryManga.manga.id)
                                }.sumOf { downloadManager.getDownloadCount(it) }.toLong()
                            } else {
                                downloadManager.getDownloadCount(libraryManga.manga).toLong()
                            }
                            // SY <--
                        } else {
                            0
                        }
                        unreadCount = if (prefs.unreadBadge) libraryManga.unreadCount else 0
                        isLocal = if (prefs.localBadge) libraryManga.manga.isLocal() else false
                        sourceLanguage = if (prefs.languageBadge) {
                            sourceManager.getOrStub(libraryManga.manga.source).lang
                        } else {
                            ""
                        }
                    }
                }
                .groupBy { it.libraryManga.category }
        }

        return combine(getCategories.subscribe(), libraryMangasFlow) { categories, libraryManga ->
            val displayCategories = if (libraryManga.isNotEmpty() && !libraryManga.containsKey(0)) {
                categories.fastFilterNot { it.isSystemCategory }
            } else {
                categories
            }

            // SY -->
            mutableState.update { state ->
                state.copy(ogCategories = displayCategories)
            }
            // SY <--
            displayCategories.associateWith { libraryManga[it.id] ?: emptyList() }
        }
    }

    // SY -->
    private fun LibraryMap.applyGrouping(groupType: Int, displayMode: LibraryDisplayMode): LibraryMap {
        val items = when (groupType) {
            LibraryGroup.BY_DEFAULT -> this
            LibraryGroup.UNGROUPED -> {
                mapOf(
                    Category(
                        0,
                        preferences.context.getString(R.string.ungrouped),
                        0,
                        displayMode.flag,
                    ) to
                        values.flatten().distinctBy { it.libraryManga.manga.id },
                )
            }
            else -> {
                getGroupedMangaItems(
                    groupType = groupType,
                    libraryManga = this.values.flatten().distinctBy { it.libraryManga.manga.id },
                    displayMode = displayMode,
                )
            }
        }

        return items
    }
    // SY <--

    /**
     * Flow of tracking filter preferences
     *
     * @return map of track id with the filter value
     */
    private fun getTrackingFilterFlow(): Flow<Map<Long, Int>> {
        val loggedServices = trackManager.services.filter { it.isLogged }
        return if (loggedServices.isNotEmpty()) {
            val prefFlows = loggedServices
                .map { libraryPreferences.filterTracking(it.id.toInt()).changes() }
                .toTypedArray()
            combine(*prefFlows) {
                loggedServices
                    .mapIndexed { index, trackService -> trackService.id to it[index] }
                    .toMap()
            }
        } else {
            flowOf(emptyMap())
        }
    }

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas
            .map { getCategories.await(it.id).toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        return getChaptersByMangaId.await(manga.id).getNextUnread(manga, downloadManager)
    }

    /**
     * Returns the mix (non-common) categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getMixCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        val mangaCategories = mangas.map { getCategories.await(it.id).toSet() }
        val common = mangaCategories.reduce { set1, set2 -> set1.intersect(set2) }
        return mangaCategories.flatten().distinct().subtract(common)
    }

    fun runDownloadActionSelection(action: DownloadAction) {
        val selection = state.value.selection
        val mangas = selection.map { it.manga }.toList()
        when (action) {
            DownloadAction.NEXT_1_CHAPTER -> downloadUnreadChapters(mangas, 1)
            DownloadAction.NEXT_5_CHAPTERS -> downloadUnreadChapters(mangas, 5)
            DownloadAction.NEXT_10_CHAPTERS -> downloadUnreadChapters(mangas, 10)
            DownloadAction.UNREAD_CHAPTERS -> downloadUnreadChapters(mangas, null)
            DownloadAction.CUSTOM -> {
                mutableState.update { state ->
                    state.copy(
                        dialog = Dialog.DownloadCustomAmount(
                            mangas,
                            selection.maxOf { it.unreadCount }.toInt(),
                        ),
                    )
                }
                return
            }
            else -> {}
        }
        clearSelection()
    }

    /**
     * Queues the amount specified of unread chapters from the list of mangas given.
     *
     * @param mangas the list of manga.
     * @param amount the amount to queue or null to queue all
     */
    fun downloadUnreadChapters(mangas: List<Manga>, amount: Int?) {
        coroutineScope.launchNonCancellable {
            mangas.forEach { manga ->
                // SY -->
                if (manga.source == MERGED_SOURCE_ID) {
                    val mergedMangas = getMergedMangaById.await(manga.id)
                        .associateBy { it.id }
                    getNextChapters.await(manga.id)
                        .let { if (amount != null) it.take(amount) else it }
                        .groupBy { it.mangaId }
                        .forEach ab@{ (mangaId, chapters) ->
                            val mergedManga = mergedMangas[mangaId] ?: return@ab
                            val downloadChapters = chapters.fastFilterNot { chapter ->
                                downloadManager.queue.fastAny { chapter.id == it.chapter.id } ||
                                    downloadManager.isChapterDownloaded(
                                        chapter.name,
                                        chapter.scanlator,
                                        mergedManga.ogTitle,
                                        mergedManga.source,
                                    )
                            }

                            downloadManager.downloadChapters(mergedManga, downloadChapters)
                        }

                    return@forEach
                }

                // SY <--
                val chapters = getNextChapters.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                // SY -->
                                manga.ogTitle,
                                // SY <--
                                manga.source,

                            )
                    }
                    .let { if (amount != null) it.take(amount) else it }

                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    // SY -->
    fun cleanTitles() {
        state.value.selection.fastFilter {
            it.manga.isEhBasedManga() ||
                it.manga.source in nHentaiSourceIds
        }.fastForEach { (manga) ->
            val editedTitle = manga.title.replace("\\[.*?]".toRegex(), "").trim().replace("\\(.*?\\)".toRegex(), "").trim().replace("\\{.*?\\}".toRegex(), "").trim().let {
                if (it.contains("|")) {
                    it.replace(".*\\|".toRegex(), "").trim()
                } else {
                    it
                }
            }
            if (manga.title == editedTitle) return@fastForEach
            val mangaJson = CustomMangaManager.MangaJson(
                id = manga.id,
                title = editedTitle.nullIfBlank(),
                author = manga.author.takeUnless { it == manga.ogAuthor },
                artist = manga.artist.takeUnless { it == manga.ogArtist },
                description = manga.description.takeUnless { it == manga.ogDescription },
                genre = manga.genre.takeUnless { it == manga.ogGenre },
                status = manga.status.takeUnless { it == manga.ogStatus }?.toLong(),
            )

            customMangaManager.saveMangaInfo(mangaJson)
        }
        clearSelection()
    }

    fun syncMangaToDex() {
        launchIO {
            MdUtil.getEnabledMangaDex(unsortedPreferences, sourcePreferences, sourceManager)?.let { mdex ->
                state.value.selection.fastFilter { it.manga.source in mangaDexSourceIds }.fastForEach { (manga) ->
                    mdex.updateFollowStatus(MdUtil.getMangaId(manga.url), FollowStatus.READING)
                }
            }
            clearSelection()
        }
    }
    // SY <--

    /**
     * Marks mangas' chapters read status.
     */
    fun markReadSelection(read: Boolean) {
        val mangas = state.value.selection.toList()
        coroutineScope.launchNonCancellable {
            mangas.forEach { manga ->
                setReadStatus.await(
                    manga = manga.manga,
                    read = read,
                )
            }
        }
        clearSelection()
    }

    /**
     * Remove the selected manga.
     *
     * @param mangaList the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     */
    fun removeMangas(mangaList: List<Manga>, deleteFromLibrary: Boolean, deleteChapters: Boolean) {
        coroutineScope.launchNonCancellable {
            val mangaToDelete = mangaList.distinctBy { it.id }

            if (deleteFromLibrary) {
                val toDelete = mangaToDelete.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateManga.awaitAll(toDelete)
            }

            if (deleteChapters) {
                mangaToDelete.forEach { manga ->
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) {
                        if (source is MergedSource) {
                            val mergedMangas = getMergedMangaById.await(manga.id)
                            val sources = mergedMangas.distinctBy { it.source }.map { sourceManager.getOrStub(it.source) }
                            mergedMangas.forEach merge@{ mergedManga ->
                                val mergedSource = sources.firstOrNull { mergedManga.source == it.id } as? HttpSource ?: return@merge
                                downloadManager.deleteManga(mergedManga, mergedSource)
                            }
                        } else {
                            downloadManager.deleteManga(manga, source)
                        }
                    }
                }
            }
        }
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    fun setMangaCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        coroutineScope.launchNonCancellable {
            mangaList.forEach { manga ->
                val categoryIds = getCategories.await(manga.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setMangaCategories.await(manga.id, categoryIds)
            }
        }
    }

    fun getColumnsPreferenceForCurrentOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns() else libraryPreferences.portraitColumns()).asState(coroutineScope)
    }

    suspend fun getRandomLibraryItemForCurrentCategory(): LibraryItem? {
        return withIOContext {
            state.value
                .getLibraryItemsByCategoryId(activeCategory.toLong())
                .randomOrNull()
        }
    }

    // SY -->

    fun getCategoryName(
        context: Context,
        category: Category?,
        groupType: Int,
        categoryName: String,
    ): String {
        return when (groupType) {
            LibraryGroup.BY_STATUS -> when (category?.id) {
                SManga.ONGOING.toLong() -> context.getString(R.string.ongoing)
                SManga.LICENSED.toLong() -> context.getString(R.string.licensed)
                SManga.CANCELLED.toLong() -> context.getString(R.string.cancelled)
                SManga.ON_HIATUS.toLong() -> context.getString(R.string.on_hiatus)
                SManga.PUBLISHING_FINISHED.toLong() -> context.getString(R.string.publishing_finished)
                SManga.COMPLETED.toLong() -> context.getString(R.string.completed)
                else -> context.getString(R.string.unknown)
            }
            LibraryGroup.BY_SOURCE -> if (category?.id == LocalSource.ID) {
                context.getString(R.string.local_source)
            } else {
                categoryName
            }
            LibraryGroup.BY_TRACK_STATUS -> TrackStatus.values()
                .find { it.int.toLong() == category?.id }
                .let { it ?: TrackStatus.OTHER }
                .let { context.getString(it.res) }
            LibraryGroup.UNGROUPED -> context.getString(R.string.ungrouped)
            else -> categoryName
        }
    }

    suspend fun filterLibrary(unfiltered: List<LibraryItem>, query: String?, loggedInTrackServices: Map<Long, Int>): List<LibraryItem> {
        return if (unfiltered.isNotEmpty() && !query.isNullOrBlank()) {
            // Prepare filter object
            val parsedQuery = searchEngine.parseQuery(query)
            val mangaWithMetaIds = getIdsOfFavoriteMangaWithMetadata.await()
            val tracks = if (loggedInTrackServices.isNotEmpty()) {
                getTracks.await().groupBy { it.mangaId }
            } else {
                emptyMap()
            }
            val sources = unfiltered
                .distinctBy { it.libraryManga.manga.source }
                .fastMapNotNull { sourceManager.get(it.libraryManga.manga.source) }
                .associateBy { it.id }
            unfiltered.asFlow().cancellable().filter { item ->
                val mangaId = item.libraryManga.manga.id
                val sourceId = item.libraryManga.manga.source
                if (isMetadataSource(sourceId)) {
                    if (mangaWithMetaIds.binarySearch(mangaId) < 0) {
                        // No meta? Filter using title
                        filterManga(
                            queries = parsedQuery,
                            libraryManga = item.libraryManga,
                            tracks = tracks[mangaId],
                            source = sources[sourceId],
                            loggedInTrackServices = loggedInTrackServices,
                        )
                    } else {
                        val tags = getSearchTags.await(mangaId)
                        val titles = getSearchTitles.await(mangaId)
                        filterManga(
                            queries = parsedQuery,
                            libraryManga = item.libraryManga,
                            tracks = tracks[mangaId],
                            source = sources[sourceId],
                            checkGenre = false,
                            searchTags = tags,
                            searchTitles = titles,
                            loggedInTrackServices = loggedInTrackServices,
                        )
                    }
                } else {
                    filterManga(
                        queries = parsedQuery,
                        libraryManga = item.libraryManga,
                        tracks = tracks[mangaId],
                        source = sources[sourceId],
                        loggedInTrackServices = loggedInTrackServices,
                    )
                }
            }.toList()
        } else {
            unfiltered
        }
    }

    private fun filterManga(
        queries: List<QueryComponent>,
        libraryManga: LibraryManga,
        tracks: List<Track>?,
        source: Source?,
        checkGenre: Boolean = true,
        searchTags: List<SearchTag>? = null,
        searchTitles: List<SearchTitle>? = null,
        loggedInTrackServices: Map<Long, Int>,
    ): Boolean {
        val manga = libraryManga.manga
        val sourceIdString = manga.source.takeUnless { it == LocalSource.ID }?.toString()
        val genre = if (checkGenre) manga.genre.orEmpty() else emptyList()
        return queries.all { queryComponent ->
            when (queryComponent.excluded) {
                false -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        manga.title.contains(query, true) ||
                            (manga.author?.contains(query, true) == true) ||
                            (manga.artist?.contains(query, true) == true) ||
                            (manga.description?.contains(query, true) == true) ||
                            (source?.name?.contains(query, true) == true) ||
                            (sourceIdString != null && sourceIdString == query) ||
                            (loggedInTrackServices.isNotEmpty() && tracks != null && filterTracks(query, tracks)) ||
                            (genre.fastAny { it.contains(query, true) }) ||
                            (searchTags?.fastAny { it.name.contains(query, true) } == true) ||
                            (searchTitles?.fastAny { it.title.contains(query, true) } == true)
                    }
                    is Namespace -> {
                        searchTags != null && searchTags.fastAny {
                            val tag = queryComponent.tag
                            (it.namespace.equals(queryComponent.namespace, true) && tag?.run { it.name.contains(tag.asQuery(), true) } == true) ||
                                (tag == null && it.namespace.equals(queryComponent.namespace, true))
                        }
                    }
                    else -> true
                }
                true -> when (queryComponent) {
                    is Text -> {
                        val query = queryComponent.asQuery()
                        query.isBlank() || (
                            (!manga.title.contains(query, true)) &&
                                (manga.author?.contains(query, true) != true) &&
                                (manga.artist?.contains(query, true) != true) &&
                                (manga.description?.contains(query, true) != true) &&
                                (source?.name?.contains(query, true) != true) &&
                                (sourceIdString != null && sourceIdString != query) &&
                                (loggedInTrackServices.isEmpty() || tracks == null || !filterTracks(query, tracks)) &&
                                (!genre.fastAny { it.contains(query, true) }) &&
                                (searchTags?.fastAny { it.name.contains(query, true) } != true) &&
                                (searchTitles?.fastAny { it.title.contains(query, true) } != true)
                            )
                    }
                    is Namespace -> {
                        val searchedTag = queryComponent.tag?.asQuery()
                        searchTags == null || (queryComponent.namespace.isBlank() && searchedTag.isNullOrBlank()) || searchTags.fastAll { mangaTag ->
                            if (queryComponent.namespace.isBlank() && !searchedTag.isNullOrBlank()) {
                                !mangaTag.name.contains(searchedTag, true)
                            } else if (searchedTag.isNullOrBlank()) {
                                mangaTag.namespace == null || !mangaTag.namespace.equals(queryComponent.namespace, true)
                            } else if (mangaTag.namespace.isNullOrBlank()) {
                                true
                            } else {
                                !mangaTag.name.contains(searchedTag, true) || !mangaTag.namespace.equals(queryComponent.namespace, true)
                            }
                        }
                    }
                    else -> true
                }
            }
        }
    }

    private fun filterTracks(constraint: String, tracks: List<Track>): Boolean {
        return tracks.fastAny {
            val trackService = trackManager.getService(it.syncId)
            if (trackService != null) {
                val status = trackService.getStatus(it.status.toInt())
                val name = services[it.syncId]
                status.contains(constraint, true) || name?.contains(constraint, true) == true
            } else {
                false
            }
        }
    }
    // SY <--

    fun clearSelection() {
        mutableState.update { it.copy(selection = emptyList()) }
    }

    fun toggleSelection(manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.toMutableList().apply {
                if (fastAny { it.id == manga.id }) {
                    removeAll { it.id == manga.id }
                } else {
                    add(manga)
                }
            }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Selects all mangas between and including the given manga and the last pressed manga from the
     * same category as the given manga
     */
    fun toggleRangeSelection(manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.toMutableList().apply {
                val lastSelected = lastOrNull()
                if (lastSelected?.category != manga.category) {
                    add(manga)
                    return@apply
                }

                val items = state.getLibraryItemsByCategoryId(manga.category)
                    .fastMap { it.libraryManga }
                val lastMangaIndex = items.indexOf(lastSelected)
                val curMangaIndex = items.indexOf(manga)

                val selectedIds = fastMap { it.id }
                val selectionRange = when {
                    lastMangaIndex < curMangaIndex -> IntRange(lastMangaIndex, curMangaIndex)
                    curMangaIndex < lastMangaIndex -> IntRange(curMangaIndex, lastMangaIndex)
                    // We shouldn't reach this point
                    else -> return@apply
                }
                val newSelections = selectionRange.mapNotNull { index ->
                    items[index].takeUnless { it.id in selectedIds }
                }
                addAll(newSelections)
            }
            state.copy(selection = newSelection)
        }
    }

    fun selectAll(index: Int) {
        mutableState.update { state ->
            val newSelection = state.selection.toMutableList().apply {
                val categoryId = state.categories.getOrNull(index)?.id ?: -1
                val selectedIds = fastMap { it.id }
                val newSelections = state.getLibraryItemsByCategoryId(categoryId)
                    .fastMapNotNull { item ->
                        item.libraryManga.takeUnless { it.id in selectedIds }
                    }

                addAll(newSelections)
            }
            state.copy(selection = newSelection)
        }
    }

    fun invertSelection(index: Int) {
        mutableState.update { state ->
            val newSelection = state.selection.toMutableList().apply {
                val categoryId = state.categories[index].id
                val items = state.getLibraryItemsByCategoryId(categoryId).fastMap { it.libraryManga }
                val selectedIds = fastMap { it.id }
                val (toRemove, toAdd) = items.fastPartition { it.id in selectedIds }
                val toRemoveIds = toRemove.fastMap { it.id }
                removeAll { it.id in toRemoveIds }
                addAll(toAdd)
            }
            state.copy(selection = newSelection)
        }
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun openChangeCategoryDialog() {
        coroutineScope.launchIO {
            // Create a copy of selected manga
            val mangaList = state.value.selection.map { it.manga }

            // Hide the default category because it has a different behavior than the ones from db.
            val categories = state.value.categories.filter { it.id != 0L }

            // Get indexes of the common categories to preselect.
            val common = getCommonCategories(mangaList)
            // Get indexes of the mix categories to preselect.
            val mix = getMixCategories(mangaList)
            val preselected = categories.map {
                when (it) {
                    in common -> CheckboxState.State.Checked(it)
                    in mix -> CheckboxState.TriState.Exclude(it)
                    else -> CheckboxState.State.None(it)
                }
            }
            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(mangaList, preselected)) }
        }
    }

    fun openDeleteMangaDialog() {
        val mangaList = state.value.selection.map { it.manga }
        mutableState.update { it.copy(dialog = Dialog.DeleteManga(mangaList)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed class Dialog {
        data class ChangeCategory(val manga: List<Manga>, val initialSelection: List<CheckboxState<Category>>) : Dialog()
        data class DeleteManga(val manga: List<Manga>) : Dialog()
        data class DownloadCustomAmount(val manga: List<Manga>, val max: Int) : Dialog()
        object SyncFavoritesWarning : Dialog()
        object SyncFavoritesConfirm : Dialog()
    }

    // SY -->
    /** Returns first unread chapter of a manga */
    suspend fun getFirstUnread(manga: Manga): Chapter? {
        return getNextChapters.await(manga.id).firstOrNull()
    }

    private fun getGroupedMangaItems(groupType: Int, libraryManga: List<LibraryItem>, displayMode: LibraryDisplayMode): LibraryMap {
        val context = preferences.context
        return when (groupType) {
            LibraryGroup.BY_TRACK_STATUS -> {
                val tracks = runBlocking { getTracks.await() }.groupBy { it.mangaId }
                libraryManga.groupBy { item ->
                    val status = tracks[item.libraryManga.manga.id]?.firstNotNullOfOrNull { track ->
                        TrackStatus.parseTrackerStatus(track.syncId, track.status)
                    } ?: TrackStatus.OTHER

                    status.int
                }.mapKeys { (id) ->
                    Category(
                        id = id.toLong(),
                        name = TrackStatus.values()
                            .find { it.int == id }
                            .let { it ?: TrackStatus.OTHER }
                            .let { context.getString(it.res) },
                        order = TrackStatus.values().indexOfFirst { it.int == id }.takeUnless { it == -1 }?.toLong() ?: TrackStatus.OTHER.ordinal.toLong(),
                        flags = displayMode.flag,
                    )
                }
            }
            LibraryGroup.BY_SOURCE -> {
                val sources: List<Long>
                libraryManga.groupBy { item ->
                    item.libraryManga.manga.source
                }.also {
                    sources = it.keys
                        .map {
                            sourceManager.getOrStub(it)
                        }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                        .map { it.id }
                }.mapKeys {
                    Category(
                        id = it.key,
                        name = if (it.key == LocalSource.ID) {
                            context.getString(R.string.local_source)
                        } else {
                            sourceManager.getOrStub(it.key).name
                        },
                        order = sources.indexOf(it.key).takeUnless { it == -1 }?.toLong() ?: Long.MAX_VALUE,
                        flags = displayMode.flag,
                    )
                }
            }
            else -> {
                libraryManga.groupBy { item ->
                    item.libraryManga.manga.status
                }.mapKeys {
                    Category(
                        id = it.key + 1,
                        name = when (it.key) {
                            SManga.ONGOING.toLong() -> context.getString(R.string.ongoing)
                            SManga.LICENSED.toLong() -> context.getString(R.string.licensed)
                            SManga.CANCELLED.toLong() -> context.getString(R.string.cancelled)
                            SManga.ON_HIATUS.toLong() -> context.getString(R.string.on_hiatus)
                            SManga.PUBLISHING_FINISHED.toLong() -> context.getString(R.string.publishing_finished)
                            SManga.COMPLETED.toLong() -> context.getString(R.string.completed)
                            else -> context.getString(R.string.unknown)
                        },
                        order = when (it.key) {
                            SManga.ONGOING.toLong() -> 1
                            SManga.LICENSED.toLong() -> 2
                            SManga.CANCELLED.toLong() -> 3
                            SManga.ON_HIATUS.toLong() -> 4
                            SManga.PUBLISHING_FINISHED.toLong() -> 5
                            SManga.COMPLETED.toLong() -> 6
                            else -> 7
                        },
                        flags = displayMode.flag,
                    )
                }
            }
        }.toSortedMap(compareBy { it.order })
    }

    fun runSync() {
        favoritesSync.runSync(coroutineScope)
    }

    fun onAcceptSyncWarning() {
        unsortedPreferences.exhShowSyncIntro().set(false)
    }

    fun openFavoritesSyncDialog() {
        mutableState.update {
            it.copy(
                dialog = if (unsortedPreferences.exhShowSyncIntro().get()) {
                    Dialog.SyncFavoritesWarning
                } else {
                    Dialog.SyncFavoritesConfirm
                },
            )
        }
    }
    // SY <--

    @Immutable
    private data class ItemPreferences(
        val downloadBadge: Boolean,
        val unreadBadge: Boolean,
        val localBadge: Boolean,
        val languageBadge: Boolean,

        val globalFilterDownloaded: Boolean,
        val filterDownloaded: Int,
        val filterUnread: Int,
        val filterStarted: Int,
        val filterBookmarked: Int,
        val filterCompleted: Int,
        // SY -->
        val filterLewd: Int,
        // SY <--
    )

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val library: LibraryMap = emptyMap(),
        val searchQuery: String? = null,
        val selection: List<LibraryManga> = emptyList(),
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = false,
        val showMangaCount: Boolean = false,
        val showMangaContinueButton: Boolean = false,
        val dialog: Dialog? = null,
        // SY -->
        val showSyncExh: Boolean = false,
        val ogCategories: List<Category> = emptyList(),
        val groupType: Int = LibraryGroup.BY_DEFAULT,
        // SY <--
    ) {
        val selectionMode = selection.isNotEmpty()

        val categories = library.keys.toList()

        val libraryCount by lazy {
            library
                .flatMap { (_, v) -> v }
                .fastDistinctBy { it.libraryManga.manga.id }
                .size
        }

        // SY -->
        val showCleanTitles: Boolean by lazy {
            selection.any {
                it.manga.isEhBasedManga() ||
                    it.manga.source in nHentaiSourceIds
            }
        }

        val showAddToMangadex: Boolean by lazy {
            selection.any { it.manga.source in mangaDexSourceIds }
        }
        // SY <--

        fun getLibraryItemsByCategoryId(categoryId: Long): List<LibraryItem> {
            return library.firstNotNullOf { (k, v) -> v.takeIf { k.id == categoryId } }
        }

        fun getLibraryItemsByPage(page: Int): List<LibraryItem> {
            return library.values.toTypedArray().getOrNull(page) ?: emptyList()
        }

        fun getMangaCountForCategory(category: Category): Int? {
            return library[category]?.size?.takeIf { showMangaCount }
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            page: Int,
        ): LibraryToolbarTitle {
            val category = categories.getOrNull(page) ?: return LibraryToolbarTitle(defaultTitle)
            val categoryName = category.let {
                if (it.isSystemCategory) defaultCategoryTitle else it.name
            }

            val title = if (showCategoryTabs && categories.size <= 1) categoryName else defaultTitle
            val count = when {
                !showMangaCount -> null
                !showCategoryTabs -> getMangaCountForCategory(category)
                // Whole library count
                else -> libraryCount
            }

            return LibraryToolbarTitle(title, count)
        }
    }
}
