package com.example.hellocompose.data.source

import com.example.hellocompose.core.AppConfig
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 把一份 JSON 文本解析成 [SourceConfig]。
 *
 * 用 Android 自带的 org.json 而不是 kotlinx.serialization：
 * 后者要额外挂编译器插件，和 AGP 9 内置 Kotlin 的兼容性没把握，
 * 而配置文件结构简单、字段少，手写解析反而更好控制错误信息 ——
 * 配置写错时要能明确告诉用户"哪个字段不对"，而不是抛一串堆栈。
 *
 * 错误信息分两类，都面向终端用户而不是开发者：
 * - JSON 语法错：转换成中文并定位到行列，因为用户是在手机上粘贴的，看不到字符编号
 * - 字段缺失或非法：带上 id 和字段路径，例如 [66漫画] home.item 不能为空
 */
object SourceParser {

    /** 当前支持的结构版本。配置里写了更高的版本说明是给新版 App 用的 */
    const val SUPPORTED_SCHEMA = 1

    private val CHAR_INDEX = Regex("at character (\\d+)")

    /**
     * 新建站点时预填的内容。
     * 字段齐全、开箱可改，用户只需要替换 baseUrl 和 4 组选择器。
     */
    val TEMPLATE: String = """
        {
          "schemaVersion": 1,
          "id": "my-site",
          "name": "我的站点",
          "description": "",
          "baseUrl": "https://example.com",
          "enabled": true,
          "referer": "{baseUrl}/",
          "timeoutMs": 15000,
          "trustAllCerts": false,
          "home": {
            "path": "/",
            "item": "div.comic-item",
            "link": { "select": "a", "attr": "href" },
            "cover": { "select": "img", "attr": "data-src|src" },
            "title": { "select": "h3", "attr": "text" }
          },
          "search": {
            "path": "/search?q={keyword}",
            "item": "div.comic-item",
            "link": { "select": "a", "attr": "href" },
            "cover": { "select": "img", "attr": "data-src|src" },
            "title": { "select": "h3", "attr": "text" }
          },
          "detail": {
            "item": "ul#chapter-list li a",
            "chapterUrl": { "select": "", "attr": "href" },
            "chapterTitle": { "select": "", "attr": "text" }
          },
          "reader": {
            "item": "div.reader img",
            "image": { "select": "", "attr": "src" },
            "referer": "{pageUrl}"
          }
        }
    """.trimIndent()

    fun parse(rawJson: String, fallbackId: String): SourceConfig {
        val json = normalize(rawJson)
        require(json.isNotEmpty()) { "内容为空，请先粘贴站点配置 JSON" }

        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException(syntaxMessage(json, e))
        }

        val id = root.optString("id").trim().ifBlank { fallbackId }
        val schema = root.optInt("schemaVersion", SUPPORTED_SCHEMA)
        require(schema <= SUPPORTED_SCHEMA) {
            "[$id] 配置版本 $schema 高于本应用支持的第 $SUPPORTED_SCHEMA 版，请先升级 App"
        }

        val baseUrl = root.optString("baseUrl").trim().trimEnd('/')
        require(baseUrl.startsWith("http")) { "[$id] baseUrl 必须以 http:// 或 https:// 开头" }

        val home = root.optJSONObject("home")
            ?: throw IllegalArgumentException("[$id] 缺少 home 节点，无法抓首页列表")
        val detail = root.optJSONObject("detail")
            ?: throw IllegalArgumentException("[$id] 缺少 detail 节点，无法抓章节列表")
        val reader = root.optJSONObject("reader")
            ?: throw IllegalArgumentException("[$id] 缺少 reader 节点，无法抓章节图片")

        val config = SourceConfig(
            id = id,
            name = root.optString("name").trim().ifBlank { id },
            baseUrl = baseUrl,
            enabled = root.optBoolean("enabled", true),
            description = root.optString("description").trim(),
            userAgent = root.optString("userAgent").trim()
                .ifBlank { AppConfig.USER_AGENT },
            timeoutMs = root.optLong("timeoutMs", AppConfig.TIMEOUT_MS)
                .coerceIn(3_000L, 60_000L),
            referer = root.optString("referer", "{baseUrl}/"),
            trustAllCerts = root.optBoolean("trustAllCerts", false),
            home = home.toListRule(id, "home"),
            search = root.optJSONObject("search")?.toListRule(id, "search"),
            detail = DetailRule(
                item = detail.optString("item").trim(),
                url = detail.optJSONObject("chapterUrl").toFieldRule(FieldRule(select = "", attr = "href")),
                title = detail.optJSONObject("chapterTitle").toFieldRule(FieldRule.TEXT),
                script = detail.optJSONObject("script").toScriptRule(id, "detail")
            ).also {
                require(it.item.isNotBlank() || it.script != null) {
                    "[$id] detail.item 不能为空（除非配置了 detail.script）"
                }
            },
            reader = ReaderRule(
                item = reader.requiredString(id, "reader", "item"),
                image = reader.optJSONObject("image").toFieldRule(
                    FieldRule(select = "", attr = "data-original|data-src|src")
                ),
                referer = reader.optString("referer", "{pageUrl}")
            ),
            confidence = root.optJSONObject("confidence")?.toConfidence()
        )

