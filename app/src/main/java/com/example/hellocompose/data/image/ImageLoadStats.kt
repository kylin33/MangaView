package com.example.hellocompose.data.image

import java.net.URI

/** 请求来自哪里：用户正在看的那一页，还是滚到后面去的投机预加载 */
enum class LoadRole { VISIBLE, PRELOAD }

/**
 * 图片最终从哪来。
 *
 * 直接对应 Coil 的 `DataSource`，不另外编一套：缓存命中率、命中构成都靠它，
 * 换成别的值就等于自己猜。
 */
enum class LoadSource { MEMORY_CACHE, DISK, NETWORK, NONE }

/** 一次已结束的图片加载 */
data class LoadEvent(
    val url: String,
    val host: String,
    val role: LoadRole,
    val durationMs: Long,
    val success: Boolean,
    val source: LoadSource,
    val failure: String?,
    val at: Long
)

data class FailureBucket(val reason: String, val count: Int)

data class RecentFailure(val url: String, val host: String, val reason: String, val at: Long)

/**
 * 面板读的一份快照。
 *
 * 统计口径写在这里，UI 不再二次计算 —— 否则「成功率」在两处会得出两个数。
 */
data class ImageStatsSnapshot(
    val startedAt: Long,
    val total: Int,
    val success: Int,
    val failed: Int,
    val cancelled: Int,
    val successRate: Double,
    val avgMs: Long,
    val p50Ms: Long,
    val p90Ms: Long,
    val avgVisibleMs: Long,
    val avgPreloadMs: Long,
    val memoryHits: Int,
    val diskHits: Int,
    val networkLoads: Int,
    val cacheHitRate: Double,
    val currentInFlight: Int,
    val peakInFlight: Int,
    val windowSize: Int,
    val failures: List<FailureBucket>,
    val recentFailures: List<RecentFailure>
) {
    val isEmpty: Boolean get() = total == 0

    companion object {
        val EMPTY = ImageStatsSnapshot(
            startedAt = 0L, total = 0, success = 0, failed = 0, cancelled = 0,
            successRate = 0.0, avgMs = 0, p50Ms = 0, p90Ms = 0,
            avgVisibleMs = 0, avgPreloadMs = 0,
            memoryHits = 0, diskHits = 0, networkLoads = 0, cacheHitRate = 0.0,
            currentInFlight = 0, peakInFlight = 0, windowSize = 0,
            failures = emptyList(), recentFailures = emptyList()
        )
    }
}

/**
 * 图片加载统计。
 *
 * 两个用途：一是面板上的数字，二是改参数时的依据 —— 「把并发从 2 调到 3 到底有没有用」
 * 只能靠 p50/p90 和失败率回答，靠肉眼看图看不出来。
 *
 * 口径：
 * - **成功率** = 成功 / (成功 + 失败)。主动取消不算失败 —— 滚动时取消预加载是正常行为，
 *   把它算进分母只会让成功率随着滑动变低，看起来像网络坏了。
 * - **平均耗时** 取全部已结束请求；**p50 / p90** 只取最近 [capacity] 条，
 *   早期数据对「现在快不快」没有参考价值。
 * - **缓存命中率** = (内存命中 + 磁盘命中) / 成功数。网络失败不拉低命中率，
 *   两个指标分开看才能定位问题。
 *
 * 事件保留在内存里，重启 App 就清零 —— 这是排查工具，不是上报系统。
 */
