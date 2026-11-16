package com.example.hellocompose.data.remote

import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.source.DetailRule
import com.example.hellocompose.data.source.FieldRule
import com.example.hellocompose.data.source.ImageUrlResolver
import com.example.hellocompose.data.source.ListRule
import com.example.hellocompose.data.source.ReaderRule
import com.example.hellocompose.data.source.ScriptData
import com.example.hellocompose.data.source.pickByRule
import com.example.hellocompose.data.source.readByRule
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 纯解析层：HTML + 规则 → 数据。不发请求、不碰线程、不抛业务异常。
 *
 * 独立于 [MangaScraper] 的原因：自动识别站点时需要「拿候选规则反复试解析同一份 HTML」，
 * 只有把解析逻辑和网络分开，才能做到零额外请求地评估几十个候选选择器，
 * 并且保证识别用的解析结果和正式运行时完全一致。
 */
object PageParser {

    fun list(doc: Document, rule: ListRule, baseUrl: String): List<Manga> {
        // 脚本取数优先。抓不到时再走 DOM —— 有些页面的脚本只在特定状态下输出数据，
        // 让两条路互为兜底比硬性二选一稳
        rule.script?.let { script ->
            ScriptData.list(doc, script, baseUrl).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return doc.select(rule.item).mapNotNull { element ->
            val linkElement = element.pickByRule(rule.link)
            val rawLink = linkElement.readByRule(rule.link.attr)
            // 封面走解析层：srcset / 背景图 / 占位 src + 真实 data-src 都在这里兜住。
            // 直接用 readByRule 取第一个非空属性，懒加载站点拿回来的会是 loading.gif
            val coverElement = element.pickByRule(rule.cover) ?: element
            val cover = ImageUrlResolver.imageUrl(coverElement, rule.cover.attr, baseUrl)
            // 标题优先取规则指定属性；很多站封面图 alt 为空，退化成条目里第一个有文字的链接
            val title = titleText(element, rule.title)
                .ifBlank { linkElement?.text()?.trim().orEmpty() }

            if (rawLink.isBlank() || cover.isBlank()) {
                null
            } else {
                Manga(
                    title = title.ifBlank { UNTITLED },
                    coverUrl = cover,
                    detailUrl = UrlResolver.resolve(rawLink, baseUrl)
                )
            }
        }.distinctBy { it.detailUrl }
    }

    fun chapters(doc: Document, rule: DetailRule, baseUrl: String): List<Chapter> {
        rule.script?.let { script ->
            ScriptData.chapters(doc, script, baseUrl).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return doc.select(rule.item).mapNotNull { element ->
            val href = element.pickByRule(rule.url).readByRule(rule.url.attr)
            val title = element.pickByRule(rule.title).readByRule(rule.title.attr)
            if (href.isBlank() || title.isBlank()) {
                null
            } else {
                Chapter(
                    title = title,
                    url = UrlResolver.resolve(href, baseUrl)
                )
            }
        }.distinctBy { it.url }
    }

    fun images(doc: Document, rule: ReaderRule, baseUrl: String): List<String> =
        doc.select(rule.item).mapNotNull { element ->
            val target = element.pickByRule(rule.image) ?: element
            // 正文图同样走解析层：一页里可能混着 srcset、背景图、占位 src，
            // 只要这一项解析出了地址就保留一项，不因为取不到而错位
            ImageUrlResolver.imageUrl(target, rule.image.attr, baseUrl).ifBlank { null }
        }.distinct()

    /**
     * 按规则取条目标题，取不到时退到条目里第一个有文字的链接。
     *
     * 为什么需要这层兜底：老式站点（表格布局）把封面图包在第一个 `<a>` 里、书名放在
     * 第二个 `<a>` 的 `<font>` 里。`a + text` 这类规则命中的是第一个链接，文字是空的，
     * 于是整页书名全变成「未命名漫画」—— 用户看到的是「识别成功但书名全错」，
     * 比直接失败更难查。
     *
     * 兜底只看「有没有文字」，不做任何语义判断：真正决定规则好坏的是识别阶段的
     * [com.example.hellocompose.engine.autogen.SourceAutoDetector] 的标题质量评分，
     * 这里保证「评分看到的值」和「运行时取到的值」是同一个。
     */
    fun titleText(item: Element, rule: FieldRule): String {
        val primary = item.pickByRule(rule).readByRule(rule.attr).trim()
        if (primary.isNotBlank()) return primary
        return TITLE_TEXT_CARRIERS
            .firstNotNullOfOrNull { item.select(it).firstOrNull { el -> el.text().isNotBlank() } }
            ?.text()
            ?.trim()
            .orEmpty()
    }

    /** 书名常见载体，按「越常见越先」排：链接文字 → 加粗 → 字号标签（老站） */
    private val TITLE_TEXT_CARRIERS = listOf("a", "b", "strong", "font", "span")

    /** 标题抓不出来时的占位文案，自动识别会统计它的占比来判断 title 规则好不好 */
    const val UNTITLED = "未命名漫画"
}
