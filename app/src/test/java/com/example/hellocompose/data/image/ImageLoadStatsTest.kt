package com.example.hellocompose.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计口径的回归测试。
 *
 * 面板上的数字是要拿来下判断的（「并发调到 3 有没有用」「是不是图床慢」），
 * 所以口径必须钉死：取消不算失败、命中率不背网络的锅、分位数只看最近窗口。
 */
class ImageLoadStatsTest {

    private fun event(
        durationMs: Long,
        success: Boolean = true,
        source: LoadSource = LoadSource.NETWORK,
        role: LoadRole = LoadRole.VISIBLE,
        failure: String? = null,
        url: String = "https://img.example.com/p/1.jpg"
    ) = LoadEvent(
        url = url,
        host = ImageLoadStats.hostOf(url),
        role = role,
        durationMs = durationMs,
        success = success,
        source = source,
        failure = failure,
        at = 1_000L
    )

    private fun stats(vararg events: LoadEvent, capacity: Int = 400): ImageLoadStats {
        val stats = ImageLoadStats(capacity = capacity) { 0L }
        events.forEach(stats::record)
        return stats
    }

    @Test
    fun successRateIgnoresCancelledRequests() {
        val stats = ImageLoadStats { 0L }
        repeat(3) { stats.record(event(100)) }
        stats.record(event(100, success = false, source = LoadSource.NONE, failure = "超时"))
        repeat(2) { stats.recordCancelled() }

        val snapshot = stats.snapshot(currentInFlight = 0, peakInFlight = 0)

        assertEquals(4, snapshot.total)
        assertEquals(3, snapshot.success)
        assertEquals(1, snapshot.failed)
        assertEquals(2, snapshot.cancelled)
        // 取消不参与分母，否则滑动越快成功率越低
        assertEquals(0.75, snapshot.successRate, 1e-9)
    }

    @Test
    fun cacheHitRateCountsMemoryAndDiskOnly() {
        val stats = stats(
            event(10, source = LoadSource.MEMORY_CACHE),
            event(20, source = LoadSource.MEMORY_CACHE),
            event(30, source = LoadSource.DISK),
            event(40, source = LoadSource.NETWORK)
        )

        val snapshot = stats.snapshot(0, 0)

        assertEquals(2, snapshot.memoryHits)
        assertEquals(1, snapshot.diskHits)
        assertEquals(1, snapshot.networkLoads)
        assertEquals(0.75, snapshot.cacheHitRate, 1e-9)
        // 平均耗时取全部，包括命中缓存的
        assertEquals(25, snapshot.avgMs)
    }

    @Test
    fun percentilesUseRecentWindow() {
        // 10 条 10..100ms：p90 落在第 9 条（90），不是最大值
        val events = (1..10).map { event(it * 10L) }.toTypedArray()
        val snapshot = stats(*events).snapshot(0, 0)

        assertEquals(90, snapshot.p90Ms)
        assertEquals(50, snapshot.p50Ms)
    }

    @Test
    fun windowKeepsRecentEventsButTotalsStayLifetime() {
        val stats = ImageLoadStats(capacity = 3) { 0L }
        (1..5).forEach { stats.record(event(it * 100L)) }

        val snapshot = stats.snapshot(0, 0)

        assertEquals(5, snapshot.total)
        assertEquals(3, snapshot.windowSize)
        // 平均取全部：(100+200+300+400+500)/5 = 300
        assertEquals(300, snapshot.avgMs)
        // p50 只看最近三条 300/400/500
        assertEquals(400, snapshot.p50Ms)
    }

    @Test
    fun failuresAreGroupedAndSortedByCount() {
        val stats = stats(
            event(10, success = false, source = LoadSource.NONE, failure = "图片加载超时"),
            event(10, success = false, source = LoadSource.NONE, failure = "图片加载超时"),
            event(10, success = false, source = LoadSource.NONE, failure = "站点拒绝了图片请求（可能是防盗链），可以试试换源")
        )

        val snapshot = stats.snapshot(0, 0)

        assertEquals(2, snapshot.failures.size)
        assertEquals("图片加载超时", snapshot.failures[0].reason)
        assertEquals(2, snapshot.failures[0].count)
        // 最近失败按时间倒序，最新的在最前
        assertEquals(3, snapshot.recentFailures.size)
        assertTrue(snapshot.recentFailures.first().reason.contains("防盗链"))
    }

    @Test
    fun rolesAreAveragedSeparately() {
        val stats = stats(
            event(200, role = LoadRole.VISIBLE),
            event(400, role = LoadRole.VISIBLE),
            event(1_000, role = LoadRole.PRELOAD)
        )

        val snapshot = stats.snapshot(0, 0)

        assertEquals(300, snapshot.avgVisibleMs)
        assertEquals(1_000, snapshot.avgPreloadMs)
    }

    @Test
    fun peakIsPassedThroughNotInferred() {
        // 并发是请求层的知识，统计层不猜；这里只确认原样透出
        val snapshot = stats(event(10)).snapshot(currentInFlight = 2, peakInFlight = 5)

        assertEquals(2, snapshot.currentInFlight)
        assertEquals(5, snapshot.peakInFlight)
    }

    @Test
    fun resetClearsCountersAndWindow() {
        val stats = stats(event(10), event(20, success = false, source = LoadSource.NONE, failure = "x"))
        stats.reset()

        val snapshot = stats.snapshot(0, 0)

        assertTrue(snapshot.isEmpty)
        assertEquals(0, snapshot.total)
        assertEquals(0, snapshot.avgMs)
        assertTrue(snapshot.failures.isEmpty())
        assertFalse(snapshot.startedAt < 0)
    }

    @Test
    fun hostStripsWwwAndSurvivesBrokenUrls() {
        assertEquals("img.example.com", ImageLoadStats.hostOf("https://www.img.example.com/a.jpg"))
        assertEquals("cdn.example.com", ImageLoadStats.hostOf("https://cdn.example.com:8443/a.jpg"))
        // 解析不了也不能抛，否则统计把自己搞挂了
        assertTrue(ImageLoadStats.hostOf("not a url").isNotBlank())
    }

    @Test
    fun emptyStatsReportZeroesNotCrash() {
        val snapshot = ImageLoadStats { 0L }.snapshot(0, 0)

        assertTrue(snapshot.isEmpty)
        assertEquals(0.0, snapshot.successRate, 1e-9)
        assertEquals(0.0, snapshot.cacheHitRate, 1e-9)
        assertEquals(0, snapshot.p90Ms)
    }
}
