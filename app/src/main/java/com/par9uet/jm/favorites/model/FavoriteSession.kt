package com.par9uet.jm.favorites.model

import com.par9uet.jm.core.network.NetWorkResult
import kotlinx.coroutines.flow.Flow

data class FavoriteSessionSnapshot(
    val accountId: Int,
    val generation: Long,
)

/** The authenticated identity boundary shared by Favorites mutations and synchronization. */
interface FavoriteSession {
    val accountIdFlow: Flow<Int>
    /** Emits for every identity or generation change, including re-login to the same account. */
    val sessionFlow: Flow<FavoriteSessionSnapshot>

    fun currentAccountId(): Int

    fun snapshot(): FavoriteSessionSnapshot

    fun isCurrent(snapshot: FavoriteSessionSnapshot): Boolean

    /** Null means the snapshot cannot be renewed; never switches the active account. */
    suspend fun recoverExpiredSession(snapshot: FavoriteSessionSnapshot): NetWorkResult<Unit>? = null

    suspend fun <T> withCurrentSession(
        snapshot: FavoriteSessionSnapshot,
        block: suspend () -> T,
    ): T?

    /**
     * 会话绑定的远程执行边界：把“快照仍是当前会话”校验与“远程能力归属于该会话”合并为
     * 一个原子步骤。[block] 只在该快照仍代表活动会话时执行，且与所有会话转换串行化 ——
     * 阻塞式远程调用期间账号不可能切换到 B。返回 null 表示快照已过期或认证不可用。
     */
    suspend fun <T> withBoundRemoteSession(
        snapshot: FavoriteSessionSnapshot,
        block: suspend () -> T,
    ): T? = withCurrentSession(snapshot, block)
}
