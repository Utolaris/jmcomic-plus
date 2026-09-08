package com.par9uet.jm.favorites

import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.retrofit.model.NetWorkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class TestFavoriteSession(accountId: Int = 7) : FavoriteSession {
    var recovery: suspend () -> NetWorkResult<Unit>? = { null }
    override suspend fun recoverExpiredSession(snapshot: FavoriteSessionSnapshot): NetWorkResult<Unit>? = recovery()
    override val sessionFlow = MutableStateFlow(FavoriteSessionSnapshot(accountId, 0L))
    override val accountIdFlow = sessionFlow.map { it.accountId }
    override fun currentAccountId() = snapshot().accountId
    override fun snapshot() = sessionFlow.value
    override fun isCurrent(snapshot: FavoriteSessionSnapshot) = snapshot.accountId > 0 && snapshot == this.snapshot()
    override suspend fun <T> withCurrentSession(snapshot: FavoriteSessionSnapshot, block: suspend () -> T): T? =
        if (isCurrent(snapshot)) block() else null

    fun switchAccount(accountId: Int) {
        sessionFlow.value = FavoriteSessionSnapshot(accountId, snapshot().generation + 1)
    }
}
