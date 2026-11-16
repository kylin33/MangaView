package com.example.hellocompose.engine.autogen.corpus

import com.example.hellocompose.data.remote.UrlResolver
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File

/**
 * 语料库里的一个站点样本。
 *
 * 和 `FixtureSite` 的分工：
 * - `FixtureSite`（`fixtures/site-*`）回答「这个机制有没有被正确处理」——禁止项、脚本取数、
 *   搜索生效判定这类**行为**断言，一个样本只盯一件事。
 * - `CorpusSite`（`corpus/site_00x`）回答「识别得准不准」——每个阶段都有人工确认过的
 *   **标准答案**（选择器、条数、标题/封面/图片地址），用来算分阶段准确率。
 *
 * 目录约定（`app/src/test/resources/corpus/<id>/`）：
 * - `expected.json`：入口地址、路由表、以及人工确认的正确答案
 * - `home.html` / `detail_001.html` / `detail_002.html` / `reader.html`
 *
 * 标准答案有两种写法，取决于「正确答案在页面的哪里」：
 * 1. **DOM 里有列表**（绝大多数站）：写选择器，答案由 jsoup 展开。改站点只改选择器 + 条数，
 *    不用重抄一遍数据；展开过程不会被手抄笔误污染。
 * 2. **DOM 里没有列表**（SPA）：写内联变量名（`home.json: "homeData"`），答案从那段 JSON 展开。
 *    这类站页面上唯一的真相就是那段数据，用选择器根本表达不出来。
 *
 * 两种写法都只做最朴素的取值（`a[href]`、`img@attr`、`json["url"]`），不碰任何懒加载/占位图
 * 判断 —— 那些正是被测的能力，掺进标准答案里就等于自己给自己打分。
 */
