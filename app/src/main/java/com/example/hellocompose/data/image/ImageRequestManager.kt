package com.example.hellocompose.data.image

import android.content.Context
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.network.HttpException
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ImageRequest
import com.example.hellocompose.core.AppConfig
import com.example.hellocompose.engine.source.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片请求层：页面地址 → [ImageRequest]。
 *
 * 这一层存在的意义是「请求」和「显示」分开：
 *
 * ```
 * Page URL → ImageRequestManager → ImageLoader → Compose
 *              ├─ 请求去重
 *              ├─ 失败重试
 *              ├─ 章节级释放
 *              └─ 缓存 key / 采样尺寸
 * ```
 *
 * Compose 只负责把 [pageRequest] 的结果交给 `AsyncImage`，不再关心 Referer、
 * 缓存 key、解码尺寸这些事；预加载走同一套请求构造，才可能命中同一份缓存。
 * 如果两边各拼一套请求，预加载就只是白白多下了一遍图。
 */
class ImageRequestManager(
    context: Context,
    val imageLoader: ImageLoader
) {

    private val appContext = context.applicationContext

    /**
     * 页面解码宽度 = 屏幕宽度。
     *
     * 阅读页是通栏的，稿件宽度就是屏幕宽度；多解的像素既看不见又占内存。
     * 用 getter 而不是常量：分屏、折叠、旋转之后宽度会变。
     */
    val pageWidthPx: Int
        get() = appContext.resources.displayMetrics.widthPixels.coerceAtLeast(1)

    /** 失败重试的退避延时用，和 ViewModel 生命周期无关 */
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 与 ImageLoader 的解码闸门共用，避免「请求层放行 10 个、解码层只放 2 个」的积压 */
    private val decodeSemaphore = Semaphore(MangaImageLoader.MAX_CONCURRENT_DECODES)

    /** 已经入队但还没结束的预加载，key 是页面地址 */
    private val inFlight = ConcurrentHashMap<String, Disposable>()

    /** 页面地址 → 内存缓存 key，退出章节时按它精确摘除 */
    private val pageKeys = ConcurrentHashMap<String, MemoryCache.Key>()

    /** 页面宽高比，用来在图片到达前就把高度占好 */
    val sizes = PageSizeCache()

    // ---------------- 请求构造 ----------------

    /**
     * 构造一页的图片请求。
     *
     * UI 和预加载都必须从这里出，保证两条路径的缓存 key、采样尺寸完全一致。
     */
    fun pageRequest(page: Page): ImageRequest {
        val width = pageWidthPx
        val key = memoryCacheKey(page.url, width)
        pageKeys[page.url] = key

        return ImageRequest.Builder(appContext)
            .data(page.url)
            // 按屏宽解码，而不是按原图解码
            .size(width)
            .decoderFactory(PageDecoderFactory(width, decodeSemaphore))
            .memoryCacheKey(key)
            .allowHardware(true)
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .apply {
                // 防盗链靠 Referer；站点没配就别发空头，有的 CDN 会把空 Referer 当盗链
                if (page.referer.isNotBlank()) addHeader("Referer", page.referer)
                addHeader("User-Agent", AppConfig.USER_AGENT)
            }
            .build()
    }

    // ---------------- 预加载 ----------------

    /**
     * 预加载一页。
     *
     * 同一页正在飞的时候直接跳过：阅读引擎那边按页索引去重，这里按地址去重，
     * 换源、回看同一页时不会重复下单。
     */
    fun preload(page: Page) {
        if (page.url.isBlank()) return
        if (inFlight.containsKey(page.url)) return
        enqueuePreload(page, attempt = 1)
    }

    private fun enqueuePreload(page: Page, attempt: Int) {
        val request = ImageRequest.Builder(pageRequest(page), appContext)
            .listener(
                onSuccess = { _, result ->
                    inFlight.remove(page.url)
                    val drawable = result.drawable
                    sizes.put(page.url, drawable.intrinsicWidth, drawable.intrinsicHeight)
                },
                onError = { _, result ->
                    inFlight.remove(page.url)
                    val code = (result.throwable as? HttpException)?.response?.code
                    val wait = RetryPolicy.delayMs(attempt)
                    if (wait != null && RetryPolicy.shouldRetry(attempt, result.throwable, code)) {
                        retryScope.launch {
                            delay(wait)
                            enqueuePreload(page, attempt + 1)
                        }
                    }
                }
            )
            .build()

        inFlight[page.url] = imageLoader.enqueue(request)
    }

    /**
     * 切章后调用：只保留 [urls] 里的页面。
     *
     * 做两件事，缺一不可——
     * 1. 取消旧章节还在飞的请求，不让它们继续占带宽和解码名额；
     * 2. 把旧章节的位图从内存缓存里摘掉。LRU 迟早会淘汰它们，但那要等到
     *    新章节的图把缓存占满；中间这段时间是白占的。
     */
    fun retainOnly(urls: Collection<String>) {
        val keep = urls.toHashSet()

        val staleRequests = inFlight.entries.filter { it.key !in keep }
        staleRequests.forEach { (url, disposable) ->
            disposable.dispose()
            inFlight.remove(url)
        }

        val cache = imageLoader.memoryCache ?: return
        val staleKeys = pageKeys.entries.filter { it.key !in keep }
        staleKeys.forEach { (url, key) ->
            // 用同一个 Key 实例去删，免得多余的 extras 导致删不掉
            cache.remove(key)
            pageKeys.remove(url)
        }
    }

    /** 离开阅读页：取消所有在飞请求（已解好的位图留在缓存里，回来还能直接用） */
    fun cancelPreloads() {
        inFlight.values.forEach { it.dispose() }
        inFlight.clear()
    }

    /**
     * 系统内存吃紧时把缓存让出来。
     *
     * Coil 的 `trimMemory` 自己会按等级处理（后台时清掉不可达的位图、
     * 内存极低时整体清空），这里只补一件它不负责的事：把投机性的预加载停掉——
     * 已经按下却没人在等的图，这时候继续解只会让情况更糟。
     */
    fun onTrimMemory(level: Int) {
        imageLoader.memoryCache?.trimMemory(level)
        cancelPreloads()
    }

    /** 真正见底了：连已解好的位图也交出去，活下来比下一次秒开重要 */
    fun onLowMemory() {
        cancelPreloads()
        imageLoader.memoryCache?.clear()
        pageKeys.clear()
    }

    /** 内存缓存 key：带上宽度，换了屏幕尺寸就是另一张（不同采样尺寸的位图不能混用） */
    private fun memoryCacheKey(url: String, width: Int) = MemoryCache.Key("page|$url|w$width")
}
