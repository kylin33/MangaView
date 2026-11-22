package com.example.hellocompose.data.image

import android.content.Context
import android.os.SystemClock
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.memory.MemoryCache
import coil.network.HttpException
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ImageRequest
import com.example.hellocompose.core.AppConfig
import com.example.hellocompose.engine.source.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** 一次加载的结局，预加载的重试循环靠它判断要不要再试 */
data class ImageLoadOutcome(
    val success: Boolean,
    val throwable: Throwable? = null,
    val httpCode: Int? = null,
    val cancelled: Boolean = false
)

/**
 * 图片请求层：页面地址 → [ImageRequest]。
 *
 * 这一层存在的意义是「请求」和「显示」分开：
 *
 * ```
 * Page URL → ImageRequestManager → ImageLoader → Compose
 *              ├─ 请求去重 / 可见优先
 *              ├─ 失败重试
 *              ├─ 章节级释放
 *              ├─ 缓存 key / 采样尺寸
 *              └─ 耗时、命中来源、成功率统计
 * ```
 *
 * Compose 只负责把 [pageRequest] 的结果交给 `AsyncImage`，不再关心 Referer、
 * 缓存 key、解码尺寸这些事；预加载走同一套请求构造，才可能命中同一份缓存。
 * 如果两边各拼一套请求，预加载就只是白白多下了一遍图。
 *
 * 统计和优先级都在这一层做，原因很简单：这是唯一能同时看到「用户要的图」和
 * 「提前下的图」的地方，而且 Coil 2 的请求本身没有优先级字段。
 */
