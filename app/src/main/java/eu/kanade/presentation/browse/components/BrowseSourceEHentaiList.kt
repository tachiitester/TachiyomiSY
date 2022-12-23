package eu.kanade.presentation.browse.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.items
import com.gowtham.ratingbar.RatingBar
import com.gowtham.ratingbar.RatingBarConfig
import eu.kanade.domain.manga.model.Manga
import eu.kanade.presentation.components.Badge
import eu.kanade.presentation.components.BadgeGroup
import eu.kanade.presentation.components.LazyColumn
import eu.kanade.presentation.components.MangaCover
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.lang.withIOContext
import exh.metadata.MetadataUtil
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedSearchMetadata
import exh.util.SourceTagsUtil
import exh.util.SourceTagsUtil.GenreColor
import exh.util.floor
import kotlinx.coroutines.flow.StateFlow
import java.util.Date

@Composable
fun BrowseSourceEHentaiList(
    mangaList: LazyPagingItems<StateFlow</* SY --> */Pair<Manga, RaisedSearchMetadata?>/* SY <-- */>>,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onMangaLongClick: (Manga) -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding,
    ) {
        item {
            if (mangaList.loadState.prepend is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }

        items(mangaList) { initialManga ->
            val pair by initialManga?.collectAsState() ?: return@items
            val manga = pair.first
            // SY -->
            val metadata = pair.second
            // SY <--
            BrowseSourceEHentaiListItem(
                manga = manga,
                // SY -->
                metadata = metadata,
                // SY <--
                onClick = { onMangaClick(manga) },
                onLongClick = { onMangaLongClick(manga) },
            )
        }

        item {
            if (mangaList.loadState.refresh is LoadState.Loading || mangaList.loadState.append is LoadState.Loading) {
                BrowseSourceLoadingItem()
            }
        }
    }
}

@Composable
fun BrowseSourceEHentaiListItem(
    manga: Manga,
    // SY -->
    metadata: RaisedSearchMetadata?,
    // SY <--
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = onClick,
) {
    if (metadata !is EHentaiSearchMetadata) return
    val overlayColor = MaterialTheme.colorScheme.background.copy(alpha = 0.66f)

    val resources = LocalContext.current.resources
    val languageText by produceState("", metadata) {
        value = withIOContext {
            val locale = SourceTagsUtil.getLocaleSourceUtil(
                metadata.tags
                    .firstOrNull { it.namespace == EHentaiSearchMetadata.EH_LANGUAGE_NAMESPACE }
                    ?.name,
            )
            val pageCount = metadata.length
            if (locale != null && pageCount != null) {
                resources.getQuantityString(R.plurals.browse_language_and_pages, pageCount, pageCount, locale.toLanguageTag().uppercase())
            } else if (pageCount != null) {
                resources.getQuantityString(R.plurals.num_pages, pageCount, pageCount)
            } else {
                locale?.toLanguageTag()?.uppercase().orEmpty()
            }
        }
    }
    val datePosted by produceState("", metadata) {
        value = withIOContext {
            runCatching { metadata.datePosted?.let { MetadataUtil.EX_DATE_FORMAT.format(Date(it)) } }
                .getOrNull()
                .orEmpty()
        }
    }
    val genre by produceState<Pair<GenreColor, Int>?>(null, metadata) {
        value = withIOContext {
            when (metadata.genre) {
                "doujinshi" -> GenreColor.DOUJINSHI_COLOR to R.string.doujinshi
                "manga" -> GenreColor.MANGA_COLOR to R.string.entry_type_manga
                "artistcg" -> GenreColor.ARTIST_CG_COLOR to R.string.artist_cg
                "gamecg" -> GenreColor.GAME_CG_COLOR to R.string.game_cg
                "western" -> GenreColor.WESTERN_COLOR to R.string.western
                "non-h" -> GenreColor.NON_H_COLOR to R.string.non_h
                "imageset" -> GenreColor.IMAGE_SET_COLOR to R.string.image_set
                "cosplay" -> GenreColor.COSPLAY_COLOR to R.string.cosplay
                "asianporn" -> GenreColor.ASIAN_PORN_COLOR to R.string.asian_porn
                "misc" -> GenreColor.MISC_COLOR to R.string.misc
                else -> null
            }
        }
    }
    val rating by produceState(0f, metadata) {
        value = withIOContext {
            val rating = metadata.averageRating?.toFloat()
            rating?.div(0.5F)?.floor()?.let { 0.5F.times(it) } ?: 0f
        }
    }

    Row(
        modifier = Modifier
            .height(148.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            MangaCover.Book(
                modifier = Modifier
                    .fillMaxHeight()
                    .drawWithContent {
                        drawContent()
                        if (manga.favorite) {
                            drawRect(overlayColor)
                        }
                    },
                data = manga.thumbnailUrl,
            )
            if (manga.favorite) {
                BadgeGroup(
                    modifier = Modifier
                        .padding(4.dp)
                        .align(Alignment.TopStart),
                ) {
                    Badge(stringResource(R.string.in_library))
                }
            }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = manga.title,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    overflow = TextOverflow.Ellipsis,
                )
                metadata.uploader?.let {
                    Text(
                        text = it,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp, start = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    RatingBar(
                        value = rating,
                        onValueChange = {},
                        onRatingChanged = {},
                        config = RatingBarConfig().apply {
                            isIndicator(true)
                            numStars(5)
                            size(18.dp)
                            activeColor(Color(0xFF005ED7))
                            inactiveColor(Color(0xE1E2ECFF))
                        },
                    )
                    val color = genre?.first?.color
                    val res = genre?.second
                    Card(
                        colors = if (color != null) {
                            CardDefaults.cardColors(Color(color))
                        } else {
                            CardDefaults.cardColors()
                        },
                    ) {
                        Text(
                            text = if (res != null) {
                                stringResource(res)
                            } else {
                                metadata.genre.orEmpty()
                            },
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        languageText,
                        maxLines = 1,
                        fontSize = 14.sp,
                    )
                    Text(
                        datePosted,
                        maxLines = 1,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
