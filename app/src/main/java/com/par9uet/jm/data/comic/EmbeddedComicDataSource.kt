package com.par9uet.jm.data.comic

import com.par9uet.jm.data.comic.mapper.toCommentListItem
import com.par9uet.jm.data.comic.mapper.toComicDetailResponse
import com.par9uet.jm.data.comic.mapper.toComicListResponse
import com.par9uet.jm.data.comic.mapper.toContentListItem
import com.par9uet.jm.data.comic.mapper.toHomeListItem
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.BaseRepository
import com.par9uet.jm.repository.impl.AuthenticatedEmbeddedClient
import com.par9uet.jm.repository.impl.EmbeddedClientManager
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.WeekRecommendComicResponse
import com.par9uet.jm.retrofit.model.WeekResponse
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import io.github.jukomu.jmcomic.api.enums.Category
import io.github.jukomu.jmcomic.api.enums.FavoriteFolderType
import io.github.jukomu.jmcomic.api.enums.ForumMode
import io.github.jukomu.jmcomic.api.enums.OrderBy
import io.github.jukomu.jmcomic.api.enums.SearchMainTag
import io.github.jukomu.jmcomic.api.enums.TimeOption
import io.github.jukomu.jmcomic.api.model.ForumQuery
import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.api.model.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface ComicEmbeddedDataSource {
    suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse>
    suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse>
    suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse>
    suspend fun getHomeCategory(
        categoryId: String,
    ): NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>>

    suspend fun getComicPicList(id: Int): NetWorkResult<ComicPicListResponse>
    suspend fun getComicList(
        page: Int,
        order: ComicSearchOrderFilter,
        searchContent: String,
    ): NetWorkResult<ComicListResponse>

    suspend fun getWeekData(): NetWorkResult<WeekResponse>
    suspend fun getWeekRecommendComicList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetWorkResult<WeekRecommendComicResponse>

    suspend fun getCommentList(page: Int, comicId: Int): NetWorkResult<CommentListResponse>
    suspend fun comment(
        content: String,
        comicId: Int,
        commentId: Int?,
    ): NetWorkResult<CommentComicResponse>

    suspend fun createFavoriteFolder(name: String): NetWorkResult<Unit>
    suspend fun deleteFavoriteFolder(folderId: String): NetWorkResult<Unit>
    suspend fun renameFavoriteFolder(folderId: String, newName: String): NetWorkResult<Unit>
    suspend fun moveComicToFolder(comicId: Int, folderId: String): NetWorkResult<Unit>
    suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int>
    suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray?
}

