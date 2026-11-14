package com.example.hellocompose.data.remote

import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.source.DetailRule
import com.example.hellocompose.data.source.ListRule
import com.example.hellocompose.data.source.ReaderRule
import com.example.hellocompose.data.source.ScriptData
import com.example.hellocompose.data.source.pickByRule
import com.example.hellocompose.data.source.readByRule
import org.jsoup.nodes.Document

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
            val rawCover = element.pickByRule(rule.cover).readByRule(rule.cover.attr)
            // 标题优先取规则指定属性；很多站封面图 alt 为空，退化成链接文字
            val title = element.pickByRule(rule.title).readByRule(rule.title.attr)
                .ifBlank { linkElement?.text()?.trim().orEmpty() }

            if (rawLink.isBlank() || rawCover.isBlank()) {
                null
            } else {
                Manga(
                    title = title.ifBlank { UNTITLED },
                    coverUrl = UrlResolver.resolve(rawCover, baseUrl),
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
            val raw = element.pickByRule(rule.image).readByRule(rule.image.attr)
            if (raw.isBlank()) null else UrlResolver.resolve(raw, baseUrl)
        }.distinct()

    /** 标题抓不出来时的占位文案，自动识别会统计它的占比来判断 title 规则好不好 */
    const val UNTITLED = "未命名漫画"
}
