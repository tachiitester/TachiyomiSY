package tachiyomi.data.source

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.MERGED_SOURCE_ID
import exh.source.isEhBasedSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.model.SourceWithCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.SourcePagingSourceType
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager

class SourceRepositoryImpl(
    private val sourceManager: SourceManager,
    private val handler: DatabaseHandler,
) : SourceRepository {

    override fun getSources(): Flow<List<Source>> {
        return sourceManager.catalogueSources.map { sources ->
            sources.map {
                sourceMapper(it).copy(
                    supportsLatest = it.supportsLatest,
                )
            }
        }
    }

    override fun getOnlineSources(): Flow<List<Source>> {
        return sourceManager.catalogueSources.map { sources ->
            sources
                .filterIsInstance<HttpSource>()
                .map(sourceMapper)
        }
    }

    override fun getSourcesWithFavoriteCount(): Flow<List<Pair<Source, Long>>> {
        val sourceIdWithFavoriteCount = handler.subscribeToList { mangasQueries.getSourceIdWithFavoriteCount() }
        return sourceIdWithFavoriteCount.map { sourceIdsWithCount ->
            sourceIdsWithCount
                // SY -->
                .filterNot { it.source == MERGED_SOURCE_ID }
                // SY <--
                .map { (sourceId, count) ->
                    val source = sourceManager.getOrStub(sourceId)
                    val domainSource = sourceMapper(source).copy(
                        isStub = source is StubSource,
                    )
                    domainSource to count
                }
        }
    }

    override fun getSourcesWithNonLibraryManga(): Flow<List<SourceWithCount>> {
        val sourceIdWithNonLibraryManga = handler.subscribeToList { mangasQueries.getSourceIdsWithNonLibraryManga() }
        return sourceIdWithNonLibraryManga.map { sourceId ->
            sourceId.map { (sourceId, count) ->
                val source = sourceManager.getOrStub(sourceId)
                val domainSource = sourceMapper(source).copy(
                    isStub = source is StubSource,
                )
                SourceWithCount(domainSource, count)
            }
        }
    }

    override fun search(
        sourceId: Long,
        query: String,
        filterList: FilterList,
    ): SourcePagingSourceType {
        val source = sourceManager.get(sourceId) as CatalogueSource
        // SY -->
        if (source.isEhBasedSource()) {
            return EHentaiSearchPagingSource(source, query, filterList)
        }
        // SY <--
        return SourceSearchPagingSource(source, query, filterList)
    }

    override fun getPopular(sourceId: Long): SourcePagingSourceType {
        val source = sourceManager.get(sourceId) as CatalogueSource
        // SY -->
        if (source.isEhBasedSource()) {
            return EHentaiPopularPagingSource(source)
        }
        // SY <--
        return SourcePopularPagingSource(source)
    }

    override fun getLatest(sourceId: Long): SourcePagingSourceType {
        val source = sourceManager.get(sourceId) as CatalogueSource
        // SY -->
        if (source.isEhBasedSource()) {
            return EHentaiLatestPagingSource(source)
        }
        // SY <--
        return SourceLatestPagingSource(source)
    }
}
