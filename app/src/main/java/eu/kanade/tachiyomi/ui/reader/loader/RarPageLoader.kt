package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.util.system.ImageUtil
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Loader used to load a chapter from a .rar or .cbr file.
 */
internal class RarPageLoader(file: File) : PageLoader() {

    private val rar = Archive(file)

    // SY -->
    private val context: Application by injectLazy()
    private val readerPreferences: ReaderPreferences by injectLazy()
    private val tmpDir = File(context.externalCacheDir, "reader_${file.hashCode()}").also {
        it.deleteRecursively()
    }

    init {
        if (readerPreferences.cacheArchiveMangaOnDisk().get()) {
            tmpDir.mkdirs()
            Archive(file).use { rar ->
                rar.fileHeaders.asSequence()
                    .filterNot { it.isDirectory }
                    .forEach { header ->
                        val pageOutputStream = File(tmpDir, header.fileName.substringAfterLast("/"))
                            .also { it.createNewFile() }
                            .outputStream()
                        getStream(rar, header).use {
                            it.copyTo(pageOutputStream)
                        }
                    }
            }
        }
    }
    // SY <--

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        // SY -->
        if (readerPreferences.cacheArchiveMangaOnDisk().get()) {
            return DirectoryPageLoader(tmpDir).getPages()
        }
        // SY <--
        return rar.fileHeaders.asSequence()
            .filter { !it.isDirectory && ImageUtil.isImage(it.fileName) { rar.getInputStream(it) } }
            .sortedWith { f1, f2 -> f1.fileName.compareToCaseInsensitiveNaturalOrder(f2.fileName) }
            .mapIndexed { i, header ->
                ReaderPage(i).apply {
                    stream = { getStream(rar, header) }
                    status = Page.State.READY
                }
            }
            .toList()
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        rar.close()
        // SY -->
        tmpDir.deleteRecursively()
        // SY <--
    }

    /**
     * Returns an input stream for the given [header].
     */
    private fun getStream(rar: Archive, header: FileHeader): InputStream {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        synchronized(this) {
            try {
                pipeOut.use {
                    rar.extractFile(header, it)
                }
            } catch (e: Exception) {
            }
        }
        return pipeIn
    }
}
