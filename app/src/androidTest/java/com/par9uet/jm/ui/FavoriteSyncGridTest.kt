package com.par9uet.jm.ui

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.SuccessResult
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.favorites.presentation.CollectComicPagingSource
import com.par9uet.jm.store.FavoriteMetadataPayload
import com.par9uet.jm.store.FavoriteRemoteItem
import com.par9uet.jm.store.FavoriteStore
import com.par9uet.jm.ui.components.JmCoverImage
import com.par9uet.jm.ui.components.PullRefreshAndLoadMoreGrid
import com.par9uet.jm.ui.glass.GlassCaptureHost
import com.par9uet.jm.ui.screens.LocalMainNavController
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FavoriteSyncGridTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
    ).build()
    private val store = FavoriteStore(
        database, database.favoriteComicDao(), database.favoriteFolderDao(),
        database.favoriteFolderMembershipDao(), database.favoriteMetadataDao(),
        database.favoriteMetadataTermDao(), database.favoriteSyncStateDao(),
    )
    private var imageLoader: ImageLoader? = null

    @After
    fun tearDown() {
        imageLoader?.shutdown()
        database.close()
    }

    @Test
    fun syncAtTopKeepsVisibleCoversMountedWithoutNewImageRequests() {
        val item = FavoriteRemoteItem(11, "列表短标题", authors = listOf("列表作者"), description = "列表简介")
        val full = FavoriteMetadataPayload(11, "详情完整标题", "详情完整简介", listOf("详情作者"), emptyList(), emptyList(), emptyList())
        val items = (11..70).map { item.copy(albumId = it) }
        val metadata = items.map { full.copy(albumId = it.albumId) }
        runBlocking {
            store.replaceAllSnapshot(7, items, mapOf(0 to "全部"), metadata, 100, 100, emptyMap())
        }
        val sourcesCreated = AtomicInteger()
        val imageRequests = AtomicInteger()
        val coverMounts = AtomicInteger()
        val coverDisposals = AtomicInteger()
        val progress = mutableIntStateOf(0)
        val pager = Pager(PagingConfig(pageSize = 20, initialLoadSize = 20)) {
            sourcesCreated.incrementAndGet()
            CollectComicPagingSource(store.pagingSource(7, emptyList(), "", emptySet(), emptySet(), 0, TagFilterLogic.AND))
        }.flow
        val loader = ImageLoader.Builder(compose.activity).components {
            add { chain ->
                imageRequests.incrementAndGet()
                SuccessResult(ColorDrawable(android.graphics.Color.RED), chain.request, DataSource.NETWORK)
            }
        }.build()
        imageLoader = loader
        val resolver = CoverImageHostResolver(knownHosts = listOf("covers.example"))
        compose.setContent {
            val pagingItems = pager.collectAsLazyPagingItems()
            val completed = progress.intValue
            val pagerState = rememberPagerState(initialPage = 1) { 3 }
            val navController = rememberNavController()
            MaterialTheme {
                // Match the screen's changing content lambda during sync progress updates.
                val content: @Composable () -> Unit = {
                    Column {
                        Text("同步 $completed")
                        PullRefreshAndLoadMoreGrid(
                            lazyPagingItems = pagingItems,
                            key = { it.id },
                            columns = GridCells.Fixed(2),
                            enablePullRefresh = false,
                        ) { comic ->
                            DisposableEffect(comic.id) {
                                coverMounts.incrementAndGet()
                                onDispose { coverDisposals.incrementAndGet() }
                            }
                            JmCoverImage(
                                comicId = comic.id,
                                remoteHost = "covers.example",
                                contentDescription = comic.name,
                                modifier = Modifier.height(180.dp),
                                imageLoader = loader,
                                resolver = resolver,
                            )
                        }
                    }
                }
                CompositionLocalProvider(
                    LocalMainNavController provides navController,
                    LocalExtendedColors provides extendedColorSchemeFor(MaterialTheme.colorScheme, false),
                ) {
                    GlassCaptureHost(
                        modifier = Modifier.fillMaxSize(),
                        sourceContent = {
                            HorizontalPager(state = pagerState, beyondViewportPageCount = 2) { page ->
                                if (page == 1) content() else Box(Modifier.fillMaxSize())
                            }
                        },
                        overlayContent = {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) { imageRequests.get() > 0 }
        compose.waitForIdle()
        val initialMounts = coverMounts.get()
        val initialRequests = imageRequests.get()
        compose.runOnIdle { progress.intValue++ }
        compose.waitForIdle()
        assertEquals(initialMounts, coverMounts.get())
        assertEquals(initialRequests, imageRequests.get())
        runBlocking {
            store.reconcileLightweightSnapshot(7, 0, items, mapOf(0 to "全部"), 200)
            metadata.forEach { store.applyMetadata(7, it, 200) }
            store.markSyncSuccess(7, 0, 200)
        }
        compose.runOnIdle { progress.intValue++ }
        compose.waitForIdle()
        assertEquals(
            "Sync must not recreate the Room source; mounts=${coverMounts.get()}/$initialMounts, " +
                "disposals=${coverDisposals.get()}, requests=${imageRequests.get()}/$initialRequests",
            1, sourcesCreated.get(),
        )
        assertEquals("Visible covers must remain composed", 0, coverDisposals.get())
        assertEquals(initialMounts, coverMounts.get())
        assertEquals("Visible covers must keep the completed image request", initialRequests, imageRequests.get())
    }
}
