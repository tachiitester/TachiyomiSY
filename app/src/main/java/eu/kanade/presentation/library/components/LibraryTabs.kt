package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.visualName
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.components.material.Divider
import tachiyomi.presentation.core.components.material.TabIndicator
import tachiyomi.presentation.core.components.material.TabText

@Composable
internal fun LibraryTabs(
    categories: List<Category>,
    pagerState: PagerState,
    getNumberOfMangaForCategory: (Category) -> Int?,
    onTabItemClick: (Int) -> Unit,
) {
    // SY -->
    val currentPageIndex = pagerState.currentPage.coerceAtMost(categories.lastIndex)
    // SY <--
    Column {
        ScrollableTabRow(
            selectedTabIndex = currentPageIndex,
            edgePadding = 0.dp,
            indicator = { TabIndicator(it[currentPageIndex], pagerState.currentPageOffsetFraction) },
            // TODO: use default when width is fixed upstream
            // https://issuetracker.google.com/issues/242879624
            divider = {},
        ) {
            categories.forEachIndexed { index, category ->
                Tab(
                    selected = currentPageIndex == index,
                    onClick = { onTabItemClick(index) },
                    text = {
                        TabText(
                            text = category.visualName,
                            badgeCount = getNumberOfMangaForCategory(category),
                        )
                    },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Divider()
    }
}
