package com.par9uet.jm.data.comic.mapper

import com.par9uet.jm.retrofit.model.ComicDetailRelatedListItemResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicDetailSeriesListItemResponse
import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta
import io.github.jukomu.jmcomic.api.model.JmComment
import io.github.jukomu.jmcomic.api.model.JmSearchPage

internal fun JmAlbum.toComicDetailResponse(): ComicDetailResponse {
    return ComicDetailResponse(
        id = id().toIntOrNull() ?: 0,
        name = title().orEmpty(),
        description = description().orEmpty(),
        author = authors().orEmpty(),
        total_views = views().toDisplayCount(),
        likes = likes().toDisplayCount(),
        comment_total = commentCount(),
        tags = tags().orEmpty(),
        actors = actors().orEmpty(),
        works = works().orEmpty(),
        is_favorite = isFavorite,
        related_list = relatedAlbums().orEmpty().map {
            ComicDetailRelatedListItemResponse(
                id = it.id().orEmpty(),
                name = it.title().orEmpty(),
                author = it.authors().orEmpty().firstOrNull().orEmpty(),
                image = it.image().orEmpty(),
            )
        },
        series = photoMetas().orEmpty().map {
            ComicDetailSeriesListItemResponse(
                id = it.id().orEmpty(),
                name = it.title().orEmpty(),
                sort = it.sortOrder().toString(),
            )
        },
        series_id = seriesId().orEmpty(),
        price = price().orEmpty(),
        purchased = purchased().equals("true", ignoreCase = true),
    )
}

internal fun JmSearchPage.toComicListResponse(searchContent: String): ComicListResponse {
    return ComicListResponse(
        search_query = searchContent,
        total = totalItems().toString(),
        redirect_aid = null,
        content = content().orEmpty().map { it.toContentListItem() },
    )
}

internal fun JmAlbumMeta.toContentListItem(): ComicListResponse.ContentListItem {
    return ComicListResponse.ContentListItem(
        id = id().orEmpty(),
        author = authors().orEmpty().firstOrNull().orEmpty(),
        description = description(),
        name = title().orEmpty(),
        image = image().orEmpty(),
        category = category().toContentCategory(),
        category_sub = subCategory().toContentCategory(),
        is_favorite = false,
        update_at = 0,
    )
}

internal fun JmAlbumMeta.toHomeListItem(): HomeSwiperComicListItemResponse.ListItem {
    return HomeSwiperComicListItemResponse.ListItem(
        id = id().orEmpty(),
        author = authors().orEmpty().firstOrNull().orEmpty(),
        description = description(),
        name = title().orEmpty(),
        image = image().orEmpty(),
        category = category().toHomeCategory(),
        category_sub = subCategory().toHomeCategory(),
        is_favorite = false,
        update_at = 0,
    )
}

internal fun JmComment.toCommentListItem(): CommentListResponse.ListItem {
    return CommentListResponse.ListItem(
        AID = null,
        BID = commentId(),
        CID = commentId(),
        UID = userId(),
        username = username(),
        nickname = nickname(),
        likes = likes.toString(),
        gender = gender(),
        update_at = updateAt(),
        addtime = postDate(),
        parent_CID = parentCommentId(),
        name = nickname(),
        content = content(),
        photo = photo() ?: "",
        spoiler = spoiler().toString(),
        replys = replys().orEmpty().map { it.toCommentListItem() },
    )
}

private fun JmCategoryMeta?.toContentCategory(): ComicListResponse.ContentListItem.Category {
    return ComicListResponse.ContentListItem.Category(
        id = this?.id(),
        title = this?.title(),
    )
}

private fun JmCategoryMeta?.toHomeCategory(): HomeSwiperComicListItemResponse.ListItem.Category {
    return HomeSwiperComicListItemResponse.ListItem.Category(
        id = this?.id(),
        title = this?.title(),
    )
}

private fun String?.toDisplayCount(): Int {
    val text = this?.trim().orEmpty()
    if (text.isBlank()) return 0
    val multiplier = when {
        text.endsWith("K", ignoreCase = true) -> 1_000
        text.endsWith("M", ignoreCase = true) -> 1_000_000
        else -> 1
    }
    val numeric = if (multiplier == 1) text else text.dropLast(1)
    return (numeric.toDoubleOrNull()?.times(multiplier)
        ?: text.filter(Char::isDigit).toDoubleOrNull()
        ?: 0.0).toInt()
}
