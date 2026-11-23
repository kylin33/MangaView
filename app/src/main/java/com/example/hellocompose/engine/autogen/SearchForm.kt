package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.remote.UrlResolver
import com.example.hellocompose.data.source.HttpMethod
import com.example.hellocompose.data.source.ListRule
import com.example.hellocompose.data.source.SearchRequestBuilder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 首页里能当站内搜索用的表单。
 *
 * 为什么必须从表单里读，而不是继续猜地址：搜索地址的形态是站点自己定的。
 * `/search?keyword=` 只是其中一种，真实站点里还有
 * `/index.php?m=search&a=init&wd=`（带隐藏域）、`/search.php` + POST（关键字在请求体里）、
 * `?s=`（WordPress）、`?k=`、`/so/{keyword}` … 穷举永远漏。
 * 但这些站点**都必须在页面上放一个搜索框**给用户用，那个 `<form>` 就是站点自报的答案：
 * action 是请求路径、method 是提交方式、input 的 name 是参数名。
 *
 * 所以这里只做「读表单」这一件事，不发任何请求。读出来的候选还要经过
 * [SourceAutoGenerator] 的真实抓取验证才会写进配置 —— 页面上摆一个搜索框、
 * 提交后却什么都不搜的站点并不少见。
 */
data class SearchForm(
    /** 提交地址，已补成绝对地址，且已去掉查询串（查询参数在 [fields] 里） */
    val action: String,
    val method: HttpMethod,
    /** 关键字参数名，来自 `<input name="...">` */
    val keywordParam: String,
    /** 隐藏域等固定参数：少一个就会被重定向回首页的那种 */
    val fields: Map<String, String>,
    val score: Int,
    /** 判断依据，写进识别步骤让用户看得见 */
    val reason: String
) {

    /**
     * 拼成 `path` 模板。
     *
     * GET 把关键字和固定参数一起嵌进地址，生成出来的配置和手写的一模一样：
     * `/index.php?m=search&a=init&wd={keyword}` —— 用户一眼能看懂，也方便直接改。
     * POST 的关键字只能在请求体里，所以地址保持原样，靠 [keywordParam] 带出去。
     */
    fun pathTemplate(): String {
        if (method == HttpMethod.POST) return action
        val query = buildList {
            fields.forEach { (k, v) -> add(SearchRequestBuilder.encode(k) + "=" + SearchRequestBuilder.encode(v)) }
            add(keywordParam + "=" + SearchRequestBuilder.KEYWORD)
        }.joinToString("&")
        return action + (if (action.contains('?')) "&" else "?") + query
    }

    /** 套到首页已验证的列表规则上：搜索结果的 DOM 结构通常与首页一致 */
    fun ruleOver(home: ListRule): ListRule =
        home.copy(
            path = pathTemplate(),
            method = method,
            keywordParam = keywordParam,
            // GET 的固定参数已经写进 path，再填一份就会被重复拼一次
            fields = if (method == HttpMethod.POST) fields else emptyMap()
        )
}

/**
 * 从页面里找搜索表单，并推断参数名。
 *
 * 全部是保守规则：宁可不给搜索，也不要给一个「提交了但结果和热门列表一样」的假搜索。
 * 这类错误用户很难自己发现 —— 搜索框能用，返回一堆漫画，看起来完全正常。
 */
object SearchFormScanner {

    /** 参数名是这些的，几乎可以肯定是搜索框 */
    private val STRONG_PARAMS = setOf(
        "keyword", "keywords", "searchkey", "search_key", "searchword", "searchwords",
        "wds", "wd", "kw", "q", "s", "search", "key", "word", "query", "msg"
    )

    /** 也常用来当搜索参数，但也可能出现在别的表单里，权重低一些 */
    private val WEAK_PARAMS = setOf("k", "name", "title", "text", "content", "keys", "w", "str", "value")

    /** 表单/输入框上的搜索痕迹 */
    private val SEARCH_HINT = Regex("search|sousuo|so-|query|find|关键词|搜索", RegexOption.IGNORE_CASE)

    /** action 指向这些地方的，是登录/注册/评论表单，不是搜索 */
    private val NOT_SEARCH_ACTION = Regex(
        "login|signin|sign-in|register|signup|logout|password|comment|feedback|subscribe|cart|order",
        RegexOption.IGNORE_CASE
    )

    /** 输入框 name 里带这些字样的，基本可以排除（用户名、验证码、邮箱…） */
    private val NOT_SEARCH_INPUT = Regex(
        "pass|pwd|email|mail|phone|mobile|tel|captcha|vcode|verify|code|user|login|nick|qq|url|site|comment|content_id",
        RegexOption.IGNORE_CASE
    )

    /** 一个表单里超过这么多输入框，多半是筛选器或注册表单 */
    private const val MAX_INPUTS = 10

    /** 低于这个分数就不当作搜索表单（3 分 = 至少有一条明确证据） */
    private const val MIN_SCORE = 3

    /** 隐藏域最多带几个：真实搜索表单通常 1~3 个 */
    private const val MAX_HIDDEN = 8

