package com.example.hellocompose.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码并发的建议值。
 *
 * 这段逻辑决定了一台低端机会不会一进阅读页就 OOM，所以边界要钉死：
 * 堆上限 192MB 是单路和两路的分界，低内存标记一票否决。
 */
class ImageConcurrencyTest {

    @Test
    fun lowRamDeviceAlwaysGetsOneLane() {
        assertEquals(1, ImageConcurrency.recommend(memoryClassMb = 512, isLowRamDevice = true))
        assertEquals(1, ImageConcurrency.recommend(memoryClassMb = 128, isLowRamDevice = true))
    }

    @Test
    fun smallHeapGetsOneLane() {
        assertEquals(1, ImageConcurrency.recommend(memoryClassMb = 128, isLowRamDevice = false))
        assertEquals(1, ImageConcurrency.recommend(memoryClassMb = 192, isLowRamDevice = false))
    }

    @Test
    fun midHeapGetsTwoLanes() {
        assertEquals(2, ImageConcurrency.recommend(memoryClassMb = 193, isLowRamDevice = false))
        assertEquals(2, ImageConcurrency.recommend(memoryClassMb = 256, isLowRamDevice = false))
        assertEquals(2, ImageConcurrency.recommend(memoryClassMb = 384, isLowRamDevice = false))
    }

    @Test
    fun largeHeapGetsThreeLanesButNotMore() {
        assertEquals(3, ImageConcurrency.recommend(memoryClassMb = 385, isLowRamDevice = false))
        assertEquals(3, ImageConcurrency.recommend(memoryClassMb = 1024, isLowRamDevice = false))
    }

    @Test
    fun reasonExplainsTheDecision() {
        assertTrue(ImageConcurrency.reason(128, true).contains("低内存"))
        assertTrue(ImageConcurrency.reason(256, false).contains("256"))
        assertTrue(ImageConcurrency.reason(512, false).contains("三路"))
    }
}
