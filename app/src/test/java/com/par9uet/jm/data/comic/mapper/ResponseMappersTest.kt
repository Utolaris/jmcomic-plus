package com.par9uet.jm.data.comic.mapper

import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 分页契约的协议解析：合法值必须正确；畸形输入必须失败，不得降级成 0 / null。
 */
class ResponseMappersTest {
    @Test
    fun `search page keeps numeric redirect as comic id`() {
        val page = listResponse(redirectAid = "4242").toComicSearchPage()

        assertEquals(4242, page.redirectComicId)
        assertEquals(7, page.total)
    }

    @Test
    fun `search page treats absent redirect as no redirect`() {
        assertNull(listResponse(redirectAid = null).toComicSearchPage().redirectComicId)
    }

    @Test
    fun `search page treats blank redirect as no redirect`() {
        assertNull(listResponse(redirectAid = "").toComicSearchPage().redirectComicId)
        assertNull(listResponse(redirectAid = "   ").toComicSearchPage().redirectComicId)
    }

    @Test
    fun `search page fails on non-blank malformed redirect`() {
        assertThrows(NumberFormatException::class.java) {
            listResponse(redirectAid = "not-a-number").toComicSearchPage()
        }
    }

    @Test
    fun `search page fails on malformed total`() {
        assertThrows(NumberFormatException::class.java) {
            listResponse(total = "").toComicSearchPage()
        }
        assertThrows(NumberFormatException::class.java) {
            listResponse(total = "abc").toComicSearchPage()
        }
    }

    @Test
    fun `comment page parses numeric total`() {
        assertEquals(31, commentListResponse(total = "31").toCommentPage().total)
    }

    @Test
    fun `comment page fails on malformed total`() {
        assertThrows(NumberFormatException::class.java) {
            commentListResponse(total = "").toCommentPage()
        }
        assertThrows(NumberFormatException::class.java) {
            commentListResponse(total = "abc").toCommentPage()
        }
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
