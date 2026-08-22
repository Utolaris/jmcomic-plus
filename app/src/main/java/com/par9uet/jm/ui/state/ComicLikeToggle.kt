package com.par9uet.jm.ui.state

/**
 * 喜欢（Like）切换的纯逻辑部分。
 *
 * JM 的喜欢接口（内置 API toggleAlbumLike 与网络 API POST /like）都是"切换"语义：
 * 调用一次反转一次服务端状态。因此客户端在请求成功后只需把本地状态取反，
 * 并同步增减 likeCount（下限为 0）。
 */
internal object ComicLikeToggle {

    /** 请求失败时原样返回；请求成功时应用一次 toggle。 */
    fun applyToggleResult(
        isLike: Boolean,
        likeCount: Int,
        succeeded: Boolean,
    ): Pair<Boolean, Int> = if (succeeded) {
        applyToggleSuccess(isLike, likeCount)
    } else {
        isLike to likeCount
    }

    /** 请求成功后应用的新状态：isLike 取反，likeCount 相应 +1/-1，且不为负。 */
    fun applyToggleSuccess(isLike: Boolean, likeCount: Int): Pair<Boolean, Int> =
        if (isLike) {
            false to (likeCount.toLong() - 1L).coerceAtLeast(0L).toInt()
        } else {
            true to (likeCount.coerceAtLeast(0).toLong() + 1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
}

/**
 * 喜欢切换请求闸：同一本漫画同一时间只允许一个切换请求在途。
 * 请求未完成期间的重复点击直接忽略，避免快速连点产生多个并发切换
 * 导致服务端最终状态不可预测。
 */
internal class LikeRequestGate {
    private val inFlightComicIds = HashSet<Int>()

    /** @return true 表示获得提交权；false 表示该漫画已有在途请求，本次点击应忽略。 */
    @Synchronized
    fun tryAcquire(comicId: Int): Boolean = inFlightComicIds.add(comicId)

    @Synchronized
    fun release(comicId: Int) {
        inFlightComicIds.remove(comicId)
    }

    @Synchronized
    fun isInFlight(comicId: Int): Boolean = comicId in inFlightComicIds
}
