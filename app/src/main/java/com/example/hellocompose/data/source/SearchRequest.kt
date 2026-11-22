package com.example.hellocompose.data.source

import java.net.URI
import java.net.URLEncoder

/** 搜索请求的提交方式。表单里写的是 `method="get"` / `method="post"` */
enum class HttpMethod {
    GET,
    POST;

    companion object {
        fun parse(raw: String): HttpMethod =
            if (raw.trim().equals("post", ignoreCase = true)) POST else GET
    }

    /** 写回 JSON 时用的小写形式，与 HTML 表单里的写法一致 */
    val token: String get() = name.lowercase()
}

/**
 * 一次站内搜索要发的请求。
 *
 * 单独抽出来是因为**识别阶段和正式运行必须走同一段代码**。
 * 以前两边各写一遍拼地址的逻辑，识别时拼对了、运行时拼错了，用户看到的就是
 * 「识别时说搜索可用，实际搜不出东西」—— 这类偏差读代码看不出来，只能靠共用实现来杜绝。
 */
data class SearchRequest(
    val url: String,
    val method: HttpMethod = HttpMethod.GET,
    /** 只有 POST 才有；GET 的参数已经拼进 [url] */
    val body: Map<String, String> = emptyMap()
)

object SearchRequestBuilder {

    /** 地址模板里的关键字占位符 */
    const val KEYWORD = "{keyword}"

    /** 没有占位符时用的默认参数名（手写配置漏写 keywordParam 时的兜底） */
    const val DEFAULT_KEYWORD_PARAM = "keyword"

    fun encode(raw: String): String = URLEncoder.encode(raw, "UTF-8")

    /**
     * 把规则 + 关键字拼成一次请求。
     *
     * 三种形态都要支持，它们来自真实站点：
     * 1. `path = "/search?keyword={keyword}"`：最常见，关键字在地址里；
     * 2. `path = "/index.php"` + `fields = {m: search, a: init}` + `keywordParam = "wd"`：
     *    表单里有隐藏域，少一个参数就返回首页（苹果CMS 系模板的典型形态）；
     * 3. `method = "post"`：表单用 POST 提交，关键字在请求体里。
     *
     * 第 2、3 种以前根本表达不出来 —— 配置里只有一个 `path` 模板，
     * 于是这类站点只能被写成「没有搜索」。
     */
    fun build(rule: ListRule, keyword: String, baseUrl: String): SearchRequest {
        val hasPlaceholder = rule.path.contains(KEYWORD)
        val path = if (hasPlaceholder) rule.path.replace(KEYWORD, encode(keyword)) else rule.path

        // 表单隐藏域先放、关键字后放：顺序固定，便于比对两次请求的差异
        val params = LinkedHashMap<String, String>()
        params.putAll(rule.fields)
        if (!hasPlaceholder) {
            params[rule.keywordParam.ifBlank { DEFAULT_KEYWORD_PARAM }] = keyword
        }

        val url = absolute(path, baseUrl)
        return when (rule.method) {
            HttpMethod.POST -> SearchRequest(url = url, method = HttpMethod.POST, body = params)
            // GET 的值在这里编码；POST 交给表单编码（jsoup 的 data() 会做）
            HttpMethod.GET -> SearchRequest(url = appendQuery(url, params), method = HttpMethod.GET)
        }
    }

    /** 相对路径补成绝对地址；本身就是绝对地址时原样返回 */
    fun absolute(path: String, baseUrl: String): String {
        val p = path.trim()
        val base = baseUrl.trim().trimEnd('/')
        return when {
            p.isEmpty() -> base
            p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true) -> p
            p.startsWith("/") -> base + p
            else -> "$base/$p"
        }
    }

    /**
     * 把绝对地址缩回「相对站点根」的形式。
     *
     * 配置里的 path 用相对地址可读性最好（换镜像域名时也不用改配置），
     * 但表单的 action 可能指向另一个域（搜索站、API 子域），那种情况只能保留绝对地址。
     */
    fun relativize(url: String, baseUrl: String): String {
        val origin = originOf(baseUrl) ?: return url
        if (originOf(url) != origin) return url
        val rest = url.removePrefix(origin)
        return if (rest.startsWith("/")) rest else "/$rest"
    }

    private fun appendQuery(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val separator = when {
            !url.contains('?') -> "?"
            url.endsWith("?") || url.endsWith("&") -> ""
            else -> "&"
        }
        return url + separator + query
    }

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url.trim())
        if (uri.host.isNullOrBlank()) null else "${uri.scheme}://${uri.host}"
    }.getOrNull()
}
