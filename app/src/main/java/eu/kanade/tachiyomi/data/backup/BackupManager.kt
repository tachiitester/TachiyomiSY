package eu.kanade.tachiyomi.data.backup

import android.Manifest
import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.domain.chapter.model.copyFrom
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CATEGORY
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CATEGORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CHAPTER
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CHAPTER_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CUSTOM_INFO
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CUSTOM_INFO_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_HISTORY
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_HISTORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_READ_MANGA
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_READ_MANGA_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_TRACK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_TRACK_MASK
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMangaReference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSerializer
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.backupCategoryMapper
import eu.kanade.tachiyomi.data.backup.models.backupChapterMapper
import eu.kanade.tachiyomi.data.backup.models.backupMergedMangaReferenceMapper
import eu.kanade.tachiyomi.data.backup.models.backupSavedSearchMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import eu.kanade.tachiyomi.source.model.copyFrom
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.util.system.hasPermission
import exh.source.MERGED_SOURCE_ID
import exh.source.getMainSource
import exh.util.nullIfBlank
import exh.util.nullIfEmpty
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.util.lang.toLong
import tachiyomi.core.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Manga_sync
import tachiyomi.data.Mangas
import tachiyomi.data.listOfStringsAndAdapter
import tachiyomi.data.manga.mangaMapper
import tachiyomi.data.manga.mergedMangaReferenceMapper
import tachiyomi.data.updateStrategyAdapter
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.util.Date
import kotlin.math.max

