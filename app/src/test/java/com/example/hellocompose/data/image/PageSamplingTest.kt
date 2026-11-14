package com.example.hellocompose.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码尺寸与内存预算。
 *
 * 光看代码很难判断「这张图最后会占多少内存」，所以这里直接断言像素数：
 * 一张图最多 [PageSampling.MAX_PIXELS]，超了就是 OOM 的前置条件。
 */
class PageSamplingTest {

    @Test
    fun normalPageDecodesAtPowerOfTwoBelowScreenWidth() {
        // 站点给 2000×5000，屏幕 1080 宽
        val plan = PageSampling.plan(2000, 5000, 1080)!!

        // 不要求 1080 而要求 1000：2000/2 正好是 2 的幂分之一，
        // BitmapFactory 一次采样到位，省掉一张 2000 宽的中间位图
        assertEquals(1000, plan.width)
        assertEquals(2500, plan.height)
        assertTrue(plan.scaled)
        assertTrue("2500 没超过纹理上限，应该允许硬件位图", plan.allowHardware)
        // 比例必须保持，否则长图会被拉扁
        assertEquals(0.4f, plan.width.toFloat() / plan.height, 0.001f)
    }

    @Test
    fun doesNotSacrificeSharpnessForAlignment() {
        // 1600 宽的图：再缩一半只剩 800，铺到 1080 屏要放大 1.35 倍，字会糊。
        // 这种情况宁可多解一点，也不对齐
        val plan = PageSampling.plan(1600, 4000, 1080)!!

        assertEquals(1080, plan.width)
        assertEquals(2700, plan.height)
    }

    @Test
    fun smallImageIsNotUpscaled() {
        // 小图放大只会更糊更多占内存，不如按原样显示
        val plan = PageSampling.plan(800, 1200, 1080)!!

        assertEquals(800, plan.width)
        assertEquals(1200, plan.height)
        assertFalse(plan.scaled)
    }

    @Test
    fun ultraTallPageIsCappedByPixelBudget() {
        // 一整话拼成一张长图的站点：2000×20000
        val plan = PageSampling.plan(2000, 20000, 1080)!!

        val pixels = plan.width.toLong() * plan.height
        assertTrue("单页像素 $pixels 超过预算 ${PageSampling.MAX_PIXELS}", pixels <= PageSampling.MAX_PIXELS)
        // 缩了，但不能缩到看不清：至少还有屏幕宽度的一半
        assertTrue("缩得太狠：${plan.width}px 宽", plan.width > 540)
        // 高度 7000+ 放不进纹理，必须退回软件位图
        assertFalse(plan.allowHardware)
    }

    @Test
    fun hardwareAllowedOnlyWithinTextureLimit() {
        // 1080×4000：像素没超预算，但高度贴着 4096 的边界
        assertTrue(PageSampling.plan(1080, 4000, 1080)!!.allowHardware)
        // 再高一像素就不行了
        assertFalse(PageSampling.plan(1080, 4100, 1080)!!.allowHardware)
    }

    @Test
    fun invalidDimensionsGiveNoPlan() {
        // 拿不到原图尺寸时不要瞎猜，交给 Coil 的默认路径
        assertNull(PageSampling.plan(0, 100, 1080))
        assertNull(PageSampling.plan(100, -1, 1080))
        assertNull(PageSampling.plan(100, 100, 0))
    }

    @Test
    fun placeholderFreeNormalPageKeepsAspectRatio() {
        val plan = PageSampling.plan(1500, 3000, 750)
        assertNotNull(plan)
        assertEquals(750, plan!!.width)
        assertEquals(1500, plan.height)
    }
}
