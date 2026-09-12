package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.storage.ContentPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    @Before fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun FakeComicRepository(): ComicRepository = Proxy.newProxyInstance(
        ComicRepository::class.java.classLoader, arrayOf(ComicRepository::class.java),
    ) { _, method, _ -> error("Unexpected request: "+method.name) } as ComicRepository

    private class FakeSettings : ContentPreferences {
        override val blockedTags = MutableStateFlow(emptyList<String>())
        override val homeExcludedTags = MutableStateFlow(emptyList<String>())
    }

    @Test
    fun searchOrderChangeUpdatesFilterAndClearsPendingComicId() = runTest(scheduler) {
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())

        assertEquals(ComicSearchOrderFilter.NEWEST, vm.searchComicFilterState.value.order)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_COLLECT_COUNT)
        assertEquals(ComicSearchOrderFilter.MOST_COLLECT_COUNT, vm.searchComicFilterState.value.order)
        assertNull(vm.searchComicIdState.value)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_PIC_COUNT)
        assertEquals(ComicSearchOrderFilter.MOST_PIC_COUNT, vm.searchComicFilterState.value.order)
        assertNull(vm.searchComicIdState.value)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_LIKE_COUNT)
        assertEquals(ComicSearchOrderFilter.MOST_LIKE_COUNT, vm.searchComicFilterState.value.order)
        assertNull(vm.searchComicIdState.value)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.NEWEST)
        assertEquals(ComicSearchOrderFilter.NEWEST, vm.searchComicFilterState.value.order)
    }

    @Test
    fun searchOrderChangeKeepsSearchCriteria() = runTest(scheduler) {
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())
        vm.changeSearchComicContent("neko", listOf("tag1", "tag2"))

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_PIC_COUNT)

        val filter = vm.searchComicFilterState.value
        assertEquals("neko", filter.searchContent)
        assertEquals(listOf("tag1", "tag2"), filter.excludedTags)
        assertEquals(ComicSearchOrderFilter.MOST_PIC_COUNT, filter.order)
    }

    @Test
    fun returningToUnchangedSearchKeepsSavedViewport() = runTest(scheduler) {
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())
        vm.changeSearchComicContent("neko", listOf("tag1"))
        val generation = vm.searchViewportState.value.resetGeneration
        vm.saveSearchViewport(
            firstVisibleItemIndex = 42,
            firstVisibleItemScrollOffset = 96,
            resetGeneration = generation,
        )

        vm.changeSearchComicContent("neko", listOf("tag1"))

        assertEquals(42, vm.searchViewportState.value.firstVisibleItemIndex)
        assertEquals(96, vm.searchViewportState.value.firstVisibleItemScrollOffset)
        assertEquals(generation, vm.searchViewportState.value.resetGeneration)
    }

    @Test
    fun changingSearchContextResetsViewportAndRejectsStaleSaves() = runTest(scheduler) {
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())
        val oldGeneration = vm.searchViewportState.value.resetGeneration
        vm.saveSearchViewport(12, 48, oldGeneration)

        vm.changeSearchComicContent("new query", emptyList())
        val reset = vm.searchViewportState.value

        assertEquals(0, reset.firstVisibleItemIndex)
        assertEquals(0, reset.firstVisibleItemScrollOffset)
        assertTrue(reset.resetGeneration > oldGeneration)

        vm.saveSearchViewport(99, 99, oldGeneration)
        assertEquals(reset, vm.searchViewportState.value)
    }

    @Test
    fun changingSearchOrderResetsViewport() = runTest(scheduler) {
        val vm = SearchViewModel(FakeComicRepository(), FakeSettings())
        val generation = vm.searchViewportState.value.resetGeneration
        vm.saveSearchViewport(18, 72, generation)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_COLLECT_COUNT)

        assertEquals(0, vm.searchViewportState.value.firstVisibleItemIndex)
        assertEquals(0, vm.searchViewportState.value.firstVisibleItemScrollOffset)
        assertTrue(vm.searchViewportState.value.resetGeneration > generation)

        val resetGeneration = vm.searchViewportState.value.resetGeneration
        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_COLLECT_COUNT)
        assertEquals(resetGeneration, vm.searchViewportState.value.resetGeneration)
    }
}
