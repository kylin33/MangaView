package com.example.hellocompose.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hellocompose.core.AppResult
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.repository.MangaRepository
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.data.source.SourceRepository
import com.example.hellocompose.data.source.matchChapter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 备选站点。searchable=false 的站点没有搜索规则，定位不到具体漫画，不给选 */
data class SourceSite(
    val config: SourceConfig,
    val searchable: Boolean,
    val isCurrent: Boolean
)

/** 用户最终选定的目标：哪本书、在哪个站、对应哪一章（可能没对上） */
data class SwitchSelection(
    val source: SourceConfig,
    val manga: Manga,
    val chapter: Chapter?
)

sealed interface SwitchStep {
    data object Listing : SwitchStep
    data class Busy(val text: String) : SwitchStep

    /**
     * @param keywordMatched 结果里至少有一条标题跟搜索词对得上。
     * 不少中文站在没命中时会返回「猜你喜欢」的推荐列表，
     * 那不是搜索结果，得提醒用户别直接点。
     */
    data class Results(
        val source: SourceConfig,
        val items: List<Manga>,
        val keywordMatched: Boolean
    ) : SwitchStep

    data class Message(val source: SourceConfig?, val text: String) : SwitchStep
}

data class SourceSwitchUiState(
    val step: SwitchStep = SwitchStep.Listing,
    val sites: List<SourceSite> = emptyList(),
    val keyword: String = "",
    /** 站点列表还没读出来，先别显示「没有可用站点」 */
    val loadingSites: Boolean = true
)

/**
 * 换源。
 *
 * 站点卡、图加载不出来的时候，用户想要的不是「重试」，而是「这本书别的站有没有」。
 * 这里用书名去目标站点搜一次，选中结果后再决定落到哪一章：
 * - 从目录页来：直接进目标站的目录；
 * - 从阅读页来：抓目标站目录，按章号对一次，对上了直接读那一章，对不上退回目录页。
 *
 * 全程用目标站点的配置发请求，不改全局站点 —— 搜索阶段只是「看看有没有」，
 * 直到用户真的选中才切站点。
 */
class SourceSwitchViewModel(
    private val mangaTitle: String,
    /** 从阅读页打开时是当前章节名，用来在目标站点定位同一章；目录页为空 */
    private val chapterTitle: String,
    private val repository: MangaRepository,
    private val sources: SourceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourceSwitchUiState(keyword = mangaTitle))
    val uiState: StateFlow<SourceSwitchUiState> = _uiState.asStateFlow()

    /**
     * 选定结果用一次性事件而不是状态字段。
     *
     * 面板的 ViewModel 跟着返回栈活着：关了再打开还是同一个实例。
     * 如果"已选定"存在状态里，第二次打开面板会立刻重放上一次的选择，
     * 用户还没点任何东西就被跳到别的站点去了。
     */
    private val _selection = MutableSharedFlow<SwitchSelection>(extraBufferCapacity = 4)
    val selection: SharedFlow<SwitchSelection> = _selection.asSharedFlow()

    private var currentSourceId: String = ""

    /** 每次打开面板都从站点列表开始，避免看到上一次的搜索结果 */
    fun reset() {
        _uiState.value = SourceSwitchUiState(keyword = mangaTitle, loadingSites = true)
        viewModelScope.launch {
            val bundle = runCatching { sources.bundle() }.getOrNull()
            val current = runCatching { sources.current() }.getOrNull()
            currentSourceId = current?.id.orEmpty()

            val sites = bundle?.configs.orEmpty()
                .filter { it.enabled }
                .map { config ->
                    SourceSite(
                        config = config,
                        searchable = config.search != null,
                        isCurrent = config.id == currentSourceId
                    )
                }
                // 能搜的排前面：不能搜的站点在这里只能当摆设
                .sortedWith(compareByDescending<SourceSite> { it.searchable }.thenBy { it.config.name })

            _uiState.update {
                it.copy(step = SwitchStep.Listing, sites = sites, loadingSites = false)
            }
        }
    }

    /**
     * 搜索词可改：同一本书在不同站点可能是繁体/简体，或者不同译名。
     * 默认用记录里的书名，搜不到时用户能就地改成另一个写法，不用退出面板。
     */
    fun onKeywordChange(value: String) {
        _uiState.update { it.copy(keyword = value) }
    }

    fun search(site: SourceSite) {
        val keyword = _uiState.value.keyword.trim()
        if (!site.searchable || site.isCurrent || keyword.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(step = SwitchStep.Busy("正在「${site.config.name}」搜索「$keyword」…"))
            }
            when (val result = repository.searchIn(site.config, keyword)) {
                is AppResult.Success -> {
                    // 标题对得上的先排前面：「推荐位」混在结果里时，用户第一眼能看到对的那本
                    val ranked = result.data.sortedByDescending { relevance(it.title, keyword) }
                    _uiState.update {
                        it.copy(
                            step = if (ranked.isEmpty()) {
                                SwitchStep.Message(
                                    site.config,
                                    "「${site.config.name}」没搜到这本书，换个源或改个关键词试试"
                                )
                            } else {
                                SwitchStep.Results(
                                    source = site.config,
                                    items = ranked,
                                    keywordMatched = ranked.any { relevance(it.title, keyword) > 0 }
                                )
                            }
                        )
                    }
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(step = SwitchStep.Message(site.config, result.error.userMessage))
                }
            }
        }
    }

    /** 3=书名一致 2=互相包含 0=看不出关系 */
    private fun relevance(title: String, keyword: String): Int {
        val t = title.trim().lowercase()
        val k = keyword.trim().lowercase()
        if (t.isEmpty() || k.isEmpty()) return 0
        return when {
            t == k -> 3
            t.contains(k) || k.contains(t) -> 2
            else -> 0
        }
    }

    /** 选定了某本书：能对章节就先对上，对不上就让用户自己进目录挑 */
    fun choose(source: SourceConfig, manga: Manga) {
        viewModelScope.launch {
            if (chapterTitle.isBlank()) {
                _selection.tryEmit(SwitchSelection(source, manga, null))
                return@launch
            }

            _uiState.update {
                it.copy(step = SwitchStep.Busy("正在对「${source.name}」的章节…"))
            }
            val chapter = when (val result = repository.chaptersIn(source, manga.detailUrl)) {
                is AppResult.Success -> matchChapter(chapterTitle, result.data)
                // 目录抓不到不算失败：进目标站点的目录页，用户自己点也一样
                is AppResult.Failure -> null
            }
            _selection.tryEmit(SwitchSelection(source, manga, chapter))
        }
    }

    fun backToList() {
        _uiState.update { it.copy(step = SwitchStep.Listing) }
    }
}
