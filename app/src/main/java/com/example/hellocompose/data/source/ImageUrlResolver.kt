package com.example.hellocompose.data.source

import com.example.hellocompose.data.remote.UrlResolver
import org.jsoup.nodes.Element

/**
 * 「这个节点上的图片地址到底是什么」的唯一答案。
 *
 * 抽成独立一层是因为这件事比看上去复杂：真实站点里同一张封面可能出现在
 * `src`、`data-src`、`data-original`、`data-lazy-src`、`srcset`、甚至 `style` 的
 * `background-image` 里，而 `src` 又常常是个 1×1 的占位 gif。只认 `src` 的后果是
 * 选择器明明命中了、抓回来的封面却是一片空白 —— 这类问题在配置里完全看不出来。
 *
 * 所以这里不做「取第一个非空属性」，而是给每个候选值打分再取最高：
 * 属性本身的可靠性（懒加载属性优于 `src`）＋ 地址形态（像不像一张内容图）－
 * 占位图/图标/广告的惩罚。识别阶段用它挑属性链，运行时用它兜底，
 * 两边共用一套语义，避免「识别时说有图、运行时取到空」。
 */
internal object ImageUrlResolver {

    /** 一个候选地址：从哪个属性来、多少分、是不是占位图 */
    data class Candidate(
        val raw: String,
        val attr: String,
        val score: Int,
        val placeholder: Boolean = false,
        val icon: Boolean = false
    ) {
        val usable: Boolean get() = raw.isNotBlank() && !placeholder && !icon
    }

    /**
     * 属性初始优先级。
     *
     * `data-original` 排在最前不是拍脑袋：用这套懒加载命名的站点（苹果CMS 系模板尤其多）
     * 里，`src` 基本固定是 `loading.gif`，真实地址只在 data-* 上。
     * `srcset` 排在 `src` 之前是因为它至少说明了真实图片存在，而 `src` 可能只是占位。
     *
     * 这张表按「属性名在真实站点里出现过几次」长期补充，新增的都往对应档位塞，
     * 不要随意调换已有顺序 —— 顺序决定 [attrChain] 写进配置的属性链，
     * 一改就会让已保存的站点配置行为发生变化。
     */
    private val ATTR_PRIORITY = listOf(
        "data-original" to 100,
        "data-original-src" to 99,
        "data-src" to 95,
        "data-lazy-src" to 92,
        "data-lazyload" to 91,
        "data-lazyload-src" to 90,
        // 腾讯系/新浪系模板的写法，和 data-lazyload 同档
        "data-lazy" to 89,
        "data-aload" to 89,
        "data-echo" to 88,
        "data-actualsrc" to 87,
        // 国内站点常见的两种冷门懒加载命名
        "data-ks-lazyload" to 86,
        "data-ll-src" to 86,
        "data-lazy-img" to 85,
        "srcset" to 85,
        "data-srcset" to 84,
        "data-url" to 83,
        "data-bg" to 82,
        "data-bg-src" to 82,
        "data-image" to 81,
        "data-image-src" to 81,
        "data-poster" to 80,
        "src" to 80,
        // 背景图写在 style 里，取值拿到的是整段 CSS，运行时由 [clean] 抽出 url()
        "style" to 75
    )

    private val ATTR_NAMES = ATTR_PRIORITY.map { it.first }

    /** 出现在这些目录下的地址，更可能是一张内容图而不是图标 */
    private val CONTENT_DIRS = Regex(
        """/(uploads?|upload|content|images?|img|pic|pics|photo|photos|media|manga|comic|book|files|res)/""",
        RegexOption.IGNORE_CASE
    )

    /** 文件名里带连续数字（001.jpg、20250101_01.webp）：正文图/封面的典型命名 */
    private val NUMBERED_NAME = Regex("""\d{2,}""")

    private val IMAGE_EXT = Regex("""\.(jpe?g|png|webp|avif|gif|bmp)(\?|#|$)""", RegexOption.IGNORE_CASE)