class CorpusSite private constructor(
    val id: String,
    val name: String,
    /** 站点类型：standard / korean / japanese / legacy / spa / wordpress / gallery / antibot / multidomain */
    val type: String,
    val baseUrl: String,
    val input: String,
    val routes: List<Route>,
    val home: HomeSpec,
    val detail: DetailSpec,
    val reader: ReaderSpec,
    /** 反爬样本：期望识别失败并给出可读原因，不参与准确率统计 */
    val blocked: Boolean,
    val notes: String,
    private val dir: File
) {

    data class Route(val match: String, val page: String) {
        fun matches(pathAndQuery: String): Boolean = when {
            match.startsWith("exact:") -> pathAndQuery == match.removePrefix("exact:")
            match.startsWith("prefix:") -> pathAndQuery.startsWith(match.removePrefix("prefix:"))
            else -> pathAndQuery.contains(match)
        }
    }

    data class HomeSpec(
        /** DOM 选择器；为空表示答案在 [json] 指向的内联数组里 */
        val selector: String?,
        val expectedCount: Int,
        /** text = 条目里第一个有文字的链接；alt = 封面图 alt（纯图片卡片站） */
        val titleFrom: String,
        val coverAttr: String,
        /** 内联数据变量名（SPA） */
        val json: String?
    )

    data class DetailSpec(
        /** 详情页书名所在的选择器，用来校验标准答案自洽（书名要和首页标题对得上） */
        val titleSelector: String?,
        /** 书名在内联对象里的变量名（SPA） */
        val titleJson: String?,
        val chapterSelector: String?,
        /** 章节列表在内联数组里的变量名（SPA） */
        val chapterJson: String?,
        val pages: List<DetailPage>
    )

    data class DetailPage(val page: String, val expectedChapterCount: Int)

    data class ReaderSpec(
        val page: String,
        val imageSelector: String,
        val imageAttr: String,
        val expectedImageCount: Int
    )

    /** 一条标准答案：地址 + 标题 + 封面，都是补全后的绝对地址 */
    data class GoldItem(val url: String, val title: String, val cover: String)

    fun page(name: String): String = File(dir, name).readText()

    fun document(page: String, requestUrl: String): Document = Jsoup.parse(page(page), requestUrl)

    /**
     * 首页的标准答案。
     *
     * 标题只认「第一个有可见文字的链接」：卡片里通常有封面链接和标题链接两个 `<a>`，
     * 封面链接的文字是空的（只有图），直接取第一个会得到空标题。
     * 纯图片卡片站没有任何文字，此时按 `titleFrom: alt` 退到封面 alt —— 这也是用户在
     * 页面上唯一能看到的书名。
     */
    fun goldHome(base: String = baseUrl): List<GoldItem> {
        val doc = document("home.html", homeUrl())

        home.json?.let { name ->
            return InlineJson.array(doc, name).mapNotNull { obj ->
                val url = obj.optString("url").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val cover = obj.optString("cover").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GoldItem(
                    url = UrlResolver.resolve(url, base),
                    title = norm(obj.optString("title")),
                    cover = UrlResolver.resolve(cover, base)
                )
            }
        }

        val selector = home.selector ?: return emptyList()
        return doc.select(selector).mapNotNull { el ->
            val links = if (el.tagName() == "a" && el.hasAttr("href")) {
                listOf(el)
            } else {
                el.select("a[href]")
            }
            val href = links.firstNotNullOfOrNull { link ->
                link.attr("href").takeIf { it.isNotBlank() }
            } ?: return@mapNotNull null

            val img = el.selectFirst("img") ?: return@mapNotNull null
            val rawCover = img.attr(home.coverAttr).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val title = if (home.titleFrom == "alt") {
                img.attr("alt")
            } else {
                links.map { it.text() }.firstOrNull { it.isNotBlank() } ?: img.attr("alt")
            }

            GoldItem(
                url = UrlResolver.resolve(href, base),
                title = norm(title),
                cover = UrlResolver.resolve(rawCover, base)
            )
        }
    }

    fun goldChapters(page: String, base: String = baseUrl): List<String> {
        val doc = document(page, homeUrl())

        detail.chapterJson?.let { name ->
            return InlineJson.array(doc, name)
                .mapNotNull { it.optString("url").trim().takeIf { url -> url.isNotBlank() } }
                .map { UrlResolver.resolve(it, base) }
                .distinct()
        }

        val selector = detail.chapterSelector ?: return emptyList()
        return doc.select(selector)
            .mapNotNull { el ->
                val raw = el.attr("href").ifBlank {
                    el.selectFirst("a[href]")?.attr("href").orEmpty()
                }
                if (raw.isBlank()) null else UrlResolver.resolve(raw, base)
            }
            .distinct()
    }

    fun goldImages(base: String = baseUrl): List<String> {
        val doc = document(reader.page, homeUrl())
        return doc.select(reader.imageSelector).mapNotNull { el ->
            val raw = el.attr(reader.imageAttr).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            UrlResolver.resolve(raw, base)
        }.distinct()
    }

    /** 详情页上的书名，用来校验「首页标题」这份标准答案没抄错 */
    fun goldBookTitle(page: String): String {
        val doc = document(page, homeUrl())
        detail.titleJson?.let { name ->
            return norm(InlineJson.obj(doc, name)?.optString("title").orEmpty())
        }
        val selector = detail.titleSelector ?: return ""
        return norm(doc.selectFirst(selector)?.text().orEmpty())
    }

    private fun homeUrl(): String = input.ifBlank { "$baseUrl/" }

    override fun toString(): String = "$id（$name）"

    companion object {

        fun loadAll(): List<CorpusSite> {
            val root = resourceDir("corpus")
                ?: error("找不到 corpus 目录：单元测试要从 build/resources/test 里读，检查是否被排除")
            return root.listFiles().orEmpty()
                .filter { it.isDirectory }
                .filter { File(it, "expected.json").exists() }
                .sortedBy { it.name }
                .map { load(it) }
        }

        private fun load(dir: File): CorpusSite {
            val json = JSONObject(File(dir, "expected.json").readText())
            val blocked = json.optString("expected", "success") == "blocked"
            val routes = json.optJSONArray("routes").let { array ->
                if (array == null) emptyList() else (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    Route(item.getString("match"), item.getString("page"))
                }
            }

            // 反爬样本没有标准答案，这里允许三个节点整体缺省
            val home = json.optJSONObject("home") ?: JSONObject()
            val detail = json.optJSONObject("detail") ?: JSONObject()
            val reader = json.optJSONObject("reader") ?: JSONObject()

            return CorpusSite(
                id = dir.name,
                name = json.optString("name", dir.name),
                type = json.optString("type", "standard"),
                baseUrl = json.getString("baseUrl"),
                input = json.optString("input", json.getString("baseUrl") + "/"),
                routes = routes,
                home = HomeSpec(
                    selector = home.stringOrNull("selector"),
                    expectedCount = home.optInt("expectedCount", 0),
                    titleFrom = home.optString("titleFrom", "text"),
                    coverAttr = home.optString("coverAttr", "src"),
                    json = home.stringOrNull("json")
                ),
                detail = DetailSpec(
                    titleSelector = detail.stringOrNull("titleSelector"),
                    titleJson = detail.stringOrNull("titleJson"),
                    chapterSelector = detail.stringOrNull("chapterSelector"),
                    chapterJson = detail.stringOrNull("chapterJson"),
                    pages = detail.optJSONArray("pages")?.let { array ->
                        (0 until array.length()).map { index ->
                            val item = array.getJSONObject(index)
                            DetailPage(item.getString("page"), item.getInt("expectedChapterCount"))
                        }
                    }.orEmpty()
                ),
                reader = ReaderSpec(
                    page = reader.optString("page", "reader.html"),
                    imageSelector = reader.optString("imageSelector", ""),
                    imageAttr = reader.optString("imageAttr", "src"),
                    expectedImageCount = reader.optInt("expectedImageCount", 0)
                ),
                blocked = blocked,
                notes = json.optString("notes", ""),
                dir = dir
            )
        }

        /** 空白归一：全角空格、换行、连续空格都压成一个空格 */
        fun norm(text: String): String =
            text.replace('\u00A0', ' ').replace('\u3000', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun JSONObject.stringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotBlank() } else null

        private fun resourceDir(name: String): File? =
            CorpusSite::class.java.classLoader?.getResource(name)
                ?.let { runCatching { File(it.toURI()) }.getOrNull() }
    }
}

/**
 * 从内联脚本里取出一个变量名对应的 JSON。
 *
 * 故意写得比 `ScriptData` 笨得多：只做「找到变量名 → 找下一个 `[`/`{` → 括号配平」，
 * 不猜字段、不猜地址模板。标准答案需要的是「页面里写的是什么」，越笨越不容易
 * 和被测实现犯同一个错。
 */
internal object InlineJson {

    fun array(doc: Document, name: String): List<JSONObject> {
        val raw = slice(doc, name) ?: return emptyList()
        val json = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until json.length()).mapNotNull { json.optJSONObject(it) }
    }

    fun obj(doc: Document, name: String): JSONObject? {
        val raw = slice(doc, name) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun slice(doc: Document, name: String): String? {
        for (script in doc.select("script")) {
            val text = script.data()
            val at = text.indexOf(name)
            if (at < 0) continue

            val start = (at + name.length until minOf(text.length, at + name.length + 80))
                .firstOrNull { text[it] == '[' || text[it] == '{' }
                ?: continue
            balancedSlice(text, start)?.let { return it }
        }
        return null
    }

    private fun balancedSlice(text: String, start: Int): String? {
        val open = text[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
