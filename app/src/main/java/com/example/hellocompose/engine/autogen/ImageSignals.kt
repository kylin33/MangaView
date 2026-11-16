package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.source.ImageUrlResolver
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 「这个节点上挂着一张内容图吗」在识别层的门面。
 *
 * 具体判定全部委托给 [ImageUrlResolver] —— 识别阶段和运行时必须用同一套语义，
 * 否则会出现「候选生成时认为有图、打分时认为没图、运行时取到占位图」三种结论。
 * 这里只保留识别特有的部分：批量选候选、按图片反推列表。
 */
internal object ImageSignals {

    /**
     * 能承载图片地址的元素。不限于 img：
     * - 背景图卡片把地址写在 a/div 的 data-* 或 style 上；
     * - 响应式站点只有 srcset。
     * 顺序保持文档顺序，`selectFirst` 会取页面上最先出现的那一个。
     */
    const val CARRIER_SELECTOR =
        "img, picture, [data-original], [data-original-src], [data-src], [data-lazy-src], " +
            "[data-lazyload], [data-echo], [data-actualsrc], [data-bg], [data-image], " +
            "[data-srcset], [srcset], [style*=background-image]"

    fun values(el: Element): List<String> =
        ImageUrlResolver.candidates(el).filter { it.usable }.map { it.raw }

    fun carries(el: Element): Boolean = ImageUrlResolver.carries(el)

    /** 条目自身或它内部第一个能取到图片地址的元素 */
    fun carrierIn(item: Element): Element? =
        ImageUrlResolver.carrierIn(item, CARRIER_SELECTOR)

    /** 按属性可靠性排序的取值链，供配置里的 `attr` 使用 */
    fun attrOrder(carriers: List<Element>): String = ImageUrlResolver.attrChain(carriers)

    fun looksLikeImage(value: String): Boolean = ImageUrlResolver.looksLikeImage(value)

    fun isIcon(value: String): Boolean = ImageUrlResolver.isIcon(value)

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

            val best = ImageUrlResolver.best(el) ?: continue
            // 全都是 icon/logo/广告之类的就别当正文图
            if (best.icon) continue

            val w = el.attr("width").toIntOrNull() ?: 0
            val h = el.attr("height").toIntOrNull() ?: 0
            if (w in 1..60 && h in 1..60) continue

            chosen += el
            chosenSet += el
        }
        return chosen
    }
}
