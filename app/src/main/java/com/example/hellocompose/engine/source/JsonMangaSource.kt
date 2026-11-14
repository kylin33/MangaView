package com.example.hellocompose.engine.source

import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.remote.MangaScraper
import com.example.hellocompose.data.remote.PageParser
import com.example.hellocompose.data.source.SourceConfig
import org.jsoup.nodes.Document

/**
 * 通用实现：一份 JSON 规则 + 抓取器 = 一个 [MangaSource]。
 *
 * 这里只做「把规则跑起来」和「把结果翻译成标准类型」，不放任何站点特判。
 * 某个站如果结构特殊到规则表达不了，不要往这里加 if，而是另写一个 [MangaSource]
 * 实现并通过 [SourceEngine.register] 挂上去。
 */
class JsonMangaSource(
    val config: SourceConfig,
    private val scraper: MangaScraper
) : MangaSource {

    override val id: String = config.id
    override val name: String = config.name
    override val baseUrl: String = config.baseUrl

    override val capabilities: SourceCapabilities = SourceCapabilities(
        // 自动识别给「只有搜索」的站点写的是永不匹配的占位选择器，
        // 对上层来说就是「没有排行榜」，不该让它去抓一次注定为空的首页
        canListHome = !config.isSearchOnly,
        canSearch = config.search != null
    )

    override suspend fun getHome(): List<Manga> {
        if (!capabilities.canListHome) {
            throw SourceUnsupportedException("「$name」没有排行榜，请用搜索观看漫画")
        }
        return scraper.fetchHome(config)
    }

    override suspend fun search(keyword: String): List<Manga> {
        if (config.search == null) {
            throw SourceUnsupportedException("「$name」没有配置搜索规则")
        }
        return scraper.search(config, keyword)
    }

    override suspend fun getDetail(url: String): MangaDetail {
        val doc = scraper.fetchDocument(config, url)
        return MangaDetail(
            url = url,
            title = pickTitle(doc),
            coverUrl = pickCover(doc),
            description = pickDescription(doc),
            chapters = PageParser.chapters(doc, config.detail, config.baseUrl)
        )
    }

    override suspend fun getChapters(url: String): List<Chapter> =
        scraper.fetchChapters(config, url)

    override suspend fun getPages(url: String): List<Page> {
        val images = scraper.fetchChapterImages(config, url)
        // Referer 在这里一次算好，阅读引擎拿到的就是完整可用的请求描述
        val referer = config.expand(config.reader.referer, url)
        return images.mapIndexed { index, imageUrl -> Page(index, imageUrl, referer) }
    }

    // ---- 详情页的「顺带信息」：捡得到就填，捡不到留空，不为它们多开一次请求 ----

    private fun pickTitle(doc: Document): String =
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore('_').substringBefore('-').trim()

    private fun pickCover(doc: Document): String =
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()

    private fun pickDescription(doc: Document): String =
        (doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content"))
            ?.trim().orEmpty()
}
