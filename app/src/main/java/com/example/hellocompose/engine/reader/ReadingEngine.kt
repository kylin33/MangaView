package com.example.hellocompose.engine.reader

import com.example.hellocompose.core.AppConfig
import com.example.hellocompose.core.AppError
import com.example.hellocompose.core.AppResult
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.engine.source.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 阅读引擎的状态。
 *
 * 只包含「读」这件事需要的东西：当前章节、页面、位置、加载状态。
 * 不含书名、封面、站点名——那是历史记录和 UI 的事。
 */
data class ReadingState(
    val chapter: Chapter = Chapter("", ""),
    val pages: List<Page> = emptyList(),
    /** 当前停在第几页（0 基） */
    val visibleIndex: Int = 0,
    /**
     * 页面加载完成后要跳到第几页（上次看到的位置）。
     * -1 表示不跳；UI 消费后调 [ReadingEngine.consumeRestore] 复位，
     * 否则用户手动滑回顶部会被反复拉回去。
     */
    val restoreIndex: Int = -1,
    val isLoading: Boolean = true,
    val error: AppError? = null,
    /** 当前章节在目录里的位置，-1 表示没在目录中找到 */
    val chapterIndex: Int = -1,
    val chapterCount: Int = 0,
    val preloadCount: Int = AppConfig.DEFAULT_PRELOAD
) {
    val pageCount: Int get() = pages.size
    val chapterTitle: String get() = chapter.title
    val chapterUrl: String get() = chapter.url

    /** 当前页的 Referer。图片防盗链靠它，页面级而非站点级 */
    val referer: String get() = pages.getOrNull(visibleIndex)?.referer.orEmpty()

    val hasPrev: Boolean get() = chapterIndex > 0
    val hasNext: Boolean get() = chapterIndex >= 0 && chapterIndex < chapterCount - 1
}

/**
 * 页面来源。阅读引擎不知道也不关心它是 CSS 选择器、内联脚本还是 API 抓来的。
 * 实现方负责「章节地址 → 标准页面列表」，并且把 Referer 填好。
 */
fun interface PageLoader {
    suspend fun load(chapterUrl: String): AppResult<List<Page>>
}

/**
 * 预加载。实现方通常是图片加载库（Coil），引擎只负责决定「预哪几页」。
 *
 * [retainOnly] 是「释放」那一半：切章时引擎告诉实现方现在只剩哪些页面需要，
 * 具体是取消请求还是清缓存由图片层决定——引擎不碰缓存，也就不会绑死在某套缓存实现上。
 */
fun interface PagePreloader {
    fun preload(page: Page)

    /** 切章后调用。默认空实现，纯预加载的实现方可以不管 */
    fun retainOnly(urls: Collection<String>) {}
}

/**
 * 位置落盘回调。
 *
 * 引擎已经做过去重和防抖，回调触发时就是「该记下来的值」，
 * 实现方直接写存储即可，不用再判断变化。
 */
fun interface PositionListener {
    suspend fun onSettled(chapter: Chapter, pageIndex: Int, pageCount: Int)
}

/**
 * 阅读引擎。
 *
 * 输入只有 [PageLoader]（产出标准化页面）、[PagePreloader] 和 [PositionListener]，
 * 因此它可以脱离任何站点、甚至在纯 JVM 里跑测试。
 *
 * 职责：
 * - **页面加载与调度**：切章时取消上一章的加载，避免旧响应覆盖新章节；
 * - **预加载窗口**：可见页之后 N 页入队，调到最末页不会越界；
 * - **跨章释放**：切章时把「只需要这几页」告诉预加载方，旧章节的请求和位图及时放掉；
 * - **位置管理**：恢复上次页码（只用一次）、停稳后防抖落盘；
 * - **章节切换**：持有正序目录，上一章/下一章在内部完成，不回到目录页；
 * - **失败重试**：重开当前章并回到原来的页码；
 * - **下一章预取**：滑到本章末尾附近时预取下一章前几页，翻页时不用干等。
 */
