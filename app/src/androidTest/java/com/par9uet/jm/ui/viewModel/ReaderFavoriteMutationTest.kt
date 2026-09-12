package com.par9uet.jm.ui.viewModel

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.usecase.CollectFavorite
import com.par9uet.jm.favorites.usecase.UncollectFavorites
import com.par9uet.jm.reader.ReaderImagePipeline
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.storage.ReadHistoryStorage
import com.par9uet.jm.storage.SecureStorage
import com.par9uet.jm.storage.ReadHistoryManager
import com.par9uet.jm.storage.ReaderPreferences
import com.par9uet.jm.core.ToastManager
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

class ReaderFavoriteMutationTest {
    @Test fun readerUpdatesLocalFavoritesAndRejectsDuplicateOrStaleActions() = runBlocking {
        val identity = MutableStateFlow(FavoriteSessionSnapshot(42, 0))
        val session = object : FavoriteSession {
            override val sessionFlow = identity
            override val accountIdFlow = identity.map { it.accountId }
            override fun currentAccountId() = identity.value.accountId
            override fun snapshot() = identity.value
            override fun isCurrent(snapshot: FavoriteSessionSnapshot) = snapshot == identity.value
            override suspend fun <T> withCurrentSession(snapshot: FavoriteSessionSnapshot, block: suspend () -> T): T? =
                if (isCurrent(snapshot)) block() else null
        }
        val local = mutableSetOf<Pair<Int, Int>>()
        var toggles = 0
        var switchAccount = false
        val remote = proxy<FavoriteRemoteMutation> { _, _ ->
            toggles++
            if (switchAccount) identity.value = FavoriteSessionSnapshot(43, 1)
            NetWorkResult.Success(Unit)
        }
        val mutations = proxy<FavoriteLocalMutation> { name, args ->
            when (name) {
                "addFromComic" -> local += (args[0] as Int) to (args[1] as Comic).id
                "remove" -> (args[1] as Collection<*>).forEach { local -= (args[0] as Int) to (it as Int) }
                else -> error("Unexpected mutation: $name")
            }
            Unit
        }
        val repository = proxy<ComicRepository> { name, _ ->
            check(name == "getComicDetail") { "Reader bypassed favorite use cases: $name" }
            NetWorkResult.Success(ComicDetailResponse(
                id = 11, name = "Reader test", description = "", author = emptyList(), total_views = 0,
                likes = 0, comment_total = 0, tags = emptyList(), actors = emptyList(), works = emptyList(),
                is_favorite = false, related_list = emptyList(), series = emptyList(), series_id = "", price = "0", purchased = false,
            ))
        }
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("reader-mutation-test-$name", mode)
        }
        val koin = GlobalContext.get()
        val viewModel = ComicReadViewModel(
            repository, koin.get<ReaderImagePipeline>(), koin.get<ReaderPreferences>(),
            com.par9uet.jm.reader.molecule.LoadLocalChapter(
                proxy<DownloadComicDao> { _, _ -> error("No download query expected") },
                com.par9uet.jm.reader.atom.LocalChapterFiles { _, _ -> error("No local read expected") },
            ),
            ToastManager(), ReadHistoryManager(ReadHistoryStorage(SecureStorage(context))),
            session, CollectFavorite(remote, mutations, session), UncollectFavorites(remote, mutations, session),
        )
        withContext(Dispatchers.Main) {
            viewModel.getComicDetail(11)
            viewModel.collect(11)
            viewModel.collect(11)
            assertEquals(setOf(42 to 11), local)
            assertTrue(viewModel.comicDetailState.value.data!!.isCollect)
            assertEquals(1, toggles)
            viewModel.unCollect(11)
            assertTrue(local.isEmpty())
            assertFalse(viewModel.comicDetailState.value.data!!.isCollect)
            assertEquals(2, toggles)
            switchAccount = true
            viewModel.collect(11)
            assertTrue(local.isEmpty())
            assertFalse(viewModel.comicDetailState.value.data!!.isCollect)
        }
    }

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args.orEmpty())
        } as T
}
