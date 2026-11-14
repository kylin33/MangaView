package com.example.hellocompose.engine.reader

import com.example.hellocompose.core.AppError
import com.example.hellocompose.core.AppResult
import com.example.hellocompose.core.ErrorType
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.engine.source.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读引擎的核心行为。
 *
 * 引擎只吃 [Page]，所以这里不需要任何站点、HTML 或网络：
 * 用假的 loader 就能把「恢复位置、预加载窗口、翻章、失败重试」全部跑一遍。
 * 这正是拆分引擎的目的——这些行为以前埋在 ViewModel 里，只能靠手点验证。
 */
class ReadingEngineTest {

    private val chapters = listOf(
        Chapter("第 1 话", "/c/1"),
        Chapter("第 2 话", "/c/2")
    )

    @Test
    fun restoresPositionAndPreloadsFollowingPages() = runBlocking {
        val preloaded = mutableListOf<String>()
        val engine = newEngine(
            loader = { url -> AppResult.Success(pagesOf(url, 6)) },
            preloader = { page -> preloaded += page.url }
        )

        engine.start(resumePage = 2)
        val state = engine.state.first { !it.isLoading }

        assertEquals(6, state.pageCount)
        // 进来就停在上次那一页，同时告诉 UI 要滚到哪
        assertEquals(2, state.visibleIndex)
        assertEquals(2, state.restoreIndex)
        // 预加载只覆盖可见页之后的窗口，不重复加载当前页
        assertEquals(listOf("/c/1/p3", "/c/1/p4"), preloaded)
    }

    @Test
    fun restoreIndexIsConsumedOnlyOnce() = runBlocking {
        val engine = newEngine(loader = { url -> AppResult.Success(pagesOf(url, 4)) })
        engine.start(resumePage = 1)
        engine.state.first { !it.isLoading }

        engine.consumeRestore()
        assertEquals(-1, engine.state.value.restoreIndex)
        // 再调一次不会出错，也不该把 -1 变成别的值
        engine.consumeRestore()
        assertEquals(-1, engine.state.value.restoreIndex)
    }

    @Test
    fun openingNextChapterMovesIndexAndKeepsOldPositionSaved() = runBlocking {
        val settled = mutableListOf<Pair<String, Int>>()
        val engine = newEngine(
            loader = { url -> AppResult.Success(pagesOf(url, 4)) },
            positionListener = { chapter, page, _ -> settled += chapter.url to page }
        )
        engine.start()
        engine.state.first { !it.isLoading }
        engine.updateChapters(chapters)

        engine.onVisiblePage(2)
        waitUntil { settled.isNotEmpty() }
        engine.openNext()

        val next = engine.state.first { !it.isLoading && it.chapterUrl == "/c/2" }
        assertEquals(1, next.chapterIndex)
        assertTrue(next.hasPrev)
        assertFalse(next.hasNext)
        // 翻章前要把上一章的页码定格：记下来的必须是第 3 页（索引 2），不是开头
        assertEquals("/c/1" to 2, settled.first())
        assertTrue(
            "翻章把上一章的位置改成了别的值：$settled",
            settled.none { it.first == "/c/1" && it.second != 2 }
        )
    }

    @Test
    fun failedLoadKeepsErrorAndRetryRecovers() = runBlocking {
        var failing = true
        val engine = newEngine(
            loader = { url ->
                if (failing) {
                    AppResult.Failure(AppError(ErrorType.PARSE, "选择器失效"))
                } else {
                    AppResult.Success(pagesOf(url, 3))
                }
            }
        )

        engine.start()
        val failed = engine.state.first { !it.isLoading }
        assertNotNull(failed.error)
        assertTrue(failed.pages.isEmpty())

        failing = false
        engine.retry()
        val recovered = engine.state.first { !it.isLoading && it.error == null }
        assertEquals(3, recovered.pageCount)
        assertNull(recovered.error)
    }

    @Test
    fun preloadWindowStopsAtLastPage() = runBlocking {
        val preloaded = mutableListOf<String>()
        val engine = newEngine(
            loader = { url -> AppResult.Success(pagesOf(url, 2)) },
            preloader = { page -> preloaded += page.url },
            preloadCount = 5
        )
        engine.start(resumePage = 1)
        engine.state.first { !it.isLoading }

        // 只有两页，可见页已经是最后一页，不该有任何预加载
        assertTrue(preloaded.isEmpty())
    }

    @Test
    fun chapterSwitchReleasesPagesOfPreviousChapter() = runBlocking {
        val retained = mutableListOf<List<String>>()
        val preloader = object : PagePreloader {
            override fun preload(page: Page) = Unit
            override fun retainOnly(urls: Collection<String>) {
                retained += urls.toList()
            }
        }
        val engine = newEngine(
            loader = { url -> AppResult.Success(pagesOf(url, 3)) },
            preloader = preloader
        )
        engine.start()
        engine.state.first { !it.isLoading }
        engine.updateChapters(chapters)

        engine.openNext()
        engine.state.first { !it.isLoading && it.chapterUrl == "/c/2" }

        // 每章加载完都告诉图片层「现在只剩这几页」：旧章节的请求和位图才能及时让位，
        // 否则长图会跟着章节一直堆在内存里
        assertEquals(listOf("/c/1/p0", "/c/1/p1", "/c/1/p2"), retained[0])
        assertEquals(listOf("/c/2/p0", "/c/2/p1", "/c/2/p2"), retained[1])
    }

    @Test
    fun blankRefererFallsBackToChapterUrl() = runBlocking {
        val preloadedReferers = mutableListOf<String>()
        val engine = newEngine(
            loader = { url -> AppResult.Success((0 until 4).map { Page(it, "$url/p$it") }) },
            preloader = { page -> preloadedReferers += page.referer }
        )

        engine.start()
        val state = engine.state.first { !it.isLoading }

        // 站点没配 Referer：用章节页兜底，且预加载拿到的请求头必须和展示一致，
        // 否则预载下来的图对显示那条请求毫无帮助
        assertTrue(state.pages.all { it.referer == "/c/1" })
        assertTrue(preloadedReferers.isNotEmpty())
        assertTrue(preloadedReferers.all { it == "/c/1" })
    }

    // ---------------- 工具 ----------------

    private suspend fun newEngine(
        loader: PageLoader,
        preloader: PagePreloader = PagePreloader { },
        preloadCount: Int = 2,
        positionListener: PositionListener? = null
    ): ReadingEngine = ReadingEngine(
        loader = loader,
        preloader = preloader,
        initialChapter = chapters.first(),
        // runBlocking 的 scope：引擎里的协程和测试在同一个事件循环上
        scope = CoroutineScope(coroutineContext),
        preloadCount = preloadCount,
        positionListener = positionListener,
        settleDelayMs = 0
    )

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(5)
        }
    }

    private fun pagesOf(chapterUrl: String, count: Int): List<Page> =
        (0 until count).map { Page(it, "$chapterUrl/p$it", "https://ref/") }
}
