package com.example.hellocompose.engine.autogen

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URLDecoder

/**
 * 一组本地 HTML 夹具，代表某类站点结构。
 *
 * 为什么用夹具而不是实连测试：识别算法是启发式的，改一条打分规则很难靠读代码判断
 * 「有没有让别的站点退化」。实连测试能给出真实反馈，但站点一改版、一限流就红，
 * 跑几次之后人就学会无视它了。夹具是固定的、秒级完成的，改算法前后各跑一遍，
 * 退化的地方会立刻指出来。
 *
 * 目录约定（`app/src/test/resources/fixtures/<id>/`）：
 * - `site.json`：入口地址、地址路由表、期望值
 * - `home.html` / `detail.html` / `reader.html` / `search.html`：各阶段页面
 *
 * 搜索页里的 `{{keyword}}` 会被替换成当次请求的关键词 —— 搜索地址是否真的用上了
 * 关键词，只能靠「换个词结果变不变」来判断，静态页面模拟不出这个行为。
 */
class FixtureSite private constructor(
    val id: String,
    val name: String,
    /** 用户输入的站点根地址 */
    val baseUrl: String,
    /** 传给 generate 的输入 */
    val input: String,
    val routes: List<Route>,
    val expect: Expectation,
    private val dir: File
) {

    data class Route(val match: String, val page: String) {
        fun matches(pathAndQuery: String): Boolean = when {
            match.startsWith("exact:") -> pathAndQuery == match.removePrefix("exact:")
            match.startsWith("prefix:") -> pathAndQuery.startsWith(match.removePrefix("prefix:"))
            else -> pathAndQuery.contains(match)
        }
    }

    data class Expectation(
        /** success / failed */
        val outcome: String,
        val baseUrl: String? = null,
        val homeMin: Int? = null,
        val homeTitleMaxUntitled: Int? = null,
        val homeCoverHost: String? = null,
        /** 书名里不允许出现的字样：用来证明抓的不是推广位（「推广 1」「广告位」） */
        val homeTitleForbid: List<String> = emptyList(),
        /** 封面地址里不允许出现的字样：占位图、推广图 */
        val homeCoverForbid: List<String> = emptyList(),
        val chapterMin: Int? = null,
        val chapterTitleLike: Double? = null,
        val imageMin: Int? = null,
        val imageDistinctRatio: Double? = null,
        val imageLast: String? = null,
        val imageHost: String? = null,
        /** 正文图地址里不允许出现的字样：1x1 占位、base64、相对预览图 */
        val imageForbid: List<String> = emptyList(),
        val searchOnly: Boolean? = null,
        val usesScriptDetail: Boolean? = null,
        val warningContains: String? = null,
        val failureContains: String? = null,
        /** site.json 里是否写了 searchPath（写 null 表示「必须没有搜索」） */
        val expectsSearchPath: Boolean = false,
        val searchPath: String? = null,
        /** 搜索请求方式：get / post。不写表示不校验 */
        val searchMethod: String? = null,
        /** 关键字参数名。不写表示不校验 */
        val searchKeywordParam: String? = null,
        /** 表单固定参数（隐藏域）。不写表示不校验 */
        val searchFields: Map<String, String> = emptyMap()
    )

    /** 按页面名读 HTML；搜索页会把占位符换成关键词 */
    fun page(name: String, keyword: String = ""): String {
        val text = File(dir, name).readText()
        return if (keyword.isBlank()) text else text.replace("{{keyword}}", keyword)
    }

    /** 解析成 Document，baseUri 用请求时的完整地址（搜索页要靠它判断关键词有没有被保留） */
    fun document(page: String, requestUrl: String): Document =
        Jsoup.parse(page(page, keywordOf(requestUrl)), requestUrl)

    override fun toString(): String = "$id（$name）"

    companion object {

        fun loadAll(): List<FixtureSite> {
            val root = resourceDir("fixtures")
                ?: error("找不到 fixtures 目录：单元测试要从 build/resources/test 里读，检查是否被排除")
            return root.listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }
                .map { load(it) }
        }

        private fun load(dir: File): FixtureSite {
            val json = JSONObject(File(dir, "site.json").readText())
            val routes = json.getJSONArray("routes").let { array ->
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    Route(item.getString("match"), item.getString("page"))
                }
            }
            return FixtureSite(
                id = dir.name,
                name = json.optString("name", dir.name),
                baseUrl = json.getString("baseUrl"),
                input = json.optString("input", json.getString("baseUrl") + "/"),
                routes = routes,
                expect = parseExpectation(json.optJSONObject("expect") ?: JSONObject()),
                dir = dir
            )
        }

        private fun parseExpectation(json: JSONObject): Expectation = Expectation(
            outcome = json.optString("outcome", "success"),
            baseUrl = json.stringOrNull("baseUrl"),
            homeMin = json.intOrNull("homeMin"),
            homeTitleMaxUntitled = json.intOrNull("homeTitleMaxUntitled"),
            homeCoverHost = json.stringOrNull("homeCoverHost"),
            homeTitleForbid = json.stringList("homeTitleForbid"),
            homeCoverForbid = json.stringList("homeCoverForbid"),
            chapterMin = json.intOrNull("chapterMin"),
            chapterTitleLike = json.doubleOrNull("chapterTitleLike"),
            imageMin = json.intOrNull("imageMin"),
            imageDistinctRatio = json.doubleOrNull("imageDistinctRatio"),
            imageLast = json.stringOrNull("imageLast"),
            imageHost = json.stringOrNull("imageHost"),
            imageForbid = json.stringList("imageForbid"),
            searchOnly = json.booleanOrNull("searchOnly"),
            usesScriptDetail = json.booleanOrNull("usesScriptDetail"),
            warningContains = json.stringOrNull("warningContains"),
            failureContains = json.stringOrNull("failureContains"),
            expectsSearchPath = json.has("searchPath"),
            searchPath = json.stringOrNull("searchPath"),
            searchMethod = json.stringOrNull("searchMethod"),
            searchKeywordParam = json.stringOrNull("searchKeywordParam"),
            searchFields = json.optJSONObject("searchFields")?.let { obj ->
                val keys = obj.keys()
                val map = LinkedHashMap<String, String>()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.optString(key)
                }
                map
            }.orEmpty()
        )

        private fun resourceDir(name: String): File? =
            FixtureSite::class.java.classLoader?.getResource(name)
                ?.let { runCatching { File(it.toURI()) }.getOrNull() }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.intOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) getInt(key) else null

        private fun JSONObject.doubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) getDouble(key) else null

        private fun JSONObject.booleanOrNull(key: String): Boolean? =
            if (has(key) && !isNull(key)) getBoolean(key) else null

        private fun JSONObject.stringList(key: String): List<String> {
            if (!has(key) || isNull(key)) return emptyList()
            val array = getJSONArray(key)
            return (0 until array.length()).map { array.getString(it) }
        }

        /**
         * 从请求地址里取出关键词。
         *
         * 以前只认 `keyword=`。真实站点的参数名五花八门（`wd`、`searchword`、`k`…），
         * 而搜索页夹具靠 `{{keyword}}` 回显关键词 —— 只认一个参数名，
         * 「表单推断出 wd」这类用例就永远看不到回显，只能被判成假搜索。
         */
        private val KEYWORD_PARAMS = listOf(
            "keyword", "wd", "kw", "q", "s", "k", "searchword", "searchkey", "key", "word"
        )

        private fun keywordOf(url: String): String {
            val query = url.substringAfter('?', "")
            if (query.isEmpty()) return ""
            val params = query.split('&')
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val raw = KEYWORD_PARAMS.firstNotNullOfOrNull { params[it]?.takeIf { v -> v.isNotBlank() } }
                ?: return ""
            return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }
    }
}
