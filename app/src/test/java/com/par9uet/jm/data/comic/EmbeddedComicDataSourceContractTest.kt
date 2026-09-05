package com.par9uet.jm.data.comic

import com.par9uet.jm.data.comic.mapper.toComicDetailResponse
import com.par9uet.jm.data.comic.mapper.toComicListResponse
import com.par9uet.jm.data.comic.mapper.toContentListItem
import com.par9uet.jm.data.comic.mapper.toHomeListItem
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta
import io.github.jukomu.jmcomic.api.model.JmPhotoMeta
import io.github.jukomu.jmcomic.api.model.JmSearchPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedComicDataSourceContractTest {
    private val category = JmCategoryMeta("2", "分类")
    private val meta = JmAlbumMeta(
        "123", "漫画标题", listOf("作者"), listOf("标签"),
        "简介", "https://img.example/cover.jpg", category, null,
    )

    @Test
    fun `search and home mappings preserve ids text image and categories`() {
        val search = JmSearchPage(2, 1, 1, listOf(meta))

        val searchResponse = search.toComicListResponse("关键词")
        val content = searchResponse.content.single()
        val home = meta.toHomeListItem()

        assertEquals("关键词", searchResponse.search_query)
        assertEquals("123", content.id)
        assertEquals("漫画标题", content.name)
        assertEquals("简介", content.description)
        assertEquals("https://img.example/cover.jpg", content.image)
        assertEquals(category.id(), content.category.id)
        assertEquals(category.title(), home.category.title)
    }

    @Test
    fun `detail mapping preserves series related metadata and display counts`() {
        val album = JmAlbum(
            "123", "漫画标题", "简介", "7", "2026", 10, "1.2K", "3.4M", 9,
            "https://img.example/cover.jpg", category, category,
            listOf("作者"), listOf("作品"), listOf("角色"), listOf("标签"),
            listOf(meta), listOf(JmPhotoMeta("1", "第一章", 0)),
            "456", true, false, false, emptyList(), "12", "true",
        )

        val detail = album.toComicDetailResponse()

        assertEquals(123, detail.id)
        assertEquals("漫画标题", detail.name)
        assertEquals(1_200, detail.likes)
        assertEquals(3_400_000, detail.total_views)
        assertTrue(detail.is_favorite)
        assertEquals("456", detail.series_id)
        assertEquals("第一章", detail.series.single().name)
        assertEquals("123", detail.related_list.single().id)
    }

    @Test
    fun `content mapping uses empty defaults for absent categories`() {
        val withoutCategory = JmAlbumMeta(
            "99", "无分类", emptyList(), emptyList(), "", "", null, null,
        )

        val item = withoutCategory.toContentListItem()

        assertEquals("99", item.id)
        assertEquals("", item.author)
        assertEquals(null, item.category.id)
        assertEquals(null, item.category_sub.title)
    }
}
