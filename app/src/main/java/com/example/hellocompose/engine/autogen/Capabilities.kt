package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.source.TitleNormalizer
import org.jsoup.nodes.Element

/**
 * 一个站点「靠什么才能被抓到」。
 *
 * 早先的识别是按「标准站应该长什么样」写的：列表是 `ul > li`、封面是 `img[src]`、
 * 书名在 `a` 的文字里、章节在 DOM 上。真实站点只要缺一项，整条链路就断在那一环，
 * 而且断得很安静 —— 用户看到的是「识别失败」或「书名全是 14小时前」，
 * 却不知道是哪一项能力不支持。
 *
 * 这一层把「页面用什么方式承载内容」先探测出来，再让识别按探测结果选通道：
 *
 * - 列表：块级重复 / 整页平铺图片 / 内联脚本 JSON
 * - 封面：`img[src]` / 懒加载属性 / CSS 背景图
 * - 标题：链接的 `title` 属性 / 语义节点 / 可见文字
 * - 章节：DOM 列表 / 内联脚本 JSON
 * - 正文图：`img` 属性 / CSS 背景图
 *
 * 探测只统计**已经发生在页面上的事实**（多少条目用了哪种写法），不做猜测；
 * 命中率不够高时宁可报 [Channel.NONE]，也不要给识别一个假的信心来源。
 */
enum class Channel(val label: String) {
    /** 标准写法：`<img src>`、`<ul><li>` 这种 */
    DOM("DOM"),

    /** 懒加载属性：`data-src` / `data-original` / `data-aload` … */
    LAZY_ATTR("懒加载属性"),

    /** 地址写在 `style` 的 `background-image` 上，DOM 里没有 img */
    INLINE_BG("CSS 背景图"),

    /** 首页没有统一容器，只能按整页封面图抓 */
    IMAGE_STRIP("整页封面图"),

    /** 数据在内联脚本的 JSON 里，DOM 只有空容器 */
    SCRIPT_JSON("内联脚本"),

    /** 标题取自链接的 `title` 属性（可见文字被截断或掺了徽章） */
    LINK_TITLE("链接 title 属性"),

    /** 标题取自「名字里写着 title/name」的语义节点 */
    SEMANTIC_NODE("语义节点"),

    /** 标题只能取可见文字，可能掺更新时间之类的徽章 */
    VISIBLE_TEXT("可见文字"),

    /** 这一项在页面上找不到可靠承载方式 */
    NONE("未识别")
}

/**
 * 分阶段的通道探测结果。
 *
 * [strength] 是「有多大比例的条目支持这个结论」，用来说清可信度：
 * 一个只有 60% 条目带背景图的页面，和 100% 带背景图的页面，识别难度完全不同。
 */
data class SiteCapabilities(
    val list: Channel = Channel.NONE,
    val listStrength: Double = 0.0,
    val cover: Channel = Channel.NONE,
    val coverStrength: Double = 0.0,
    val title: Channel = Channel.NONE,
    val titleStrength: Double = 0.0,
    val chapters: Channel = Channel.NONE,
    val images: Channel = Channel.NONE
) {

    /**
     * 需要提醒用户的弱项。
     *
     * 只列「会影响使用体验、且用户能理解」的，不列技术细节：
     * 用户不需要知道什么叫 data-attribute，但需要知道书名可能带更新时间。
     */
    val weakPoints: List<String>
        get() = buildList {
            if (title == Channel.VISIBLE_TEXT) {
                add("书名只能从可见文字里取，部分条目可能带「3小时前」这类更新时间")
            }
            if (cover == Channel.INLINE_BG) {
                add("封面写在 CSS 背景图里，个别设备上可能出现封面空白")
            }
            if (cover == Channel.NONE) add("封面没有找到稳定的取值方式")
            if (list == Channel.IMAGE_STRIP) {
                add("首页没有统一的列表容器，按整页封面图抓取，条数可能包含推荐位")
            }
        }

    /** 一行给人看的摘要，进识别报告 */
    fun summary(): String = buildString {
        append("列表=${list.label}")
        append("，封面=${cover.label}")
        append("，标题=${title.label}")
        append("，章节=${chapters.label}")
        append("，正文图=${images.label}")
    }
}

