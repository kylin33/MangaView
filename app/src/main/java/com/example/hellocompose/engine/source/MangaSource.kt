package com.example.hellocompose.engine.source

import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga

/**
 * 标准化的一页：一张图的地址 + 取图必须带的请求头。
 *
 * 阅读引擎只认这个类型，不知道它是从 CSS 选择器、内联脚本还是某个 API 来的。
 * Referer 跟着页面走而不是跟着站点走：同一站不同章节可能用不同的防盗链策略，
 * 让「取数的一方」一次性算好，阅读侧就不用再回头查配置。
 */
data class Page(
    val index: Int,
    val url: String,
    val referer: String = ""
)

/**
 * 详情页的标准化结果。
 *
 * [title] / [coverUrl] / [description] 都可能为空——多数站点的详情页在抓章节时
 * 并不需要它们，能从页面里捡到就填上，捡不到就留空，不要为了「补全」再多发一次请求。
 */
data class MangaDetail(
    val url: String,
    val title: String = "",
    val coverUrl: String = "",
    val description: String = "",
    val chapters: List<Chapter> = emptyList()
)

/**
 * 站点能力。
 *
 * 上层需要知道的只有这几件事，不该再去读 JSON 判断 `home.item` 是不是占位符、
 * `search` 节点存不存在 —— 那是引擎内部的表示细节。
 */
data class SourceCapabilities(
    val canListHome: Boolean,
    val canSearch: Boolean
) {
    /** 首页抓不到排行、只能靠搜索进入的站点 */
    val searchOnly: Boolean get() = !canListHome && canSearch
}

/**
 * 站点适配引擎的统一出口。
 *
 * 实现方可以是「JSON 规则驱动的通用实现」（见 [JsonMangaSource]），
 * 也可以是某个结构特别的站点的专用实现。上层只依赖这个接口，
 * 所以以后要给某个站写死一段解析逻辑，不需要动 UI、也不需要动阅读引擎。
 *
 * 约定：
 * - 返回空列表表示「这次确实没有内容」，不是错误；
 * - 站点不具备某项能力（如没有搜索）时抛 [SourceUnsupportedException]；
 * - 网络/解析失败抛异常，由 Repository 统一转成 AppError。
 */
interface MangaSource {

    val id: String
    val name: String
    val baseUrl: String
    val capabilities: SourceCapabilities

    /** 首页（排行榜）列表 */
    suspend fun getHome(): List<Manga>

    /** 站内搜索 */
    suspend fun search(keyword: String): List<Manga>

    /** 详情页：章节列表（能顺带捡到书名/封面就填上） */
    suspend fun getDetail(url: String): MangaDetail

    /** 只取目录。比 [getDetail] 少解析一些东西，给只需要章节的场景用 */
    suspend fun getChapters(url: String): List<Chapter>

    /** 阅读页：本章所有图片，已按阅读顺序排好 */
    suspend fun getPages(url: String): List<Page>
}

/** 站点不具备某项能力。和网络错误区分开，UI 才能给出「换个源」这类建议 */
class SourceUnsupportedException(message: String) : Exception(message)
