package tachiyomi.domain.manga.repository

import exh.metadata.metadata.base.FlatMetadata
import exh.metadata.metadata.base.RaisedSearchMetadata
import exh.metadata.sql.models.SearchMetadata
import exh.metadata.sql.models.SearchTag
import exh.metadata.sql.models.SearchTitle
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.Manga

interface MangaMetadataRepository {
    suspend fun getMetadataById(id: Long): SearchMetadata?

    fun subscribeMetadataById(id: Long): Flow<SearchMetadata?>

    suspend fun getTagsById(id: Long): List<SearchTag>

    fun subscribeTagsById(id: Long): Flow<List<SearchTag>>

    suspend fun getTitlesById(id: Long): List<SearchTitle>

    fun subscribeTitlesById(id: Long): Flow<List<SearchTitle>>

    suspend fun insertFlatMetadata(flatMetadata: FlatMetadata)

    suspend fun insertMetadata(metadata: RaisedSearchMetadata) = insertFlatMetadata(metadata.flatten())

    suspend fun getExhFavoriteMangaWithMetadata(): List<Manga>

    suspend fun getIdsOfFavoriteMangaWithMetadata(): List<Long>

    suspend fun getSearchMetadata(): List<SearchMetadata>
}
