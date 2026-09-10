package com.par9uet.jm.cache

/** DocumentsContract IDs are provider-local paths; only compare within one authority. */
internal fun cacheDocumentTreesOverlap(sourceId: String, targetId: String): Boolean {
    val source = sourceId.trimEnd('/')
    val target = targetId.trimEnd('/')
    return source == target || source.startsWith("$target/") || target.startsWith("$source/") ||
        (source.endsWith(':') && target.startsWith(source)) ||
        (target.endsWith(':') && source.startsWith(target))
}
