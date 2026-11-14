package com.example.hellocompose.data.repository

import com.example.hellocompose.core.AppResult
import com.example.hellocompose.core.EmptyPageException
import com.example.hellocompose.core.appRunCatching
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.data.source.SourceRepository
import com.example.hellocompose.engine.source.MangaSource
import com.example.hellocompose.engine.source.Page
import com.example.hellocompose.engine.source.SourceEngine
import com.example.hellocompose.engine.source.SourceUnsupportedException
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
 * 取数一律经过 [SourceEngine] 拿到的 [MangaSource]，这里不出现任何选择器、
 * URL 拼接或 HTML 解析 —— 那些属于站点适配引擎。本类只做三件事：
 * 选站点、把异常翻译成 [AppResult]、维护首页缓存。
 */
class MangaRepository(
    private val engine: SourceEngine,
    private val sources: SourceRepository
) {

    private val _homeCache = MutableStateFlow<CachedHome?>(null)

    /** 首页列表的内存缓存，用于从阅读器返回时秒开 */
    val homeCache: StateFlow<CachedHome?> = _homeCache.asStateFlow()

    fun clearHomeCache() {
        _homeCache.value = null
    }

    /** 配置变了（编辑站点、删除后恢复内置），引擎里的实例必须重建 */
    fun invalidateSources() {
        engine.invalidate()
    }

    suspend fun getHome(): AppResult<List<Manga>> = appRunCatching {
        val source = engine.current()
        if (!source.capabilities.canListHome) {
            throw EmptyPageException("「${source.name}」没有排行榜，请用搜索观看漫画")
        }
        val items = source.getHome()
        if (items.isEmpty()) {
            throw EmptyPageException("首页未解析到任何漫画，请检查「${source.name}」的 home 选择器")
        }
        _homeCache.value = CachedHome(source.id, items)
        items
    }

    /**
     * 站点内搜索。站点的 search 规则缺失时抛出明确提示，
     * 由 UI 展示而不是静默返回空列表。
     */
    suspend fun search(keyword: String): AppResult<List<Manga>> = appRunCatching {
        val source = engine.current()
        if (!source.capabilities.canSearch) {
            throw SourceUnsupportedException("「${source.name}」没有搜索功能，请换一个站点")
        }
        val items = source.search(keyword)
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
        appRunCatching { engine.of(source).search(keyword) }

    /** 用指定站点抓目录，同样不污染全局站点 */
    suspend fun chaptersIn(source: SourceConfig, detailUrl: String): AppResult<List<Chapter>> =
        appRunCatching {
            val items = engine.of(source).getChapters(detailUrl)
            if (items.isEmpty()) {
                throw EmptyPageException("「${source.name}」详情页没有解析到章节")
            }
            items
        }

    suspend fun getChapters(detailUrl: String): AppResult<List<Chapter>> = appRunCatching {
        val source = engine.current()
        val items = source.getChapters(detailUrl)
        if (items.isEmpty()) {
            throw EmptyPageException("详情页未解析到章节，请检查「${source.name}」的 detail 选择器")
        }
        items
    }

    /**
     * 本章的标准化页面列表。返回值直接喂给阅读引擎，
     * 已包含每页的 Referer，阅读侧不需要再知道站点配置。
     */
    suspend fun getPages(chapterUrl: String): AppResult<List<Page>> = appRunCatching {
        val source = engine.current()
        val pages = source.getPages(chapterUrl)
        if (pages.isEmpty()) {
            throw EmptyPageException("本章未解析到图片，请检查「${source.name}」的 reader 选择器")
        }
        pages
    }

    /** 当前站点。给需要在 UI 上显示站点名/能力的场景用 */
    suspend fun currentSource(): AppResult<MangaSource> = appRunCatching { engine.current() }
}
