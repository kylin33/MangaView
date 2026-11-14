package com.example.hellocompose.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 宽高比缓存。
 *
 * 它决定「图片还没到的时候这一页占多高」，算错就是滚动位置乱跳，
 * 或者第一屏整片空白，所以按 Compose 的 aspectRatio 语义（宽 / 高）断言。
 */
class PageSizeCacheTest {

    @Test
    fun storesWidthOverHeight() {
        val cache = PageSizeCache()
        cache.put("a", 1080, 2700)
        assertEquals(0.4f, cache.aspectOf("a")!!, 0.0001f)
    }

    @Test
    fun ignoresInvalidSize() {
        val cache = PageSizeCache()
        cache.put("a", 0, 2700)
        cache.put("b", 1080, -1)
        cache.put("", 100, 100)
        assertNull(cache.aspectOf("a"))
        assertNull(cache.aspectOf("b"))
        assertNull(cache.aspectOf(""))
    }

    @Test
    fun evictsLeastRecentlyUsed() {
        val cache = PageSizeCache(capacity = 2)
        cache.put("a", 100, 100)
        cache.put("b", 100, 200)
        // 访问 a，让 b 变成最久未用
        cache.aspectOf("a")
        cache.put("c", 100, 300)

        assertNull("b 应该被淘汰", cache.aspectOf("b"))
        assertEquals(1f, cache.aspectOf("a")!!, 0.0001f)
        assertEquals(0.3333f, cache.aspectOf("c")!!, 0.001f)
    }

    @Test
    fun clearDropsEverything() {
        val cache = PageSizeCache()
        cache.put("a", 100, 100)
        cache.clear()
        assertNull(cache.aspectOf("a"))
    }
}
