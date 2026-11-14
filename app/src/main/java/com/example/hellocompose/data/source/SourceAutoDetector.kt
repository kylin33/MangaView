package com.example.hellocompose.data.source

import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.remote.PageParser
import com.example.hellocompose.data.remote.UrlResolver
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL

/**
 * 站点结构识别：给定一份 HTML，推出可用的抓取规则。
 *
 * 核心循环是「候选生成 → 用真实解析器打分」：
 * 1. 按 DOM 里重复出现的子元素结构生成候选选择器（列表、条目天然长这样）；
 * 2. 每个候选都用 [PageParser] 真的解析一遍，按解析出的条数和字段填满率打分。
 *
 * 为什么必须用 PageParser 打分而不是靠标签名猜：等到运行时才发现选择器写错，
 * 用户完全无法判断错在哪。这里打分的就是运行时那条代码路径，所以「识别通过」
 * 和「实际能抓」是同一件事。
 */
object SourceAutoDetector {

    // ==================== 结果类型 ====================

    data class HomeGuess(
        val rule: ListRule,
        val score: Double,
        val items: List<Manga>
    )

    data class ChapterGuess(
        val rule: DetailRule,
        val score: Double,
        val items: List<Chapter>,
        /** 标题形如「第 12 话」的比例。用来把章节列表和「相关推荐」区分开 */
        val chapterLikeRatio: Double
    )

    data class ReaderGuess(
        val rule: ReaderRule,
        val score: Double,
        val images: List<String>
    )

    // ==================== 首页列表 ====================

    fun detectHome(doc: Document, baseUrl: String, minSize: Int = 4): List<HomeGuess> {
        val seen = HashSet<String>()
        val prelim = ArrayList<Dom.Block>()

        for (block in Dom.blocks(doc, minSize = minSize)) {
            if (!seen.add(block.itemSelector)) continue
            val matched = doc.select(block.itemSelector)
            if (matched.size < minSize) continue
            // 首页是封面网格，条目里没有图的基本是导航/文字列表，先筛掉。
            // 判断"有图"不能只认 <img>：大量站点（苹果CMS 系模板尤其常见）把封面写成
            // <a data-original="..."></a>，靠 CSS background-image 显示，页面里根本没有 img 标签。
            val imgRatio = matched.count { imageCarrierIn(it) != null }.toDouble() / matched.size
            if (imgRatio < 0.5) continue
            prelim += block
        }

        val blockGuesses = prelim
            .sortedByDescending { doc.select(it.itemSelector).size }
            .take(12)
            .mapNotNull { block ->
                val matched = doc.select(block.itemSelector)
                val cover = pickCoverRule(matched) ?: return@mapNotNull null
                val link = pickLinkRule(matched, baseUrl) ?: return@mapNotNull null
                val title = pickTitleRule(matched)

                val rule = ListRule(
                    path = "/",
                    item = block.itemSelector,
                    link = link,
                    cover = cover,
                    title = title
                )
                val parsed = PageParser.list(doc, rule, baseUrl)
                if (parsed.size < minSize) return@mapNotNull null

                HomeGuess(rule, scoreHome(parsed, matched.size, block.container), parsed)
            }

        return (blockGuesses + detectCoverLists(doc, baseUrl, minSize))
            .sortedByDescending { it.score }
            .distinctBy { it.rule.item }
            .take(8)
    }

