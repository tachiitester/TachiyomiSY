package eu.kanade.tachiyomi.ui.browse.migration.advanced.process

import android.content.Context
import android.widget.Toast
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.coroutineScope
import eu.kanade.domain.UnsortedPreferences
import eu.kanade.domain.category.interactor.GetCategories
import eu.kanade.domain.category.interactor.SetMangaCategories
import eu.kanade.domain.chapter.interactor.GetChapterByMangaId
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.chapter.interactor.UpdateChapter
import eu.kanade.domain.chapter.model.Chapter
import eu.kanade.domain.chapter.model.ChapterUpdate
import eu.kanade.domain.history.interactor.GetHistoryByMangaId
import eu.kanade.domain.history.interactor.UpsertHistory
import eu.kanade.domain.history.model.HistoryUpdate
import eu.kanade.domain.manga.interactor.GetManga
import eu.kanade.domain.manga.interactor.GetMergedReferencesById
import eu.kanade.domain.manga.interactor.NetworkToLocalManga
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.Manga
import eu.kanade.domain.manga.model.MangaUpdate
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.track.interactor.DeleteTrack
import eu.kanade.domain.track.interactor.GetTracks
import eu.kanade.domain.track.interactor.InsertTrack
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.ui.browse.migration.MigrationFlags
import eu.kanade.tachiyomi.ui.browse.migration.advanced.process.MigratingManga.SearchResult
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withUIContext
import eu.kanade.tachiyomi.util.system.logcat
import eu.kanade.tachiyomi.util.system.toast
import exh.eh.EHentaiThrottleManager
import exh.smartsearch.SmartSearchEngine
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

