package com.betterhv.note.template

/**
 * Bounded LRU cache that bypasses caching (and immediate recycling) for items
 * exceeding [maxSizeKb]. Oversized items are returned as ephemeral entries and
 * recycled on [release] instead of evicting themselves or existing cached items.
 */
internal class TemplateBitmapCache<T>(
  private val maxSizeKb: Int,
  private val sizeKbOf: (T) -> Int,
  private val isValid: (T) -> Boolean,
  private val onRecycle: (T) -> Unit,
) {
  data class Entry<T>(val value: T, val ephemeral: Boolean)

  private data class CachedItem<T>(val value: T, val sizeKb: Int)

  private val cache = LinkedHashMap<String, CachedItem<T>>(INITIAL_CAPACITY, LOAD_FACTOR, true)
  private var totalKb = 0

  @Synchronized
  fun acquire(key: String, create: () -> T?): Entry<T>? {
    val existing = cache[key]
    if (existing != null) {
      if (isValid(existing.value)) {
        return Entry(existing.value, ephemeral = false)
      }
      cache.remove(key)
      totalKb -= existing.sizeKb
    }
    val created = create() ?: return null
    val sizeKb = sizeKbOf(created).coerceAtLeast(1)
    if (sizeKb > maxSizeKb) {
      return Entry(created, ephemeral = true)
    }
    val previous = cache.put(key, CachedItem(created, sizeKb))
    if (previous != null) {
      totalKb -= previous.sizeKb
      if (previous.value !== created && isValid(previous.value)) {
        onRecycle(previous.value)
      }
    }
    totalKb += sizeKb
    val iterator = cache.entries.iterator()
    while (totalKb > maxSizeKb && iterator.hasNext()) {
      val eldest = iterator.next()
      if (eldest.value.value === created) continue
      iterator.remove()
      totalKb -= eldest.value.sizeKb
      if (isValid(eldest.value.value)) {
        onRecycle(eldest.value.value)
      }
    }
    return Entry(created, ephemeral = false)
  }

  @Synchronized
  fun release(entry: Entry<T>) {
    if (entry.ephemeral && isValid(entry.value)) {
      onRecycle(entry.value)
    }
  }

  @Synchronized
  fun clear() {
    cache.values.forEach { item ->
      if (isValid(item.value)) {
        onRecycle(item.value)
      }
    }
    cache.clear()
    totalKb = 0
  }

  private companion object {
    const val INITIAL_CAPACITY = 16
    const val LOAD_FACTOR = 0.75f
  }
}
