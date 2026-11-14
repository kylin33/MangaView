package com.example.hellocompose.data.image

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 一张页面图要解码成多大。
 *
 * [scaled] 为 true 表示已经比原图小；[allowHardware] 表示能不能放进 GPU 纹理
 * （硬件位图不占 Java 堆，但超过纹理边长上限画不出来）。
 */
data class SamplePlan(
    val width: Int,
    val height: Int,
    val scaled: Boolean,
    val allowHardware: Boolean
)

/**
 * 解码尺寸与内存预算。
 *
 * 这里刻意写成纯函数：不碰 Bitmap、不碰 Context，所以能在 JVM 单测里
 * 把「正常页 / 超长图 / 小图」三种情况全跑一遍。解码本身仍然交给 Coil。
 *
 * 规则只有四条：
 * 1. **只缩不放**：站点给 800 宽的图就按 800 展示，放大只会更糊且更占内存；
 * 2. **按屏宽等比缩**：原图 2000 宽、屏幕 1080，多解出来的像素全是浪费；
 * 3. **对齐 2 的幂**：`BitmapFactory` 只能按 `inSampleSize`（2 的幂）粗采样。
 *    原图 2000 宽、屏幕 1080 时，若仍要求 1080，采样比只有 1.85 倍，只能 `inSampleSize=1`，
 *    于是要先解出一张 2000 宽的中间位图（约 38MB）再缩到 1080——峰值就是这么来的。
 *    改成要 1000（`2000/2`），`inSampleSize=2` 一次到位，峰值降到 10MB，
 *    代价只是绘制时放大 8%，肉眼看不出来。参见 [MIN_DECODE_RATIO]；
 * 4. **面积兜底**：缩完仍然超过 [MAX_PIXELS] 的（典型是 2000×20000 那种整卷长图），
 *    继续按面积缩。宁可略糊，也不能一次性分配上百 MB。
 */
object PageSampling {

    /**
     * 单页像素上限：600 万像素，ARGB_8888 约 24MB。
     *
     * 正常的一屏宽漫画页是 1080×2700 ≈ 290 万像素，远在预算内；
     * 只有「一整话拼成一张长图」的站点才会撞到这条线。
     */
    const val MAX_PIXELS = 6_000_000L

    /**
     * 硬件位图的边长上限。
     *
     * GL ES 3.0 只保证 2048，但真机上远大于此；取 4096 是一个不会误伤常见机型、
     * 又能挡掉「一张图超过纹理尺寸直接画不出来」的保守值。
     */
    const val MAX_HARDWARE_SIDE = 4096

    /**
     * 允许的最小解码比（解码宽度 / 屏幕宽度）。
     *
     * 0.85 意味着绘制时最多放大 1.18 倍：1000 宽的图铺满 1080 屏，肉眼无感；
     * 再往下就会开始糊字，长图里的对白最先受影响。
     */
    const val MIN_DECODE_RATIO = 0.85

    fun plan(
        intrinsicWidth: Int,
        intrinsicHeight: Int,
        targetWidth: Int,
        maxPixels: Long = MAX_PIXELS
    ): SamplePlan? {
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0 || targetWidth <= 0) return null

        // 只缩不放
        var width = if (intrinsicWidth > targetWidth) targetWidth else intrinsicWidth

        // 落到 2 的幂上，省掉一张中间位图（见类注释第 3 条）
        alignedWidth(intrinsicWidth, width)?.let { width = it }

        var height = (intrinsicHeight.toDouble() * width / intrinsicWidth).roundToInt().coerceAtLeast(1)

        val pixels = width.toLong() * height
        if (pixels > maxPixels) {
            val shrink = sqrt(maxPixels.toDouble() / pixels)
            // 向下取整：四舍五入有可能把像素数又抬回预算之上，少一个像素看不出来
            width = floor(width * shrink).toInt().coerceAtLeast(1)
            height = floor(height * shrink).toInt().coerceAtLeast(1)
        }

        return SamplePlan(
            width = width,
            height = height,
            scaled = width != intrinsicWidth || height != intrinsicHeight,
            allowHardware = width <= MAX_HARDWARE_SIDE && height <= MAX_HARDWARE_SIDE
        )
    }

    /**
     * 找出「比 [target] 小、但仍在可接受画质内」的 2 的幂分之一宽度。
     *
     * 找不到（或刚好等于目标）就返回 null，表示保持原样。
     */
    private fun alignedWidth(intrinsicWidth: Int, target: Int): Int? {
        val floorWidth = (target * MIN_DECODE_RATIO).toInt()
        var candidate = intrinsicWidth
        while (candidate / 2 >= floorWidth) {
            candidate /= 2
        }
        return candidate.takeIf { it < target }
    }
}
