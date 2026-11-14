package com.example.hellocompose.data.source

import org.json.JSONObject

/**
 * [SourceConfig] → JSON 文本，与 [SourceParser] 互为逆运算。
 *
 * 自动识别站点时，识别结果必须落成一份真实可读的 JSON 再交给用户：
 * 一来用户能看到、能改、能分享，二来保存链路仍然只认 JSON 文本，
 * 不需要为「自动生成的配置」单开一条保存路径。
 */
object SourceJson {

    private const val SCHEMA_VERSION = 1

    fun encode(config: SourceConfig): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("id", config.id)
        root.put("name", config.name)
        if (config.description.isNotBlank()) root.put("description", config.description)
        root.put("baseUrl", config.baseUrl)
        root.put("enabled", config.enabled)
        root.put("referer", config.referer)
        root.put("timeoutMs", config.timeoutMs)
        root.put("trustAllCerts", config.trustAllCerts)

        // 置信度只对自动识别出来的配置有意义，手写的配置不写这个节点，
        // 免得让人以为「没有置信度就是不可信」
        config.confidence?.let { confidence ->
            root.put("confidence", JSONObject().apply {
                put("overall", confidence.overall.round3())
                put("home", confidence.home.round3())
                put("detail", confidence.detail.round3())
                put("reader", confidence.reader.round3())
            })
        }

        root.put("home", listRule(config.home))
        config.search?.let { root.put("search", listRule(it)) }

        root.put("detail", JSONObject().apply {
            put("item", config.detail.item)
            put("chapterUrl", field(config.detail.url))
            put("chapterTitle", field(config.detail.title))
            config.detail.script?.let { put("script", scriptRule(it)) }
        })

        root.put("reader", JSONObject().apply {
            put("item", config.reader.item)
            put("image", field(config.reader.image))
            put("referer", config.reader.referer)
        })

        return root.toString(2)
    }

    private fun listRule(rule: ListRule): JSONObject = JSONObject().apply {
        put("path", rule.path)
        put("item", rule.item)
        put("link", field(rule.link))
        put("cover", field(rule.cover))
        put("title", field(rule.title))
        rule.script?.let { put("script", scriptRule(it)) }
    }

    private fun scriptRule(rule: ScriptRule): JSONObject = JSONObject().apply {
        put("pattern", rule.pattern)
        put("listPath", rule.listPath)
        put("urlTemplate", rule.urlTemplate)
        put("urlField", rule.urlField)
        put("titleField", rule.titleField)
        put("coverField", rule.coverField)
    }

    private fun field(rule: FieldRule): JSONObject = JSONObject().apply {
        put("select", rule.select)
        put("attr", rule.attr)
    }

    /** 保留三位小数：0.9200000001 这种值写进配置只会让人怀疑算错了 */
    private fun Double.round3(): Double = kotlin.math.round(this * 1000) / 1000
}
