package com.example.hellocompose.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.example.hellocompose.core.AppResult
import com.example.hellocompose.data.history.HistoryRepository
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.data.image.CoilPagePreloader
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.repository.MangaRepository
import com.example.hellocompose.data.settings.SettingsRepository
import com.example.hellocompose.data.source.SourceRepository
import com.example.hellocompose.engine.reader.ChapterUrls
import com.example.hellocompose.engine.reader.PageLoader
import com.example.hellocompose.engine.reader.PositionListener
import com.example.hellocompose.engine.reader.ReadingEngine
import com.example.hellocompose.engine.reader.ReadingState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 阅读页的编排层。
 *
 * 真正的阅读逻辑（加载、预加载、位置、翻章、重试）在 [ReadingEngine] 里，
 * 它只认 [Page]，不知道站点、也不知道配置的存在。这个 ViewModel 只剩三件事：
 * 1. 给引擎提供页面来源（[PageLoader] → 仓库 → 站点适配引擎）；
 * 2. 把引擎的位置回调翻译成 [ReadingRecord] 落盘；
 * 3. 维护目录和配置订阅。
 */
class ReaderViewModel(
    private val initialChapter: Chapter,
    /** 漫画名：历史记录靠它跨站点重建这本书的身份 */
    private val mangaTitle: String,
    private val coverUrl: String,
    /** 详情页地址，用来取整份目录。为空时只读当前章、不显示翻章按钮 */
    private val detailUrl: String,
    private val repository: MangaRepository,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    context: Context,
    imageLoader: ImageLoader
) : ViewModel() {

    private val engine = ReadingEngine(
        loader = PageLoader { chapterUrl -> repository.getPages(chapterUrl) },
        preloader = CoilPagePreloader(context.applicationContext, imageLoader),
        initialChapter = initialChapter,
        scope = viewModelScope,
        positionListener = PositionListener { chapter, page, pageCount ->
            persist(chapter, page, pageCount)
        }
    )

    /** 直接暴露引擎状态：UI 需要的就是这些，再包一层只会出现两份要同步的字段 */
    val state: StateFlow<ReadingState> = engine.state

    // 记录时要写清「在哪个站点看的」，续读前据此把站点切回来
    private var sourceId: String = ""
    private var sourceName: String = ""

    /** 上次落盘的页，避免退出时重复写同一条 */
    private var lastSavedChapterUrl: String = ""
    private var lastSavedPage: Int = -1

    init {
        observePreloadSetting()
        viewModelScope.launch {
            val source = runCatching { sources.current() }.getOrNull()
            sourceId = source?.id.orEmpty()
            sourceName = source?.name.orEmpty()

            // 先查历史再开章节：顺序反了会先滚到顶部再跳回上次的位置，白闪一下
            engine.start(resolveResumeIndex())
            loadChapters()
        }
    }

    fun retry() = engine.retry()

    fun openNext() = engine.openNext()

    fun openPrev() = engine.openPrev()

    fun onVisiblePage(index: Int) = engine.onVisiblePage(index)

    fun onRestoreConsumed() = engine.consumeRestore()

    fun setPreloadCount(count: Int) {
        viewModelScope.launch { settings.setPreloadCount(count) }
    }

    /** 上一次停在的页码；只有「这次打开的就是上次读的那一章」才算数 */
    private suspend fun resolveResumeIndex(): Int = runCatching {
        val record = history.find(detailUrl)
        if (record != null &&
            record.chapterUrl.isNotBlank() &&
            ChapterUrls.same(record.chapterUrl, initialChapter.url)
        ) {
            record.pageIndex.coerceAtLeast(0)
        } else {
            0
        }
    }.getOrDefault(0)

    /**
     * 目录后到：先让用户看到图，目录回来了再补上翻章能力。
     * 拿不到只影响翻章按钮，不影响当前章节的阅读，所以不报错。
     */
    private suspend fun loadChapters() {
        if (detailUrl.isBlank()) return
        when (val result = runCatching { repository.getChapters(detailUrl) }.getOrNull()) {
            is AppResult.Success -> engine.updateChapters(result.data)
            // 目录拿不到只影响翻章按钮，不影响当前章节的阅读，所以不报错
            is AppResult.Failure, null -> Unit
        }
    }

    private fun observePreloadSetting() {
        viewModelScope.launch {
            settings.preloadCount.collect { count ->
                engine.setPreloadCount(count)
            }
        }
    }

    private suspend fun persist(chapter: Chapter, page: Int, pageCount: Int) {
        if (pageCount <= 0) return
        val snapshot = engine.state.value
        val index = page.coerceIn(0, pageCount - 1)
        if (index == lastSavedPage && ChapterUrls.same(chapter.url, lastSavedChapterUrl)) return

        lastSavedPage = index
        lastSavedChapterUrl = chapter.url
        history.save(buildRecord(snapshot, chapter, index, pageCount))
    }

    private fun buildRecord(
        state: ReadingState,
        chapter: Chapter,
        page: Int,
        pageCount: Int
    ): ReadingRecord = ReadingRecord(
        mangaTitle = mangaTitle.ifBlank { chapter.title },
        coverUrl = coverUrl,
        // 没有详情页时退化成章节地址：至少留得下一条记录，不至于看完什么都没留下
        detailUrl = detailUrl.ifBlank { chapter.url },
        sourceId = sourceId,
        sourceName = sourceName,
        chapterTitle = chapter.title,
        chapterUrl = chapter.url,
        pageIndex = page,
        pageCount = pageCount,
        chapterIndex = state.chapterIndex,
        chapterCount = state.chapterCount,
        updatedAt = System.currentTimeMillis()
    )

    /**
     * 退出阅读器时 ViewModel 会被销毁，引擎里防抖的那次落盘会被一起取消，
     * 最后停的那一页就丢了。这里用仓库自管的作用域补一次。
     */
    override fun onCleared() {
        val snapshot = engine.snapshot()
        if (snapshot.pages.isNotEmpty()) {
            val page = snapshot.visibleIndex.coerceIn(0, snapshot.pages.lastIndex)
            if (page != lastSavedPage || !ChapterUrls.same(snapshot.chapter.url, lastSavedChapterUrl)) {
                history.saveAsync(buildRecord(snapshot, snapshot.chapter, page, snapshot.pages.size))
            }
        }
        super.onCleared()
    }
}
