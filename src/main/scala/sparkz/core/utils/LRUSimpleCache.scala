package sparkz.core.utils

import java.util

class LRUSimpleCache[K, V](threshold: Int, loadFactor: Float = .75f) extends util.LinkedHashMap[K, V] (threshold, loadFactor, true) {
  assert(threshold > 0, "Cache threshold must be greater than zero")

  override def removeEldestEntry(eldest: java.util.Map.Entry[K, V]): Boolean = size > threshold
}