    /**
     * 扫描整页表单，按可信度从高到低返回。
     *
     * [pageUrl] 用来补全相对 action（表单的 action 是相对**当前页面**而不是站点根）；
     * [baseUrl] 用来把地址缩回相对形式，让生成的配置可读、换域名时不用改。
     */
    fun scan(doc: Document, pageUrl: String, baseUrl: String): List<SearchForm> {
        val origin = urlOf(pageUrl)
        return doc.select("form").mapNotNull { form -> read(form, origin) }
            .sortedWith(compareByDescending<SearchForm> { it.score }.thenBy { it.action })
            .map { it.copy(action = SearchRequestBuilder.relativize(it.action, baseUrl)) }
    }

    /**
     * 页面里出现过的搜索参数名，按「像搜索的程度」排序。
     *
     * 给「表单不是标准的 form」的站点兜底：有的站把输入框放在 form 外面，
     * 提交逻辑写在 JS 里（`location.href = '/search?kw=' + input.value`）。
     * 那种站点读不到 action，但参数名还在 DOM 里，仍然能用来拼地址。
     */
    fun paramHints(doc: Document): List<String> {
        val seen = LinkedHashMap<String, Int>()
        doc.select("input[name]").forEach { input ->
            val name = input.attr("name").trim()
            if (name.isBlank()) return@forEach
            val weight = when {
                name.lowercase() in STRONG_PARAMS -> 2
                name.lowercase() in WEAK_PARAMS -> 1
                else -> 0
            }
            if (weight == 0) return@forEach
            if (NOT_SEARCH_INPUT.containsMatchIn(name)) return@forEach
            seen[name] = (seen[name] ?: 0) + weight
        }
        return seen.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** 脚本里出现的、被引号包起来的片段 */
    private val QUOTED = Regex("""["']([^"']{1,120})["']""")

    /** 静态资源后缀：脚本里引号包起来的东西大多是这些，不是接口 */
    private val ASSET = Regex(""".(js|css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|mp4)(\?|$)""", RegexOption.IGNORE_CASE)

    /** 片段里要带这些痕迹才可能是搜索入口 */
    private val SCRIPT_PATH_HINT = Regex("search|sousuo|/so/|/ss|/s\\?|find|query", RegexOption.IGNORE_CASE)

    /**
     * 从内联脚本里找「站点自己拼出来的搜索地址」。
     *
     * 表单探测只能读到 `<form action=...>`。SPA 和一部分模板根本没有 form：
     * 提交逻辑写成 `location.href = '/ss?wd=' + input.value`，地址只存在于 JS 字符串里。
     * 这类站点以前只能靠猜常见模板，猜不中就判成「没有搜索」。
     *
     * 只取「像搜索」的短路径，不取整段脚本：候选每多一条都是一次真实请求，
     * 猜错的代价不只是时间，还可能触发站点限流。
     */
    /** 脚本里一条搜索线索：地址和它带的参数名 */
    data class ScriptHint(val path: String, val param: String?)

    fun scriptPaths(doc: Document): List<String> = scriptHints(doc).map { it.path }

    fun scriptPaths(script: String): List<String> = scriptHints(script).map { it.path }

    fun scriptHints(doc: Document): List<ScriptHint> = hintsIn(
        doc.select("script:not([src])").joinToString("\n") { it.data() }
    )

    /** 同一套规则也用于站点的独立脚本文件（`/static/js/app.js` 这类） */
    fun scriptHints(script: String): List<ScriptHint> = hintsIn(script)

    private fun hintsIn(raw: String): List<ScriptHint> {
        // 只截前 200KB：脚本里真正的入口逻辑通常在最前面，
        // 而打包后的第三方库会把整页撑到几 MB，读完只是浪费内存
        val text = raw.take(200_000)
        if (text.isBlank()) return emptyList()

        val out = LinkedHashSet<ScriptHint>()
        for (match in QUOTED.findAll(text)) {
            if (out.size >= 6) break
            val raw = match.groupValues[1].trim()
            if (raw.isEmpty() || raw.length > 80) continue
            if (raw.startsWith("#") || raw.startsWith(".") || raw.startsWith("http")) continue
            if (ASSET.containsMatchIn(raw)) continue
            if (!SCRIPT_PATH_HINT.containsMatchIn(raw)) continue

            // 关键字之前的部分才是路径：`/ss?wd=`、`/search/{kw}`、`/search?q=`
            val head = raw.substringBefore("{").substringBefore('+')
            val param = Regex("[?&]([A-Za-z_][A-Za-z0-9_]{0,20})=").find(head)?.groupValues?.get(1)
            val path = raw.substringBefore('?')
                .substringBefore("{")
                .substringBefore('+')
                .substringBefore('#')
                .trim()
                .trimEnd('/')
            if (path.isEmpty() || path == "/") continue
            if (NOT_SEARCH_ACTION.containsMatchIn(path)) continue
            out += ScriptHint(
                path = if (path.startsWith("/")) path else "/$path",
                // 脚本自己写的参数名比页面输入框的 name 可信：前者是真正发出去的参数
                param = param?.takeIf { !NOT_SEARCH_INPUT.containsMatchIn(it) }
            )
        }
        return out.toList()
    }

    private fun read(form: Element, pageUrl: String): SearchForm? {
        val action = form.attr("action").trim()
        if (action.startsWith("javascript:", ignoreCase = true)) return null
        // 有密码框的是登录/注册/改密表单；站内搜索不会要密码
        if (form.selectFirst("input[type=password]") != null) return null

        val inputs = form.select("input, select, textarea")
        if (inputs.size > MAX_INPUTS) return null

        val resolved = if (action.isBlank()) pageUrl else resolveAction(action, pageUrl)
        if (NOT_SEARCH_ACTION.containsMatchIn(resolved)) return null

        val keywordInput = inputs.firstOrNull { isTextInput(it) && it.attr("name").isNotBlank() }
            ?: return null

        val param = keywordInput.attr("name").trim()
        if (NOT_SEARCH_INPUT.containsMatchIn(param)) return null

        var score = 0
        val reason = StringBuilder()

        val type = keywordInput.attr("type").trim().lowercase()
        if (type == "search") {
            score += 4
            reason.append("type=search，")
        }
        when (param.lowercase()) {
            in STRONG_PARAMS -> {
                score += 3
                reason.append("参数名 $param 是常见搜索参数，")
            }
            in WEAK_PARAMS -> {
                score += 1
                reason.append("参数名 $param 可能用于搜索，")
            }
        }
        if (SEARCH_HINT.containsMatchIn(resolved)) {
            score += 3
            reason.append("action 里有 search 字样，")
        }
        val formHints = "${form.attr("id")} ${form.className()} ${form.attr("role")} ${form.attr("name")}"
        if (SEARCH_HINT.containsMatchIn(formHints)) {
            score += 2
            reason.append("表单本身标记为搜索框，")
        }
        val inputHints = "${keywordInput.attr("id")} ${keywordInput.attr("class")} " +
            "${keywordInput.attr("placeholder")} ${keywordInput.attr("aria-label")}"
        if (SEARCH_HINT.containsMatchIn(inputHints)) {
            score += 2
            reason.append("输入框提示语提到搜索，")
        }
        if (form.selectFirst("button, input[type=submit], input[type=image]") != null) {
            score += 1
        }

        if (score < MIN_SCORE) return null

        val method = HttpMethod.parse(form.attr("method"))
        val hidden = LinkedHashMap<String, String>()
        // action 自带查询串时先收进来：`/index.php?m=search` 这种写法很常见，
        // 丢掉它 POST 提交就变成「查全部」
        queryParamsOf(resolved).forEach { (k, v) -> hidden[k] = v }
        inputs.forEach { input ->
            if (input.tagName() != "input") return@forEach
            if (!input.attr("type").equals("hidden", ignoreCase = true)) return@forEach
            val name = input.attr("name").trim()
            if (name.isBlank() || hidden.size >= MAX_HIDDEN) return@forEach
            hidden[name] = input.attr("value")
        }

        // GET 的固定参数稍后由 pathTemplate() 拼进 path；POST 的留在 fields 里进请求体。
        // 两处共用同一份 fields，不会出现「识别时带上了、运行时漏了」。
        val noQuery = resolved.substringBefore('?')
        return SearchForm(
            action = noQuery,
            method = method,
            keywordParam = param,
            fields = hidden,
            score = score,
            reason = reason.toString().trimEnd('，', ' ')
        )
    }

    /**
     * 按**页面地址**解析 action，而不是按站点根。
     *
     * [UrlResolver.resolve] 的第二个参数是站点根地址，拿页面地址去喂它，
     * `/index.php` 会被拼成 `/list/hot/index.php` —— 生成的配置看起来完全正常，
     * 只是每次搜索都打到不存在的地址上。
     */
    private fun resolveAction(action: String, pageUrl: String): String =
        runCatching { java.net.URI(pageUrl).resolve(action).toString() }
            .getOrDefault(UrlResolver.resolve(action, UrlResolver.originOf(pageUrl).ifBlank { pageUrl }))

    /**
     * 能当搜索框的输入框。
     *
     * 没写 type 的 `<input>` 默认就是 text，这种写法在老站里非常普遍，
     * 按 type 过滤掉会把一半站点漏掉。
     */
    private fun isTextInput(input: Element): Boolean {
        val type = input.attr("type").trim().lowercase()
        return type.isEmpty() || type == "text" || type == "search"
    }

    private fun queryParamsOf(url: String): Map<String, String> {
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (key.isBlank()) return@forEach
            result[decode(key)] = decode(value)
        }
        return result
    }

    private fun decode(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    /** 表单 action 为空表示提交给当前页面，所以需要一个能用的绝对地址 */
    private fun urlOf(pageUrl: String): String =
        if (pageUrl.startsWith("http")) pageUrl else "https://localhost/"
}
