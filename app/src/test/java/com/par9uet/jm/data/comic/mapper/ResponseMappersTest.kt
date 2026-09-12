package com.par9uet.jm.data.comic.mapper

import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 钉住分页契约里两个**服务端协议畸形**时的降级行为。
 *
 * 改动前这两处直接 `.toInt()`，非数字会抛异常；改成防御性解析后必须保证：
 * 1. 不崩溃（否则整个查询/评论列表崩掉）；
 * 2. 解析失败时该字段落到明确值（`null` / `0`），并在 mapper 里留日志。
 * 这两条是"静默降级"的边界，必须有测试，否则以后有人改回 `!!` 不会有人发现。
 */
class ResponseMappersTest {
    @Test
    fun `search page keeps numeric redirect as comic id`() {
        val page = listResponse(redirectAid = "4242").toComicSearchPage()

        assertEquals(4242, page.redirectComicId)
        assertEquals(7, page.total)
    }

    @Test
    fun `search page tolerates absent redirect`() {
        assertNull(listResponse(redirectAid = null).toComicSearchPage().redirectComicId)
    }

    @Test
    fun `search page tolerates blank redirect`() {
        assertNull(listResponse(redirectAid = "").toComicSearchPage().redirectComicId)
        assertNull(listResponse(redirectAid = "   ").toComicSearchPage().redirectComicId)
    }

    @Test
    fun `search page does not crash on malformed redirect`() {
        // 服务端给了 redirect_aid 但不可解析：降级为"不重定向"，
        // 由 mapper 打日志暴露，而不是让整次分页失败。
        assertNull(listResponse(redirectAid = "not-a-number").toComicSearchPage().redirectComicId)
    }

    @Test
    fun `search page tolerates malformed total`() {
        val page = listResponse(total = "").toComicSearchPage()

        assertEquals(0, page.total)
    }

    @Test
    fun `comment page parses numeric total`() {
        assertEquals(31, commentListResponse(total = "31").toCommentPage().total)
    }

    @Test
    fun `comment page does not crash on malformed total`() {
        // 0 会让分页在第一页就判定末页（"评论只有一页"）。
        // 这是有意的降级：宁可少翻一页，也不要整页崩溃；mapper 会打日志。
        assertEquals(0, commentListResponse(total = "").toCommentPage().total)
        assertEquals(0, commentListResponse(total = "abc").toCommentPage().total)
    }

    private fun listResponse(
        total: String = "7",
        redirectAid: String? = null,
    ): ComicListResponse = ComicListResponse(
        search_query = "query",
        total = total,
        redirect_aid = redirectAid,
        content = emptyList(),
    )

    private fun commentListResponse(total: String): CommentListResponse =
        CommentListResponse(list = emptyList(), total = total)
}
