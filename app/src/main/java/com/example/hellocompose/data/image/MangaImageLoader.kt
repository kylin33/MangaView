package com.example.hellocompose.data.image

import android.app.ActivityManager
import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * 本机的图片解码档位。面板要显示「为什么是两路」，所以把判断依据一起带出来。
 */
data class ImageDeviceProfile(
    val memoryClassMb: Int,
    val isLowRamDevice: Boolean,
    val concurrency: Int,
    val reason: String
)

/**
 * 全局唯一的图片加载器。
 *
 * 韩漫的图片特点很极端：长图、原图分辨率远超屏幕。一张 2000×5000 的图
 * 按原尺寸解出来是 38MB，同时解十张就能把堆吃干净。所以这里的每个参数
 * 都是冲着「峰值内存」去的，而不是默认值随手用：
 *
 * - **内存缓存按堆比例**：写死 32MB 在 128MB 堆的低端机上仍然是灾难，
 *   在 512MB 堆的旗舰机上又太小；
 * - **解码并发上限**：真正吃内存的是「同时解几张」，而不是「一共解过几张」，
 *   具体几路由 [ImageConcurrency] 按设备堆上限决定；
 * - **ARGB_8888 + 不用 RGB_565**：韩漫大量渐变和细文字，565 的色带肉眼可见，
 *   省下的内存用采样和缓存上限去抠，不从这里抠。
 *
 * 磁盘缓存存的是原始字节，一张图只下一次；内存缓存存的是解好的位图，
 * 容量满了由 Coil 内部的有界 LRU 淘汰。
 */
object MangaImageLoader {

    /** 内存缓存占堆上限的比例 */
    const val MEMORY_CACHE_PERCENT = 0.18

    /** 磁盘缓存上限：长图单张几百 KB，256MB 够存十几话 */
    const val DISK_CACHE_BYTES = 256L * 1024 * 1024

    /** 读取设备信息，决定解码并发。没申请任何权限，也不需要 Context 常驻 */
    fun deviceProfile(context: Context): ImageDeviceProfile {
        val am = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryClass = am.memoryClass
        val lowRam = am.isLowRamDevice
        return ImageDeviceProfile(
            memoryClassMb = memoryClass,
            isLowRamDevice = lowRam,
            concurrency = ImageConcurrency.recommend(memoryClass, lowRam),
            reason = ImageConcurrency.reason(memoryClass, lowRam)
        )
    }

    fun create(context: Context): ImageLoader {
        val app = context.applicationContext
        val parallel = deviceProfile(app).concurrency

        return ImageLoader.Builder(app)
            .memoryCache {
                MemoryCache.Builder(app)
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(app.cacheDir, "image_cache"))
                    // 由我们自己定上限，不跟着系统剩余空间飘
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .allowRgb565(false)
            // 硬件位图放在 GPU 纹理里，不占 Java 堆——长图最怕的就是堆被打满。
            // 注意不要同时写死 bitmapConfig：显式配置优先于 allowHardware，
            // 两条都写等于把硬件位图彻底关掉。超长图在解码器里单独降级。
            .allowHardware(true)
            // 阅读页一张接一张地出现，淡入只会让「已加载」和「正在加载」看起来一样
            .crossfade(false)
            // 很多图床对图片返回 no-cache，照做等于每次滚动都重新下载
            .respectCacheHeaders(false)
            // 双重闸门：BitmapFactory 内部信号量 + 解码协程调度器的并行度
            .bitmapFactoryMaxParallelism(parallel)
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(parallel))
            .build()
    }
}