    /**
     * 另一种首页形态：整页没有统一的列表容器，而是十几个小板块各自成列表。
     * 这时「选中某一个板块」只能拿到一小部分，反倒是「所有封面图」最接近用户想看的首页。
     * 手写配置里那句 `img[data-original]` 就是这个思路。
     */
    private fun detectCoverLists(doc: Document, baseUrl: String, minSize: Int): List<HomeGuess> {
        val images = contentImages(doc)
        if (images.size < minSize) return emptyList()

        val selectors = LinkedHashSet<String>()
        selectors += "img[data-original]"
        selectors += "img[data-src]"
        // 背景图卡片形态：封面地址挂在 <a>/<div> 上而不是 <img> 上
        selectors += "a[data-original]"
        selectors += "[data-original]"

        images.flatMap { it.classNames() }
            .filter { Dom.isSafeClass(it) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .filter { it.value >= 4 }
            .forEach { selectors += "img.${it.key}" }

        val out = ArrayList<HomeGuess>()
        for (selector in selectors) {
            val matched = doc.select(selector)
            if (matched.size < 4) continue

            val rule = ListRule(
                path = "/",
                item = selector,
                // 封面图外面通常裹着指向详情页的 <a>
                link = FieldRule(select = "^", attr = "href"),
                cover = FieldRule(select = "", attr = imageAttrOrder(matched)),
                title = pickTitleRule(matched)
            )
            val parsed = PageParser.list(doc, rule, baseUrl)
            if (parsed.size < minSize) continue

            val container = matched.firstOrNull()?.parent() ?: doc.body()
            out += HomeGuess(rule, scoreHome(parsed, matched.size, container), parsed)
        }
        return out
    }

    private fun scoreHome(parsed: List<Manga>, matchedCount: Int, container: Element): Double {
        val n = parsed.size
        val named = parsed.count { it.title != PageParser.UNTITLED }
        var s = n.coerceAtMost(60) * 0.9
        s += named.toDouble() / n * 14.0
        // 命中数远大于解析结果，说明选择器太宽，把半页链接都框进来了
        if (matchedCount > n * 3 + 12) s -= 12.0
        if (Dom.isBoilerplate(container)) s -= 8.0
        return s
    }

    // ==================== 章节列表 ====================

    fun detectChapters(doc: Document, baseUrl: String): List<ChapterGuess> {
        val seen = HashSet<String>()
        val prelim = ArrayList<Triple<Dom.Block, Int, Double>>()

        for (block in Dom.blocks(doc, minSize = 2)) {
            if (!seen.add(block.itemSelector)) continue
            val matched = doc.select(block.itemSelector)
            if (matched.isEmpty()) continue

            val withHref = matched.count {
                (it.tagName() == "a" && it.hasAttr("href")) || it.selectFirst("a[href]") != null
            }.toDouble() / matched.size
            if (withHref < 0.8) continue

            val likeRatio = matched.count { isChapterLike(it.text().trim()) }.toDouble() / matched.size
            prelim += Triple(block, matched.size, likeRatio)
        }

        return prelim
            .sortedWith(
                compareByDescending<Triple<Dom.Block, Int, Double>> { it.third }
                    .thenByDescending { it.second }
            )
            .take(12)
            .mapNotNull { (block, _, _) ->
                val matched = doc.select(block.itemSelector)
                val url = pickChapterUrlRule(matched, baseUrl) ?: return@mapNotNull null
                val title = pickChapterTitleRule(matched)

                val rule = DetailRule(item = block.itemSelector, url = url, title = title)
                val parsed = PageParser.chapters(doc, rule, baseUrl)
                if (parsed.isEmpty()) return@mapNotNull null
                // 作者/题材/地区这些链接也长成「一堆同类 a 标签」，不筛掉就会被当成章节
                if (!isChapterLinkSet(parsed)) return@mapNotNull null

                val ratio = parsed.count { isChapterLike(it.title) }.toDouble() / parsed.size
                val score = scoreChapters(parsed, matched.size, block.container, ratio)
                ChapterGuess(rule, score, parsed, ratio)
            }
            .sortedByDescending { it.score }
            .take(8)
    }

    private fun scoreChapters(
        parsed: List<Chapter>,
        matchedCount: Int,
        container: Element,
        ratio: Double
    ): Double {
        val n = parsed.size
        var s = n.coerceAtMost(200) * 0.4
        s += ratio * 18.0
        s += parsed.map { it.url }.distinct().size.toDouble() / n * 4.0
        if (matchedCount > n * 2 + 8) s -= 8.0
        if (Dom.isBoilerplate(container)) s -= 10.0
        return s
    }

    // ==================== 章节图片 ====================

    fun detectReaders(doc: Document, baseUrl: String): List<ReaderGuess> {
        val images = contentImages(doc)
        if (images.isEmpty()) return emptyList()

        // 只按「父元素的标签 + class」分组。
        // 不能把父元素的完整选择器（可能带唯一 id）掺进分组键：
        // 正文图常常是 #pic_1 / #pic_2 / #pic_3 这样每张一个容器，掺进去就变成一组一张。
        val groups = images.groupBy { img ->
            img.parent()?.let { Dom.signature(it) } ?: "?"
        }

        val seen = HashSet<String>()
        val out = ArrayList<ReaderGuess>()

        for ((_, members) in groups) {
            val parent = members.first().parent() ?: continue
            if (parent.tagName() in setOf("body", "html")) continue
            val parentSelector = Dom.classSelector(parent)
                ?: Dom.containerSelector(parent)
                ?: continue
            val attrOrder = imageAttrOrder(members)

            for (itemSelector in listOf("$parentSelector > img", "$parentSelector img")) {
                if (!seen.add(itemSelector)) continue
                val rule = ReaderRule(
                    item = itemSelector,
                    image = FieldRule(select = "", attr = attrOrder),
                    referer = "{pageUrl}"
                )
                val parsed = PageParser.images(doc, rule, baseUrl)
                if (parsed.isEmpty()) continue
                out += ReaderGuess(rule, scoreImages(parsed, itemSelector), parsed)
            }
        }

        return out.sortedByDescending { it.score }.take(8)
    }

    /** 推荐位/排行榜的容器名特征，用来把「封面墙」从正文图里摘出去 */
    private val COVER_HINTS = Regex(
        "(cover|recommend|rank|guess|slide|daily|relate)",
        RegexOption.IGNORE_CASE
    )

    private fun scoreImages(urls: List<String>, itemSelector: String): Double {
        val n = urls.size
        val host = hostOf(urls.first())
        val sameHost = if (host == null) {
            1.0
        } else {
            urls.count { hostOf(it) == host }.toDouble() / n
        }
        // 同一章的图通常在同一目录，目录越集中越像是正文图
        val dominantDir = urls
            .map { it.substringBeforeLast('/', "") }
            .groupingBy { it }
            .eachCount()
            .values
            .maxOrNull()
            ?.toDouble()
            ?.div(n) ?: 0.0

        var s = n.coerceAtMost(40) * 1.0
        s += sameHost * 6.0
        s += dominantDir * 8.0

        // 关键判据：一页里的图片文件名如果大量重复（全是 xxx/cover.webp），
        // 那是封面墙（推荐位），不是正文。正文图名通常是 001.jpg、202507...jpg 这类互不相同的。
        val distinctBaseNames = urls
            .map { it.substringAfterLast('/').substringBefore('?') }
            .distinct()
            .size
            .toDouble() / n
        if (distinctBaseNames < 0.8) s -= 14.0

        if (COVER_HINTS.containsMatchIn(itemSelector)) s -= 8.0
        if (n == 1) s -= 3.0
        return s
    }

    // ==================== 字段规则挑选 ====================

    private fun pickLinkRule(items: List<Element>, baseUrl: String): FieldRule? {
        val candidates = ArrayList<FieldRule>()
        if (items.count { it.tagName() == "a" && it.hasAttr("href") } >= items.size * 0.8) {
            candidates += FieldRule(select = "", attr = "href")
        }
        candidates += FieldRule(select = "a", attr = "href")
        if (items.count { it.tagName() == "img" } >= items.size * 0.8) {
            // 条目本身就是封面图，靠 closest("a") 往上找链接
            candidates += FieldRule(select = "^", attr = "href")
        }
        candidates += FieldRule(select = "a[href]", attr = "href")

        var best: FieldRule? = null
        var bestQuality = 0.0
        for (candidate in candidates) {
            val q = linkQuality(items, candidate, baseUrl)
            if (q > bestQuality) {
                bestQuality = q
                best = candidate
            }
        }
        return best?.takeIf { bestQuality >= 0.6 }
    }

    private fun linkQuality(items: List<Element>, rule: FieldRule, baseUrl: String): Double {
        if (items.isEmpty()) return 0.0
        var ok = 0
        for (el in items) {
            val raw = el.pickByRule(rule).readByRule(rule.attr)
            if (raw.isBlank()) continue
            // 只要求是绝对地址。不能强制同域：不少站点首页在 .top、详情页在 .cc（镜像/分站），
            // 一旦要求同域，整站会被判成「没有列表结构」。
            if (UrlResolver.resolve(raw, baseUrl).startsWith("http")) ok++
        }
        return ok.toDouble() / items.size
    }

    /**
     * 挑封面取值规则。
     *
     * 封面有两种主流写法，两种都要认：
     * 1. `<img src|data-src>` —— 最常见；
     * 2. `<a data-original="..."><span class="play"></span></a>` —— 苹果CMS 系模板，
     *    图片靠 CSS 背景显示，DOM 里没有 img 元素。只认 img 会把整站判成「没有列表」。
     */
    private fun pickCoverRule(items: List<Element>): FieldRule? {
        val selfMode = items.count { it.tagName() == "img" } >= items.size * 0.8
        if (selfMode) {
            val rule = FieldRule(select = "", attr = imageAttrOrder(items))
            return rule.takeIf { coverQuality(items, rule) >= 0.5 }
        }

        val images = items.mapNotNull { it.selectFirst("img") }
        val carriers = items.mapNotNull { imageCarrierIn(it) }

        val candidates = LinkedHashSet<FieldRule>()
        if (images.size >= items.size * 0.5) {
            candidates += FieldRule(select = "img", attr = imageAttrOrder(images))
        }
        if (carriers.size >= items.size * 0.5) {
            val attrOrder = imageAttrOrder(carriers)
            // 优先用「标签 + class」这种稳定选择器（如 a.vodlist_thumb），
            // 它比属性选择器更能避开同一条目里的其它链接
            carriers.groupingBy { Dom.signature(it) }
                .eachCount()
                .filterKeys { it.contains('.') }
                .maxByOrNull { it.value }
                ?.key
                ?.let { candidates += FieldRule(select = it, attr = attrOrder) }
            candidates += FieldRule(select = CARRIER_SELECTOR, attr = attrOrder)
        }
        if (candidates.isEmpty()) return null

        return candidates
            .maxByOrNull { coverQuality(items, it) }
            ?.takeIf { coverQuality(items, it) >= 0.5 }
    }

    private fun coverQuality(items: List<Element>, rule: FieldRule): Double {
        if (items.isEmpty()) return 0.0
        var ok = 0
        for (el in items) {
            val raw = el.pickByRule(rule).readByRule(rule.attr)
            if (looksLikeImage(raw)) ok++
        }
        return ok.toDouble() / items.size
    }

    private fun pickTitleRule(items: List<Element>): FieldRule {
        val candidates = listOf(
            FieldRule(select = "img", attr = "alt"),
            FieldRule(select = "", attr = "alt"),
            FieldRule(select = "a", attr = "title"),
            FieldRule(select = "h3", attr = "text"),
            FieldRule(select = "h2", attr = "text"),
            FieldRule(select = "a", attr = "text"),
            // 封面 <a> 里没有文字时，标题往往在同一层级的第二个 <a> 上
            FieldRule(select = "a:nth-of-type(2)", attr = "text"),
            FieldRule(select = "", attr = "title"),
            FieldRule(select = "", attr = "text")
        )

        var best = FieldRule(select = "", attr = "text")
        var bestQuality = -1.0
        for (candidate in candidates) {
            val q = titleQuality(items, candidate)
            if (q > bestQuality) {
                bestQuality = q
                best = candidate
            }
        }
        return best
    }

    /** 标题里掺了这些字样，说明取到的是作者/简介行，不是书名 */
    private val TITLE_NOISE = Regex("""(作者\s*[:：]|简介\s*[:：]|状态\s*[:：]|标签\s*[:：])""")

    private fun titleQuality(items: List<Element>, rule: FieldRule): Double {
        if (items.isEmpty()) return 0.0
        var good = 0
        for (el in items) {
            val text = el.pickByRule(rule).readByRule(rule.attr)
            if (text.length in 2..90 && text.count { it.isLetterOrDigit() } >= 2) {
                // 「书名 作者:XXX」这种整块文本不是书名，拿去做搜索词也搜不到
                if (!TITLE_NOISE.containsMatchIn(text)) good++
            }
        }
        return good.toDouble() / items.size
    }

    private fun pickChapterUrlRule(items: List<Element>, baseUrl: String): FieldRule? {
        val candidates = ArrayList<FieldRule>()
        if (items.count { it.tagName() == "a" && it.hasAttr("href") } >= items.size * 0.8) {
            candidates += FieldRule(select = "", attr = "href")
        }
        candidates += FieldRule(select = "a", attr = "href")
        candidates += FieldRule(select = "^", attr = "href")

        var best: FieldRule? = null
        var bestQuality = 0.0
        for (candidate in candidates) {
            val q = linkQuality(items, candidate, baseUrl)
            if (q > bestQuality) {
                bestQuality = q
                best = candidate
            }
        }
        return best?.takeIf { bestQuality >= 0.6 }
    }

    private fun pickChapterTitleRule(items: List<Element>): FieldRule {
        val candidates = listOf(
            FieldRule(select = "", attr = "text"),
            FieldRule(select = "a", attr = "text"),
            FieldRule(select = "a", attr = "title"),
            FieldRule(select = "span", attr = "text"),
            FieldRule(select = "", attr = "title")
        )

        var best = FieldRule(select = "", attr = "text")
        var bestQuality = -1.0
        for (candidate in candidates) {
            val texts = items.take(30).map { it.pickByRule(candidate).readByRule(candidate.attr) }
            if (texts.isEmpty()) continue

            val filled = texts.count { it.length in 1..120 }.toDouble() / texts.size
            val chapterish = texts.count { isChapterLike(it) }.toDouble() / texts.size
            val avgLen = texts.map { it.length }.average().let { if (it.isNaN()) 0.0 else it }

            // 长度只做弱惩罚。很多站把书名缀在 title 属性里（「书名-第3话-…」），
            // 同一个章节 a.text 才是干净的章节名；两者填满率一样时必须选短的，
            // 否则界面上每一条章节都会重复显示书名。
            val q = filled * 10.0 + chapterish * 20.0 - avgLen * 0.02
            if (q > bestQuality) {
                bestQuality = q
                best = candidate
            }
        }
        return best
    }

    // ==================== 图片属性 ====================

    private val IMAGE_ATTRS = listOf(
        "data-original", "data-src", "data-lazy-src", "data-echo", "data-actualsrc", "data-bg", "src"
    )

    /**
     * 能承载图片地址的元素。不限于 img：背景图卡片把地址写在 a/div 的 data-* 上。
     * 顺序保持文档顺序，`selectFirst` 会取页面上最先出现的那一个。
     */
    private const val CARRIER_SELECTOR =
        "img, [data-original], [data-src], [data-lazy-src], [data-echo], [data-actualsrc], [data-bg]"

    /** 元素自身携带的、看起来像图片地址的值 */
    private fun imageValues(el: Element): List<String> =
        IMAGE_ATTRS.map { el.attr(it) }.filter { looksLikeImage(it) }

    internal fun carriesImage(el: Element): Boolean = imageValues(el).isNotEmpty()

    /** 条目自身或它内部第一个能取到图片地址的元素 */
    private fun imageCarrierIn(item: Element): Element? =
        if (carriesImage(item)) item else item.selectFirst(CARRIER_SELECTOR)
            ?.takeIf { carriesImage(it) }

    /**
     * 拼属性候选链，顺序按懒加载的常见优先级。
     * `src` 永远兜底放在最后：懒加载站点的 src 常常是一张占位 gif。
     */
    private fun imageAttrOrder(images: List<Element>): String {
        val threshold = maxOf(1, (images.size * 0.5).toInt())
        val present = IMAGE_ATTRS.filter { attr ->
            images.count { looksLikeImage(it.attr(attr)) } >= threshold
        }
        val order = present.ifEmpty { listOf("src") }
        return if ("src" in order) order.joinToString("|") else (order + "src").joinToString("|")
    }

    private val PLACEHOLDER_HINTS = listOf(
        "data:image", "blank.", "spacer.", "placeholder", "loading.", "pixel.", "transparent.", "grey.gif"
    )

    private val ICON_HINTS = Regex(
        "(logo|icon|avatar|qrcode|qr-code|advert|banner|emoticon|smilies|weixin|sprite|btn_|button|arrow|pixel)",
        RegexOption.IGNORE_CASE
    )

    internal fun looksLikeImage(value: String): Boolean {
        if (value.isBlank()) return false
        val s = value.trim().lowercase()
        if (s.startsWith("data:")) return false
        if (s.length < 6) return false
        if (PLACEHOLDER_HINTS.any { s.contains(it) }) return false
        return s.startsWith("http") || s.startsWith("//") || s.contains('/')
    }

    private fun contentImages(doc: Document): List<Element> {
        val chosen = ArrayList<Element>()
        val chosenSet = HashSet<Element>()
        for (el in doc.select(CARRIER_SELECTOR)) {
            // 外层元素已经算过图片时，跳过它内部重复命中的元素（背景图卡片的常见嵌套）
            if (el.parents().any { it in chosenSet }) continue

            val values = imageValues(el)
            if (values.isEmpty()) continue
            // 全都是 icon/logo/广告之类的就别当正文图
            if (values.none { !ICON_HINTS.containsMatchIn(it) }) continue

            val w = el.attr("width").toIntOrNull() ?: 0
            val h = el.attr("height").toIntOrNull() ?: 0
            if (w in 1..60 && h in 1..60) continue

            chosen += el
            chosenSet += el
        }
        return chosen
    }

    // ==================== 章节标题判定 ====================

    private val CHAPTER_PATTERNS = listOf(
        Regex("""第\s*[0-9零一二三四五六七八九十百千]+\s*[话話回章節节集卷期]"""),
        Regex("""^[0-9]{1,4}(\.[0-9]{1,3})?(\s|$|[话話回章集卷])"""),
        Regex("""(?i)\b(chapter|episode|ep|ch)\s*[.\-]?\s*\d+"""),
        Regex("""^\s*[0-9]{1,4}\s*$"""),
        Regex("""(?i)^\s*(vol|volume)\b""")
    )

    private val NON_CHAPTER = Regex("""(上一[章话頁页]|下一[章话頁页]|目录|目錄|首页|首頁|尾页|排序|展開|展开)""")

    fun isChapterLike(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return false
        if (NON_CHAPTER.containsMatchIn(t)) return false
        return CHAPTER_PATTERNS.any { it.containsMatchIn(t) }
    }

    /**
     * 判断一组链接是不是「真的章节列表」。
     *
     * 只看标题像不像「第 N 话」不够。详情页里还有作者、题材、地区、状态这些链接，
     * 它们的标题同样是干净的短文本、数量也够，非常容易被当成章节抓走 ——
     * 抓出来是「FOE,JINGG / 暧昧 / 少年」这种，用户还得自己发现不对。
     *
     * 真正的章节链接有个很强的结构性特征：路径高度同构。
     * 比如 `/view/9174/211921`、`/view/9174/211922` 第一段永远是 view；
     * 而作者/题材/地区的链接各走各的路径（/author/…、/ticai/…、/zone/…），几乎不会重合。
     * 所以两条判据取或：标题像章节，或者路径同构。
     */
    fun isChapterLinkSet(items: List<Chapter>): Boolean {
        if (items.size < 2) return false

        val likeRatio = items.count { isChapterLike(it.title) }.toDouble() / items.size
        if (likeRatio >= 0.5) return true

        // 标题完全不像章节时，只接受「数量够多 + 路径高度同构」的组合
        if (items.size < 3) return false
        val dominant = items.groupingBy { pathRootOf(it.url) }.eachCount().values.maxOrNull() ?: 0
        return dominant.toDouble() / items.size >= 0.8
    }

    /** URL 的第一段路径，用来识别「同一类链接」 */
    private fun pathRootOf(url: String): String = runCatching {
        URL(url).path.orEmpty().trim('/').substringBefore('/')
    }.getOrDefault("")

    // ==================== 内联脚本数据 ====================

    /** 脚本里找到的一段 JSON 数组 */
    internal data class ScriptArray(
        /** 变量名或函数名，写成配置的 pattern */
        val name: String,
        /** 定位数组的点路径，如 "0.data" */
        val listPath: String,
        val objects: List<JSONObject>
    )

    /** 用脚本规则解析出来的章节候选 */
    data class ScriptGuess(
        val rule: ScriptRule,
        val items: List<Chapter>
    )

    /**
     * 详情页 DOM 里抓不到章节时，去内联脚本里找。
     *
     * 有的站把章节写成 `initIntroData([{title:"连载",data:[{…}]}])` 这样的脚本变量，
     * 页面上只有一个空的 `<div id="list">`，CSS 选择器一条都命中不了。
     * 数据和浏览器里看到的完全一样，只是不在 DOM 里。
     */
    fun detectScriptChapters(doc: Document, baseUrl: String, minSize: Int = 2): List<ScriptGuess> {
        val out = ArrayList<ScriptGuess>()
        for (array in scriptArrays(doc, minSize)) {
            val titleField = pickTextField(array.objects) ?: continue
            val likeRatio = array.objects
                .count { isChapterLike(it.optString(titleField)) }
                .toDouble() / array.objects.size
            if (likeRatio < 0.5) continue

            for (rule in urlCandidates(array, titleField)) {
                val items = ScriptData.chapters(doc, rule, baseUrl)
                if (items.size >= 2) out += ScriptGuess(rule, items)
            }
        }
        return out.distinctBy { it.rule.toString() }
    }

    /**
     * 搜索结果页同理。
     * 这里不要求标题像章节 —— 搜索结果是书名，靠「有条目 + 有地址」即可。
     * [minSize] 默认为 2，搜索场景可以降到 1：精确搜一本冷门书时结果真的只有一条。
     */
    fun detectScriptLists(doc: Document, baseUrl: String, minSize: Int = 2): List<ScriptRule> {
        val out = ArrayList<ScriptRule>()
        for (array in scriptArrays(doc, minSize)) {
            val titleField = pickTextField(array.objects) ?: continue
            for (rule in urlCandidates(array, titleField, needCover = true)) {
                val items = ScriptData.list(doc, rule, baseUrl)
                if (items.size >= minSize) out.add(rule)
            }
        }
        return out.distinct()
    }

    /** 扫一遍所有 <script>，把里面的 JSON 数组找出来（含嵌套一层） */
    internal fun scriptArrays(doc: Document, minSize: Int = 2): List<ScriptArray> {
        val out = ArrayList<ScriptArray>()
        val seen = HashSet<String>()

        for (script in doc.select("script")) {
            val text = script.data()
            if (text.length < 40) continue

            for (match in ASSIGNMENT.findAll(text)) {
                val name = match.groupValues[1]
                if (!seen.add("$name@${match.range.first}")) continue

                val start = ScriptData.jsonStartAfter(text, match.range.last + 1) ?: continue
                val raw = ScriptData.sliceBalanced(text, start) ?: continue
                val root = runCatching { JSONArray(raw) }.getOrNull() ?: continue

                collectArrays(root, name, "", minSize, out)
            }
        }
        return out
    }

    private fun collectArrays(
        arr: JSONArray,
        name: String,
        path: String,
        minSize: Int,
        out: MutableList<ScriptArray>
    ) {
        val objects = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        if (objects.size >= minSize) out += ScriptArray(name, path, objects)

        // 章节常常被包一层分组：[{title:"连载", data:[…]}]，往下一层看
        val first = objects.firstOrNull() ?: return
        for (key in first.keys()) {
            val nested = first.optJSONArray(key) ?: continue
            val nestedObjects = (0 until nested.length()).mapNotNull { nested.optJSONObject(it) }
            if (nestedObjects.size >= minSize) {
                val nestedPath = if (path.isEmpty()) "0.$key" else "$path.0.$key"
                out += ScriptArray(name, nestedPath, nestedObjects)
            }
        }
    }

    /** 挑标题字段：先按常见名字，再退到「文本填充率最高」的字段 */
    private fun pickTextField(objects: List<JSONObject>): String? {
        val known = listOf("chapter_name", "chapterName", "title", "name", "cname", "chapter_title")
        for (field in known) {
            val filled = objects.count { it.optString(field).isNotBlank() }
            if (filled >= objects.size * 0.8) return field
        }

        val keys = objects.firstOrNull()?.keys()?.asSequence()?.toList().orEmpty()
        return keys
            .filter { key -> objects.count { !it.optString(key).startsWith("{") } >= objects.size * 0.8 }
            .maxByOrNull { key ->
                objects.map { it.optString(key).trim() }
                    .count { it.length in 1..80 && it.any(Char::isLetterOrDigit) }
            }
    }

    /**
     * 生成条目地址的候选规则。
     *
     * 有两种情况：数据里直接带地址（url/link/href），或者只给 id 需要按站点路由拼。
     * 后者只能猜几条常见形态，由调用方用真实请求验证 —— 猜错不要紧，试错成本是一次请求。
     */
    private fun urlCandidates(
        array: ScriptArray,
        titleField: String,
        needCover: Boolean = false
    ): List<ScriptRule> {
        val first = array.objects.firstOrNull() ?: return emptyList()
        val base = ScriptRule(
            pattern = array.name,
            listPath = array.listPath,
            titleField = titleField,
            coverField = pickCoverField(array.objects)
        )

        // 数据自带地址，直接用
        val directField = listOf("url", "link", "href", "chapter_url", "chapterUrl")
            .firstOrNull { first.optString(it).let { v -> v.startsWith("/") || v.startsWith("http") } }
        if (directField != null) return listOf(base.copy(urlField = directField, urlTemplate = ""))

        val hasComicId = first.has("comic_id") || first.has("comicId")
        val idField = listOf("id", "chapter_id", "chapterId", "cid")
            .firstOrNull { first.has(it) } ?: return emptyList()

        val templates = if (needCover) {
            listOf("/comic/{$idField}", "/detail/{$idField}", "/manga/{$idField}")
        } else if (hasComicId) {
            val comicField = if (first.has("comic_id")) "comic_id" else "comicId"
            listOf(
                "/view/{$comicField}/{$idField}",
                "/read/{$comicField}/{$idField}",
                "/chapter/{$comicField}/{$idField}",
                "/comic/{$comicField}/{$idField}",
                "/mread/{$comicField}/{$idField}",
                "/detail/{$comicField}/{$idField}"
            )
        } else {
            listOf(
                "/view/{$idField}",
                "/chapter/{$idField}",
                "/read/{$idField}",
                "/comic/{$idField}",
                "/detail/{$idField}"
            )
        }
        return templates.map { base.copy(urlTemplate = it) }
    }

    private fun pickCoverField(objects: List<JSONObject>): String =
        listOf("cover", "coverUrl", "cover_url", "image", "img", "pic")
            .firstOrNull { field -> objects.count { it.optString(field).startsWith("http") } >= objects.size * 0.5 }
            ?: "cover"

    /** 形如 `var name = ` 或 `name(` 的赋值/调用点 */
    private val ASSIGNMENT = Regex("""\b([A-Za-z_$][A-Za-z0-9_$]*)\s*(?:=|\(\s*)""")

    // ==================== 通用工具 ====================

    internal fun hostOf(url: String): String? = runCatching {
        URL(url).host?.removePrefix("www.")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
