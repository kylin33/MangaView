package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.remote.PageParser
import com.example.hellocompose.data.remote.UrlResolver
import com.example.hellocompose.data.source.DetailRule
import com.example.hellocompose.data.source.Dom
import com.example.hellocompose.data.source.FieldRule
import com.example.hellocompose.data.source.ListRule
import com.example.hellocompose.data.source.ReaderRule
import com.example.hellocompose.data.source.ScriptData
import com.example.hellocompose.data.source.ScriptRule
import com.example.hellocompose.data.source.pickByRule
import com.example.hellocompose.data.source.readByRule
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL

/**
 * 站点结构识别：给定一份 HTML，推出可用的抓取规则。
 *
 * 列表型识别分成两步，两步各有独立文件：
 * 1. [ListCandidates] **生成候选** —— 页面上所有可能成列表的节点集合，不做取舍；
 * 2. 这里给每个候选算出字段规则，用 [PageParser] **真的解析一遍**，再把事实交给
 *    [ListScorer] 按「数量 / 图片 / 标题 / 地址 / 结构一致性」打分。
 *
 * 之所以不「找到第一个满足条件的 DOM 就返回」：首页往往同时有热门、最新、推荐、广告
 * 好几组结构相似（图片 + 链接 + 文本）的板块，先出现的那个纯属排版顺序，跟内容质量无关。
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
        val signature = StructureSignature()
        return ListCandidates.home(doc, minSize, signature)
            .mapNotNull { candidate -> evaluateHome(doc, candidate, baseUrl, minSize, signature) }
            .sortedByDescending { it.score }
            .distinctBy { it.rule.item }
            .take(8)
    }

    /**
     * 给一个候选列表算规则并打分。
     *
     * 打分用的是「按规则真的解析一遍」的结果，而不是靠标签名猜 —— 等到运行时才发现
     * 选择器写错，用户完全无法判断错在哪。这里打分的就是运行时那条代码路径，
     * 所以「识别通过」和「实际能抓」是同一件事。
     */
    private fun evaluateHome(
        doc: Document,
        candidate: ListCandidate,
        baseUrl: String,
        minSize: Int,
        signature: StructureSignature
    ): HomeGuess? {
        val members = candidate.members
        if (members.size < minSize) return null

        val cover = pickCoverRule(members) ?: return null
        val link = pickLinkRule(members, baseUrl) ?: return null
        val title = pickTitleRule(members)

        val rule = ListRule(
            path = "/",
            item = candidate.itemSelector,
            link = link,
            cover = cover,
            title = title
        )
        val parsed = PageParser.list(doc, rule, baseUrl)
        if (parsed.size < minSize) return null

        val facts = ListFacts(
            matchedCount = members.size,
            itemCount = parsed.size,
            imageValid = members.count { member ->
                ImageSignals.looksLikeImage(member.pickByRule(cover).readByRule(cover.attr))
            },
            titleValid = members.count { member -> isUsableTitle(member, title) },
            urlValid = members.count { member -> isUsableUrl(member, link, baseUrl) },
            distinctTitles = parsed.map { it.title }.distinct().size,
            distinctUrls = parsed.map { it.detailUrl }.distinct().size,
            iconCovers = members.count { member ->
                ImageSignals.isIcon(member.pickByRule(cover).readByRule(cover.attr))
            },
            structureConsistency = signature.consistency(members),
            boilerplate = Dom.isBoilerplate(candidate.container),
            originBonus = candidate.origin.bonus
        )
        return HomeGuess(rule, ListScorer.score(facts, ScoreWeights.HOME).total, parsed)
    }

    // ==================== 章节列表 ====================

    fun detectChapters(doc: Document, baseUrl: String): List<ChapterGuess> {
        val signature = StructureSignature()
        val candidates = ListCandidates.chapters(doc, signature)
        if (candidates.isEmpty()) return emptyList()

        return ListCandidates.mergeSplit(doc, candidates)
            .mapNotNull { candidate -> evaluateChapters(doc, candidate, baseUrl, signature) }
            .sortedByDescending { it.score }
            .distinctBy { it.rule.item }
            .take(8)
    }

    private fun evaluateChapters(
        doc: Document,
        candidate: ListCandidate,
        baseUrl: String,
        signature: StructureSignature
    ): ChapterGuess? {
        val members = candidate.members
        if (members.isEmpty()) return null

        val url = pickChapterUrlRule(members, baseUrl) ?: return null
        val title = pickChapterTitleRule(members)

        val rule = DetailRule(item = candidate.itemSelector, url = url, title = title)
        val parsed = PageParser.chapters(doc, rule, baseUrl)
        if (parsed.isEmpty()) return null
        // 作者/题材/地区这些链接也长成「一堆同类 a 标签」，不筛掉就会被当成章节
        if (!isChapterLinkSet(parsed)) return null

        val ratio = parsed.count { isChapterLike(it.title) }.toDouble() / parsed.size
        val facts = ListFacts(
            matchedCount = members.size,
            itemCount = parsed.size,
            // 章节列表没有封面，这一项在 CHAPTERS 权重里是 0
            imageValid = 0,
            titleValid = members.count { member ->
                member.pickByRule(title).readByRule(title.attr).length in 1..120
            },
            urlValid = members.count { member -> isUsableUrl(member, url, baseUrl) },
            distinctTitles = parsed.map { it.title }.distinct().size,
            distinctUrls = parsed.map { it.url }.distinct().size,
            structureConsistency = signature.consistency(members),
            boilerplate = Dom.isBoilerplate(candidate.container),
            chapterLike = ratio,
            originBonus = candidate.origin.bonus
        )
        return ChapterGuess(rule, ListScorer.score(facts, ScoreWeights.CHAPTERS).total, parsed, ratio)
    }

    // ==================== 章节图片 ====================

    fun detectReaders(doc: Document, baseUrl: String): List<ReaderGuess> {
        val images = ImageSignals.contentImages(doc)
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
            val attrOrder = ImageSignals.attrOrder(members)

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

    private fun pickLinkRule(items: List<Element>, baseUrl: String): FieldRule? =
        pickUrlRule(
            items = items,
            baseUrl = baseUrl,
            // 条目本身就是封面图时，详情链接只能往上找最近的 <a>
            allowClosest = items.count { it.tagName() == "img" } >= items.size * 0.8
        )

    /**
     * 挑「条目地址」的取值规则。列表和章节共用。
     *
     * 关键在于不能只认 `href`：有一类站点把跳转整个交给 JS，写的是
     * `href="javascript:void(0)"`，真实地址放在 `data-href` / `data-url` 上。
     * 只认 href 的话，整组链接都会被判成「取不到地址」，详情页直接识别不出章节 ——
     * 用户看到的是一句「这个站抓不到」，而实际上只是少试了一个属性。
     */
    private fun pickUrlRule(
        items: List<Element>,
        baseUrl: String,
        allowClosest: Boolean
    ): FieldRule? {
        if (items.isEmpty()) return null

        val candidates = LinkedHashSet<FieldRule>()
        if (items.count { it.tagName() == "a" && it.hasAttr("href") } >= items.size * 0.8) {
            candidates += FieldRule(select = "", attr = "href")
        }
        candidates += FieldRule(select = "a", attr = "href")
        if (allowClosest) candidates += FieldRule(select = "^", attr = "href")
        candidates += FieldRule(select = "a[href]", attr = "href")

        // JS 跳转的三种常见摆法：条目自身带 data-*、条目内部第一个 a 带、最近的 a 祖先带
        for (attr in JUMP_ATTRS) {
            candidates += FieldRule(select = "", attr = attr)
            candidates += FieldRule(select = "a", attr = attr)
            candidates += FieldRule(select = "^", attr = attr)
        }

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

    /** 把跳转交给 JS 的站点，真实地址通常挂在这几个属性上 */
    private val JUMP_ATTRS = listOf("data-href", "data-url", "data-link")

    /** data-* 里也可能放封面图，不能当详情链接用 */
    private val IMAGE_EXT = Regex(
        """\.(jpe?g|png|gif|webp|avif|bmp|svg)(\?|#|$)""",
        RegexOption.IGNORE_CASE
    )

    private fun linkQuality(items: List<Element>, rule: FieldRule, baseUrl: String): Double {
        if (items.isEmpty()) return 0.0
        return items.count { isUsableUrl(it, rule, baseUrl) }.toDouble() / items.size
    }

    /**
     * 这个条目按给定规则能不能取到「点得过去」的地址。
     *
     * 打分和挑规则共用同一份判断：挑规则时它回答「哪个属性最好」，
     * 打分时它回答「这组条目有几成能点」。两边语义不一致的话，
     * 会出现「挑中的规则命中率 100%、候选得分却很低」这种自相矛盾的结果。
     */
    private fun isUsableUrl(item: Element, rule: FieldRule, baseUrl: String): Boolean {
        val raw = item.pickByRule(rule).readByRule(rule.attr)
        if (raw.isBlank()) return false
        // data-* 属性里时常顺手放一张封面图，它也是合法地址，但指不到详情页
        if (rule.attr != "href" && IMAGE_EXT.containsMatchIn(raw)) return false
        // 只要求是绝对地址。不能强制同域：不少站点首页在 .top、详情页在 .cc（镜像/分站），
        // 一旦要求同域，整站会被判成「没有列表结构」。
        return UrlResolver.resolve(raw, baseUrl).startsWith("http")
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
            val rule = FieldRule(select = "", attr = ImageSignals.attrOrder(items))
            return rule.takeIf { coverQuality(items, rule) >= 0.5 }
        }

        val images = items.mapNotNull { it.selectFirst("img") }
        val carriers = items.mapNotNull { ImageSignals.carrierIn(it) }

        val candidates = LinkedHashSet<FieldRule>()
        if (images.size >= items.size * 0.5) {
            candidates += FieldRule(select = "img", attr = ImageSignals.attrOrder(images))
        }
        if (carriers.size >= items.size * 0.5) {
            val attrOrder = ImageSignals.attrOrder(carriers)
            // 条目自身就是图片载体（`<a data-original>` 直接当卡片，苹果CMS 系常见）。
            // 这种情况只能取条目自己的属性：用任何后代选择器都选不到「自己」，
            // 早先这里一律按「载体在条目内部」处理，生成的规则一个都匹配不上。
            if (carriers.count { carrier -> items.any { it === carrier } } >= items.size * 0.5) {
                candidates += FieldRule(select = "", attr = attrOrder)
            }
            // 载体在条目内部时，优先用「标签 + class」这种稳定选择器（如 a.vodlist_thumb），
            // 它比属性选择器更能避开同一条目里的其它链接
            carriers.filter { carrier -> items.none { it === carrier } }
                .groupingBy { Dom.signature(it) }
                .eachCount()
                .filterKeys { it.contains('.') }
                .maxByOrNull { it.value }
                ?.key
                ?.let { candidates += FieldRule(select = it, attr = attrOrder) }
            candidates += FieldRule(select = ImageSignals.CARRIER_SELECTOR, attr = attrOrder)
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
            if (ImageSignals.looksLikeImage(raw)) ok++
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
        return items.count { isUsableTitle(it, rule) }.toDouble() / items.size
    }

    /** 取到的这段文本能不能当书名用：长度合理、有实义字符、不是「作者：xxx」这种整块信息 */
    private fun isUsableTitle(item: Element, rule: FieldRule): Boolean {
        val text = item.pickByRule(rule).readByRule(rule.attr)
        if (text.length !in 2..90) return false
        if (text.count { it.isLetterOrDigit() } < 2) return false
        return !TITLE_NOISE.containsMatchIn(text)
    }

    private fun pickChapterUrlRule(items: List<Element>, baseUrl: String): FieldRule? =
        pickUrlRule(items = items, baseUrl = baseUrl, allowClosest = true)

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
