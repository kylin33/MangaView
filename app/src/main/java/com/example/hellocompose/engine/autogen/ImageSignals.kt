package com.example.hellocompose.engine.autogen

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 「这个节点上挂着一张内容图吗」的全部判断。
 *
 * 抽出来是因为它同时被三处使用：首页候选（封面）、正文候选（正文图）、列表打分（图片有效率）。
 * 三处必须用同一套语义 —— 否则会出现「候选生成时认为有图、打分时认为没图」这种
 * 自相矛盾的筛选，调参时根本看不出哪一步把站点筛掉了。
 */
internal object ImageSignals {

    /** 懒加载属性的优先级：`src` 永远最后，因为懒加载站点里它常是占位 gif */
    private val IMAGE_ATTRS = listOf(
        "data-original", "data-src", "data-lazy-src", "data-echo", "data-actualsrc", "data-bg", "src"
    )

    /**
     * 能承载图片地址的元素。不限于 img：背景图卡片把地址写在 a/div 的 data-* 上。
     * 顺序保持文档顺序，`selectFirst` 会取页面上最先出现的那一个。
     */
    const val CARRIER_SELECTOR =
        "img, [data-original], [data-src], [data-lazy-src], [data-echo], [data-actualsrc], [data-bg]"

    private val PLACEHOLDER_HINTS = listOf(
        "data:image", "blank.", "spacer.", "placeholder", "loading.", "pixel.", "transparent.", "grey.gif"
    )

    private val ICON_HINTS = Regex(
        "(logo|icon|avatar|qrcode|qr-code|advert|banner|emoticon|smilies|weixin|sprite|btn_|button|arrow|pixel)",
        RegexOption.IGNORE_CASE
    )

    /** 元素自身携带的、看起来像图片地址的值 */
    fun values(el: Element): List<String> =
        IMAGE_ATTRS.map { el.attr(it) }.filter { looksLikeImage(it) }

    fun carries(el: Element): Boolean = values(el).isNotEmpty()

    /** 条目自身或它内部第一个能取到图片地址的元素 */
    fun carrierIn(item: Element): Element? =
        if (carries(item)) item else item.selectFirst(CARRIER_SELECTOR)?.takeIf { carries(it) }

    /**
     * 拼属性候选链，顺序按懒加载的常见优先级。
     * 只保留「过半元素上都放得出图」的属性，避免把某一条目的偶然属性当成通用属性。
     */
    fun attrOrder(carriers: List<Element>): String {
        val threshold = maxOf(1, (carriers.size * 0.5).toInt())
        val present = IMAGE_ATTRS.filter { attr ->
            carriers.count { looksLikeImage(it.attr(attr)) } >= threshold
        }
        val order = present.ifEmpty { listOf("src") }
        return if ("src" in order) order.joinToString("|") else (order + "src").joinToString("|")
    }

    fun looksLikeImage(value: String): Boolean {
        if (value.isBlank()) return false
        val s = value.trim().lowercase()
        if (s.startsWith("data:")) return false
        if (s.length < 6) return false
        if (PLACEHOLDER_HINTS.any { s.contains(it) }) return false
        return s.startsWith("http") || s.startsWith("//") || s.contains('/')
    }

    /** 地址里带 logo / icon / banner 之类的字样：是图标广告，不是作品封面 */
    fun isIcon(value: String): Boolean = ICON_HINTS.containsMatchIn(value)

    /**
     * 页面上的内容图，按「外层已计入则跳过内部重复命中」去重。
     * 用于没有统一列表容器时按图片反推列表（封面墙站点）。
     */
    fun contentImages(doc: Document): List<Element> {
        val chosen = ArrayList<Element>()
        val chosenSet = HashSet<Element>()
        for (el in doc.select(CARRIER_SELECTOR)) {
            // 外层元素已经算过图片时，跳过它内部重复命中的元素（背景图卡片的常见嵌套）
            if (el.parents().any { it in chosenSet }) continue

            val values = values(el)
            if (values.isEmpty()) continue
            // 全都是 icon/logo/广告之类的就别当正文图
            if (values.none { !isIcon(it) }) continue

            val w = el.attr("width").toIntOrNull() ?: 0
            val h = el.attr("height").toIntOrNull() ?: 0
            if (w in 1..60 && h in 1..60) continue

            chosen += el
            chosenSet += el
        }
        return chosen
    }
}
