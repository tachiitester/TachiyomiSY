package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.os.Build
import com.hippo.unifile.UniFile
import com.jakewharton.rxrelay.PublishRelay
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.getComicInfo
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import eu.kanade.tachiyomi.util.storage.saveTo
import exh.util.DataSaver
import exh.util.DataSaver.Companion.getImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.Response
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.util.lang.awaitSingle
import tachiyomi.core.util.lang.launchIO
import tachiyomi.core.util.lang.launchNow
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.lang.withUIContext
import tachiyomi.core.util.system.ImageUtil
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download. In order to download them, the downloader
 * subscription must be running and the list of chapters must be sent to them by [downloadsRelay].
 *
 * The queue manipulation must be done in one thread (currently the main thread) to avoid unexpected
 * behavior, but it's safe to read it from multiple threads.
 */
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager = Injekt.get(),
    private val chapterCache: ChapterCache = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val xml: XML = Injekt.get(),
    // SY -->
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    // SY <--
) {

    /**
     * Store for persisting downloads across restarts.
     */
    private val store = DownloadStore(context)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { DownloadNotifier(context) }

    /**
     * Downloader subscription.
     */
    private var subscription: Subscription? = null

    /**
     * Relay to send a list of downloads to the downloader.
     */
    private val downloadsRelay = PublishRelay.create<List<Download>>()

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = subscription != null

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    init {
        launchNow {
            val chapters = async { store.restore() }
            addAllToQueue(chapters.await())
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (subscription != null || queueState.value.isEmpty()) {
            return false
        }

        initializeSubscription()

        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        isPaused = false

        downloadsRelay.call(pending)
        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        destroySubscription()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        isPaused = false

        // Prevent recursion when DownloadService.onDestroy() calls downloader.stop()
        if (DownloadService.isRunning.value) {
            DownloadService.stop(context)
        }
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        destroySubscription()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue() {
        destroySubscription()

        _clearQueue()
        notifier.dismissProgress()
    }

    /**
     * Prepares the subscriptions to start downloading.
     */
    private fun initializeSubscription() {
        if (subscription != null) return

        subscription = downloadsRelay.concatMapIterable { it }
            // Concurrently download from 5 different sources
            .groupBy { it.source }
            .flatMap(
                { bySource ->
                    bySource.concatMap { download ->
                        Observable.fromCallable {
                            runBlocking { downloadChapter(download) }
                            download
                        }.subscribeOn(Schedulers.io())
                    }
                },
                5,
            )
            .onBackpressureLatest()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                {
                    // Remove successful download from queue
                    if (it.status == Download.State.DOWNLOADED) {
                        removeFromQueue(it)
                    }
                    if (areAllDownloadsFinished()) {
                        stop()
                    }
                },
                { error ->
                    logcat(LogPriority.ERROR, error)
                    notifier.onError(error.message)
                    stop()
                },
            )
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun destroySubscription() {
        subscription?.unsubscribe()
        subscription = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) = launchIO {
        if (chapters.isEmpty()) {
            return@launchIO
        }

        val source = sourceManager.get(manga.source) as? HttpSource ?: return@launchIO
        val wasEmpty = queueState.value.isEmpty()
        // Called in background thread, the operation can be slow with SAF.
        val chaptersWithoutDir = async {
            chapters
                // Filter out those already downloaded.
                .filter { provider.findChapterDir(it.name, it.scanlator, /* SY --> */ manga.ogTitle /* SY <-- */, source) == null }
                // Add chapters to queue from the start.
                .sortedByDescending { it.sourceOrder }
        }

        // Runs in main thread (synchronization needed).
        val chaptersToQueue = chaptersWithoutDir.await()
            // Filter out those already enqueued.
            .filter { chapter -> queueState.value.none { it.chapter.id == chapter.id } }
            // Create a download for each one.
            .map { Download(source, manga, it) }

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            if (isRunning) {
                // Send the list of downloads to the downloader.
                downloadsRelay.call(chaptersToQueue)
            }

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    withUIContext {
                        notifier.onWarning(
                            context.getString(R.string.download_queue_size_warning),
                            WARNING_NOTIF_TIMEOUT_MS,
                            NotificationHandler.openUrl(context, LibraryUpdateNotifier.HELP_WARNING_URL),
                        )
                    }
                }
                DownloadService.start(context)
            }
        }
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     */
    private suspend fun downloadChapter(download: Download) {
        val mangaDir = provider.getMangaDir(/* SY --> */ download.manga.ogTitle /* SY <-- */, download.source)

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            notifier.onError(context.getString(R.string.download_insufficient_space), download.chapter.name, download.manga.title)
            return
        }

        val chapterDirname = provider.getChapterDirName(download.chapter.name, download.chapter.scanlator)
        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)

        try {
            // If the page list already exists, start from the file
            val pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = download.source.getPageList(download.chapter.toSChapter())

                if (pages.isEmpty()) {
                    throw Exception(context.getString(R.string.page_list_empty_error))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
                download.pages = reIndexedPages
                reIndexedPages
            }

            val dataSaver = if (sourcePreferences.dataSaverDownloader().get()) {
                DataSaver(download.source, sourcePreferences)
            } else {
                DataSaver.NoOp
            }

            // Delete all temporary (unfinished) files
            tmpDir.listFiles()
                ?.filter { it.name!!.endsWith(".tmp") }
                ?.forEach { it.delete() }

            download.status = Download.State.DOWNLOADING

            // Start downloading images, consider we can have downloaded images already
            // Concurrently do 2 pages at a time
            pageList.asFlow()
                .flatMapMerge(concurrency = 2) { page ->
                    flow {
                        // Fetch image URL if necessary
                        if (page.imageUrl.isNullOrEmpty()) {
                            page.status = Page.State.LOAD_PAGE
                            try {
                                page.imageUrl = download.source.fetchImageUrl(page).awaitSingle()
                            } catch (e: Throwable) {
                                page.status = Page.State.ERROR
                            }
                        }

                        withIOContext { getOrDownloadImage(page, download, tmpDir, dataSaver) }
                        emit(page)
                    }.flowOn(Dispatchers.IO)
                }
                .collect {
                    // Do when page is downloaded.
                    notifier.onProgressChange(download)
                }

            // Do after download completes

            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = Download.State.ERROR
                return
            }

            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                download.source,
            )

            // Only rename the directory if it's downloaded
            if (downloadPreferences.saveChaptersAsCBZ().get()) {
                archiveChapter(mangaDir, chapterDirname, tmpDir)
            } else {
                tmpDir.renameTo(chapterDirname)
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            download.status = Download.State.DOWNLOADED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the page list threw, it will resume here
            logcat(LogPriority.ERROR, error)
            download.status = Download.State.ERROR
            notifier.onError(error.message, download.chapter.name, download.manga.title)
        }
    }

    /**
     * Gets the image from the filesystem if it exists or downloads it otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(page: Page, download: Download, tmpDir: UniFile, dataSaver: DataSaver) {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = String.format("%0${digitCount}d", page.number)
        val tmpFile = tmpDir.findFile("$filename.tmp")

        // Delete temp file if it exists
        tmpFile?.delete()

        // Try to find the image file
        val imageFile = tmpDir.listFiles()?.firstOrNull { it.name!!.startsWith("$filename.") || it.name!!.startsWith("${filename}__001") }

        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(page.imageUrl!!) -> copyImageFromCache(chapterCache.getImageFile(page.imageUrl!!), tmpDir, filename)
                else -> downloadImage(page, download.source, tmpDir, filename, dataSaver)
            }

            // When the page is ready, set page path, progress (just in case) and status
            splitTallImageIfNeeded(page, tmpDir)

            page.uri = file.uri
            page.progress = 100
            page.status = Page.State.READY
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.ERROR
            notifier.onError(e.message, download.chapter.name, download.manga.title)
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(page: Page, source: HttpSource, tmpDir: UniFile, filename: String, dataSaver: DataSaver): UniFile {
        page.status = Page.State.DOWNLOAD_IMAGE
        page.progress = 0
        return flow {
            val response = source.getImage(page, dataSaver)
            val file = tmpDir.createFile("$filename.tmp")
            try {
                response.body.source().saveTo(file.openOutputStream())
                val extension = getImageExtension(response, file)
                file.renameTo("$filename.$extension")
            } catch (e: Exception) {
                response.close()
                file.delete()
                throw e
            }
            emit(file)
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { _, attempt ->
                if (attempt < 3) {
                    delay((2L shl attempt.toInt()) * 1000)
                    true
                } else {
                    false
                }
            }
            .first()
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun copyImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        tmpFile.renameTo("$filename.${extension.extension}")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * Returns the extension of the downloaded image from the network response, or if it's null,
     * analyze the file. If everything fails, assume it's a jpg.
     *
     * @param response the network response of the image.
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(response: Response, file: UniFile): String {
        // Read content type if available.
        val mime = response.body.contentType()?.run { if (type == "image") "image/$subtype" else null }
            // Else guess from the uri.
            ?: context.contentResolver.getType(file.uri)
            // Else read magic numbers.
            ?: ImageUtil.findImageType { file.openInputStream() }?.mime

        return ImageUtil.getExtensionFromMimeType(mime)
    }

    private fun splitTallImageIfNeeded(page: Page, tmpDir: UniFile) {
        if (!downloadPreferences.splitTallImages().get()) return

        try {
            val filenamePrefix = String.format("%03d", page.number)
            val imageFile = tmpDir.listFiles()?.firstOrNull { it.name.orEmpty().startsWith(filenamePrefix) }
                ?: error(context.getString(R.string.download_notifier_split_page_not_found, page.number))

            // If the original page was previously split, then skip
            if (imageFile.name.orEmpty().startsWith("${filenamePrefix}__")) return

            ImageUtil.splitTallImage(
                tmpDir,
                imageFile,
                filenamePrefix,
                // SY -->
                zip4jFile = null,
                zip4jEntry = null,
                // SY <--
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to split downloaded image" }
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param tmpDir the directory where the download is currently stored.
     */
    private fun isDownloadSuccessful(
        download: Download,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false

        // Ensure that all pages have been downloaded
        if (download.downloadedImages != downloadPageCount) {
            return false
        }

        // Ensure that the chapter folder has all the pages
        val downloadedImagesCount = tmpDir.listFiles().orEmpty().count {
            val fileName = it.name.orEmpty()
            when {
                fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false
                fileName.endsWith(".tmp") -> false
                // Only count the first split page and not the others
                fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                else -> true
            }
        }
        if (downloadedImagesCount != downloadPageCount) {
            return false
        }

        return true
    }

    /**
     * Archive the chapter pages as a CBZ.
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ) {
        // SY -->
        if (CbzCrypto.getPasswordProtectDlPref() && CbzCrypto.isPasswordSet()) {
            archiveEncryptedChapter(mangaDir, dirname, tmpDir)
            return
        }
        // SY <--

        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")
        ZipOutputStream(BufferedOutputStream(zip.openOutputStream())).use { zipOut ->
            zipOut.setMethod(ZipEntry.STORED)

            tmpDir.listFiles()?.forEach { img ->
                img.openInputStream().use { input ->
                    val data = input.readBytes()
                    val size = img.length()
                    val entry = ZipEntry(img.name).apply {
                        val crc = CRC32().apply {
                            update(data)
                        }
                        setCrc(crc.value)

                        compressedSize = size
                        setSize(size)
                    }
                    zipOut.putNextEntry(entry)
                    zipOut.write(data)
                }
            }
        }
        zip.renameTo("$dirname.cbz")
        tmpDir.delete()
    }

    // SY -->

    private fun archiveEncryptedChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ) {
        val zip = ZipFile("${mangaDir.filePath}/$dirname.cbz$TMP_DIR_SUFFIX")
        val zipParameters = ZipParameters()

        CbzCrypto.setZipParametersEncrypted(zipParameters)
        zip.setPassword(CbzCrypto.getDecryptedPasswordCbz())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) zip.charset = StandardCharsets.ISO_8859_1

        tmpDir.filePath?.let { addPaddingToImage(File(it)) }

        zip.addFiles(
            tmpDir.listFiles()?.map { img -> img.filePath?.let { File(it) } },
            zipParameters,
        )

        mangaDir.findFile("$dirname.cbz$TMP_DIR_SUFFIX")?.renameTo("$dirname.cbz")
        tmpDir.delete()
    }

    private fun addPaddingToImage(imageDir: File) {
        imageDir.listFiles()
            // using ImageUtils isImage and findImageType functions causes IO errors when deleting files to set Exif Metadata
            // it should be safe to assume that all files with image extensions are actual images at this point
            ?.filter {
                it.extension.equals("jpg", true) ||
                    it.extension.equals("jpeg", true) ||
                    it.extension.equals("png", true) ||
                    it.extension.equals("webp", true)
            }
            ?.forEach { ImageUtil.addPaddingToImageExif(it) }
    }
    // SY <--

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     */
    private fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val chapterUrl = source.getChapterUrl(chapter.toSChapter())
        val comicInfo = getComicInfo(manga, chapter, chapterUrl)
        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE).openOutputStream().use {
            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
            it.write(comicInfoString.toByteArray())
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueByPredicate(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads
        }
    }

    fun removeFromQueue(chapters: List<Chapter>) {
        val chapterIds = chapters.map { it.id }
        removeFromQueueByPredicate { it.chapter.id in chapterIds }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueByPredicate { it.manga.id == manga.id }
    }

    private fun _clearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        pause()
        _clearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
