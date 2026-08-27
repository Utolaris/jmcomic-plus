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

    /**
     * 会话绑定的远程执行边界：把“快照仍是当前会话”校验与“使用属于该会话的认证远程
     * 能力”合并为一个原语。[block] 整体只在该快照所属会话仍然有效时执行；执行期间发生
     * 会话切换会让 block 被中止并返回 null，而不是继续对新账号发起远程调用。
     */
    suspend fun <T> withBoundRemoteSession(
        snapshot: FavoriteSessionSnapshot,
        block: suspend () -> T,
    ): T? = withCurrentSession(snapshot, block)
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

    override suspend fun <T> withBoundRemoteSession(
        snapshot: FavoriteSessionSnapshot,
        block: suspend () -> T,
    ): T? = userManager.withBoundRemoteSession(
        accountId = snapshot.accountId,
        generation = snapshot.generation,
        block = block,
    )
}