/**
 * 能力探测。输入是各阶段已经拿到的**真实条目集合**，输出它们被哪种方式承载。
 *
 * 为什么不在全页面上扫：全页扫出来的比例会被页脚、侧栏、广告稀释，
 * 一个「30 个条目里 28 个用 data-src」的页面，全页统计可能只有 40%，
 * 于是错误地退回到 `img[src]`。按条目集合统计，问的才是「这个列表靠什么」。
 */
internal object CapabilityProbe {

    /** 懒加载属性的覆盖比例超过这个值，就认为站点走的是懒加载通道 */
    private const val STRONG = 0.6

    /** 探测用的懒加载属性清单。只影响「判断站点靠什么」，不影响实际取值 */
    private val LAZY_ATTRS = listOf(
        "data-original", "data-original-src", "data-src", "data-lazy-src", "data-lazyload",
        "data-lazyload-src", "data-lazy", "data-aload", "data-echo", "data-actualsrc",
        "data-ks-lazyload", "data-ll-src", "data-lazy-img", "data-srcset",
        "data-bg", "data-bg-src", "data-image", "data-image-src"
    )

    /**
     * 封面通道：优先看「能取到图的那个属性是什么」，而不是标签名。
     * 一个 `<a data-original>` 和一个 `<img src>` 的区别，比 `<a>` 和 `<img>` 的区别重要得多。
     */
    fun cover(members: List<Element>): Pair<Channel, Double> {
        if (members.isEmpty()) return Channel.NONE to 0.0
        // 按「胜出的属性」统计，而不是按元素标签统计：<picture> 结构里第一个子元素
        // 是 <source srcset>，按标签判断会把这类站点误判成没有封面
        val attrs = members.mapNotNull { ImageSignals.bestAttrIn(it) }
        if (attrs.isEmpty()) return Channel.NONE to 0.0
        val n = members.size.toDouble()

        val styleHits = attrs.count { it == "style" }
        val lazyHits = attrs.count { LAZY_ATTRS.contains(it) }
        val directHits = attrs.count { it == "src" || it.endsWith("srcset") }

        return when {
            // 背景图优先判断：这类元素往往同时有 src（占位图），不能先看 src
            styleHits / n >= STRONG -> Channel.INLINE_BG to styleHits / n
            lazyHits / n >= STRONG -> Channel.LAZY_ATTR to lazyHits / n
            directHits / n >= STRONG -> Channel.DOM to directHits / n
            else -> Channel.NONE to 0.0
        }
    }

    /**
     * 标题通道。
     *
     * 判据是「链接的 title 属性能不能独立给出一条像书名的文字」：
     * 能，说明站点本来就把完整书名放在属性里，可见文字只是展示用的；
     * 不能，就只能读可见文字，再靠 [TitleNormalizer] 剥徽章。
     */
    fun title(members: List<Element>): Pair<Channel, Double> {
        if (members.isEmpty()) return Channel.NONE to 0.0
        val n = members.size.toDouble()

        val cleanLinkTitles = members.count { m ->
            val link = if (m.tagName() == "a") m else m.selectFirst("a") ?: m
            val t = link.attr("title").trim()
            t.length in 2..90 && !TitleNormalizer.isGeneric(t) && t.count { it.isLetterOrDigit() } >= 2
        }
        if (cleanLinkTitles / n >= STRONG) return Channel.LINK_TITLE to cleanLinkTitles / n

        // 语义节点：名字里写着 title / name / book-name 的后代
        val semanticHits = members.count { m ->
            m.select("[class*=title], [class*=name], [itemprop=name], [class*=book]").isNotEmpty()
        }
        if (semanticHits / n >= STRONG) return Channel.SEMANTIC_NODE to semanticHits / n

        return Channel.VISIBLE_TEXT to 1.0
    }

    /** 正文图通道：`img` 上取得到地址，还是只能从背景图里捞 */
    fun images(members: List<Element>): Channel {
        if (members.isEmpty()) return Channel.NONE
        val n = members.size.toDouble()
        if (members.count { it.tagName() == "img" } / n >= STRONG) return Channel.DOM
        if (members.count { it.hasAttr("style") && it.attr("style").contains("url(", true) } / n >= STRONG) {
            return Channel.INLINE_BG
        }
        return if (members.any { it.tagName() == "img" }) Channel.DOM else Channel.NONE
    }
}
