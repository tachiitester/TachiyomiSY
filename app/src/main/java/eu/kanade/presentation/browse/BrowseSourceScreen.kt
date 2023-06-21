package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.presentation.browse.components.BrowseSourceComfortableGrid
import eu.kanade.presentation.browse.components.BrowseSourceCompactGrid
import eu.kanade.presentation.browse.components.BrowseSourceEHentaiList
import eu.kanade.presentation.browse.components.BrowseSourceList
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.Source
import exh.metadata.metadata.base.RaisedSearchMetadata
import exh.source.isEhBasedSource
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

@Composable
fun BrowseSourceContent(
    source: Source?,
    mangaList: LazyPagingItems<StateFlow</* SY --> */Pair<Manga, RaisedSearchMetadata?>/* SY <-- */>>,
    columns: GridCells,
    // SY -->
    ehentaiBrowseDisplayMode: Boolean,
    // SY <--
    displayMode: LibraryDisplayMode,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    // SY -->
    onWebViewClick: (() -> Unit)?,
    onHelpClick: (() -> Unit)?,
    onLocalSourceHelpClick: (() -> Unit)?,
    // SY <--
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    val context = LocalContext.current

    val errorState = mangaList.loadState.refresh.takeIf { it is LoadState.Error }
        ?: mangaList.loadState.append.takeIf { it is LoadState.Error }

    val getErrorMessage: (LoadState.Error) -> String = { state ->
        with(context) { state.error.formattedMessage }
    }

    LaunchedEffect(errorState) {
        if (mangaList.itemCount > 0 && errorState != null && errorState is LoadState.Error) {
            val result = snackbarHostState.showSnackbar(
                message = getErrorMessage(errorState),
                actionLabel = context.getString(R.string.action_webview_refresh),
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.Dismissed -> snackbarHostState.currentSnackbarData?.dismiss()
                SnackbarResult.ActionPerformed -> mangaList.refresh()
            }
        }
    }

    if (mangaList.itemCount <= 0 && errorState != null && errorState is LoadState.Error) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = getErrorMessage(errorState),
            actions = if (source is LocalSource /* SY --> */ && onLocalSourceHelpClick != null /* SY <-- */) {
                listOf(
                    EmptyScreenAction(
                        stringResId = R.string.local_source_help_guide,
                        icon = Icons.Outlined.HelpOutline,
                        onClick = onLocalSourceHelpClick,
                    ),
                )
            } else {
                listOfNotNull(
                    EmptyScreenAction(
                        stringResId = R.string.action_retry,
                        icon = Icons.Outlined.Refresh,
                        onClick = mangaList::refresh,
                    ),
                    // SY -->
                    if (onWebViewClick != null) {
                        EmptyScreenAction(
                            stringResId = R.string.action_open_in_web_view,
                            icon = Icons.Outlined.Public,
                            onClick = onWebViewClick,
                        )
                    } else {
                        null
                    },
                    if (onHelpClick != null) {
                        EmptyScreenAction(
                            stringResId = R.string.label_help,
                            icon = Icons.Outlined.HelpOutline,
                            onClick = onHelpClick,
                        )
                    } else {
                        null
                    },
                    // SY <--
                )
            },
        )

        return
    }

    if (mangaList.itemCount == 0 && mangaList.loadState.refresh is LoadState.Loading) {
        LoadingScreen(
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    // SY -->
    if (source?.isEhBasedSource() == true && ehentaiBrowseDisplayMode) {
        BrowseSourceEHentaiList(
            mangaList = mangaList,
            contentPadding = contentPadding,
            onMangaClick = onMangaClick,
            onMangaLongClick = onMangaLongClick,
        )
        return
    }
    // SY <--

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseSourceComfortableGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
        LibraryDisplayMode.List -> {
            BrowseSourceList(
                mangaList = mangaList,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
        LibraryDisplayMode.CompactGrid, LibraryDisplayMode.CoverOnlyGrid -> {
            BrowseSourceCompactGrid(
                mangaList = mangaList,
                columns = columns,
                contentPadding = contentPadding,
                onMangaClick = onMangaClick,
                onMangaLongClick = onMangaLongClick,
            )
        }
    }
}

@Composable
fun MissingSourceScreen(
    source: StubSource,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source.name,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        EmptyScreen(
            message = stringResource(R.string.source_not_installed, source.toString()),
            modifier = Modifier.padding(paddingValues),
        )
    }
}
