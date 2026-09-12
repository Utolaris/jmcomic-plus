package com.par9uet.jm.favorites.data

import com.par9uet.jm.favorites.model.FavoriteSession
import com.par9uet.jm.favorites.model.FavoriteSessionSnapshot
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.core.network.NetWorkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserManagerFavoriteSession(
    private val userManager: UserManager,
) : FavoriteSession {
    override suspend fun recoverExpiredSession(snapshot: FavoriteSessionSnapshot): NetWorkResult<Unit>? =
        userManager.recoverExpiredSession(snapshot.accountId, snapshot.generation)

    override val accountIdFlow: Flow<Int> = userManager.userState.map { it.data?.id ?: 0 }
    override val sessionFlow: Flow<FavoriteSessionSnapshot> = userManager.sessionState.map {
        FavoriteSessionSnapshot(it.accountId, it.generation)
    }

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

    override suspend fun <T> withBoundRemoteSession(
        snapshot: FavoriteSessionSnapshot,
        block: suspend () -> T,
    ): T? = userManager.withBoundRemoteSession(
        accountId = snapshot.accountId,
        generation = snapshot.generation,
        block = block,
    )
}