class ReadingEngine(
    private val loader: PageLoader,
    private val preloader: PagePreloader,
    private val initialChapter: Chapter,
    private val scope: CoroutineScope,
    preloadCount: Int = AppConfig.DEFAULT_PRELOAD,
    private val positionListener: PositionListener? = null,
    /** 滑动停多久之后算「停稳」，用于防抖落盘 */
    private val settleDelayMs: Long = 600L
) {

    private val _state = MutableStateFlow(
        ReadingState(
            chapter = initialChapter,
            preloadCount = preloadCount
        )
    )
    val state: StateFlow<ReadingState> = _state.asStateFlow()

    /** 已归一化（从早到晚）的目录。目录晚于图片到达，所以允许先空着后补 */
    private var chapters: List<Chapter> = listOf(initialChapter)

    private var currentChapter: Chapter = initialChapter

    /** 已入队的页索引，避免同一张图被重复请求。切章时清空 */
    private var enqueued = mutableSetOf<Int>()

    private var loadJob: Job? = null
    private var settleJob: Job? = null
    private var prefetchJob: Job? = null

    private var settledChapterUrl = ""
    private var settledPage = -1

    /** 已预取过的下一章，防止反复触发 */
    private var prefetchedChapterUrl: String? = null

    private var windowSize = preloadCount

    /** 首次进入：resumePage 是上次读到的那一页（0 表示从头开始） */
    fun start(resumePage: Int = 0) {
        open(initialChapter, resumePage, restore = resumePage > 0)
    }

    /** 目录到齐后调用。会重新定位当前章节，刷新上一章/下一章可用性 */
    fun updateChapters(list: List<Chapter>) {
        chapters = if (list.isEmpty()) listOf(currentChapter) else ChapterOrder.normalize(list)
        syncChapterIndex()
    }

    /** 失败重试：重开当前章，回到原来的页码 */
    fun retry() {
        open(currentChapter, _state.value.visibleIndex, restore = _state.value.visibleIndex > 0)
    }

    fun openNext() {
        val index = _state.value.chapterIndex
        if (index >= 0 && index + 1 < chapters.size) open(chapters[index + 1], 0, restore = false)
    }

    fun openPrev() {
        val index = _state.value.chapterIndex
        if (index > 0) open(chapters[index - 1], 0, restore = false)
    }

    fun openChapter(chapter: Chapter) = open(chapter, 0, restore = false)

    /**
     * 可见页变化。这是阅读过程中最频繁的调用，只做三件事：
     * 更新位置、维护预加载窗口、安排停稳后落盘。
     */
    fun onVisiblePage(index: Int) {
        val snapshot = _state.value
        if (snapshot.pages.isEmpty() || index !in snapshot.pages.indices) return
        if (snapshot.visibleIndex == index) return

        _state.update { it.copy(visibleIndex = index) }
        schedulePreload(index)
        scheduleSettle(index)
        prefetchNextIfNeeded(index)
    }

    /** UI 已经按 restoreIndex 跳过位置，复位防止反复跳动 */
    fun consumeRestore() {
        if (_state.value.restoreIndex < 0) return
        _state.update { it.copy(restoreIndex = -1) }
    }

    fun setPreloadCount(count: Int) {
        windowSize = count
        _state.update { it.copy(preloadCount = count) }
        // 用户调大预载数后立即补齐窗口，不用等下次滑动
        schedulePreload(_state.value.visibleIndex)
    }

    /**
     * 离开阅读页时取一次快照，用于落盘兜底。
     * 引擎自己的协程可能已经被取消，所以不在这里写存储。
     */
    fun snapshot(): ReadingState = _state.value

    // ---------------- 内部 ----------------

    private fun open(chapter: Chapter, resumePage: Int, restore: Boolean) {
        val changingChapter = !ChapterUrls.same(chapter.url, currentChapter.url)

        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedChapterUrl = null

        if (changingChapter) {
            // 翻章前先把上一章的位置定格，否则「看到第 20 页就翻下一章」
            // 会把 20 页记成本章的开头
            settleJob?.cancel()
            settleNow(_state.value)
        }

        currentChapter = chapter
        enqueued.clear()

        _state.update {
            it.copy(
                chapter = chapter,
                pages = emptyList(),
                isLoading = true,
                error = null,
                visibleIndex = 0,
                restoreIndex = -1
            )
        }

        loadJob = scope.launch {
            when (val result = loader.load(chapter.url)) {
                is AppResult.Success -> {
                    val pages = result.data.withRefererFallback(chapter.url)
                    val resume = resumePage.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
                    // 新章节的页面到了，旧章节的预载和位图就没用了：
                    // 长图很占内存，不主动放掉，新一话的图就得跟它们抢名额
                    preloader.retainOnly(pages.map { it.url })
                    _state.update {
                        it.copy(
                            isLoading = false,
                            pages = pages,
                            error = null,
                            visibleIndex = resume,
                            restoreIndex = if (restore && resume > 0) resume else -1
                        )
                    }
                    schedulePreload(resume)
                    prefetchNextIfNeeded(resume)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(isLoading = false, pages = emptyList(), error = result.error)
                }
            }
            syncChapterIndex()
        }
    }

    private fun syncChapterIndex() {
        val index = chapters.indexOfFirst { ChapterUrls.same(it.url, currentChapter.url) }
        _state.update {
            it.copy(
                chapterIndex = index,
                chapterCount = chapters.size,
                chapter = if (it.chapter.title.isBlank() && index >= 0) {
                    chapters[index]
                } else {
                    it.chapter
                }
            )
        }
    }

    private fun schedulePreload(fromIndex: Int) {
        val pages = _state.value.pages
        if (pages.isEmpty() || windowSize <= 0) return

        val start = fromIndex + 1
        val end = (fromIndex + windowSize).coerceAtMost(pages.lastIndex)
        if (start > end) return

        for (index in start..end) {
            if (enqueued.add(index)) {
                preloader.preload(pages[index])
            }
        }
    }

    /**
     * 滑到本章末尾附近时预取下一章的头几页。
     *
     * 只预取、不切换：用户可能只是想重看最后几页。真正翻章还是点按钮或滑到底，
     * 但那时候图片已经进缓存，等待感基本消失。
     */
    private fun prefetchNextIfNeeded(index: Int) {
        val snapshot = _state.value
        if (!snapshot.hasNext || snapshot.pageCount == 0) return
        // 离末尾还远，没必要为下一章浪费流量
        if (index < snapshot.pageCount - NEXT_PREFETCH_DISTANCE) return

        val next = chapters.getOrNull(snapshot.chapterIndex + 1) ?: return
        if (prefetchedChapterUrl == next.url) return
        prefetchedChapterUrl = next.url

        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            when (val result = loader.load(next.url)) {
                is AppResult.Success -> result.data.take(NEXT_PREFETCH_PAGES).forEach(preloader::preload)
                // 预取失败不影响当前章，等用户真的翻过去时再正经加载并报错
                is AppResult.Failure -> Unit
            }
        }
    }

    private fun scheduleSettle(page: Int) {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(settleDelayMs)
            settle(page, currentChapter)
        }
    }

    private suspend fun settle(page: Int, chapter: Chapter) {
        if (page == settledPage && ChapterUrls.same(chapter.url, settledChapterUrl)) return
        settledPage = page
        settledChapterUrl = chapter.url
        positionListener?.onSettled(chapter, page, _state.value.pageCount)
    }

    /** 立即定格当前位置（翻章/退出时用），不等防抖 */
    private fun settleNow(snapshot: ReadingState) {
        if (snapshot.pages.isEmpty()) return
        val page = snapshot.visibleIndex.coerceIn(0, snapshot.pages.lastIndex)
        scope.launch { settle(page, snapshot.chapter) }
    }

    private companion object {
        /** 距离本章末尾还有几页时开始预取下一章 */
        const val NEXT_PREFETCH_DISTANCE = 3

        /** 预取下一章的页数：只够「翻过去不白屏」，多了浪费流量 */
        const val NEXT_PREFETCH_PAGES = 2
    }
}

/**
 * 站点没配 Referer 时，用章节页地址兜底。
 *
 * 不少站只要 Referer 存在就放行，值是什么无所谓。补在这里而不是放在 UI：
 * 展示和预加载必须发同一套请求头，否则预加载下来的图对显示那条请求毫无帮助，
 * 白花一次流量。
 */
private fun List<Page>.withRefererFallback(chapterUrl: String): List<Page> =
    if (chapterUrl.isBlank() || none { it.referer.isBlank() }) {
        this
    } else {
        map { if (it.referer.isBlank()) it.copy(referer = chapterUrl) else it }
    }
