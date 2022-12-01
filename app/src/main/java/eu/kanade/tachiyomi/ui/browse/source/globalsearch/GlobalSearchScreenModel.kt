package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Immutable
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.model.Manga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GlobalSearchScreenModel(
    initialQuery: String = "",
    initialExtensionFilter: String = "",
    preferences: BasePreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) : SearchScreenModel<GlobalSearchState>(GlobalSearchState(searchQuery = initialQuery)) {

    val incognitoMode = preferences.incognitoMode()
    val lastUsedSourceId = sourcePreferences.lastUsedSource()

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || initialExtensionFilter.isNotBlank()) {
            search(initialQuery)
        }
    }

    override fun getEnabledSources(): List<CatalogueSource> {
        val enabledLanguages = sourcePreferences.enabledLanguages().get()
        val disabledSources = sourcePreferences.disabledSources().get()
        val pinnedSources = sourcePreferences.pinnedSources().get()

        // SY -->
        val shouldSearchPinnedOnly = sourcePreferences.searchPinnedSourcesOnly().get()
        // SY <--

        return sourceManager.getCatalogueSources()
            .filter { it.lang in enabledLanguages }
            .filterNot { "${it.id}" in disabledSources }
            .sortedWith(compareBy({ "${it.id}" !in pinnedSources }, { "${it.name.lowercase()} (${it.lang})" }))
            // SY -->
            .filter {
                if (shouldSearchPinnedOnly) {
                    "${it.id}" in pinnedSources
                } else {
                    true
                }
            }
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

sealed class GlobalSearchItemResult {
    object Loading : GlobalSearchItemResult()

    data class Error(
        val throwable: Throwable,
    ) : GlobalSearchItemResult()

    data class Success(
        val result: List<Manga>,
    ) : GlobalSearchItemResult() {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }
}

@Immutable
data class GlobalSearchState(
    val searchQuery: String? = null,
    val items: Map<CatalogueSource, GlobalSearchItemResult> = emptyMap(),
) {

    val progress: Int = items.count { it.value !is GlobalSearchItemResult.Loading }

    val total: Int = items.size
}
