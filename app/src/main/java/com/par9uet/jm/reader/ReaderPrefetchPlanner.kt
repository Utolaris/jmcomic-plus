package com.par9uet.jm.reader

/**
 * Returns the warming order rather than just a set. The first part follows the current travel
 * direction; the optional opposite tail keeps a nearby backtrack cheap in paged mode.
 */
internal fun readerPrefetchPlan(
    currentPageIndex: Int,
    pageCount: Int,
    distance: Int,
    direction: Int,
    includeOpposite: Boolean,
    visibleStart: Int = currentPageIndex,
    visibleEnd: Int = currentPageIndex,
): List<Int> {
    if (pageCount <= 0 || distance <= 0) return emptyList()
    val start = minOf(visibleStart, visibleEnd).coerceIn(0, pageCount - 1)
    val end = maxOf(visibleStart, visibleEnd).coerceIn(0, pageCount - 1)
    val primaryDirection = if (direction < 0) -1 else 1
    val result = ArrayList<Int>(distance.coerceAtMost(pageCount - 1))
    var primaryOffset = 1

    fun appendPrimaryUntil(limit: Int) {
        while (result.size < limit) {
            val index = if (primaryDirection > 0) {
                end + primaryOffset
            } else {
                start - primaryOffset
            }
            if (index !in 0 until pageCount) break
            result += index
            primaryOffset++
        }
    }

    if (!includeOpposite) {
        appendPrimaryUntil(distance)
        return result
    }

    val primaryQuota = (distance + 1) / 2
    appendPrimaryUntil(primaryQuota)
    var oppositeOffset = 1
    while (result.size < distance) {
        val index = if (primaryDirection > 0) {
            start - oppositeOffset
        } else {
            end + oppositeOffset
        }
        if (index !in 0 until pageCount) break
        result += index
        oppositeOffset++
    }
    appendPrimaryUntil(distance)
    while (result.size < distance) {
        val index = if (primaryDirection > 0) {
            start - oppositeOffset
        } else {
            end + oppositeOffset
        }
        if (index !in 0 until pageCount) break
        result += index
        oppositeOffset++
    }
    return result
}

/** A large jump or a stable direction gets a small forward runway without unbounded work. */
internal fun readerPrefetchDistance(
    configuredDistance: Int,
    jumpDistance: Int,
    directionStreak: Int,
): Int {
    val base = configuredDistance.coerceAtLeast(0)
    if (base == 0) return 0
    val jumpBonus = if (jumpDistance >= 4) base.coerceAtMost(3) else 0
    val streakBonus = if (directionStreak >= 3) 1 else 0
    return (base + jumpBonus + streakBonus).coerceAtMost(12)
}