@OptIn(ExperimentalCoilApi::class)
class ImageRequestManager(
    context: Context,
    val imageLoader: ImageLoader,
    /** 全 App 共用一份统计，面板读的就是它 */
    val stats: ImageLoadStats = ImageLoadStats(),
    /** 解码并发：由设备堆上限决定，见 [ImageConcurrency] */
    decodeConcurrency: Int = MangaImageLoader.deviceProfile(context).concurrency,
    /** 预加载并发固定为 1：投机行为，多开一路只会让可见页更慢 */
    preloadConcurrency: Int = 1
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

    val decodeSlots: Int = decodeConcurrency

    /** 失败重试、排队都跑在这里，和 ViewModel 生命周期无关 */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 与 ImageLoader 的解码闸门共用，避免「请求层放行 10 个、解码层只放 2 个」的积压 */
    private val decodeSemaphore = Semaphore(decodeConcurrency)

    private val gate = PreloadGate(maxPreloads = preloadConcurrency)

    /** 已经排队或正在飞的预加载，key 是页面地址 */
    private val preloadJobs = ConcurrentHashMap<String, Job>()

    /** 页面地址 → 内存缓存 key，退出章节时按它精确摘除 */
    private val pageKeys = ConcurrentHashMap<String, MemoryCache.Key>()

    private val inFlight = AtomicInteger(0)
    private val peakInFlight = AtomicInteger(0)

    /** 页面宽高比，用来在图片到达前就把高度占好 */
    val sizes = PageSizeCache()

    // ---------------- 请求构造 ----------------

    /**
     * 构造一页的图片请求。
     *
     * UI 和预加载都必须从这里出，保证两条路径的缓存 key、采样尺寸完全一致。
     * [role] 决定它参不参与「可见优先」：可见请求一进场，预加载就停在闸门外。
     * [onOutcome] 只给预加载用 —— UI 的结果由 `AsyncImage` 的 state 回调自己拿。
     */
    fun pageRequest(
        page: Page,
        role: LoadRole = LoadRole.VISIBLE,
        onOutcome: ((ImageLoadOutcome) -> Unit)? = null
    ): ImageRequest {
        val width = pageWidthPx
        val key = memoryCacheKey(page.url, width)
        pageKeys[page.url] = key

        val startedAtMs = AtomicLong(0L)
        val settled = AtomicBoolean(false)

        fun begin() {
            startedAtMs.set(SystemClock.elapsedRealtime())
            if (role == LoadRole.VISIBLE) gate.visibleStarted()
            val now = inFlight.incrementAndGet()
            peakInFlight.updateAndGet { peak -> if (now > peak) now else peak }
        }

        fun settle(success: Boolean, source: LoadSource, failure: String?, cancelled: Boolean = false) {
            // Coil 只会回调一个终止事件，但 dispose 与 onError 的先后在某些版本里并不严格，
            // 用 CAS 保证一次请求只记一笔，否则成功率会被重复计数污染
            if (!settled.compareAndSet(false, true)) return

            val startedAt = startedAtMs.get()
            if (startedAt == 0L) {
                // 还没开始就被回收（列表滑过去了），不算一次加载
                return
            }

            if (role == LoadRole.VISIBLE) gate.visibleFinished()
            inFlight.decrementAndGet()

            val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
            if (cancelled) {
                stats.recordCancelled()
            } else {
                stats.record(
                    LoadEvent(
                        url = page.url,
                        host = ImageLoadStats.hostOf(page.url),
                        role = role,
                        durationMs = elapsed,
                        success = success,
                        source = source,
                        failure = failure,
                        at = System.currentTimeMillis()
                    )
                )
            }
        }

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
            .listener(
                onStart = { begin() },
                onSuccess = { _, result ->
                    settle(true, result.dataSource.toLoadSource(), null)
                    onOutcome?.invoke(ImageLoadOutcome(success = true))
                },
                onError = { _, result ->
                    val code = (result.throwable as? HttpException)?.response?.code
                    val reason = ImageErrorText.of(result.throwable, code)
                    settle(false, LoadSource.NONE, reason)
                    onOutcome?.invoke(
                        ImageLoadOutcome(success = false, throwable = result.throwable, httpCode = code)
                    )
                },
                onCancel = {
                    settle(false, LoadSource.NONE, null, cancelled = true)
                    onOutcome?.invoke(ImageLoadOutcome(success = false, cancelled = true))
                }
            )
            .build()
    }

    // ---------------- 预加载 ----------------

    /**
     * 预加载一页。
     *
     * 同一页正在飞、或正在闸门外排队时直接跳过：阅读引擎那边按页索引去重，
     * 这里按地址去重，换源、回看同一页时不会重复下单。
     *
     * 整个「排队 → 下载 → 重试」是一个 Job，占一个预加载名额。重试不再另外排队，
     * 否则一次失败就会变成两路并发，把可见页挤慢。
     */
    fun preload(page: Page) {
        if (page.url.isBlank()) return
        if (preloadJobs.containsKey(page.url)) return

        val job = workScope.launch(start = CoroutineStart.LAZY) {
            gate.awaitSlot()
            try {
                var attempt = 1
                while (true) {
                    val outcome = loadOnce(page)
                    if (outcome.success) break
                    if (outcome.cancelled) break

                    val wait = RetryPolicy.delayMs(attempt)
                    if (wait == null ||
                        !RetryPolicy.shouldRetry(attempt, outcome.throwable, outcome.httpCode)
                    ) {
                        break
                    }

                    delay(wait)
                    attempt += 1
                }
            } finally {
                gate.release()
            }
        }

        // 先放进 map 再启动：LAZY 避免了「跑完了才 put，留下一个永不清理的已完成 Job」
        preloadJobs[page.url] = job
        job.invokeOnCompletion { preloadJobs.remove(page.url, job) }
        job.start()
    }

    /** 预加载一次，结果通过 [pageRequest] 的 listener 回来 */
    private suspend fun loadOnce(page: Page): ImageLoadOutcome =
        suspendCancellableCoroutine { cont ->
            val request = pageRequest(page, LoadRole.PRELOAD) { outcome ->
                if (cont.isActive) cont.resume(outcome)
            }
            val disposable: Disposable = imageLoader.enqueue(request)
            cont.invokeOnCancellation { disposable.dispose() }
        }

    /**
     * 切章后调用：只保留 [urls] 里的页面。
     *
     * 做两件事，缺一不可——
     * 1. 取消旧章节还在排队/在飞的请求，不让它们继续占带宽和解码名额；
     * 2. 把旧章节的位图从内存缓存里摘掉。LRU 迟早会淘汰它们，但那要等到
     *    新章节的图把缓存占满；中间这段时间是白占的。
     */
    fun retainOnly(urls: Collection<String>) {
        val keep = urls.toHashSet()

        preloadJobs.entries.filter { it.key !in keep }.forEach { (url, job) ->
            job.cancel()
            preloadJobs.remove(url)
        }

        val cache = imageLoader.memoryCache ?: return
        val staleKeys = pageKeys.entries.filter { it.key !in keep }
        staleKeys.forEach { (url, key) ->
            // 用同一个 Key 实例去删，免得多余的 extras 导致删不掉
            cache.remove(key)
            pageKeys.remove(url)
        }
    }

    /** 离开阅读页：取消所有在飞的预加载（已解好的位图留在缓存里，回来还能直接用） */
    fun cancelPreloads() {
        preloadJobs.values.forEach { it.cancel() }
        preloadJobs.clear()
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

    // ---------------- 统计与缓存 ----------------

    /** 面板读的一份快照。并发数是这一层才知道的，所以不放进 [ImageLoadStats] */
    fun snapshot(): ImageStatsSnapshot =
        stats.snapshot(currentInFlight = inFlight.get(), peakInFlight = peakInFlight.get())

    /** 预加载闸门状态：面板用它解释「为什么预加载在等」 */
    val gateState: PreloadGateState get() = gate.snapshot

    /** 磁盘缓存当前占用。`size` 是 Coil 自己维护的计数，不遍历目录 */
    fun diskCacheSizeBytes(): Long = imageLoader.diskCache?.size ?: 0L

    /**
     * 清掉图片缓存。
     *
     * 磁盘缓存本身是「按大小淘汰」的（超过 256MB 自动清理最久未用的），
     * 所以这里不自己写一套 LRU：只在用户明确要求时整体清空，出问题时有路可退。
     */
    suspend fun clearCaches() {
        imageLoader.memoryCache?.clear()
        pageKeys.clear()
        withContext(Dispatchers.IO) {
            imageLoader.diskCache?.clear()
        }
    }

    /** 内存缓存 key：带上宽度，换了屏幕尺寸就是另一张（不同采样尺寸的位图不能混用） */
    private fun memoryCacheKey(url: String, width: Int) = MemoryCache.Key("page|$url|w$width")

    /** Coil 的数据源映射成我们自己的枚举：只做一对一翻译，不改语义 */
    private fun DataSource.toLoadSource(): LoadSource = when (this) {
        DataSource.MEMORY_CACHE, DataSource.MEMORY -> LoadSource.MEMORY_CACHE
        DataSource.DISK -> LoadSource.DISK
        DataSource.NETWORK -> LoadSource.NETWORK
    }

    companion object {
        /** 面板上显示「x / 上限」用，和 ImageLoader 里的配置同源 */
        const val DISK_CACHE_LIMIT_BYTES = MangaImageLoader.DISK_CACHE_BYTES
    }
}
