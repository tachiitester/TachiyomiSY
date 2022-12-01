package eu.kanade.tachiyomi.ui.browse.migration.search

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.coroutineScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.interactor.GetManga
import eu.kanade.domain.manga.model.Manga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateSearchScreenModel(
    val mangaId: Long,
    // SY -->
    val validSources: List<Long>,
    // SY <--
    initialExtensionFilter: String = "",
    preferences: BasePreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
) : SearchScreenModel<MigrateSearchState>(MigrateSearchState()) {

    init {
        extensionFilter = initialExtensionFilter
        coroutineScope.launch {
            val manga = getManga.await(mangaId)!!

            mutableState.update {
                it.copy(manga = manga, searchQuery = manga.title)
            }

            search(manga.title)
        }
    }

    val incognitoMode = preferences.incognitoMode()
    val lastUsedSourceId = sourcePreferences.lastUsedSource()

    override fun getEnabledSources(): List<CatalogueSource> {
        // SY -->
        return validSources.mapNotNull { sourceManager.get(it) }
            .filterIsInstance<CatalogueSource>()
        // SY <--
    }

    override fun updateSearchQuery(query: String?) {
        mutableState.update {
            it.copy(searchQuery = query)
        }
    }

    override fun updateItems(items: Map<CatalogueSource, GlobalSearchItemResult>) {
        mutableState.update {
            it.copy(items = items)
        }
    }

    override fun getItems(): Map<CatalogueSource, GlobalSearchItemResult> {
        return mutableState.value.items
    }
}

@Immutable
data class MigrateSearchState(
    val manga: Manga? = null,
    val searchQuery: String? = null,
    val items: Map<CatalogueSource, GlobalSearchItemResult> = emptyMap(),
) {

    val progress: Int = items.count { it.value !is GlobalSearchItemResult.Loading }

    val total: Int = items.size
}
