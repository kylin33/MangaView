package com.example.hellocompose.data.remote

import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.source.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 页面抓取器：发请求 + 交给 [PageParser] 解析。
 *
 * 所有选择器都来自站点配置（assets/sources 目录下的 JSON），这里不含任何站点常量：
 * 换站点、站点改版都只改 JSON，不动代码。
 */
class MangaScraper(
    private val http: JsoupHttpClient = JsoupHttpClient()
) {

    suspend fun fetchHome(source: SourceConfig): List<Manga> = withContext(Dispatchers.IO) {
        val pageUrl = source.url(source.home.path)
        val doc = http.get(source, pageUrl)
        PageParser.list(doc, source.home, source.baseUrl)
    }

    suspend fun search(source: SourceConfig, keyword: String): List<Manga> =
        withContext(Dispatchers.IO) {
            val rule = source.search
                ?: throw IllegalStateException("站点「${source.name}」未配置 search 规则")
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val pageUrl = source.url(rule.path.replace("{keyword}", encoded))
            val doc = http.get(source, pageUrl)
            PageParser.list(doc, rule, source.baseUrl)
        }

    suspend fun fetchChapters(source: SourceConfig, detailUrl: String): List<Chapter> =
        withContext(Dispatchers.IO) {
            val doc = http.get(source, detailUrl)
            PageParser.chapters(doc, source.detail, source.baseUrl)
        }

    suspend fun fetchChapterImages(source: SourceConfig, chapterUrl: String): List<String> =
        withContext(Dispatchers.IO) {
            val doc = http.get(source, chapterUrl)
            PageParser.images(doc, source.reader, source.baseUrl)
        }

    /**
     * 直接拿整份页面。给需要「页面本身」的调用方用（如详情页顺带取标题/简介），
     * 避免为了几个 meta 字段再写一套请求逻辑。
     */
    suspend fun fetchDocument(source: SourceConfig, url: String): Document =
        withContext(Dispatchers.IO) { http.get(source, url) }
}
