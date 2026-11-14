package com.example.hellocompose.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import com.example.hellocompose.core.AppConfig
import com.example.hellocompose.core.AppError
import com.example.hellocompose.core.AppResult
import com.example.hellocompose.data.history.HistoryRepository
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.data.image.MangaImageRequests
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.repository.MangaRepository
import com.example.hellocompose.data.settings.SettingsRepository
import com.example.hellocompose.data.source.SourceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState(
    val isLoading: Boolean = true,
    val images: List<String> = emptyList(),
    val error: AppError? = null,
    val preloadCount: Int = AppConfig.DEFAULT_PRELOAD,
    val visibleIndex: Int = 0,
    /** 图片防盗链用的 Referer，来自站点配置的 reader.referer 模板 */
    val referer: String = "",
    val chapterUrl: String = "",
    val chapterTitle: String = "",
    /** 当前章节在目录里的位置，-1 表示没在目录中找到 */
    val chapterIndex: Int = -1,
    val chapterCount: Int = 0,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    /**
     * 页面加载完成后要跳到第几页（上次看到的位置）。
     * -1 表示不跳，UI 消费掉之后会调 [ReaderViewModel.onRestoreConsumed] 复位，
     * 避免用户手动滑回顶部后又被拉回去。
     */
    val restoreIndex: Int = -1
)

/**
 * 阅读器。
 *
 * 它持有整份章节目录，翻章在内部完成而不是再走一次导航：
 * - 用户读完本章可以直接点「下一章」，不用退回目录；
 * - 不会因为连翻十章把返回栈堆成十层，返回键仍然直接回详情页。
 *
 * 预加载调度也在这里（而不是 Composable），旋转屏幕时已排队的请求不会重复发起。
 *
 * 进度也归它管：进来先查这本书上次停在第几页，出去或翻页时写回。
 * 写回带上书名、封面、站点名，所以哪怕站点第二天就关了，
 * 历史页仍然能显示「我看过什么」，并拿书名去别的站点搜。
 */
