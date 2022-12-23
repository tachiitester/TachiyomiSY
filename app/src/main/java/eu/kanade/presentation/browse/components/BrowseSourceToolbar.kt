package eu.kanade.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import eu.kanade.domain.library.model.LibraryDisplayMode
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.LocalSource
import exh.source.anyIs

@Composable
fun BrowseSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: CatalogueSource?,
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSearch: (String) -> Unit,
    // SY -->
    onSettingsClick: () -> Unit,
    // SY <--
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    // Avoid capturing unstable source in actions lambda
    val title = source?.name
    val isLocalSource = source is LocalSource

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(title) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = navigateUp,
        actions = {
            var selectingDisplayMode by remember { mutableStateOf(false) }
            // SY -->
            val isConfigurableSource = source?.anyIs<ConfigurableSource>() == true
            AppBarActions(
                actions = listOfNotNull(
                    AppBar.Action(
                        title = stringResource(R.string.action_display_mode),
                        icon = if (displayMode == LibraryDisplayMode.List) Icons.Filled.ViewList else Icons.Filled.ViewModule,
                        onClick = { selectingDisplayMode = true },
                    ).takeIf { displayMode != null },
                    if (isLocalSource) {
                        if (isConfigurableSource && displayMode != null) {
                            AppBar.OverflowAction(
                                title = stringResource(R.string.label_help),
                                onClick = onHelpClick,
                            )
                        } else {
                            AppBar.Action(
                                title = stringResource(R.string.label_help),
                                icon = Icons.Outlined.Help,
                                onClick = onHelpClick,
                            )
                        }
                    } else {
                        if (isConfigurableSource && displayMode != null) {
                            AppBar.OverflowAction(
                                title = stringResource(R.string.action_web_view),
                                onClick = onWebViewClick,
                            )
                        } else {
                            AppBar.Action(
                                title = stringResource(R.string.action_web_view),
                                icon = Icons.Outlined.Public,
                                onClick = onWebViewClick,
                            )
                        }
                    },
                    if (isConfigurableSource) {
                        AppBar.OverflowAction(
                            title = stringResource(R.string.action_settings),
                            onClick = onSettingsClick,
                        )
                    } else {
                        null
                    },
                    // SY <--
                ),
            )
            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(R.string.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(R.string.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(R.string.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
