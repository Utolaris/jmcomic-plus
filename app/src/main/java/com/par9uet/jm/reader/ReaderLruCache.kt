package com.par9uet.jm.reader

/** Small synchronized access-order cache used by the reader and JVM tests. */
internal class ReaderLruCache<K, V>(
    maxSize: Long,
    private val sizeOf: (V) -> Long = { 1L },
) {
    private val capacity = maxSize.coerceAtLeast(1L)
    private val values = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = false
    }
    private var currentSize = 0L

    @Synchronized
    operator fun get(key: K): V? = values[key]

    @Synchronized
    fun put(key: K, value: V): V? {
        val old = values.put(key, value)
        currentSize -= old?.let(sizeOf)?.coerceAtLeast(0L) ?: 0L
        currentSize += sizeOf(value).coerceAtLeast(1L)
        trimToSizeLocked(capacity)
        return old
    }

    @Synchronized
    fun remove(key: K): V? {
        val old = values.remove(key)
        currentSize -= old?.let(sizeOf)?.coerceAtLeast(0L) ?: 0L
        return old
    }

    @Synchronized
    fun trimToSize(maxSize: Long) {
        trimToSizeLocked(maxSize.coerceAtLeast(0L))
    }

    @Synchronized
    fun evictAll() {
        values.clear()
        currentSize = 0L
    }

    @Synchronized
    fun size(): Long = currentSize

    @Synchronized
    fun maxSize(): Long = capacity

    @Synchronized
    fun keys(): List<K> = values.keys.toList()

    private fun trimToSizeLocked(maxSize: Long) {
        val iterator = values.entries.iterator()
        while (currentSize > maxSize && iterator.hasNext()) {
            val entry = iterator.next()
            currentSize -= sizeOf(entry.value).coerceAtLeast(0L)
            iterator.remove()
        }
    }
}
