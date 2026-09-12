package com.par9uet.jm.data.comic.mapper

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.Comment
import com.par9uet.jm.data.models.HomeComicSwiperItem
import com.par9uet.jm.data.models.WeekData
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryCommentListResponse
import com.par9uet.jm.retrofit.model.WeekResponse
import com.par9uet.jm.retrofit.model.WeekRecommendComicResponse
import com.par9uet.jm.utils.translateCommentTime

/**
 * Response -> domain mappers. They live on the data side so that `retrofit/model`
 * stays free of domain types and the data -> retrofit dependency remains one-way.
 */

internal fun ComicDetailResponse.toComic(): Comic {
    return Comic(
        id = id,
        name = name,
        authorList = author,
        description = description,
        readCount = total_views,
        likeCount = likes,
        commentCount = comment_total,
        tagList = tags,
        roleList = actors,
        workList = works,
        isCollect = is_favorite,
        relateComicList = related_list.map {
            Comic.create(
                it.id.toInt(),
                it.name,
                listOf(it.author)
            )
        },
        comicChapterList = series.map { ComicChapter(it.id.toInt(), it.name) },
        seriesId = series_id,
        price = price.toIntOrNull() ?: 0,
        isBuy = purchased
    )
}

internal fun ComicListResponse.toComicList(): List<Comic> {
    return content.map {
        Comic(
            id = it.id.toInt(),
            name = it.name,
            authorList = listOf(it.author),
            description = it.description ?: "",
            readCount = 0,
            likeCount = 0,
            commentCount = 0,
            tagList = if (!it.tags.isNullOrEmpty()) {
                it.tags.filter { t -> t.isNotBlank() }.distinct()
            } else {
                listOfNotNull(
                    it.category.title,
                    it.category_sub.title
                ).filter { title -> title.isNotBlank() }.distinct()
            },
            roleList = listOf(),
            workList = listOf(),
            isCollect = false,
            relateComicList = listOf(),
            comicChapterList = listOf(),
            price = 0,
            isBuy = false,
        )
    }
}

internal fun CommentListResponse.toCommentList(): List<Comment> {
    return list.map { it.toComment() }
}

private fun CommentListResponse.ListItem.toComment(): Comment = Comment(
    userId = UID.toIntOrZero(),
    comicId = AID.toIntOrZero(),
    id = CID.toIntOrZero(),
    time = translateCommentTime(addtime.orEmpty()),
    content = content.orEmpty(),
    username = username.orEmpty(),
    nickname = nickname.orEmpty().ifBlank { username.orEmpty() },
    avatar = photo.orEmpty(),
    parentId = parent_CID.toIntOrZero(),
    spoiler = spoiler == "1",
    replyCommentList = replys?.map { it.toComment() } ?: listOf(),
    sourceComicName = name.orEmpty(),
    sourceChapterId = BID.orEmpty(),
    sourceBlogId = BID.orEmpty()
)

internal fun HomeSwiperComicListItemResponse.ListItem.toComic(): Comic {
    return Comic(
        id = id.toInt(),
        name = name,
        authorList = listOf(author),
        description = description ?: "",
        readCount = 0,
        likeCount = 0,
        commentCount = 0,
        tagList = listOf(),
        roleList = listOf(),
        workList = listOf(),
        isCollect = false,
        relateComicList = listOf(),
        comicChapterList = listOf(),
        price = 0,
        isBuy = false,
    )
}

internal fun HomeSwiperComicListItemResponse.toHomeComicSwiperItem(): HomeComicSwiperItem {
    return HomeComicSwiperItem(
        id = id,
        title = title,
        list = content.map { it.toComic() }
    )
}

internal fun UserHistoryComicListResponse.toComicList(): List<Comic> {
    return list.map {
        Comic(
            id = it.id.toInt(),
            name = it.name,
            authorList = listOf(it.author),
            description = it.description ?: "",
            readCount = 0,
            likeCount = 0,
            commentCount = 0,
            tagList = listOfNotNull(
                it.category.title,
                it.category_sub.title
            ).filter { title -> title.isNotBlank() }.distinct(),
            roleList = listOf(),
            workList = listOf(),
            isCollect = false,
            relateComicList = listOf(),
            comicChapterList = listOf(),
            price = 0,
            isBuy = false,
        )
    }
}

internal fun UserHistoryCommentListResponse.toCommentList(): List<Comment> {
    return list.map {
        val username = it.username.orEmpty()
        val nickname = it.nickname.orEmpty().ifBlank { username }
        Comment(
            userId = it.UID.toIntOrZero(),
            comicId = it.AID.toIntOrZero(),
            id = it.CID.toIntOrZero(),
            time = translateCommentTime(it.addtime.orEmpty()),
            content = it.content.orEmpty(),
            username = username,
            nickname = nickname,
            avatar = it.photo.orEmpty(),
            parentId = it.parent_CID.toIntOrZero(),
            spoiler = it.spoiler == "1",
            replyCommentList = listOf(),
            sourceComicName = it.name.orEmpty(),
            sourceChapterId = it.BID.orEmpty(),
            sourceBlogId = it.BID.orEmpty()
        )
    }
}

internal fun WeekResponse.toWeekData() = WeekData(
    categoryList = categories.map { it.id to it.time },
    typeList = type.map { it.id to it.title }
)

internal fun WeekRecommendComicResponse.toComicList(): List<Comic> {
    return list.map {
        Comic(
            id = it.id.toInt(),
            name = it.name,
            authorList = listOf(it.author),
            description = it.description ?: "",
            readCount = 0,
            likeCount = 0,
            commentCount = 0,
            tagList = listOfNotNull(
                it.category.title,
                it.category_sub.title
            ).filter { title -> title.isNotBlank() }.distinct(),
            roleList = listOf(),
            workList = listOf(),
            isCollect = false,
            relateComicList = listOf(),
            comicChapterList = listOf(),
            price = 0,
            isBuy = false,
        )
    }
}

private fun String?.toIntOrZero(): Int = this?.toIntOrNull() ?: 0