class BackupManager(
    private val context: Context,
) {

    private val handler: DatabaseHandler = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val backupPreferences: BackupPreferences = Injekt.get()
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private val getCategories: GetCategories = Injekt.get()
    private val getFavorites: GetFavorites = Injekt.get()

    // SY -->
    private val getMergedManga: GetMergedManga = Injekt.get()
    private val getCustomMangaInfo: GetCustomMangaInfo = Injekt.get()
    private val setCustomMangaInfo: SetCustomMangaInfo = Injekt.get()
    private val insertFlatMetadata: InsertFlatMetadata = Injekt.get()
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get()
    // SY <--

    internal val parser = ProtoBuf

    /**
     * Create backup file from database
     *
     * @param uri path of Uri
     * @param isAutoBackup backup called from scheduled backup job
     */
    suspend fun createBackup(uri: Uri, flags: Int, isAutoBackup: Boolean): String {
        if (!context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            throw IllegalStateException(context.getString(R.string.missing_storage_permission))
        }

        val databaseManga = getFavorites.await() /* SY --> */ + if (flags and BACKUP_READ_MANGA_MASK == BACKUP_READ_MANGA) {
            handler.awaitList { mangasQueries.getReadMangaNotInLibrary(mangaMapper) }
        } else {
            emptyList()
        } + getMergedManga.await() // SY <--

        val backup = Backup(
            backupMangas(databaseManga, flags),
            backupCategories(flags),
            emptyList(),
            backupExtensionInfo(databaseManga),
            // SY -->
            backupSavedSearches(),
            // SY <--
        )

        var file: UniFile? = null
        try {
            file = (
                if (isAutoBackup) {
                    // Get dir of file and create
                    var dir = UniFile.fromUri(context, uri)
                    dir = dir.createDirectory("automatic")

                    // Delete older backups
                    val numberOfBackups = backupPreferences.numberOfBackups().get()
                    // SY -->
                    val backupRegex = Regex("""tachiyomi(?:_sy)?_\d+-\d+-\d+_\d+-\d+.proto.gz""")
                    // SY <--
                    dir.listFiles { _, filename -> backupRegex.matches(filename) }
                        .orEmpty()
                        .sortedByDescending { it.name }
                        .drop(numberOfBackups - 1)
                        .forEach { it.delete() }

                    // Create new file to place backup
                    dir.createFile(Backup.getBackupFilename())
                } else {
                    UniFile.fromUri(context, uri)
                }
                )
                ?: throw Exception("Couldn't create backup file")

            if (!file.isFile) {
                throw IllegalStateException("Failed to get handle on file")
            }

            val byteArray = parser.encodeToByteArray(BackupSerializer, backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.getString(R.string.empty_backup_error))
            }

            file.openOutputStream().also {
                // Force overwrite old file
                (it as? FileOutputStream)?.channel?.truncate(0)
            }.sink().gzip().buffer().use { it.write(byteArray) }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator().validate(context, fileUri)

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    private fun backupExtensionInfo(mangas: List<Manga>): List<BackupSource> {
        return mangas
            .asSequence()
            .map(Manga::source)
            .distinct()
            .map(sourceManager::getOrStub)
            .map(BackupSource::copyFrom)
            .toList()
    }

    /**
     * Backup the categories of library
     *
     * @return list of [BackupCategory] to be backed up
     */
    private suspend fun backupCategories(options: Int): List<BackupCategory> {
        // Check if user wants category information in backup
        return if (options and BACKUP_CATEGORY_MASK == BACKUP_CATEGORY) {
            getCategories.await()
                .filterNot(Category::isSystemCategory)
                .map(backupCategoryMapper)
        } else {
            emptyList()
        }
    }

    private suspend fun backupMangas(mangas: List<Manga>, flags: Int): List<BackupManga> {
        return mangas.map {
            backupManga(it, flags)
        }
    }

    // SY -->
    /**
     * Backup the saved searches from sources
     *
     * @return list of [BackupSavedSearch] to be backed up
     */
    private suspend fun backupSavedSearches(): List<BackupSavedSearch> {
        return handler.awaitList { saved_searchQueries.selectAll(backupSavedSearchMapper) }
    }
    // SY <--

    /**
     * Convert a manga to Json
     *
     * @param manga manga that gets converted
     * @param options options for the backup
     * @return [BackupManga] containing manga in a serializable form
     */
    private suspend fun backupManga(manga: Manga, options: Int): BackupManga {
        // Entry for this manga
        val mangaObject = BackupManga.copyFrom(
            manga,
            // SY -->
            if (options and BACKUP_CUSTOM_INFO_MASK == BACKUP_CUSTOM_INFO) getCustomMangaInfo.get(manga.id) else null, /* SY <-- */
        )

        // SY -->
        if (manga.source == MERGED_SOURCE_ID) {
            mangaObject.mergedMangaReferences = handler.awaitList { mergedQueries.selectByMergeId(manga.id, backupMergedMangaReferenceMapper) }
        }

        val source = sourceManager.get(manga.source)?.getMainSource<MetadataSource<*, *>>()
        if (source != null) {
            getFlatMetadataById.await(manga.id)?.let { flatMetadata ->
                mangaObject.flatMetadata = BackupFlatMetadata.copyFrom(flatMetadata)
            }
        }
        // SY <--

        // Check if user wants chapter information in backup
        if (options and BACKUP_CHAPTER_MASK == BACKUP_CHAPTER) {
            // Backup all the chapters
            val chapters = handler.awaitList { chaptersQueries.getChaptersByMangaId(manga.id, backupChapterMapper) }
            if (chapters.isNotEmpty()) {
                mangaObject.chapters = chapters
            }
        }

        // Check if user wants category information in backup
        if (options and BACKUP_CATEGORY_MASK == BACKUP_CATEGORY) {
            // Backup categories for this manga
            val categoriesForManga = getCategories.await(manga.id)
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.map { it.order }
            }
        }

        // Check if user wants track information in backup
        if (options and BACKUP_TRACK_MASK == BACKUP_TRACK) {
            val tracks = handler.awaitList { manga_syncQueries.getTracksByMangaId(manga.id, backupTrackMapper) }
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks
            }
        }

        // Check if user wants history information in backup
        if (options and BACKUP_HISTORY_MASK == BACKUP_HISTORY) {
            val historyByMangaId = handler.awaitList(true) { historyQueries.getHistoryByMangaId(manga.id) }
            if (historyByMangaId.isNotEmpty()) {
                val history = historyByMangaId.map { history ->
                    val chapter = handler.awaitOne { chaptersQueries.getChapterById(history.chapter_id) }
                    BackupHistory(chapter.url, history.last_read?.time ?: 0L, history.time_read)
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        return mangaObject
    }

    internal suspend fun restoreExistingManga(manga: Manga, dbManga: Mangas): Manga {
        var updatedManga = manga.copy(id = dbManga._id)
        updatedManga = updatedManga.copyFrom(dbManga)
        updateManga(updatedManga)
        return updatedManga
    }

    /**
     * Fetches manga information
     *
     * @param manga manga that needs updating
     * @return Updated manga info.
     */
    internal suspend fun restoreNewManga(manga: Manga): Manga {
        return manga.copy(
            initialized = manga.description != null,
            id = insertManga(manga),
        )
    }

    /**
     * Restore the categories from Json
     *
     * @param backupCategories list containing categories
     */
    internal suspend fun restoreCategories(backupCategories: List<BackupCategory>) {
        // Get categories from file and from db
        val dbCategories = getCategories.await()

        val categories = backupCategories.map {
            var category = it.getCategory()
            var found = false
            for (dbCategory in dbCategories) {
                // If the category is already in the db, assign the id to the file's category
                // and do nothing
                if (category.name == dbCategory.name) {
                    category = category.copy(id = dbCategory.id)
                    found = true
                    break
                }
            }
            if (!found) {
                // Let the db assign the id
                val id = handler.awaitOne {
                    categoriesQueries.insert(category.name, category.order, category.flags)
                    categoriesQueries.selectLastInsertedRowId()
                }
                category = category.copy(id = id)
            }

            category
        }

        libraryPreferences.categorizedDisplaySettings().set(
            (dbCategories + categories)
                .distinctBy { it.flags }
                .size > 1,
        )
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    internal suspend fun restoreCategories(manga: Manga, categories: List<Int>, backupCategories: List<BackupCategory>) {
        val dbCategories = getCategories.await()
        val mangaCategoriesToUpdate = mutableListOf<Pair<Long, Long>>()

        categories.forEach { backupCategoryOrder ->
            backupCategories.firstOrNull {
                it.order == backupCategoryOrder.toLong()
            }?.let { backupCategory ->
                dbCategories.firstOrNull { dbCategory ->
                    dbCategory.name == backupCategory.name
                }?.let { dbCategory ->
                    mangaCategoriesToUpdate.add(Pair(manga.id, dbCategory.id))
                }
            }
        }

        // Update database
        if (mangaCategoriesToUpdate.isNotEmpty()) {
            handler.await(true) {
                mangas_categoriesQueries.deleteMangaCategoryByMangaId(manga.id)
                mangaCategoriesToUpdate.forEach { (mangaId, categoryId) ->
                    mangas_categoriesQueries.insert(mangaId, categoryId)
                }
            }
        }
    }

    /**
     * Restore history from Json
     *
     * @param history list containing history to be restored
     */
    internal suspend fun restoreHistory(history: List<BackupHistory>) {
        // List containing history to be updated
        val toUpdate = mutableListOf<HistoryUpdate>()
        for ((url, lastRead, readDuration) in history) {
            var dbHistory = handler.awaitOneOrNull { historyQueries.getHistoryByChapterUrl(url) }
            // Check if history already in database and update
            if (dbHistory != null) {
                dbHistory = dbHistory.copy(
                    last_read = Date(max(lastRead, dbHistory.last_read?.time ?: 0L)),
                    time_read = max(readDuration, dbHistory.time_read) - dbHistory.time_read,
                )
                toUpdate.add(
                    HistoryUpdate(
                        chapterId = dbHistory.chapter_id,
                        readAt = dbHistory.last_read!!,
                        sessionReadDuration = dbHistory.time_read,
                    ),
                )
            } else {
                // If not in database create
                handler
                    .awaitOneOrNull { chaptersQueries.getChapterByUrl(url) }
                    ?.let {
                        toUpdate.add(
                            HistoryUpdate(
                                chapterId = it._id,
                                readAt = Date(lastRead),
                                sessionReadDuration = readDuration,
                            ),
                        )
                    }
            }
        }
        handler.await(true) {
            toUpdate.forEach { payload ->
                historyQueries.upsert(
                    payload.chapterId,
                    payload.readAt,
                    payload.sessionReadDuration,
                )
            }
        }
    }

    /**
     * Restores the sync of a manga.
     *
     * @param manga the manga whose sync have to be restored.
     * @param tracks the track list to restore.
     */
    internal suspend fun restoreTracking(manga: Manga, tracks: List<tachiyomi.domain.track.model.Track>) {
        // Get tracks from database
        val dbTracks = handler.awaitList { manga_syncQueries.getTracksByMangaId(manga.id) }
        val toUpdate = mutableListOf<Manga_sync>()
        val toInsert = mutableListOf<tachiyomi.domain.track.model.Track>()

        tracks
            // Fix foreign keys with the current manga id
            .map { it.copy(mangaId = manga.id) }
            .forEach { track ->
                var isInDatabase = false
                for (dbTrack in dbTracks) {
                    if (track.syncId == dbTrack.sync_id) {
                        // The sync is already in the db, only update its fields
                        var temp = dbTrack
                        if (track.remoteId != dbTrack.remote_id) {
                            temp = temp.copy(remote_id = track.remoteId)
                        }
                        if (track.libraryId != dbTrack.library_id) {
                            temp = temp.copy(library_id = track.libraryId)
                        }
                        temp = temp.copy(last_chapter_read = max(dbTrack.last_chapter_read, track.lastChapterRead))
                        isInDatabase = true
                        toUpdate.add(temp)
                        break
                    }
                }
                if (!isInDatabase) {
                    // Insert new sync. Let the db assign the id
                    toInsert.add(track.copy(id = 0))
                }
            }

        // Update database
        if (toUpdate.isNotEmpty()) {
            handler.await(true) {
                toUpdate.forEach { track ->
                    manga_syncQueries.update(
                        track.manga_id,
                        track.sync_id,
                        track.remote_id,
                        track.library_id,
                        track.title,
                        track.last_chapter_read,
                        track.total_chapters,
                        track.status,
                        track.score.toDouble(),
                        track.remote_url,
                        track.start_date,
                        track.finish_date,
                        track._id,
                    )
                }
            }
        }
        if (toInsert.isNotEmpty()) {
            handler.await(true) {
                toInsert.forEach { track ->
                    manga_syncQueries.insert(
                        track.mangaId,
                        track.syncId,
                        track.remoteId,
                        track.libraryId,
                        track.title,
                        track.lastChapterRead,
                        track.totalChapters,
                        track.status,
                        track.score,
                        track.remoteUrl,
                        track.startDate,
                        track.finishDate,
                    )
                }
            }
        }
    }

    internal suspend fun restoreChapters(manga: Manga, chapters: List<tachiyomi.domain.chapter.model.Chapter>) {
        val dbChapters = handler.awaitList { chaptersQueries.getChaptersByMangaId(manga.id) }

        val processed = chapters.map { chapter ->
            var updatedChapter = chapter
            val dbChapter = dbChapters.find { it.url == updatedChapter.url }
            if (dbChapter != null) {
                updatedChapter = updatedChapter.copy(id = dbChapter._id)
                updatedChapter = updatedChapter.copyFrom(dbChapter)
                if (dbChapter.read && !updatedChapter.read) {
                    updatedChapter = updatedChapter.copy(read = true, lastPageRead = dbChapter.last_page_read)
                } else if (updatedChapter.lastPageRead == 0L && dbChapter.last_page_read != 0L) {
                    updatedChapter = updatedChapter.copy(lastPageRead = dbChapter.last_page_read)
                }
                if (!updatedChapter.bookmark && dbChapter.bookmark) {
                    updatedChapter = updatedChapter.copy(bookmark = true)
                }
            }

            updatedChapter.copy(mangaId = manga.id)
        }

        val newChapters = processed.groupBy { it.id > 0 }
        newChapters[true]?.let { updateKnownChapters(it) }
        newChapters[false]?.let { insertChapters(it) }
    }

    /**
     * Returns manga
     *
     * @return [Manga], null if not found
     */
    internal suspend fun getMangaFromDatabase(url: String, source: Long): Mangas? {
        return handler.awaitOneOrNull { mangasQueries.getMangaByUrlAndSource(url, source) }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return handler.awaitOne(true) {
            mangasQueries.insert(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre,
                title = manga.title,
                status = manga.status,
                thumbnailUrl = manga.thumbnailUrl,
                favorite = manga.favorite,
                lastUpdate = manga.lastUpdate,
                nextUpdate = 0L,
                calculateInterval = 0L,
                initialized = manga.initialized,
                viewerFlags = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                dateAdded = manga.dateAdded,
                // SY -->
                filteredScanlators = manga.filteredScanlators?.nullIfEmpty(),
                // SY <--
                updateStrategy = manga.updateStrategy,
            )
            mangasQueries.selectLastInsertedRowId()
        }
    }

    private suspend fun updateManga(manga: Manga): Long {
        handler.await(true) {
            mangasQueries.update(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre?.joinToString(separator = ", "),
                title = manga.title,
                status = manga.status,
                thumbnailUrl = manga.thumbnailUrl,
                favorite = manga.favorite.toLong(),
                lastUpdate = manga.lastUpdate,
                nextUpdate = null,
                calculateInterval = null,
                initialized = manga.initialized.toLong(),
                viewer = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                coverLastModified = manga.coverLastModified,
                dateAdded = manga.dateAdded,
                // SY -->
                filteredScanlators = manga.filteredScanlators?.let(listOfStringsAndAdapter::encode),
                // SY <--
                mangaId = manga.id,
                updateStrategy = manga.updateStrategy.let(updateStrategyAdapter::encode),
            )
        }
        return manga.id
    }

    /**
     * Inserts list of chapters
     */
    private suspend fun insertChapters(chapters: List<tachiyomi.domain.chapter.model.Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.insert(
                    chapter.mangaId,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read,
                    chapter.bookmark,
                    chapter.lastPageRead,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.dateFetch,
                    chapter.dateUpload,
                )
            }
        }
    }

    /**
     * Updates a list of chapters with known database ids
     */
    private suspend fun updateKnownChapters(chapters: List<tachiyomi.domain.chapter.model.Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read.toLong(),
                    bookmark = chapter.bookmark.toLong(),
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    chapterId = chapter.id,
                )
            }
        }
    }

    // SY -->
    internal suspend fun restoreSavedSearches(backupSavedSearches: List<BackupSavedSearch>) {
        val currentSavedSearches = handler.awaitList {
            saved_searchQueries.selectNamesAndSources()
        }

        handler.await {
            backupSavedSearches.filter { backupSavedSearch ->
                currentSavedSearches.none { it.source == backupSavedSearch.source && it.name == backupSavedSearch.name }
            }.forEach {
                saved_searchQueries.insert(
                    source = it.source,
                    name = it.name,
                    query = it.query.nullIfBlank(),
                    filtersJson = it.filterList.nullIfBlank()
                        ?.takeUnless { it == "[]" },
                )
            }
        }
    }

    /**
     * Restore the categories from Json
     *
     * @param manga the merge manga for the references
     * @param backupMergedMangaReferences the list of backup manga references for the merged manga
     */
    internal suspend fun restoreMergedMangaReferencesForManga(mergeMangaId: Long, backupMergedMangaReferences: List<BackupMergedMangaReference>) {
        // Get merged manga references from file and from db
        val dbMergedMangaReferences = handler.awaitList { mergedQueries.selectAll(mergedMangaReferenceMapper) }

        // Iterate over them
        backupMergedMangaReferences.forEach { backupMergedMangaReference ->
            // If the backupMergedMangaReference isn't in the db, remove the id and insert a new backupMergedMangaReference
            // Store the inserted id in the backupMergedMangaReference
            if (dbMergedMangaReferences.none { backupMergedMangaReference.mergeUrl == it.mergeUrl && backupMergedMangaReference.mangaUrl == it.mangaUrl }) {
                // Let the db assign the id
                val mergedManga = handler.awaitOneOrNull { mangasQueries.getMangaByUrlAndSource(backupMergedMangaReference.mangaUrl, backupMergedMangaReference.mangaSourceId, mangaMapper) } ?: return@forEach
                backupMergedMangaReference.getMergedMangaReference().run {
                    handler.await {
                        mergedQueries.insert(
                            infoManga = isInfoManga,
                            getChapterUpdates = getChapterUpdates,
                            chapterSortMode = chapterSortMode.toLong(),
                            chapterPriority = chapterPriority.toLong(),
                            downloadChapters = downloadChapters,
                            mergeId = mergeMangaId,
                            mergeUrl = mergeUrl,
                            mangaId = mergedManga.id,
                            mangaUrl = mangaUrl,
                            mangaSource = mangaSourceId,
                        )
                    }
                }
            }
        }
    }

    internal suspend fun restoreFlatMetadata(mangaId: Long, backupFlatMetadata: BackupFlatMetadata) {
        if (getFlatMetadataById.await(mangaId) == null) {
            insertFlatMetadata.await(backupFlatMetadata.getFlatMetadata(mangaId))
        }
    }

    internal fun restoreEditedInfo(mangaJson: CustomMangaInfo?) {
        mangaJson ?: return
        setCustomMangaInfo.set(mangaJson)
    }
    // SY <--
}
