package sparkz.core.utils

import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.propspec.AnyPropSpec

class LRUSimpleCacheTest extends AnyPropSpec {
  property("Cache should evict elements when size over grow the threshold") {
    // Arrange
    val threshold = 5
    val cache = new LRUSimpleCache[Int, Int](threshold)

    // Add elements through 1 to 5 and check the cache element number grows accordingly
    for (index <- 1 to 5) {
      cache.size shouldBe index - 1
      cache.put(index, index)
      cache.size shouldBe index
    }

    // Check all elements exists in the cache
    for (index <- 1 to 5) {
      cache.containsKey(index) shouldBe true
    }

    // Add other elements when cache's size has reached the threshold and check size doesn't grow any more
    for (index <- 6 to 10) {
      cache.put(index, index)
      cache.size shouldBe threshold
    }

    // Check previous elements have been correctly evicted
    for (index <- 1 to 5) {
      cache.containsKey(index) shouldBe false
    }

    // Check all new elements exist in the cache
    for (index <- 6 to 10) {
      cache.containsKey(index) shouldBe true
    }
  }

  property("Cache should not evict most used elements") {
    // Arrange
    val threshold = 5
    val cache = new LRUSimpleCache[Int, Int](threshold)

    for (index <- 1 to 5) {
      cache.put(index, index)
    }

    cache.get(1)
    cache.get(2)

    cache.put(10, 10)
    cache.put(20, 20)

    // 1 and 2 should be present since we recently retrieved them
    cache.containsKey(1) shouldBe true
    cache.containsKey(2) shouldBe true

    cache.containsKey(3) shouldBe false
    cache.containsKey(4) shouldBe false

    cache.containsKey(5) shouldBe true
    cache.containsKey(10) shouldBe true
    cache.containsKey(20) shouldBe true
  }
}
