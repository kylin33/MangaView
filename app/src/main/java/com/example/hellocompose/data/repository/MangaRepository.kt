package com.example.hellocompose.data.repository

import com.example.hellocompose.core.AppResult
import com.example.hellocompose.core.EmptyPageException
import com.example.hellocompose.core.appRunCatching
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.remote.MangaScraper
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.data.source.SourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 首页缓存带上站点 id：切换站点后旧数据不会被误当作新站点的内容 */
data class CachedHome(
    val sourceId: String,
    val items: List<Manga>
)

/**
 * 数据仓库：对上层屏蔽「数据从哪个站点、怎么抓」。
 *
 * 每次取数都由 [SourceRepository.current] 决定用哪份配置，
 * 所以切换站点只需要改一处偏好，UI 和 ViewModel 不用持有站点对象。
 */
class MangaRepository(
    private val scraper: MangaScraper,
    private val sources: SourceRepository
) {

    private val _homeCache = MutableStateFlow<CachedHome?>(null)

    /** 首页列表的内存缓存，用于从阅读器返回时秒开 */
    val homeCache: StateFlow<CachedHome?> = _homeCache.asStateFlow()

    fun clearHomeCache() {
        _homeCache.value = null
    }

    suspend fun getHome(): AppResult<List<Manga>> = appRunCatching {
        val source = sources.current()
        val items = scraper.fetchHome(source)
        if (items.isEmpty()) {
            throw EmptyPageException("首页未解析到任何漫画，请检查「${source.name}」的 home 选择器")
        }
        _homeCache.value = CachedHome(source.id, items)
        items
    }

    /**
     * 站点内搜索。搜索规则同样来自 JSON 的 search 节点，
     * 未配置时抛出明确提示，由 UI 展示而不是静默返回空列表。
     */
    suspend fun search(keyword: String): AppResult<List<Manga>> = appRunCatching {
        val source = sources.current()
        val items = scraper.search(source, keyword)
        if (items.isEmpty()) {
            throw EmptyPageException("「${source.name}」没有搜到结果，或 search 选择器需要调整")
        }
        items
    }

    /**
     * 在指定站点搜索（换源用）。
     *
     * 和 [search] 的区别有两个，都是换源场景必须的：
     * 1. 用传进来的配置而不是当前站点 —— 换源时要同时比对好几个站点，
     *    不能每搜一个就把全局站点切一次；
     * 2. 空结果不抛异常 —— 「这个站没有这本书」是正常结论，不是错误，
     *    交给 UI 显示「没搜到，换一个源试试」。
     */
    suspend fun searchIn(source: SourceConfig, keyword: String): AppResult<List<Manga>> =
        appRunCatching { scraper.search(source, keyword) }

    /** 用指定站点抓目录，同样不污染全局站点 */
    suspend fun chaptersIn(source: SourceConfig, detailUrl: String): AppResult<List<Chapter>> =
        appRunCatching {
            val items = scraper.fetchChapters(source, detailUrl)
            if (items.isEmpty()) {
                throw EmptyPageException("「${source.name}」详情页没有解析到章节")
            }
            items
        }

    suspend fun getChapters(detailUrl: String): AppResult<List<Chapter>> = appRunCatching {
        val source = sources.current()
        val items = scraper.fetchChapters(source, detailUrl)
        if (items.isEmpty()) {
            throw EmptyPageException("详情页未解析到章节，请检查「${source.name}」的 detail 选择器")
        }
        items
    }

    suspend fun getChapterImages(chapterUrl: String): AppResult<List<String>> = appRunCatching {
        val source = sources.current()
        val items = scraper.fetchChapterImages(source, chapterUrl)
        if (items.isEmpty()) {
            throw EmptyPageException("本章未解析到图片，请检查「${source.name}」的 reader 选择器")
        }
        items
    }
}
