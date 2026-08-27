package com.par9uet.jm.favorites.data

import com.par9uet.jm.store.UserManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class FavoriteSessionSnapshot(
    val accountId: Int,
    val generation: Long,
)

/** The authenticated identity boundary used by Favorites mutations. */
interface FavoriteSession {
    val accountIdFlow: Flow<Int>

    fun currentAccountId(): Int

    fun snapshot(): FavoriteSessionSnapshot

    fun isCurrent(snapshot: FavoriteSessionSnapshot): Boolean

    suspend fun <T> withCurrentSession(
        snapshot: FavoriteSessionSnapshot,
        block: suspend () -> T,
    ): T?
}

class UserManagerFavoriteSession(
    private val userManager: UserManager,
) : FavoriteSession {
    override val accountIdFlow: Flow<Int> = userManager.userState.map { it.data?.id ?: 0 }

    override fun currentAccountId(): Int = userManager.userState.value.data?.id ?: 0

    override fun snapshot(): FavoriteSessionSnapshot = userManager.currentSessionSnapshot().let {
        FavoriteSessionSnapshot(accountId = it.accountId, generation = it.generation)
    }

    override fun isCurrent(snapshot: FavoriteSessionSnapshot): Boolean =
        userManager.isCurrentSession(
            accountId = snapshot.accountId,
            generation = snapshot.generation,
        )

    override suspend fun <T> withCurrentSession(
        snapshot: FavoriteSessionSnapshot,
        block: suspend () -> T,
    ): T? = userManager.withCurrentSession(
        accountId = snapshot.accountId,
        generation = snapshot.generation,
        block = block,
    )
}
