package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.github.junrar.exception.UnsupportedRarV5Exception
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import exh.debug.DebugFunctions.readerPrefs
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val manga: Manga,
    private val source: Source,
    // SY -->
    private val sourceManager: SourceManager,
    private val mergedReferences: List<MergedMangaReference>,
    private val mergedManga: Map<Long, Manga>,
    // SY <--
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter /* SY --> */, page: Int? = null/* SY <-- */) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.getString(R.string.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read /* --> EH */ || readerPrefs
                    .preserveReadingPosition()
                    .get() || page != null // <-- EH
                ) {
                    chapter.requestedPage = /* SY --> */ page ?: /* SY <-- */ chapter.chapter.last_page_read
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloaded(dbChapter.name, dbChapter.scanlator, /* SY --> */ manga.ogTitle /* SY <-- */, manga.source, skipCache = true)
        return when {
            // SY -->
            source is MergedSource -> {
                val mangaReference = mergedReferences.firstOrNull { it.mangaId == chapter.chapter.manga_id } ?: error("Merge reference null")
                val source = sourceManager.get(mangaReference.mangaSourceId) ?: error("Source ${mangaReference.mangaSourceId} was null")
                val manga = mergedManga[chapter.chapter.manga_id] ?: error("Manga for merged chapter was null")
                val isMergedMangaDownloaded = downloadManager.isChapterDownloaded(chapter.chapter.name, chapter.chapter.scanlator, manga.ogTitle, manga.source, true)
                when {
                    isMergedMangaDownloaded -> DownloadPageLoader(chapter, manga, source, downloadManager, downloadProvider)
                    source is HttpSource -> HttpPageLoader(chapter, source)
                    source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                        when (format) {
                            is Format.Directory -> DirectoryPageLoader(format.file)
                            is Format.Zip -> ZipPageLoader(format.file)
                            is Format.Rar -> try {
                                RarPageLoader(format.file)
                            } catch (e: UnsupportedRarV5Exception) {
                                error(context.getString(R.string.loader_rar5_error))
                            }
                            is Format.Epub -> EpubPageLoader(format.file)
                        }
                    }
                    else -> error(context.getString(R.string.loader_not_implemented_error))
                }
            }
            // SY <--
            isDownloaded -> DownloadPageLoader(chapter, manga, source, downloadManager, downloadProvider)
            source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Zip -> ZipPageLoader(format.file)
                    is Format.Rar -> try {
                        RarPageLoader(format.file)
                    } catch (e: UnsupportedRarV5Exception) {
                        error(context.getString(R.string.loader_rar5_error))
                    }
                    is Format.Epub -> EpubPageLoader(format.file)
                }
            }
            source is HttpSource -> HttpPageLoader(chapter, source)
            source is StubSource -> error(context.getString(R.string.source_not_installed, source.toString()))
            else -> error(context.getString(R.string.loader_not_implemented_error))
        }
    }
}
