package com.par9uet.jm.ui.state

/** JM 的漫画点赞是单向操作，不把服务端状态建模成本地反转。 */
internal object ComicLikeState {

    /** 已喜欢的漫画不再提交第二次 /like 请求。 */
    fun canSubmitLike(isLike: Boolean): Boolean = !isLike

    /** 请求失败时保留原状态；成功时只把漫画标记为已喜欢并增加一次计数。 */
    fun applyLikeResult(
        isLike: Boolean,
        likeCount: Int,
        succeeded: Boolean,
    ): Pair<Boolean, Int> = if (succeeded) {
        applyLikeSuccess(isLike, likeCount)
    } else {
        isLike to likeCount
    }

    /** 成功后不会取消已有喜欢，计数保持非负且避免 Int 溢出。 */
    fun applyLikeSuccess(isLike: Boolean, likeCount: Int): Pair<Boolean, Int> {
        if (isLike) return true to likeCount.coerceAtLeast(0)
        return true to (likeCount.coerceAtLeast(0).toLong() + 1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
}

/** 同一本漫画同一时间只允许一个单向点赞请求在途。 */
internal class LikeRequestGate {
    private val inFlightComicIds = HashSet<Int>()

    /** @return true 表示获得提交权；false 表示该漫画已有在途请求。 */
    @Synchronized
    fun tryAcquire(comicId: Int): Boolean = inFlightComicIds.add(comicId)

    @Synchronized
    fun release(comicId: Int) {
        inFlightComicIds.remove(comicId)
    }

    @Synchronized
    fun isInFlight(comicId: Int): Boolean = comicId in inFlightComicIds
}
