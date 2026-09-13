package com.par9uet.jm.data.comic.mapper

import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.ComicPageList
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.Comment
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.data.models.HomeComicSwiperItem
import com.par9uet.jm.data.models.WeekData
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
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

/**
 * 分页/回执类响应 -> 领域契约。
 *
 * 这些映射原先散在 `ui/pagingSource` 与 `ui/viewModel` 里（调用方各自调 `toComicList()`），
 * 会让 wire DTO 一路渗到表现层。收口到这里后，仓库层返回的就是领域类型。
 */

internal fun ComicListResponse.toComicSearchPage(): ComicSearchPage {
    val redirect = redirect_aid
    return ComicSearchPage(
        items = toComicList(),
        // total 是必填协议字段：非数字直接失败，不降级成 0。
        total = total.toInt(),
        redirectComicId = when {
            // 协议允许未命中时不带 redirect_aid（null / blank）。
            redirect.isNullOrBlank() -> null
            // 非空且非数字属协议畸形，必须失败，不能伪装成"不重定向"。
            else -> redirect.toInt()
        },
    )
}

internal fun CommentListResponse.toCommentPage(): CommentPage {
    // total 是必填协议字段：非数字直接失败，不降级成 0（0 会被当成末页）。
    return CommentPage(items = toCommentList(), total = total.toInt())
}

internal fun UserHistoryCommentListResponse.toCommentPage(): CommentPage {
    return CommentPage(items = toCommentList(), total = total)
}

/**
 * watch_list 不返回 total，因此置 null —— 调用方按"本页是否填满页大小"判断末页，
 * 不能拿一个凑出来的数字冒充服务端总数。
 */
internal fun UserHistoryComicListResponse.toComicPage(): ComicPage {
    return ComicPage(items = toComicList(), total = null)
}

internal fun WeekRecommendComicResponse.toComicPage(): ComicPage {
    return ComicPage(items = toComicList(), total = total)
}

internal fun ComicPicListResponse.toComicPageList(): ComicPageList {
    return ComicPageList(
        urls = list,
        albumId = __aId,
        scrambleId = __scrambleId,
        speed = __speed,
    )
}

/** `status` 的成功判定规则留在数据层，表现层只读 [ActionResult.isSuccess]。 */
internal fun CommentComicResponse.toActionResult(): ActionResult {
    val normalized = status.trim()
    return ActionResult(
        isSuccess = normalized.isBlank() ||
            normalized.equals("ok", ignoreCase = true) ||
            normalized.equals("success", ignoreCase = true),
        message = msg,
    )
}
