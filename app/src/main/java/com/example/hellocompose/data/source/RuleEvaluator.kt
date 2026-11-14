package com.example.hellocompose.data.source

import org.jsoup.nodes.Element

/**
 * [FieldRule] 的唯一实现。
 *
 * 抽成独立文件是为了让「自动识别站点」和「正式抓取」共用同一份取值语义：
 * 识别阶段生成的规则，必须按运行时的完全相同的逻辑去解析，
 * 否则会出现「识别时看着对、保存后空白」这种最难排查的问题。
 */

/** 按规则定位取值用的元素 */
fun Element.pickByRule(rule: FieldRule): Element? = when {
    rule.select == "^" -> closest("a")
    rule.select.isBlank() -> this
    else -> selectFirst(rule.select)
}

/** 按规则从元素上取值；多个候选属性用 | 分隔，按顺序取第一个非空 */
fun Element?.readByRule(attrSpec: String): String {
    this ?: return ""
    if (attrSpec == "text") return text().trim()
    if (attrSpec == "html") return html().trim()

    for (raw in attrSpec.split('|')) {
        val key = raw.trim()
        if (key.isEmpty()) continue
        val value = if (key.startsWith("abs:")) {
            absUrl(key.removePrefix("abs:"))
        } else {
            attr(key)
        }
        if (value.isNotBlank()) return value.trim()
    }
    return ""
}