    /**
     * 占位图：真实地址在别的属性上，这个值本身不能用。
     *
     * 懒加载站点的 `src` 几乎都是这一类：`data:image/gif;base64,...`、
     * `loading.gif`、`blank.png`、`spacer.gif`、`1x1.png`，或者干脆是同目录下
     * 一张叫 `default.jpg` 的灰图。它们都能正常解码，所以**不能靠能不能下载来判断**，
     * 只能在地址形态上认出来。
     */
    private val PLACEHOLDER = Regex(
        "(data:image|base64,|about:blank|blank\\.|spacer\\.|placeholder|loading\\.|load\\.gif" +
            "|lazyload|lazy\\.(gif|png)|transparent\\.|pixel\\.|\\b1x1\\b|1px\\.|px\\.gif" +
            "|(^|/)1\\.(gif|png)|grey\\.gif|gray\\.gif|nopic|no-image|no_image|noimg" +
            "|none\\.gif|default\\.(gif|png|jpg|jpeg)|tmp\\.gif|spinner|preloader|hold\\.gif)",
        RegexOption.IGNORE_CASE
    )

    /** 图标/装饰：是图片，但不是作品封面 */
    private val ICON = Regex(
        "(logo|icon|avatar|qrcode|qr-code|advert|banner|emoticon|smilies|weixin|sprite|btn_|button" +
            "|arrow|pixel|badge|share|collect|star|loading)",
        RegexOption.IGNORE_CASE
    )

    private val CSS_URL = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")

    // ==================== 单个值 ====================

    fun isPlaceholder(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (v.length < 6) return true
        return PLACEHOLDER.containsMatchIn(v)
    }

    fun isIcon(value: String): Boolean = ICON.containsMatchIn(value)

