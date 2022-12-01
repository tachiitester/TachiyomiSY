package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.os.bundleOf
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.transitions.ScreenTransition
import com.bluelinelabs.conductor.Router
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.domain.UnsortedPreferences
import eu.kanade.domain.chapter.model.Chapter
import eu.kanade.domain.manga.model.Manga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.ChangeCategoryDialog
import eu.kanade.presentation.components.DuplicateMangaDialog
import eu.kanade.presentation.components.LoadingScreen
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.DownloadCustomAmountDialog
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.manga.components.SelectScanlatorsDialog
import eu.kanade.presentation.util.LocalNavigatorContentPadding
import eu.kanade.presentation.util.LocalRouter
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.controller.popControllerWithTag
import eu.kanade.tachiyomi.ui.base.controller.pushController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.browse.migration.advanced.design.PreMigrationScreen
import eu.kanade.tachiyomi.ui.browse.source.SourcesController
import eu.kanade.tachiyomi.ui.browse.source.SourcesController.Companion.SMART_SEARCH_SOURCE_TAG
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedController
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.history.HistoryController
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.merged.EditMergedSettingsDialog
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesController
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.lang.launchUI
import eu.kanade.tachiyomi.util.lang.withNonCancellableContext
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import exh.md.similar.MangaDexSimilarController
import exh.pagepreview.PagePreviewController
import exh.recs.RecommendsController
import exh.source.MERGED_SOURCE_ID
import exh.source.getMainSource
import exh.source.isMdBasedSource
import exh.ui.metadata.MetadataViewScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaScreen(
    private val mangaId: Long,
    private val fromSource: Boolean = false,
    private val smartSearchConfig: SourcesController.SmartSearchConfig? = null,
) : Screen {

    override val key = uniqueScreenKey

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val router = LocalRouter.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val screenModel = rememberScreenModel { MangaInfoScreenModel(context, mangaId, fromSource, smartSearchConfig != null) }

        val state by screenModel.state.collectAsState()

        if (state is MangaScreenState.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as MangaScreenState.Success
        val isHttpSource = remember { successState.source is HttpSource }

        // SY -->
        LaunchedEffect(Unit) {
            screenModel.redirectFlow
                .take(1)
                .onEach {
                    router.replaceTopController(
                        MangaController(it).withFadeTransaction(),
                    )
                }
                .launchIn(this)
        }
        // SY <--

        MangaScreen(
            state = successState,
            snackbarHostState = screenModel.snackbarHostState,
            isTabletUi = isTabletUi(),
            onBackClicked = {
                when {
                    navigator.canPop -> navigator.pop()
                    else -> router.popCurrentController()
                }
            },
            onChapterClicked = { openChapter(context, it) },
            onDownloadChapter = screenModel::runChapterDownloadActions.takeIf { !successState.source.isLocalOrStub() },
            onAddToLibraryClicked = {
                screenModel.toggleFavorite()
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            // SY -->
            onWebViewClicked = {
                if (successState.mergedData == null) {
                    openMangaInWebView(context, screenModel.manga, screenModel.source)
                } else {
                    openMergedMangaWebview(context, successState.mergedData)
                }
            }.takeIf { isHttpSource },
            // SY <--
            onWebViewLongClicked = { copyMangaUrl(context, screenModel.manga, screenModel.source) }.takeIf { isHttpSource },
            onTrackingClicked = screenModel::showTrackDialog.takeIf { successState.trackingAvailable },
            onTagClicked = { performGenreSearch(router, navigator, it, screenModel.source!!) },
            onFilterButtonClicked = screenModel::showSettingsDialog,
            onRefresh = screenModel::fetchAllFromSource,
            onContinueReading = { continueReading(context, screenModel.getNextUnreadChapter()) },
            onSearch = { query, global -> performSearch(router, navigator, query, global) },
            onCoverClicked = screenModel::showCoverDialog,
            onShareClicked = { shareManga(context, screenModel.manga, screenModel.source) }.takeIf { isHttpSource },
            onDownloadActionClicked = screenModel::runDownloadAction.takeIf { !successState.source.isLocalOrStub() },
            onEditCategoryClicked = screenModel::promptChangeCategories.takeIf { successState.manga.favorite },
            // SY -->
            onMigrateClicked = { migrateManga(navigator, screenModel.manga!!) }.takeIf { successState.manga.favorite },
            onMetadataViewerClicked = { openMetadataViewer(navigator, successState.manga) },
            onEditInfoClicked = screenModel::showEditMangaInfoDialog,
            onRecommendClicked = { openRecommends(context, router, screenModel.source?.getMainSource(), successState.manga) },
            onMergedSettingsClicked = screenModel::showEditMergedSettingsDialog,
            onMergeClicked = { openSmartSearch(router, successState.manga) },
            onMergeWithAnotherClicked = { mergeWithAnother(router, context, successState.manga, screenModel::smartSearchMerge) },
            onOpenPagePreview = { openPagePreview(context, successState.chapters.getNextUnread(successState.manga), it) },
            onMorePreviewsClicked = { openMorePagePreviews(router, successState.manga) },
            // SY <--
            onMultiBookmarkClicked = screenModel::bookmarkChapters,
            onMultiMarkAsReadClicked = screenModel::markChaptersRead,
            onMarkPreviousAsReadClicked = screenModel::markPreviousChapterRead,
            onMultiDeleteClicked = screenModel::showDeleteChapterDialog,
            onChapterSelected = screenModel::toggleSelection,
            onAllChapterSelected = screenModel::toggleAllSelection,
            onInvertSelection = screenModel::invertSelection,
        )

        // SY -->
        var showScanlatorsDialog by remember { mutableStateOf(false) }
        // SY <--

        val onDismissRequest = { screenModel.dismissDialog() }
        when (val dialog = (state as? MangaScreenState.Success)?.dialog) {
            null -> {}
            is MangaInfoScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                    },
                )
            }
            is MangaInfoScreenModel.Dialog.DeleteChapters -> {
                DeleteChaptersDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.toggleAllSelection(false)
                        screenModel.deleteChapters(dialog.chapters)
                    },
                )
            }
            is MangaInfoScreenModel.Dialog.DownloadCustomAmount -> {
                DownloadCustomAmountDialog(
                    maxAmount = dialog.max,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { amount ->
                        val chaptersToDownload = screenModel.getUnreadChaptersSorted().take(amount)
                        if (chaptersToDownload.isNotEmpty()) {
                            screenModel.startDownload(chapters = chaptersToDownload, startNow = false)
                        }
                    },
                )
            }
            is MangaInfoScreenModel.Dialog.DuplicateManga -> DuplicateMangaDialog(
                onDismissRequest = onDismissRequest,
                onConfirm = { screenModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                onOpenManga = { navigator.push(MangaScreen(dialog.duplicate.id)) },
                duplicateFrom = screenModel.getSourceOrStub(dialog.duplicate),
            )
            MangaInfoScreenModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
                onDismissRequest = onDismissRequest,
                manga = successState.manga,
                onDownloadFilterChanged = screenModel::setDownloadedFilter,
                onUnreadFilterChanged = screenModel::setUnreadFilter,
                onBookmarkedFilterChanged = screenModel::setBookmarkedFilter,
                onSortModeChanged = screenModel::setSorting,
                onDisplayModeChanged = screenModel::setDisplayMode,
                onSetAsDefault = screenModel::setCurrentSettingsAsDefault,
                // SY -->
                onClickShowScanlatorSelection = { showScanlatorsDialog = true }.takeIf { successState.scanlators.size > 1 },
                // SY <--
            )
            MangaInfoScreenModel.Dialog.TrackSheet -> {
                var enableSwipeDismiss by remember { mutableStateOf(true) }
                AdaptiveSheet(
                    enableSwipeDismiss = enableSwipeDismiss,
                    onDismissRequest = onDismissRequest,
                ) { contentPadding ->
                    Navigator(
                        screen = TrackInfoDialogHomeScreen(
                            mangaId = successState.manga.id,
                            mangaTitle = successState.manga.title,
                            sourceId = successState.source.id,
                        ),
                        content = {
                            enableSwipeDismiss = it.lastItem is TrackInfoDialogHomeScreen
                            CompositionLocalProvider(LocalNavigatorContentPadding provides contentPadding) {
                                ScreenTransition(
                                    navigator = it,
                                    transition = {
                                        fadeIn(animationSpec = tween(220, delayMillis = 90)) with
                                            fadeOut(animationSpec = tween(90))
                                    },
                                )
                            }
                        },
                    )
                }
            }
            MangaInfoScreenModel.Dialog.FullCover -> {
                val sm = rememberScreenModel { MangaCoverScreenModel(successState.manga.id) }
                val manga by sm.state.collectAsState()
                if (manga != null) {
                    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                        if (it == null) return@rememberLauncherForActivityResult
                        sm.editCover(context, it)
                    }
                    MangaCoverDialog(
                        coverDataProvider = { manga!! },
                        snackbarHostState = sm.snackbarHostState,
                        isCustomCover = remember(manga) { manga!!.hasCustomCover() },
                        onShareClick = { sm.shareCover(context) },
                        onSaveClick = { sm.saveCover(context) },
                        onEditClick = {
                            when (it) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> sm.deleteCustomCover(context)
                            }
                        },
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    LoadingScreen(Modifier.systemBarsPadding())
                }
            }
            // SY -->
            is MangaInfoScreenModel.Dialog.EditMangaInfo -> {
                EditMangaDialog(
                    manga = dialog.manga,
                    onDismissRequest = screenModel::dismissDialog,
                    onPositiveClick = screenModel::updateMangaInfo,
                )
            }
            is MangaInfoScreenModel.Dialog.EditMergedSettings -> {
                EditMergedSettingsDialog(
                    mergedData = dialog.mergedData,
                    onDismissRequest = screenModel::dismissDialog,
                    onDeleteClick = screenModel::deleteMerge,
                    onPositiveClick = screenModel::updateMergeSettings,
                )
            }
            // SY <--
        }
        // SY -->
        if (showScanlatorsDialog) {
            SelectScanlatorsDialog(
                onDismissRequest = { showScanlatorsDialog = false },
                availableScanlators = successState.scanlators,
                initialSelectedScanlators = successState.manga.filteredScanlators ?: successState.scanlators,
                onSelectScanlators = screenModel::setScanlatorFilter,
            )
        }
        // SY <--
    }

    private fun continueReading(context: Context, unreadChapter: Chapter?) {
        if (unreadChapter != null) openChapter(context, unreadChapter)
    }

    private fun openChapter(context: Context, chapter: Chapter) {
        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
    }

    private fun openMangaInWebView(context: Context, manga_: Manga?, source_: Source?) {
        val manga = manga_ ?: return
        val source = source_ as? HttpSource ?: return

        val url = try {
            source.getMangaUrl(manga.toSManga())
        } catch (e: Exception) {
            return
        }

        val intent = WebViewActivity.newIntent(context, url, source.id, manga.title)
        context.startActivity(intent)
    }

    private fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
        val manga = manga_ ?: return
        val source = source_ as? HttpSource ?: return
        try {
            val uri = Uri.parse(source.getMangaUrl(manga.toSManga()))
            val intent = uri.toShareIntent(context, type = "text/plain")
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private fun performSearch(router: Router, navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            router.pushController(GlobalSearchController(query))
            return
        }

        // SY -->
        if (navigator.canPop) {
            when (val previousScreen = navigator.items[navigator.items.size - 2]) {
                is SourceFeedScreen -> {
                    navigator.pop()
                    previousScreen.onBrowseClick(router, previousScreen.sourceId, query)
                }
            }

            return
        }
        // SY <--

        if (router.backstackSize < 2) {
            return
        }

        when (val previousController = router.backstack[router.backstackSize - 2].controller) {
            is LibraryController -> {
                router.handleBack()
                previousController.search(query)
            }
            is UpdatesController,
            is HistoryController,
            -> {
                // Manually navigate to LibraryController
                router.handleBack()
                (router.activity as MainActivity).setSelectedNavItem(R.id.nav_library)
                val controller = router.getControllerWithTag(R.id.nav_library.toString()) as LibraryController
                controller.search(query)
            }
            is BrowseSourceController -> {
                router.handleBack()
                previousController.searchWithQuery(query)
            }
            // SY -->
            is SourceFeedController -> {
                router.handleBack()
                router.handleBack()
                router.pushController(BrowseSourceController(previousController.sourceId, query))
            }
            // SY <--
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private fun performGenreSearch(router: Router, navigator: Navigator, genreName: String, source: Source) {
        if (router.backstackSize < 2) {
            return
        }

        val previousController = router.backstack[router.backstackSize - 2].controller

        if (previousController is BrowseSourceController &&
            source is HttpSource
        ) {
            router.handleBack()
            previousController.searchWithGenre(genreName)
        } else {
            performSearch(router, navigator, genreName, global = false)
        }
    }

    /**
     * Copy Manga URL to Clipboard
     */
    private fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
        val manga = manga_ ?: return
        val source = source_ as? HttpSource ?: return
        val url = source.getMangaUrl(manga.toSManga())
        context.copyToClipboard(url, url)
    }

    // SY -->
    /**
     * Initiates source migration for the specific manga.
     */
    private fun migrateManga(navigator: Navigator, manga: Manga) {
        // SY -->
        PreMigrationScreen.navigateToMigration(
            Injekt.get<UnsortedPreferences>().skipPreMigration().get(),
            navigator,
            listOf(manga.id),
        )
        // SY <--
    }

    private fun openMetadataViewer(navigator: Navigator, manga: Manga) {
        navigator.push(MetadataViewScreen(manga.id, manga.source))
    }

    private fun openMergedMangaWebview(context: Context, mergedMangaData: MergedMangaData) {
        val sourceManager: SourceManager = Injekt.get()
        val mergedManga = mergedMangaData.manga.values.filterNot { it.source == MERGED_SOURCE_ID }
        val sources = mergedManga.map { sourceManager.getOrStub(it.source) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.action_open_in_web_view)
            .setSingleChoiceItems(
                Array(mergedManga.size) { index -> sources[index].toString() },
                -1,
            ) { dialog, index ->
                dialog.dismiss()
                openMangaInWebView(context, mergedManga[index], sources[index] as? HttpSource)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openMorePagePreviews(router: Router, manga: Manga) {
        router.pushController(PagePreviewController(manga.id))
    }

    private fun openPagePreview(context: Context, chapter: Chapter?, page: Int) {
        chapter ?: return
        context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, page))
    }
    // SY <--

    // EXH -->
    private fun openSmartSearch(router: Router, manga: Manga) {
        val smartSearchConfig = SourcesController.SmartSearchConfig(manga.title, manga.id)

        router.pushController(
            SourcesController(
                bundleOf(
                    SourcesController.SMART_SEARCH_CONFIG to smartSearchConfig,
                ),
            ).withFadeTransaction().tag(SMART_SEARCH_SOURCE_TAG),
        )
    }

    private fun mergeWithAnother(
        router: Router,
        context: Context,
        manga: Manga,
        smartSearchMerge: suspend (Manga, Long) -> Manga,
    ) {
        launchUI {
            try {
                val mergedManga = withNonCancellableContext {
                    smartSearchMerge(manga, smartSearchConfig?.origMangaId!!)
                }

                router.popControllerWithTag(SMART_SEARCH_SOURCE_TAG)
                router.popCurrentController()
                router.replaceTopController(
                    MangaController(
                        mergedManga.id,
                        true,
                    ).withFadeTransaction(),
                )
                context.toast(R.string.entry_merged)
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                context.toast(context.getString(R.string.failed_merge, e.message))
            }
        }
    }
    // EXH <--

    // AZ -->
    private fun openRecommends(context: Context, router: Router, source: Source?, manga: Manga) {
        source ?: return
        if (source.isMdBasedSource()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.az_recommends)
                .setSingleChoiceItems(
                    arrayOf(
                        context.getString(R.string.mangadex_similar),
                        context.getString(R.string.community_recommendations),
                    ),
                    -1,
                ) { dialog, index ->
                    dialog.dismiss()
                    when (index) {
                        0 -> router.pushController(MangaDexSimilarController(manga, source as CatalogueSource))
                        1 -> router.pushController(RecommendsController(manga, source as CatalogueSource))
                    }
                }
                .show()
        } else if (source is CatalogueSource) {
            router.pushController(RecommendsController(manga, source))
        }
    }
    // AZ <--
}