class MigrationListScreenModel(
    private val config: MigrationProcedureConfig,
    private val preferences: UnsortedPreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val getChapterByMangaId: GetChapterByMangaId = Injekt.get(),
    private val getMergedReferencesById: GetMergedReferencesById = Injekt.get(),
    private val getHistoryByMangaId: GetHistoryByMangaId = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val getTracks: GetTracks = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    private val deleteTrack: DeleteTrack = Injekt.get(),
) : ScreenModel {

    private val smartSearchEngine = SmartSearchEngine(config.extraSearchParams)
    private val throttleManager = EHentaiThrottleManager()

    val migratingItems = MutableStateFlow<List<MigratingManga>?>(null)
    val migrationDone = MutableStateFlow(false)
    val unfinishedCount = MutableStateFlow(0)

    val manualMigrations = MutableStateFlow(0)

    val hideNotFound = preferences.hideNotFoundMigration().get()

    val navigateOut = MutableSharedFlow<Unit>()

    val dialog = MutableStateFlow<Dialog?>(null)

    init {
        coroutineScope.launchIO {
            runMigrations(
                config.mangaIds
                    .map {
                        async {
                            val manga = getManga.await(it) ?: return@async null
                            MigratingManga(
                                manga = manga,
                                chapterInfo = getChapterInfo(it),
                                sourcesString = sourceManager.getOrStub(manga.source).getNameForMangaInfo(
                                    if (manga.source == MERGED_SOURCE_ID) {
                                        getMergedReferencesById.await(manga.id)
                                            .map { sourceManager.getOrStub(it.mangaSourceId) }
                                    } else {
                                        null
                                    },
                                ),
                                parentContext = coroutineScope.coroutineContext,
                            )
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
                    .also {
                        migratingItems.value = it
                    },
            )
        }
    }

    suspend fun getManga(result: SearchResult.Result) = getManga(result.id)
    suspend fun getManga(id: Long) = getManga.await(id)
    suspend fun getChapterInfo(result: SearchResult.Result) = getChapterInfo(result.id)
    suspend fun getChapterInfo(id: Long) = getChapterByMangaId.await(id).let { chapters ->
        MigratingManga.ChapterInfo(
            latestChapter = chapters.maxOfOrNull { it.chapterNumber },
            chapterCount = chapters.size,
        )
    }
    fun getSourceName(manga: Manga) = sourceManager.getOrStub(manga.source).getNameForMangaInfo(null)

    fun getMigrationSources() = preferences.migrationSources().get().split("/").mapNotNull {
        val value = it.toLongOrNull() ?: return@mapNotNull null
        sourceManager.get(value) as? CatalogueSource
    }

    private suspend fun runMigrations(mangas: List<MigratingManga>) {
        throttleManager.resetThrottle()
        unfinishedCount.value = mangas.size
        val useSourceWithMost = preferences.useSourceWithMost().get()
        val useSmartSearch = preferences.smartMigration().get()

        val sources = getMigrationSources()
        for (manga in mangas) {
            if (!currentCoroutineContext().isActive) {
                break
            }
            // in case it was removed
            if (manga.manga.id !in config.mangaIds) {
                continue
            }
            if (manga.searchResult.value == SearchResult.Searching && manga.migrationScope.isActive) {
                val mangaObj = manga.manga
                val mangaSource = sourceManager.getOrStub(mangaObj.source)

                val result = try {
                    manga.migrationScope.async {
                        val validSources = if (sources.size == 1) {
                            sources
                        } else {
                            sources.filter { it.id != mangaSource.id }
                        }
                        if (useSourceWithMost) {
                            val sourceSemaphore = Semaphore(3)
                            val processedSources = AtomicInteger()

                            validSources.map { source ->
                                async async2@{
                                    sourceSemaphore.withPermit {
                                        try {
                                            val searchResult = if (useSmartSearch) {
                                                smartSearchEngine.smartSearch(source, mangaObj.ogTitle)
                                            } else {
                                                smartSearchEngine.normalSearch(source, mangaObj.ogTitle)
                                            }

                                            if (searchResult != null && !(searchResult.url == mangaObj.url && source.id == mangaObj.source)) {
                                                val localManga = networkToLocalManga.await(searchResult)

                                                val chapters = if (source is EHentai) {
                                                    source.getChapterList(localManga.toSManga(), throttleManager::throttle)
                                                } else {
                                                    source.getChapterList(localManga.toSManga())
                                                }

                                                try {
                                                    syncChaptersWithSource.await(chapters, localManga, source)
                                                } catch (e: Exception) {
                                                    return@async2 null
                                                }
                                                manga.progress.value = validSources.size to processedSources.incrementAndGet()
                                                localManga to chapters.size
                                            } else {
                                                null
                                            }
                                        } catch (e: CancellationException) {
                                            // Ignore cancellations
                                            throw e
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                }
                            }.mapNotNull { it.await() }.maxByOrNull { it.second }?.first
                        } else {
                            validSources.forEachIndexed { index, source ->
                                val searchResult = try {
                                    val searchResult = if (useSmartSearch) {
                                        smartSearchEngine.smartSearch(source, mangaObj.ogTitle)
                                    } else {
                                        smartSearchEngine.normalSearch(source, mangaObj.ogTitle)
                                    }

                                    if (searchResult != null) {
                                        val localManga = networkToLocalManga.await(searchResult)
                                        val chapters = try {
                                            if (source is EHentai) {
                                                source.getChapterList(localManga.toSManga(), throttleManager::throttle)
                                            } else {
                                                source.getChapterList(localManga.toSManga())
                                            }
                                        } catch (e: Exception) {
                                            this@MigrationListScreenModel.logcat(LogPriority.ERROR, e)
                                            emptyList()
                                        }
                                        syncChaptersWithSource.await(chapters, localManga, source)
                                        localManga
                                    } else {
                                        null
                                    }
                                } catch (e: CancellationException) {
                                    // Ignore cancellations
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                                manga.progress.value = validSources.size to (index + 1)
                                if (searchResult != null) return@async searchResult
                            }

                            null
                        }
                    }.await()
                } catch (e: CancellationException) {
                    // Ignore canceled migrations
                    continue
                }

                if (result != null && result.thumbnailUrl == null) {
                    try {
                        val newManga = sourceManager.getOrStub(result.source).getMangaDetails(result.toSManga())
                        updateManga.awaitUpdateFromSource(result, newManga, true)
                    } catch (e: CancellationException) {
                        // Ignore cancellations
                        throw e
                    } catch (e: Exception) {
                    }
                }

                manga.searchResult.value = if (result == null) {
                    SearchResult.NotFound
                } else {
                    SearchResult.Result(result.id)
                }
                if (result == null && hideNotFound) {
                    removeManga(manga)
                }
                sourceFinished()
            }
        }
    }

    private suspend fun sourceFinished() {
        unfinishedCount.value = migratingItems.value.orEmpty().count {
            it.searchResult.value != SearchResult.Searching
        }
        if (allMangasDone()) {
            migrationDone.value = true
        }
        if (migratingItems.value?.isEmpty() == true) {
            navigateOut()
        }
    }

    fun allMangasDone() = migratingItems.value.orEmpty().all { it.searchResult.value != SearchResult.Searching } &&
        migratingItems.value.orEmpty().any { it.searchResult.value is SearchResult.Result }

    fun mangasSkipped() = migratingItems.value.orEmpty().count { it.searchResult.value == SearchResult.NotFound }

    private suspend fun migrateMangaInternal(
        prevManga: Manga,
        manga: Manga,
        replace: Boolean,
    ) {
        if (prevManga.id == manga.id) return // Nothing to migrate

        val flags = preferences.migrateFlags().get()
        // Update chapters read
        if (MigrationFlags.hasChapters(flags)) {
            val prevMangaChapters = getChapterByMangaId.await(prevManga.id)
            val maxChapterRead = prevMangaChapters.filter(Chapter::read)
                .maxOfOrNull(Chapter::chapterNumber)
            val dbChapters = getChapterByMangaId.await(manga.id)
            val prevHistoryList = getHistoryByMangaId.await(prevManga.id)

            val chapterUpdates = mutableListOf<ChapterUpdate>()
            val historyUpdates = mutableListOf<HistoryUpdate>()

            dbChapters.forEach { chapter ->
                if (chapter.isRecognizedNumber) {
                    val prevChapter = prevMangaChapters.find { it.isRecognizedNumber && it.chapterNumber == chapter.chapterNumber }
                    if (prevChapter != null) {
                        chapterUpdates += ChapterUpdate(
                            id = chapter.id,
                            bookmark = prevChapter.bookmark,
                            read = prevChapter.read,
                            dateFetch = prevChapter.dateFetch,
                        )
                        prevHistoryList.find { it.chapterId == prevChapter.id }?.let { prevHistory ->
                            historyUpdates += HistoryUpdate(
                                chapter.id,
                                prevHistory.readAt ?: return@let,
                                prevHistory.readDuration,
                            )
                        }
                    } else if (maxChapterRead != null && chapter.chapterNumber <= maxChapterRead) {
                        chapterUpdates += ChapterUpdate(
                            id = chapter.id,
                            read = true,
                        )
                    }
                }
            }

            updateChapter.awaitAll(chapterUpdates)
            historyUpdates.forEach {
                upsertHistory.await(it)
            }
        }
        // Update categories
        if (MigrationFlags.hasCategories(flags)) {
            val categories = getCategories.await(prevManga.id)
            setMangaCategories.await(manga.id, categories.map { it.id })
        }
        // Update track
        if (MigrationFlags.hasTracks(flags)) {
            val tracks = getTracks.await(prevManga.id)
            if (tracks.isNotEmpty()) {
                getTracks.await(manga.id).forEach {
                    deleteTrack.await(manga.id, it.syncId)
                }
                insertTrack.awaitAll(tracks.map { it.copy(mangaId = manga.id) })
            }
        }
        // Update custom cover
        if (MigrationFlags.hasCustomCover(flags) && prevManga.hasCustomCover(coverCache)) {
            coverCache.setCustomCoverToCache(manga, coverCache.getCustomCoverFile(prevManga.id).inputStream())
        }

        var mangaUpdate = MangaUpdate(manga.id, favorite = true, dateAdded = System.currentTimeMillis())
        var prevMangaUpdate: MangaUpdate? = null
        // Update extras
        if (MigrationFlags.hasExtra(flags)) {
            mangaUpdate = mangaUpdate.copy(
                chapterFlags = prevManga.chapterFlags,
                viewerFlags = prevManga.viewerFlags,
            )
        }
        // Update favorite status
        if (replace) {
            prevMangaUpdate = MangaUpdate(
                id = prevManga.id,
                favorite = false,
                dateAdded = 0,
            )
            mangaUpdate = mangaUpdate.copy(
                dateAdded = prevManga.dateAdded,
            )
        }

        updateManga.awaitAll(listOfNotNull(mangaUpdate, prevMangaUpdate))
    }

    fun useMangaForMigration(context: Context, newMangaId: Long, selectedMangaId: Long) {
        val migratingManga = migratingItems.value.orEmpty().find { it.manga.id == selectedMangaId }
            ?: return
        migratingManga.searchResult.value = SearchResult.Searching
        coroutineScope.launchIO {
            val result = migratingManga.migrationScope.async {
                val manga = getManga(newMangaId)!!
                val localManga = networkToLocalManga.await(manga)
                try {
                    val source = sourceManager.get(manga.source)!!
                    val chapters = source.getChapterList(localManga.toSManga())
                    syncChaptersWithSource.await(chapters, localManga, source)
                } catch (e: Exception) {
                    return@async null
                }
                localManga
            }.await()

            if (result != null) {
                try {
                    val newManga = sourceManager.getOrStub(result.source).getMangaDetails(result.toSManga())
                    updateManga.awaitUpdateFromSource(result, newManga, true)
                } catch (e: CancellationException) {
                    // Ignore cancellations
                    throw e
                } catch (e: Exception) {
                }

                migratingManga.searchResult.value = SearchResult.Result(result.id)
            } else {
                migratingManga.searchResult.value = SearchResult.NotFound
                withUIContext {
                    context.toast(R.string.no_chapters_found_for_migration, Toast.LENGTH_LONG)
                }
            }
        }
    }

    fun migrateMangas() {
        coroutineScope.launchIO {
            migratingItems.value.orEmpty().forEach { manga ->
                val searchResult = manga.searchResult.value
                if (searchResult is SearchResult.Result) {
                    val toMangaObj = getManga.await(searchResult.id) ?: return@forEach
                    migrateMangaInternal(
                        manga.manga,
                        toMangaObj,
                        true,
                    )
                }
            }

            navigateOut()
        }
    }

    fun copyMangas() {
        coroutineScope.launchIO {
            migratingItems.value.orEmpty().forEach { manga ->
                val searchResult = manga.searchResult.value
                if (searchResult is SearchResult.Result) {
                    val toMangaObj = getManga.await(searchResult.id) ?: return@forEach
                    migrateMangaInternal(
                        manga.manga,
                        toMangaObj,
                        false,
                    )
                }
            }
            navigateOut()
        }
    }

    private suspend fun navigateOut() {
        navigateOut.emit(Unit)
    }

    fun migrateManga(mangaId: Long, copy: Boolean) {
        manualMigrations.value++
        coroutineScope.launchIO {
            val manga = migratingItems.value.orEmpty().find { it.manga.id == mangaId }
                ?: return@launchIO

            val toMangaObj = getManga.await((manga.searchResult.value as? SearchResult.Result)?.id ?: return@launchIO)
                ?: return@launchIO
            migrateMangaInternal(
                manga.manga,
                toMangaObj,
                !copy,
            )

            removeManga(mangaId)
        }
    }

    fun removeManga(mangaId: Long) {
        coroutineScope.launchIO {
            val item = migratingItems.value.orEmpty().find { it.manga.id == mangaId }
                ?: return@launchIO
            removeManga(item)
            item.migrationScope.cancel()
            sourceFinished()
        }
    }

    fun removeManga(item: MigratingManga) {
        val ids = config.mangaIds.toMutableList()
        val index = ids.indexOf(item.manga.id)
        if (index > -1) {
            ids.removeAt(index)
            config.mangaIds = ids
            val index2 = migratingItems.value.orEmpty().indexOf(item)
            if (index2 > -1) migratingItems.value = migratingItems.value.orEmpty() - item
        }
    }

    override fun onDispose() {
        super.onDispose()
        migratingItems.value.orEmpty().forEach {
            it.migrationScope.cancel()
        }
    }

    fun openMigrateDialog(
        copy: Boolean,
    ) {
        dialog.value = Dialog.MigrateMangaDialog(
            copy,
            migratingItems.value.orEmpty().size,
            mangasSkipped(),
        )
    }

    sealed class Dialog {
        data class MigrateMangaDialog(val copy: Boolean, val mangaSet: Int, val mangaSkipped: Int) : Dialog()
        object MigrationExitDialog : Dialog()
    }
}
