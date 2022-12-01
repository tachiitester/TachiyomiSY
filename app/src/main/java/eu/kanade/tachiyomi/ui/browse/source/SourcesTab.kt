package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.interactor.GetRemoteManga.Companion.QUERY_POPULAR
import eu.kanade.presentation.browse.SourceCategoriesDialog
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.util.LocalRouter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.controller.pushController
import eu.kanade.tachiyomi.ui.browse.source.SourcesController.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedController
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchController
import exh.ui.smartsearch.SmartSearchController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun Screen.sourcesTab(
    smartSearchConfig: SmartSearchConfig? = null,
): TabContent {
    val router = LocalRouter.currentOrThrow
    val screenModel = rememberScreenModel { SourcesScreenModel(smartSearchConfig = smartSearchConfig) }
    val state by screenModel.state.collectAsState()

    return TabContent(
        // SY -->
        titleRes = when (smartSearchConfig == null) {
            true -> R.string.label_sources
            false -> R.string.find_in_another_source
        },
        actions = if (smartSearchConfig == null) {
            listOf(
                AppBar.Action(
                    title = stringResource(R.string.action_global_search),
                    icon = Icons.Outlined.TravelExplore,
                    onClick = { router.pushController(GlobalSearchController()) },
                ),
                AppBar.Action(
                    title = stringResource(R.string.action_filter),
                    icon = Icons.Outlined.FilterList,
                    onClick = { router.pushController(SourceFilterController()) },
                ),
            )
        } else {
            emptyList()
        },
        // SY <--
        content = { contentPadding, snackbarHostState ->
            SourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source, query ->
                    // SY -->
                    val controller = when {
                        smartSearchConfig != null -> SmartSearchController(source.id, smartSearchConfig)
                        (query.isBlank() || query == QUERY_POPULAR) && screenModel.useNewSourceNavigation -> SourceFeedController(source.id)
                        else -> BrowseSourceController(source, query)
                    }
                    screenModel.onOpenSource(source)
                    router.pushController(controller)
                    // SY <--
                },
                onClickPin = screenModel::togglePin,
                onLongClickItem = screenModel::showSourceDialog,
            )

            state.dialog?.let { dialog ->
                when (dialog) {
                    is SourcesScreenModel.Dialog.SourceCategories -> {
                        val source = dialog.source
                        SourceOptionsDialog(
                            source = source,
                            onClickPin = {
                                screenModel.togglePin(source)
                                screenModel.closeDialog()
                            },
                            onClickDisable = {
                                screenModel.toggleSource(source)
                                screenModel.closeDialog()
                            },
                            // SY -->
                            onClickSetCategories = {
                                screenModel.showSourceCategoriesDialog(source)
                                screenModel.closeDialog()
                            },
                            onClickToggleDataSaver = {
                                screenModel.toggleExcludeFromDataSaver(source)
                                screenModel.closeDialog()
                            },
                            onDismiss = screenModel::closeDialog,
                        )
                    }
                    is SourcesScreenModel.Dialog.SourceLongClick -> {
                        val source = dialog.source
                        SourceCategoriesDialog(
                            source = source,
                            categories = state.categories,
                            onClickCategories = { categories ->
                                screenModel.setSourceCategories(source, categories)
                                screenModel.closeDialog()
                            },
                            onDismiss = screenModel::closeDialog,
                        )
                    }
                }
            }

            val internalErrString = stringResource(R.string.internal_error)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        SourcesScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
