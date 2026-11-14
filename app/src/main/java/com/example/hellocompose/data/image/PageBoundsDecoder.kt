package com.example.hellocompose.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import coil.size.Size
import kotlinx.coroutines.sync.Semaphore

/**
 * 页面解码器：先看原图多大，再决定解多大。
 *
 * 为什么需要它？因为 `ImageRequest.size()` 只能给一个宽度，Coil 会按「宽度对齐」
 * 去采样。对普通页这没问题，但遇到「一整话拼成一张」的站点（2000×20000），
 * 按宽度缩完仍然上千万像素，一次分配就是几十上百 MB。
 *
 * 做法刻意保守：**不重写解码**。这里只做三件事——
 * 1. `peek` 出图片头拿原始尺寸（不消费数据流，后面 Coil 还要从头解）；
 * 2. 用 [PageSampling] 算一个带预算的目标尺寸，改一下 `Options`；
 * 3. 剩下的采样、EXIF、色彩空间、硬件位图全部交给 Coil 自己的
 *    [BitmapFactoryDecoder]。
 *
 * 任何一步出意外（例如流不支持 peek）都退回原始 `Options`，
 * 退化成本来的行为，不会因为优化而变得更容易失败。
 */
class PageBoundsDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val targetWidth: Int,
    private val semaphore: Semaphore
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val plan = runCatching { plan() }.getOrNull()
        // 只降级、不升级：Coil 自己的硬件位图判定（纹理上限、文件描述符是否够用）
        // 已经做过一轮，这里只补它看不到的那件事——真实高度。
        val tuned = if (plan == null) options else options.copy(
            size = Size(Dimension.Pixels(plan.width), Dimension.Pixels(plan.height)),
            config = if (plan.allowHardware) options.config else Bitmap.Config.ARGB_8888
        )
        return BitmapFactoryDecoder(source, tuned, semaphore).decode()
    }

    private fun plan(): SamplePlan? {
        val stream = source.sourceOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // peek：只读头部字节，不推进原始流的位置
        stream.peek().inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        return PageSampling.plan(bounds.outWidth, bounds.outHeight, targetWidth)
    }
}

/**
 * 把 [PageBoundsDecoder] 挂到单条请求上。
 *
 * 用 `ImageRequest.Builder.decoderFactory` 而不是全局组件，是为了只影响阅读页：
 * 封面、历史缩略图该走 Coil 默认路径就走默认路径。
 */
class PageDecoderFactory(
    private val targetWidth: Int,
    private val semaphore: Semaphore
) : Decoder.Factory {

    override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder =
        PageBoundsDecoder(result.source, options, targetWidth, semaphore)
}
