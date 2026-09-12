package com.par9uet.jm.repository

import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.ComicPageList
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.data.models.HomeComicSwiperItem
import com.par9uet.jm.data.models.WeekData

/**
 * 漫画数据仓库（L3 边界）。
 *
 * 这里**只出现领域类型**：`retrofit/model` 的 wire DTO 由 `repository/impl` 内部消费，
 * 映射统一走 `data/comic/mapper`。表现层因此不需要认识任何 `*Response`，
 * 也就不需要在 ViewModel / PagingSource 里重复调用 mapper。
 */
interface ComicRepository {
    suspend fun getComicDetail(id: Int): NetWorkResult<Comic>

    suspend fun collectComic(id: Int): NetWorkResult<Unit>

    suspend fun unCollectComic(id: Int): NetWorkResult<Unit>

    /** Embedded API: load one Home category lazily. */
    suspend fun getEmbeddedHomeCategory(categoryId: String): NetWorkResult<List<Comic>>

    /** Optional network /promote page; each response section is a Home category. */
    suspend fun getNetworkHomePage(): NetWorkResult<List<HomeComicSwiperItem>>

    suspend fun getComicPicList(id: Int): NetWorkResult<ComicPageList>

    suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray?

    suspend fun getComicList(
        page: Int,
        order: ComicSearchOrderFilter,
        searchContent: String,
    ): NetWorkResult<ComicSearchPage>

    suspend fun getWeekData(): NetWorkResult<WeekData>

    suspend fun getWeekRecommendComicList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetWorkResult<ComicPage>

    suspend fun getCommentList(
        page: Int,
        comicId: Int,
    ): NetWorkResult<CommentPage>

    suspend fun comment(
        content: String,
        comicId: Int,
        commentId: Int?
    ): NetWorkResult<ActionResult>

    /**
     * 通过 JMComic 内置 API 按标签名搜索，返回该标签下的漫画 ID 集合。
     * 用于标签排除：获取所有排除标签下的漫画 ID 并集，从搜索结果中过滤掉。
     *
     * @param tagName 标签名（如 "催眠"）
     * @param maxPages 最多扫描的页数（每页约 20 条），默认 5 页
     * @return 该标签下的漫画 ID 集合；标签不存在或网络错误时返回空集合
     */
    suspend fun getComicIdsByTag(tagName: String, maxPages: Int = 5): Set<Int>
}