        validateSelectors(config)
        return config
    }

    /**
     * 去掉 BOM、首尾空白和 markdown 代码围栏。
     *
     * 配置多半是从聊天软件或网页复制来的，经常连 ```json 一起带进来；
     * 这种不属于「内容写错」，帮用户剥掉比让他自己找更合适。
     */
    private fun normalize(raw: String): String {
        var s = raw
        while (s.startsWith("\uFEFF")) s = s.substring(1)
        s = s.trim()

        if (s.startsWith("```")) {
            s = s.substringAfter('\n', "").trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        return s
    }

    /**
     * 把 org.json 的英文报错翻译成能指路的中文。
     * 只做映射，不尝试修复内容 —— 猜错了反而更难排查。
     */
    private fun syntaxMessage(json: String, e: JSONException): String {
        val raw = e.message.orEmpty()
        val location = CHAR_INDEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 0..json.length }
            ?.let { pos ->
                val head = json.substring(0, pos)
                val line = head.count { it == '\n' } + 1
                val column = pos - head.lastIndexOf('\n')
                "（第 $line 行第 $column 列附近）"
            }
            .orEmpty()

        val reason = when {
            raw.startsWith("Unterminated string") -> "字符串缺少结束引号"
            raw.startsWith("End of input") -> "内容不完整，可能少了 } 或 ]"
            raw.startsWith("Expected ':'") -> "字段名后面缺少冒号"
            raw.startsWith("Expected ','") -> "缺少逗号或右括号"
            raw.startsWith("Expected name") -> "字段名必须用英文双引号包起来"
            raw.contains("Not a primitive") || raw.startsWith("Value") -> "有值不是合法的 JSON 写法"
            raw.isEmpty() -> "无法解析"
            else -> raw.substringBefore(" at character")
        }

        val hint = if (json.contains('“') || json.contains('”')) {
            "\n提示：内容里有中文引号“ ”，JSON 只认英文双引号 \""
        } else {
            ""
        }
        return "JSON 语法错误$location：$reason$hint"
    }

    /**
     * 提前拦掉写错的选择器。
     *
     * 否则用户要等到「试抓取解析到 0 部作品」才知道 item 写错了，
     * 而那时候他分不清是选择器语法错、还是站点结构不同。
     * `^` 是自定义的「向上找 a 标签」语法，不参与 CSS 校验。
     */
    private fun validateSelectors(config: SourceConfig) {
        fun check(label: String, selector: String) {
            if (selector.isBlank() || selector == "^") return
            try {
                Jsoup.parse("").select(selector)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "[${config.id}] $label 不是合法的 CSS 选择器：$selector"
                )
            }
        }

        check("home.item", config.home.item)
        config.search?.let { check("search.item", it.item) }
        check("detail.item", config.detail.item)
        check("reader.item", config.reader.item)
    }

    private fun JSONObject?.toListRule(id: String, node: String): ListRule {
        val obj = this ?: throw IllegalArgumentException("[$id] $node 节点为空")
        val script = obj.optJSONObject("script").toScriptRule(id, node)
        return ListRule(
            path = obj.optString("path", "/").ifBlank { "/" },
            item = obj.optString("item").trim(),
            link = obj.optJSONObject("link").toFieldRule(FieldRule.LINK),
            cover = obj.optJSONObject("cover").toFieldRule(FieldRule(select = "", attr = "data-original|src")),
            title = obj.optJSONObject("title").toFieldRule(FieldRule(select = "", attr = "alt")),
            script = script
        ).also {
            require(it.item.isNotBlank() || script != null) {
                "[$id] $node.item 不能为空（除非配置了 $node.script）"
            }
        }
    }

    /**
     * 解析脚本取数规则。
     *
     * pattern 有两种写法：普通字符串当作内联变量名（`serchArry`、`initIntroData`），
     * `re:` 前缀当作正则。变量名形式不容易写错，正则留给结构特殊的站点。
     * 正则写错时立刻报错：等到抓取阶段才抛异常，用户根本不知道是哪一行配置的问题。
     */
    private fun JSONObject?.toScriptRule(id: String, node: String): ScriptRule? {
        val obj = this ?: return null
        val pattern = obj.optString("pattern").trim()
        if (pattern.isBlank()) return null

        if (pattern.startsWith(ScriptData.REGEX_PREFIX)) {
            val regex = pattern.removePrefix(ScriptData.REGEX_PREFIX).trim()
            require(regex.isNotBlank()) { "[$id] $node.script.pattern 缺少正则内容" }
            try {
                Regex(regex)
            } catch (e: Exception) {
                throw IllegalArgumentException("[$id] $node.script.pattern 不是合法的正则表达式：$regex")
            }
        }

        return ScriptRule(
            pattern = pattern,
            listPath = obj.optString("listPath").trim(),
            urlTemplate = obj.optString("urlTemplate").trim(),
            urlField = obj.optString("urlField", "url").trim().ifBlank { "url" },
            titleField = obj.optString("titleField", "title").trim().ifBlank { "title" },
            coverField = obj.optString("coverField", "cover").trim().ifBlank { "cover" }
        )
    }

    private fun JSONObject?.toFieldRule(default: FieldRule): FieldRule {
        val obj = this ?: return default
        return FieldRule(
            select = obj.optString("select", default.select),
            attr = obj.optString("attr", default.attr).ifBlank { default.attr }
        )
    }

    private fun JSONObject.requiredString(id: String, node: String, key: String): String {
        val v = optString(key).trim()
        require(v.isNotBlank()) { "[$id] $node.$key 不能为空" }
        return v
    }

    /**
     * 置信度是「参考信息」，不是必需字段。
     * 写坏了也不该让整份配置失效——夹到 0~1 之间继续用，比直接报错合适。
     */
    private fun JSONObject.toConfidence(): SourceConfidence = SourceConfidence(
        overall = optDouble("overall", 0.0).coerceIn(0.0, 1.0),
        home = optDouble("home", 0.0).coerceIn(0.0, 1.0),
        detail = optDouble("detail", 0.0).coerceIn(0.0, 1.0),
        reader = optDouble("reader", 0.0).coerceIn(0.0, 1.0)
    )
}