class EmbeddedComicDataSource(
    private val embeddedClientManager: EmbeddedClientManager,
    private val authenticatedEmbeddedClient: AuthenticatedEmbeddedClient,
) : BaseRepository(), ComicEmbeddedDataSource {
    companion object {
        private val imageCache = mutableMapOf<Int, List<JmImage>>()
        private val cleanHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    override suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse> =
        withContext(Dispatchers.IO) {
            try {
                NetWorkResult.Success(withEmbeddedClient { client ->
                    client.getAlbum(id.toString()).toComicDetailResponse()
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NetWorkResult.Error("内置 API 获取漫画详情失败：${e.message ?: "未知错误"}")
            }
        }

    override suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse> =
        withContext(Dispatchers.IO) {
            try {
                authenticatedEmbeddedClient.withClient { client ->
                    client.toggleAlbumFavorite(id.toString(), "0")
                }
                NetWorkResult.Success(CollectComicResponse(msg = "success", status = "ok", type = "collect"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NetWorkResult.Error("内置 API 收藏失败：${e.message ?: "未知错误"}")
            }
        }

    override suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse> =
        withContext(Dispatchers.IO) {
            try {
                authenticatedEmbeddedClient.withClient { client ->
                    client.toggleAlbumFavorite(id.toString(), "0")
                }
                NetWorkResult.Success(CollectComicResponse(msg = "success", status = "ok", type = "uncollect"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NetWorkResult.Error("内置 API 取消收藏失败：${e.message ?: "未知错误"}")
            }
        }

    override suspend fun getHomeCategory(
        categoryId: String,
    ): NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>> = withContext(Dispatchers.IO) {
        try {
            val client = getEmbeddedClient()
            val items = runCatchingCancellable {
                when (categoryId) {
                    "builtin_latest" ->
                        client.getLatest(1).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_week_hot" ->
                        client.getCategories(
                            SearchQuery.Builder()
                                .orderBy(OrderBy.MOST_VIEWED).time(TimeOption.WEEK).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_month_hot" ->
                        client.getCategories(
                            SearchQuery.Builder()
                                .orderBy(OrderBy.MOST_VIEWED).time(TimeOption.MONTH).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_most_liked" ->
                        client.getCategories(
                            SearchQuery.Builder()
                                .orderBy(OrderBy.MOST_LIKED).time(TimeOption.ALL).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_random" ->
                        client.getRandomRecommend().orEmpty().map { it.toHomeListItem() }

                    "builtin_doujin" ->
                        client.getCategories(
                            SearchQuery.Builder().category(Category.DOUJIN).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_single" ->
                        client.getCategories(
                            SearchQuery.Builder().category(Category.SINGLE).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_short" ->
                        client.getCategories(
                            SearchQuery.Builder().category(Category.SHORT).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_korean" ->
                        client.getCategories(
                            SearchQuery.Builder().category(Category.KOREAN).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_american" ->
                        client.getCategories(
                            SearchQuery.Builder().category(Category.AMERICAN).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_cosplay" ->
                        client.getCategories(
                            SearchQuery.Builder().category(Category.COSPLAY).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_3d" ->
                        client.getCategories(
                            SearchQuery.Builder().category(Category.IMAGE_3D).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    "builtin_most_images" ->
                        client.getCategories(
                            SearchQuery.Builder()
                                .orderBy(OrderBy.MOST_IMAGES).time(TimeOption.ALL).page(1).build()
                        ).content().orEmpty().map { it.toHomeListItem() }

                    else -> throw IllegalArgumentException("未知首页分类: $categoryId")
                }
            }.getOrElse { throw it }
            NetWorkResult.Success(items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetWorkResult.Error("内置 API 获取首页分类失败：${e.message ?: "未知错误"}")
        }
    }

    override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPicListResponse> =
        withContext(Dispatchers.IO) {
            try {
                withEmbeddedClient { client ->
                    val photo = try {
                        client.getPhoto(id.toString())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val images = photo?.images()?.takeIf { it.isNotEmpty() }
                        ?: client.getComicRead(id.toString()).images().orEmpty()
                    if (images.isEmpty()) {
                        NetWorkResult.Error("内置 API 未返回图片列表")
                    } else {
                        synchronized(imageCache) { imageCache[id] = images }
                        NetWorkResult.Success(
                            ComicPicListResponse(
                                list = images.map { fixImageUrl(it.getDownloadUrl()) },
                                __aId = photo?.albumId()?.toIntOrNull() ?: id,
                                __scrambleId = photo?.scrambleId()?.toIntOrNull()
                                    ?: images.firstOrNull()?.scrambleId()?.toIntOrNull()
                                    ?: 0,
                                __speed = "0",
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NetWorkResult.Error("内置 API 获取图片列表失败：${e.message ?: "未知错误"}")
            }
        }

    override suspend fun getComicList(
        page: Int,
        order: ComicSearchOrderFilter,
        searchContent: String,
    ): NetWorkResult<ComicListResponse> = withContext(Dispatchers.IO) {
        try {
            NetWorkResult.Success(withEmbeddedClient { client ->
                val query = SearchQuery.Builder()
                    .text(searchContent)
                    .page(page)
                    .orderBy(order.toEmbeddedOrderBy())
                    .build()
                client.search(query).toComicListResponse(searchContent)
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetWorkResult.Error("内置 API 搜索漫画失败：${e.message ?: "未知错误"}")
        }
    }

    override suspend fun getWeekData(): NetWorkResult<WeekResponse> = withContext(Dispatchers.IO) {
        try {
            NetWorkResult.Success(withEmbeddedClient { client ->
                val picks = client.getWeeklyPicksList()
                WeekResponse(
                    categories = picks.categories.map { category ->
                        WeekResponse.CategoryItem(
                            id = category.id(),
                            time = category.time(),
                            title = category.title(),
                        )
                    },
                    type = picks.type.map { type ->
                        WeekResponse.TypeItem(id = type.id(), title = type.title())
                    },
                )
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetWorkResult.Error("内置 API 获取周刊数据失败：${e.message ?: "未知错误"}")
        }
    }

    override suspend fun getWeekRecommendComicList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetWorkResult<WeekRecommendComicResponse> = withContext(Dispatchers.IO) {
        try {
            NetWorkResult.Success(withEmbeddedClient { client ->
                val detail = client.getWeeklyPicksDetail(categoryId)
                WeekRecommendComicResponse(
                    total = detail.list.size,
                    list = detail.list.map { albumMeta ->
                        WeekRecommendComicResponse.ListItem(
                            id = albumMeta.id(),
                            author = albumMeta.authors().joinToString(", "),
                            description = albumMeta.description(),
                            name = albumMeta.title(),
                            image = albumMeta.image() ?: "",
                            category = WeekRecommendComicResponse.ListItem.Category(
                                id = albumMeta.category()?.id(),
                                title = albumMeta.category()?.title(),
                            ),
                            category_sub = WeekRecommendComicResponse.ListItem.Category(
                                id = albumMeta.subCategory()?.id(),
                                title = albumMeta.subCategory()?.title(),
                            ),
                            is_favorite = false,
                            update_at = 0,
                        )
                    },
                )
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetWorkResult.Error("内置 API 获取周刊详情失败：${e.message ?: "未知错误"}")
        }
    }

    override suspend fun getCommentList(
        page: Int,
        comicId: Int,
    ): NetWorkResult<CommentListResponse> = withContext(Dispatchers.IO) {
        try {
            NetWorkResult.Success(withEmbeddedClient { client ->
                val query = ForumQuery.album(comicId.toString())
                    .mode(ForumMode.ALL)
                    .page(page)
                    .build()
                val commentList = client.getComments(query)
                CommentListResponse(
                    list = commentList.list.map { it.toCommentListItem() },
                    total = commentList.total.toString(),
                )
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetWorkResult.Error("内置 API 获取评论列表失败：${e.message ?: "未知错误"}")
        }
    }

    override suspend fun comment(
        content: String,
        comicId: Int,
        commentId: Int?,
    ): NetWorkResult<CommentComicResponse> = withContext(Dispatchers.IO) {
        try {
            // Upstream 1.1.8 throws after a successful POST when a restored session has no
            // in-memory username; withClient maps that exact failure to success (null result).
            authenticatedEmbeddedClient.withClient { client ->
                if (commentId != null) {
                    client.replyToComment(comicId.toString(), content, commentId.toString())
                } else {
                    client.postComment(comicId.toString(), content)
                }
            }
            NetWorkResult.Success(
                CommentComicResponse(
                    msg = "success",
                    status = "ok",
                    aid = comicId,
                    cid = 0,
                    spoiler = "0",
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetWorkResult.Error("内置 API 评论失败：${e.message ?: "未知错误"}")
        }
    }

    override suspend fun createFavoriteFolder(name: String): NetWorkResult<Unit> =
        manageFavoriteFolder(FavoriteFolderType.ADD, "0", name, "创建收藏夹失败")

    override suspend fun deleteFavoriteFolder(folderId: String): NetWorkResult<Unit> =
        manageFavoriteFolder(FavoriteFolderType.DELETE, folderId, "", "删除收藏夹失败")

    override suspend fun renameFavoriteFolder(
        folderId: String,
        newName: String,
    ): NetWorkResult<Unit> = manageFavoriteFolder(
        FavoriteFolderType.EDIT,
        folderId,
        newName,
        "重命名收藏夹失败",
    )

    override suspend fun moveComicToFolder(
        comicId: Int,
        folderId: String,
    ): NetWorkResult<Unit> = manageFavoriteFolder(
        FavoriteFolderType.MOVE,
        folderId,
        "",
        "移动漫画到收藏夹失败",
        comicId.toString(),
    )

    override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> {
        if (tagName.isBlank()) return emptySet()
        return withContext(Dispatchers.IO) {
            try {
                val client = getEmbeddedClient()
                val ids = mutableSetOf<Int>()
                for (page in 1..maxPages.coerceAtLeast(1)) {
                    val query = SearchQuery.Builder()
                        .text(tagName)
                        .mainTag(SearchMainTag.TAG)
                        .page(page)
                        .build()
                    val result = try {
                        client.search(query)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logError("EmbeddedComicDataSource", "搜索标签 [$tagName] 第${page}页失败：${e.message}")
                        break
                    }
                    val content = result.content().orEmpty()
                    if (content.isEmpty()) break
                    content.forEach { meta ->
                        meta.id().toIntOrNull()?.let { ids += it }
                    }
                    if (result.totalItems() <= page * 20) break
                    if (page < maxPages) delay(150)
                }
                log("EmbeddedComicDataSource", "标签 [$tagName] 获取到 ${ids.size} 个漫画ID")
                ids
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("EmbeddedComicDataSource", "获取标签 [$tagName] 漫画ID失败：${e.message}")
                emptySet()
            }
        }
    }

    override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? {
        val image = synchronized(imageCache) { imageCache[comicId]?.getOrNull(imageIndex) } ?: return null
        val imageUrl = fixImageUrl(image.getDownloadUrl())
        return withContext(Dispatchers.IO) {
            try {
                logError("EmbeddedComicDataSource", "下载图片 comicId=$comicId index=$imageIndex URL=$imageUrl")
                cleanHttpClient.newCall(buildImageRequest(imageUrl)).execute().use { response ->
                    if (!response.isSuccessful) {
                        logError(
                            "EmbeddedComicDataSource",
                            "下载图片失败 comicId=$comicId index=$imageIndex: HTTP ${response.code} URL=$imageUrl",
                        )
                        return@withContext null
                    }
                    response.body?.bytes()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(
                    "EmbeddedComicDataSource",
                    "下载图片异常 comicId=$comicId index=$imageIndex: ${e.message} URL=$imageUrl",
                )
                null
            }
        }
    }

    private suspend fun manageFavoriteFolder(
        type: FavoriteFolderType,
        folderId: String,
        name: String,
        operation: String,
        comicId: String = "",
    ): NetWorkResult<Unit> = withContext(Dispatchers.IO) {
        try {
            authenticatedEmbeddedClient.withClient { client ->
                client.manageFavoriteFolder(type, folderId, name, comicId)
            }
            NetWorkResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("EmbeddedComicDataSource", "$operation：${e.message}")
            NetWorkResult.Error("内置API$operation：${e.message ?: "未知错误"}")
        }
    }

    private fun ComicSearchOrderFilter.toEmbeddedOrderBy(): OrderBy = when (this) {
        ComicSearchOrderFilter.NEWEST -> OrderBy.LATEST
        ComicSearchOrderFilter.MOST_COLLECT_COUNT -> OrderBy.MOST_VIEWED
        ComicSearchOrderFilter.MOST_PIC_COUNT -> OrderBy.MOST_IMAGES
        ComicSearchOrderFilter.MOST_LIKE_COUNT -> OrderBy.MOST_LIKED
    }

    private fun getEmbeddedClient() = embeddedClientManager.getClient()

    private fun <T> withEmbeddedClient(block: (io.github.jukomu.jmcomic.core.client.impl.JmApiClient) -> T): T =
        block(getEmbeddedClient())

    private fun fixImageUrl(url: String): String {
        val secondHttps = url.indexOf("https://", 8)
        return if (secondHttps > 0) url.substring(secondHttps) else url
    }

    private fun buildImageRequest(url: String): Request = Request.Builder()
        .url(url)
        .get()
        .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 " +
                "Safari/537.36",
        )
        .header("Referer", "https://18comic.vip")
        .build()
}