class ReaderViewModel(
    private val initialChapterUrl: String,
    initialChapterTitle: String,
    /** 漫画名：历史记录靠它跨站点重建这本书的身份 */
    private val mangaTitle: String,
    private val coverUrl: String,
    /** 详情页地址，用来取整份目录。为空时只读当前章、不显示翻章按钮 */
    private val detailUrl: String,
    private val repository: MangaRepository,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val context: Context,
    private val imageLoader: ImageLoader
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            chapterUrl = initialChapterUrl,
            chapterTitle = initialChapterTitle
        )
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    /** 归一化成正序（从早到晚）的章节目录 */
    private val chapters = mutableListOf<Chapter>()

    // 已入队的页索引，避免同一张图被重复请求
    private val enqueuedIndices = mutableSetOf<Int>()
    private var preloadCount = AppConfig.DEFAULT_PRELOAD

    private var currentUrl = initialChapterUrl

    // 站点没配 reader.referer 时退化成"章节页自身"，绝大多数站这么做都能过防盗链
    private var referer: String = initialChapterUrl

    // 记录时要写清「在哪个站点看的」，续读前据此把站点切回来
    private var sourceId: String = ""
    private var sourceName: String = ""

    /** 待恢复的页码，0 表示从头开始 */
    private var resumePageIndex = 0

    private var saveJob: Job? = null
    private var lastPersistedPage = -1
    private var lastPersistedChapterUrl = ""

    init {
        observePreloadSetting()
        viewModelScope.launch {
            // 先查历史再开章节：顺序反了会先滚到顶部再跳回上次的位置，白闪一下
            resumePageIndex = resolveResumeIndex()
            open(initialChapterUrl, initialChapterTitle, reloadChapters = true)
        }
    }

    /** 上一次停在的页码；只有「这次打开的就是上次读的那一章」才算数 */
    private suspend fun resolveResumeIndex(): Int = runCatching {
        val record = history.find(detailUrl)
        if (record != null && record.chapterUrl.isNotBlank() && sameUrl(record.chapterUrl, initialChapterUrl)) {
            record.pageIndex.coerceAtLeast(0)
        } else {
            0
        }
    }.getOrDefault(0)

    /** 失败重试：重开当前章节；目录已经有了就不再重复请求 */
    fun load() = open(currentUrl, _uiState.value.chapterTitle, chapters.isEmpty())

    fun openNext() {
        val index = _uiState.value.chapterIndex
        if (index >= 0 && index + 1 < chapters.size) {
            val next = chapters[index + 1]
            open(next.url, next.title)
        }
    }

    fun openPrev() {
        val index = _uiState.value.chapterIndex
        if (index > 0) {
            val prev = chapters[index - 1]
            open(prev.url, prev.title)
        }
    }

    fun onVisibleIndexChanged(index: Int) {
        if (_uiState.value.visibleIndex == index) return
        _uiState.update { it.copy(visibleIndex = index) }
        schedulePreload(index)
        schedulePersist(index)
    }

    /** UI 已经按 restoreIndex 跳过位置，复位防止反复跳动 */
    fun onRestoreConsumed() {
        if (_uiState.value.restoreIndex < 0) return
        _uiState.update { it.copy(restoreIndex = -1) }
    }

    fun setPreloadCount(count: Int) {
        viewModelScope.launch { settings.setPreloadCount(count) }
    }

    /**
     * 打开某一章。
     *
     * 先取图片再取目录：图片是用户等待的那一步，目录晚几百毫秒出现无所谓。
     */
    private fun open(url: String, title: String, reloadChapters: Boolean = false) {
        viewModelScope.launch {
            // 翻章前先把上一章的位置定格，否则「看到第 20 页就翻下一章」会把 20 页记成本章的开头
            if (url != currentUrl) {
                saveJob?.cancel()
                persistPage(_uiState.value.visibleIndex)
            }

            currentUrl = url
            enqueuedIndices.clear()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    images = emptyList(),
                    visibleIndex = 0,
                    restoreIndex = -1,
                    chapterUrl = url,
                    chapterTitle = title
                )
            }

            val source = runCatching { sources.current() }.getOrNull()
            sourceId = source?.id.orEmpty()
            sourceName = source?.name.orEmpty()
            referer = source?.expand(source.reader.referer, url) ?: url
            _uiState.update { it.copy(referer = referer) }

            when (val result = repository.getChapterImages(url)) {
                is AppResult.Success -> {
                    val images = result.data
                    val resume = resumePageIndex.coerceIn(0, images.lastIndex.coerceAtLeast(0))
                    resumePageIndex = 0
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            images = images,
                            error = null,
                            visibleIndex = resume,
                            restoreIndex = if (resume > 0) resume else -1
                        )
                    }
                    schedulePreload(resume)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.error)
                }
            }

            if (reloadChapters || chapters.isEmpty()) {
                loadChapters()
            } else {
                syncChapterIndex()
            }
        }
    }

    private suspend fun loadChapters() {
        if (detailUrl.isBlank() || chapters.isNotEmpty()) return

        when (val result = repository.getChapters(detailUrl)) {
            is AppResult.Success -> {
                chapters.clear()
                chapters.addAll(orderChapters(result.data))
                syncChapterIndex()
            }
            // 目录拿不到只影响翻章按钮，不影响当前章节的阅读，所以不报错
            is AppResult.Failure -> Unit
        }
    }

    private fun syncChapterIndex() {
        val index = chapters.indexOfFirst { sameUrl(it.url, currentUrl) }
        _uiState.update { state ->
            state.copy(
                chapterIndex = index,
                chapterCount = chapters.size,
                hasPrev = index > 0,
                hasNext = index >= 0 && index < chapters.lastIndex,
                chapterTitle = if (state.chapterTitle.isBlank() && index >= 0) {
                    chapters[index].title
                } else {
                    state.chapterTitle
                }
            )
        }
    }

    /**
     * 目录顺序归一化。
     *
     * 有的站点按「最新在前」排，直接 +1 会翻回上一章。这里比较相邻标题里的数字，
     * 整体递减就反转一次，之后统一按「从早到晚」处理。
     */
    private fun orderChapters(list: List<Chapter>): List<Chapter> {
        if (list.size < 2) return list

        val comparisons = list
            .map { CHAPTER_NUMBER.find(it.title)?.value?.toIntOrNull() }
            .zipWithNext()
            .mapNotNull { (a, b) -> if (a == null || b == null) null else a.compareTo(b) }

        val descending = comparisons.count { it > 0 }
        val ascending = comparisons.count { it < 0 }
        return if (descending > ascending) list.reversed() else list
    }

    private fun sameUrl(a: String, b: String): Boolean =
        a == b || a.substringBefore('?').trimEnd('/') == b.substringBefore('?').trimEnd('/')

    private fun observePreloadSetting() {
        viewModelScope.launch {
            settings.preloadCount.collect { count ->
                preloadCount = count
                _uiState.update { it.copy(preloadCount = count) }
                // 用户调大预载数后立即补齐窗口，不用等下次滑动
                schedulePreload(_uiState.value.visibleIndex)
            }
        }
    }

    private fun schedulePreload(fromIndex: Int) {
        val images = _uiState.value.images
        if (images.isEmpty() || preloadCount <= 0) return

        val start = fromIndex + 1
        val end = (fromIndex + preloadCount).coerceAtMost(images.lastIndex)
        if (start > end) return

        for (index in start..end) {
            if (enqueuedIndices.add(index)) {
                imageLoader.enqueue(
                    MangaImageRequests.build(context, images[index], referer)
                )
            }
        }
    }

    /**
     * 进度落盘防抖。
     *
     * 滑一屏会连续触发好几次 index 变化，每次都写一遍整份历史毫无必要；
     * 停 600ms 再写，既不会漏掉最后一次，也不至于把 IO 打满。
     */
    private fun schedulePersist(index: Int) {
        if (index < 0 || _uiState.value.images.isEmpty()) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persistPage(index)
        }
    }

    private suspend fun persistPage(page: Int) {
        val state = _uiState.value
        val total = state.images.size
        if (total == 0) return

        val index = page.coerceIn(0, total - 1)
        if (index == lastPersistedPage && state.chapterUrl == lastPersistedChapterUrl) return

        lastPersistedPage = index
        lastPersistedChapterUrl = state.chapterUrl
        history.save(buildRecord(state, index))
    }

    private fun buildRecord(state: ReaderUiState, page: Int): ReadingRecord = ReadingRecord(
        mangaTitle = mangaTitle.ifBlank { state.chapterTitle },
        coverUrl = coverUrl,
        // 没有详情页时退化成章节地址：至少留得下一条记录，不至于看完什么都没留下
        detailUrl = detailUrl.ifBlank { state.chapterUrl },
        sourceId = sourceId,
        sourceName = sourceName,
        chapterTitle = state.chapterTitle,
        chapterUrl = state.chapterUrl,
        pageIndex = page,
        pageCount = state.images.size,
        chapterIndex = state.chapterIndex,
        chapterCount = state.chapterCount,
        updatedAt = System.currentTimeMillis()
    )

    /**
     * 退出阅读器时 ViewModel 会被销毁，viewModelScope 里的防抖写盘会被一起取消，
     * 最后停的那一页就丢了。这里用仓库自管的作用域补一次，保证「看到哪」一定存下来。
     */
    override fun onCleared() {
        saveJob?.cancel()
        val state = _uiState.value
        if (state.images.isNotEmpty()) {
            val page = state.visibleIndex.coerceIn(0, state.images.lastIndex)
            if (page != lastPersistedPage || state.chapterUrl != lastPersistedChapterUrl) {
                history.saveAsync(buildRecord(state, page))
            }
        }
        super.onCleared()
    }

    private companion object {
        val CHAPTER_NUMBER = Regex("\\d+")

        /** 滑动停多久之后写一次进度 */
        const val SAVE_DEBOUNCE_MS = 600L
    }
}
