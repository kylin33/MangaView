package com.example.hellocompose.data.source

import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.remote.PageParser
import com.example.hellocompose.data.remote.UrlResolver
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * 从页面内联脚本里的 JSON 取数据，[ScriptRule] 的唯一实现。
 *
 * 和 [RuleEvaluator] 并列：那条路走 DOM + CSS 选择器，这条路走 `<script>` 文本 + 正则。
 * 两条路都产出同样的 [Chapter] / [Manga]，所以上层（抓取、UI）不需要知道数据是从哪来的。
 *
 * 为什么不干脆上无头浏览器：一份配置只要几十行 JSON，而这些站点真正缺的只是
 * 「把内联变量里的 JSON 读出来」这一件事。为它引入 WebView 渲染整页，
 * 代价（内存、速度、被反爬识别）远大于收益。
 */
object ScriptData {

    private val PLACEHOLDER = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)\}""")

    fun chapters(doc: Document, rule: ScriptRule, baseUrl: String): List<Chapter> =
        items(doc, rule).mapNotNull { obj ->
            val href = buildUrl(obj, rule)
            val title = obj.optString(rule.titleField).trim()
            if (href.isBlank() || title.isBlank()) {
                null
            } else {
                Chapter(title = title, url = UrlResolver.resolve(href, baseUrl))
            }
        }.distinctBy { it.url }

    fun list(doc: Document, rule: ScriptRule, baseUrl: String): List<Manga> =
        items(doc, rule).mapNotNull { obj ->
            val href = buildUrl(obj, rule)
            if (href.isBlank()) return@mapNotNull null
            Manga(
                title = obj.optString(rule.titleField).trim().ifBlank { PageParser.UNTITLED },
                coverUrl = UrlResolver.resolve(obj.optString(rule.coverField).trim(), baseUrl),
                detailUrl = UrlResolver.resolve(href, baseUrl)
            )
        }.distinctBy { it.detailUrl }

    /** 按模板把 JSON 字段填进地址；没填上的占位符原样保留，交给上层判定为空 */
    private fun buildUrl(obj: JSONObject, rule: ScriptRule): String {
        if (rule.urlTemplate.isBlank()) return obj.optString(rule.urlField).trim()
        return PLACEHOLDER.replace(rule.urlTemplate) { m ->
            val value = obj.optString(m.groupValues[1]).trim()
            value.ifBlank { m.value }
        }
    }

    /** 取出 JSON 数组；找不到返回空列表，不抛异常 */
    private fun items(doc: Document, rule: ScriptRule): List<JSONObject> {
        val array = findArray(doc, rule) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun findArray(doc: Document, rule: ScriptRule): JSONArray? {
        if (rule.pattern.startsWith(REGEX_PREFIX)) {
            val pattern = rule.pattern.removePrefix(REGEX_PREFIX).trim()
            return findArrayByRegex(doc, pattern, rule.listPath)
        }
        return findArrayByName(doc, rule.pattern, rule.listPath)
    }

    private fun findArrayByRegex(doc: Document, pattern: String, listPath: String): JSONArray? {
        val regex = runCatching { Regex(pattern, RegexOption.DOT_MATCHES_ALL) }.getOrNull()
            ?: return null

        for (script in doc.select("script")) {
            val text = script.data()
            if (text.isEmpty()) continue

            val raw = regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (raw.isEmpty()) continue

            val located = runCatching { locate(raw, listPath) }.getOrNull()
            if (located != null && located.length() > 0) return located
        }
        return null
    }

    /**
     * 按变量名找：定位标识符后第一处 `[` / `{`，再靠括号配平截出完整 JSON。
     *
     * 比正则可靠得多 —— 内联数组里常常还嵌着数组（`[{title:"连载",data:[…]}]`），
     * 非贪婪正则会在第一个 `]` 就截断，配平不会。
     */
    private fun findArrayByName(doc: Document, name: String, listPath: String): JSONArray? {
        if (name.isBlank()) return null
        for (script in doc.select("script")) {
            val text = script.data()
            var from = 0
            while (true) {
                val at = text.indexOf(name, from)
                if (at < 0) break
                from = at + name.length

                val start = jsonStartAfter(text, at + name.length) ?: continue
                val raw = sliceBalanced(text, start) ?: continue
                val located = runCatching { locate(raw, listPath) }.getOrNull()
                if (located != null && located.length() > 0) return located
            }
        }
        return null
    }

    /**
     * 从 [from] 往后找 JSON 的起点。
     * 中途遇到 `;` 就放弃：说明这个名字出现在一句无关代码里，后面跟的不是它的数据。
     */
    internal fun jsonStartAfter(text: String, from: Int): Int? {
        val limit = minOf(text.length, from + 120)
        var i = from
        while (i < limit) {
            when (text[i]) {
                '[', '{' -> return i
                ';' -> return null
                '"', '\'' -> {
                    // 跳过字符串字面量，避免把字符串里的 [ 当成数组起点
                    val quote = text[i]
                    var j = i + 1
                    while (j < limit && text[j] != quote) {
                        if (text[j] == '\\') j++
                        j++
                    }
                    if (j >= limit) return null
                    i = j
                }
            }
            i++
        }
        return null
    }

    /** 从 [start] 处的 `[` / `{` 开始做括号配平，返回完整 JSON 文本；不完整时返回 null */
    internal fun sliceBalanced(text: String, start: Int): String? {
        if (start !in text.indices) return null
        val open = text[start]
        if (open != '[' && open != '{') return null
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

    /** `pattern` 以这个前缀开头时按正则处理，否则当作内联变量名 */
    const val REGEX_PREFIX = "re:"

    /**
     * 按点路径在 JSON 里走到目标数组。
     * 路径段是数字时按下标取（数组），否则按字段名取（对象）。
     */
    private fun locate(raw: String, path: String): JSONArray? {
        var current: Any? = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)

        for (segment in path.split('.').filter { it.isNotBlank() }) {
            current = when (val cur = current) {
                is JSONArray -> segment.toIntOrNull()
                    ?.takeIf { it in 0 until cur.length() }
                    ?.let { cur.opt(it) }

                is JSONObject -> cur.opt(segment)
                else -> null
            } ?: return null
        }
        return current as? JSONArray
    }
}
