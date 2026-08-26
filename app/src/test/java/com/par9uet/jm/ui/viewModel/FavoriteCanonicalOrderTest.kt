package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.favorites.sync.FAVORITE_CANONICAL_ORDER
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteCanonicalOrderTest {
    @Test
    fun `favorite synchronization keeps collect time as compatibility order`() {
        assertEquals(CollectComicOrderFilter.COLLECT_TIME, FAVORITE_CANONICAL_ORDER)
    }
}