    /** 地址形态是否像一张图（不判断占位，占位由 [isPlaceholder] 单独判） */
    fun looksLikeImage(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.length < 6) return false
        if (v.startsWith("data:")) return false
        if (PLACEHOLDER.containsMatchIn(v)) return false
        return v.startsWith("http") || v.startsWith("//") || v.contains('/')
    }

    /**
     * 给一个「属性 + 原始值」打分。
     *
     * 分值只用于同一节点内部比较，绝对值没有意义，所以直接沿用属性优先级做基数。
     */
    fun score(attr: String, raw: String): Candidate {
        val value = raw.trim()
        val base = ATTR_PRIORITY.firstOrNull { it.first == attr }?.second ?: 70
        if (value.isEmpty()) return Candidate(value, attr, 0)

        // srcset 里可能塞了好几档，style 里是整段 CSS：都先拆出一个真实地址再打分，
        // 否则 best() 交给上层的是「一 CSS 语句」，还得每个调用方各自再清一遍
        val single = when {
            attr.endsWith("srcset") -> fromSrcset(value) ?: value
            attr == "style" -> fromStyle(value) ?: value
            else -> value
        }
        if (isPlaceholder(single)) return Candidate(single, attr, 0, placeholder = true)

        var score = base
        val icon = isIcon(single) || single.substringBefore('?').endsWith(".svg", ignoreCase = true)
        if (icon) score -= 30
        if (CONTENT_DIRS.containsMatchIn(single)) score += 10
        if (NUMBERED_NAME.containsMatchIn(single.substringAfterLast('/'))) score += 6
        if (IMAGE_EXT.containsMatchIn(single)) score += 4
        // 绝对地址比相对地址更可能是「真实图片」：占位图几乎都是相对路径或 data:
        if (single.startsWith("http") || single.startsWith("//")) score += 2
        return Candidate(single, attr, score, icon = icon)
    }

    /**
     * `srcset="a.jpg 1x, b.jpg 2x"` 取最大的一档。
     *
     * 不取第一个：很多站在 srcset 里放「小图预览 + 原图」两档，第一档往往是压缩过的预览图。
     */
    fun fromSrcset(value: String): String? {
        val v = value.trim()
        if (v.isEmpty() || v.contains("data:")) return null
        val entries = v.split(',')
            .mapNotNull { part ->
                val pieces = part.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
                val url = pieces.firstOrNull() ?: return@mapNotNull null
                if (url.isEmpty()) return@mapNotNull null
                val weight = when {
                    pieces.size < 2 -> 1.0
                    pieces[1].endsWith("w", ignoreCase = true) ->
                        pieces[1].dropLast(1).toDoubleOrNull() ?: 1.0
                    pieces[1].endsWith("x", ignoreCase = true) ->
                        (pieces[1].dropLast(1).toDoubleOrNull() ?: 1.0) * 1000
                    else -> 1.0
                }
                url to weight
            }
        return entries.maxByOrNull { it.second }?.first
    }

    /** 从 `style="background-image:url(...)"` 里抽地址，多个时取第一个非占位 */
    fun fromStyle(value: String): String? =
        CSS_URL.findAll(value)
            .map { it.groupValues[1].trim() }
            .firstOrNull { it.isNotEmpty() && !isPlaceholder(it) }

    /**
     * 把「取到的原始值」清洗成可用地址。
     *
     * 两种特殊值必须处理，否则会一路传到下载器：
     * - `srcset` 的多档描述（`a.jpg 1x, b.jpg 2x`）
     * - `style` 里的 `url(...)`
     */
    fun clean(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        if (v.contains("url(")) return fromStyle(v) ?: ""
        if (isSrcset(v)) return fromSrcset(v) ?: ""
        return v
    }

    /** 形如 `a.jpg 1x, b.jpg 2x` / `a.jpg 640w, b.jpg 1280w` */
    private fun isSrcset(value: String): Boolean =
        value.contains(',') && Regex("""\s\d+(\.\d+)?[wx]\b""").containsMatchIn(value)

    // ==================== 元素 ====================

    /**
     * 元素自身能提供的全部图片候选，按分数从高到低。
     *
     * [includeSources] 为真时把内部 `<source>` 也算进来（`<picture>` 结构）。
     */
    fun candidates(el: Element, includeSources: Boolean = true): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (attr in ATTR_NAMES) {
            if (!el.hasAttr(attr)) continue
            out += score(attr, el.attr(attr))
        }
        // 背景图卡片：地址可能写在 style 上，也可能写在下面这句内联脚本里
        if (!el.hasAttr("style")) {
            val inline = el.attr("data-background").ifBlank { el.attr("data-bg-image") }
            if (inline.isNotBlank()) out += score("style", inline)
        }
        if (includeSources) {
            for (source in el.select("source[srcset], source[data-srcset]")) {
                for (attr in listOf("srcset", "data-srcset")) {
                    if (source.hasAttr(attr)) out += score(attr, source.attr(attr))
                }
            }
        }

        // 声明尺寸极小的 `src` 基本是占位（1×1 埋点、48×48 的 loading 图标），
        // 但只有在同元素上还有别的候选时才降权：正文图本身可能真的只有 40 宽，
        // 无差别降权会把它误杀
        if (out.any { it.usable && it.attr != "src" } && declaredSizeIsTiny(el)) {
            out.replaceAll { c -> if (c.attr == "src") c.copy(score = c.score - 25) else c }
        }
        return out.filter { it.raw.isNotBlank() }.sortedByDescending { it.score }
    }

    /** 元素上声明的宽高都很小（≤48），且形状接近正方形 —— 更可能是图标而不是封面 */
    private fun declaredSizeIsTiny(el: Element): Boolean {
        val w = el.attr("width").toIntOrNull() ?: return false
        val h = el.attr("height").toIntOrNull() ?: return false
        return w <= 48 && h <= 48
    }

    /** 这个元素上最可信的一张图；全是占位/图标时返回 null */
    fun best(el: Element, includeSources: Boolean = true): Candidate? =
        candidates(el, includeSources).firstOrNull { it.usable }

    fun carries(el: Element): Boolean = best(el) != null

    /** 元素自身或它内部第一个能取到图片地址的元素 */
    fun carrierIn(item: Element, selector: String): Element? =
        if (carries(item)) item else item.selectFirst(selector)?.takeIf { carries(it) }

    /**
     * 为一批「同构条目」生成属性取值链（`data-original|data-src|src`）。
     *
     * 三条规则：
     * 1. 只保留「足够多条目上都放得出图」的属性 —— 某一条目上的偶然属性不能当通用属性；
     * 2. 按属性可靠性排序，而不是按出现率。`src` 的覆盖率经常是 100%（因为它放的是占位图），
     *    按出现率排会把占位图顶到最前面；
     * 3. 覆盖率门槛随条目数放宽到 30%。同一个列表里混着三种懒加载写法的站点很常见
     *    （老页面用 data-original、新页面用 srcset），门槛卡在 50% 会把两种写法都排除掉，
     *    最后只剩 `src`，反而全取到占位图。
     */
    fun attrChain(carriers: List<Element>): String {
        if (carriers.isEmpty()) return "src"
        val threshold = if (carriers.size <= 2) 1 else maxOf(2, (carriers.size * 0.3).toInt())

        val present = ATTR_PRIORITY
            .filter { (attr, _) -> carriers.count { el -> usableAttr(el, attr) } >= threshold }
            .map { it.first }

        if (present.isEmpty()) return "src"
        return (present + "src").distinct().joinToString("|")
    }

    private fun usableAttr(el: Element, attr: String): Boolean {
        if (!el.hasAttr(attr)) return false
        val candidate = score(attr, el.attr(attr))
        return candidate.usable
    }

    // ==================== 运行时兜底 ====================

    /**
     * 按规则取图，并把「取到占位图」这种情况兜回来。
     *
     * 识别阶段已经尽量把属性链排对了，但站点内容五花八门：同一条目里可能
     * 只有一部分图换了懒加载属性。这时如果老老实实返回 `loading.gif`，
     * 用户看到的就是一张空白封面 —— 不如回头在这个元素上再挑一次最可信的地址。
     */
    fun imageUrl(el: Element, attrSpec: String, baseUrl: String): String {
        val raw = clean(el.readByRule(attrSpec))
        if (raw.isNotBlank() && looksLikeImage(raw)) return UrlResolver.resolve(raw, baseUrl)

        // 取到的是占位图或空值时，回头在这个元素上重新挑一次最可信的地址
        val fallback = best(el)?.raw ?: return ""
        val cleaned = clean(fallback)
        return if (cleaned.isBlank()) "" else UrlResolver.resolve(cleaned, baseUrl)
    }

    // ==================== 地址归一化 ====================

    /**
     * 归一化：给「同一个地址的不同写法」一个统一形态，用于去重和缓存 key。
     *
     * 只做不影响请求结果的变形 —— 去掉 fragment、小写 host、丢掉时间戳类参数。
     * 绝不排序或丢弃其它参数：很多站的图片要靠 `?w=800&sign=...` 才能取到，
     * 改了就 404。所以归一化结果只用于比较，不用于下载。
     */
    fun normalize(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val withoutFragment = trimmed.substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', "")
        if (query.isEmpty()) return base

        val kept = query.split('&')
            .filter { it.isNotBlank() }
            .filterNot { param ->
                // `_t` / `__v` 这类前缀下划线是缓存击穿的常见写法，先削掉再比对
                val key = param.substringBefore('=').lowercase().trimStart('_')
                key in VOLATILE_PARAMS
            }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    /** 缓存击穿类参数：每次渲染都变，但和图片内容无关 */
    private val VOLATILE_PARAMS = setOf(
        "t", "ts", "time", "timestamp", "v", "ver", "version", "rnd", "random", "rand",
        "cache", "cb", "cachebust", "_"
    )
}