class ImageLoadStats(
    private val capacity: Int = 400,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val lock = Any()

    private val window = ArrayDeque<LoadEvent>(capacity)

    private var startedAt = clock()
    private var total = 0
    private var success = 0
    private var failed = 0
    private var cancelled = 0
    private var durationSum = 0L
    private var visibleSum = 0L
    private var visibleCount = 0
    private var preloadSum = 0L
    private var preloadCount = 0
    private var memoryHits = 0
    private var diskHits = 0
    private var networkLoads = 0
    private val failureCounts = HashMap<String, Int>()
    private val recentFailures = ArrayDeque<RecentFailure>(MAX_RECENT_FAILURES)

    fun record(event: LoadEvent) {
        synchronized(lock) {
            total += 1
            durationSum += event.durationMs
            if (event.success) {
                success += 1
                when (event.source) {
                    LoadSource.MEMORY_CACHE -> memoryHits += 1
                    LoadSource.DISK -> diskHits += 1
                    LoadSource.NETWORK -> networkLoads += 1
                    LoadSource.NONE -> Unit
                }
            } else {
                failed += 1
                val reason = event.failure.orEmpty().ifBlank { "未知原因" }
                failureCounts[reason] = (failureCounts[reason] ?: 0) + 1
                recentFailures.addFirst(
                    RecentFailure(event.url, event.host, reason, event.at)
                )
                while (recentFailures.size > MAX_RECENT_FAILURES) recentFailures.removeLast()
            }

            when (event.role) {
                LoadRole.VISIBLE -> {
                    visibleSum += event.durationMs
                    visibleCount += 1
                }
                LoadRole.PRELOAD -> {
                    preloadSum += event.durationMs
                    preloadCount += 1
                }
            }

            window.addLast(event)
            while (window.size > capacity) window.removeFirst()
        }
    }

    /** 用户主动取消的请求：数量记下来，不参与成功率 */
    fun recordCancelled() {
        synchronized(lock) { cancelled += 1 }
    }

    fun reset() {
        synchronized(lock) {
            window.clear()
            recentFailures.clear()
            failureCounts.clear()
            startedAt = clock()
            total = 0
            success = 0
            failed = 0
            cancelled = 0
            durationSum = 0
            visibleSum = 0
            visibleCount = 0
            preloadSum = 0
            preloadCount = 0
            memoryHits = 0
            diskHits = 0
            networkLoads = 0
        }
    }

    fun snapshot(currentInFlight: Int, peakInFlight: Int): ImageStatsSnapshot = synchronized(lock) {
        val durations = window.map { it.durationMs }.sorted()
        val settled = success + failed
        val hits = memoryHits + diskHits

        ImageStatsSnapshot(
            startedAt = startedAt,
            total = total,
            success = success,
            failed = failed,
            cancelled = cancelled,
            successRate = if (settled == 0) 0.0 else success.toDouble() / settled,
            avgMs = if (total == 0) 0 else durationSum / total,
            p50Ms = percentile(durations, 0.50),
            p90Ms = percentile(durations, 0.90),
            avgVisibleMs = if (visibleCount == 0) 0 else visibleSum / visibleCount,
            avgPreloadMs = if (preloadCount == 0) 0 else preloadSum / preloadCount,
            memoryHits = memoryHits,
            diskHits = diskHits,
            networkLoads = networkLoads,
            cacheHitRate = if (success == 0) 0.0 else hits.toDouble() / success,
            currentInFlight = currentInFlight,
            peakInFlight = peakInFlight,
            windowSize = window.size,
            failures = failureCounts.entries
                .sortedByDescending { it.value }
                .take(MAX_FAILURE_KINDS)
                .map { FailureBucket(it.key, it.value) },
            recentFailures = recentFailures.toList()
        )
    }

    /** 最近一次请求的耗时，用来判断「刚才那下卡了多久」；空表示还没有记录 */
    fun lastDurationMs(): Long? = synchronized(lock) { window.lastOrNull()?.durationMs }

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0
        // 向上取整，保证 p90 至少落在第 90% 那个样本上；用 round 会把小样本压到 0
        val index = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    companion object {
        private const val MAX_RECENT_FAILURES = 12
        private const val MAX_FAILURE_KINDS = 5

        /** 从地址里取主机名；解析不了就退回原串，不能让统计本身炸掉 */
        fun hostOf(url: String): String = runCatching {
            URI(url).host?.removePrefix("www.")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: url.take(40)
    }
}
