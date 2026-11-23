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
import com.example.hellocompose.data.source.SelectorSynth
import com.example.hellocompose.data.source.TitleNormalizer
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
        val items: List<Manga>,
        /** 这份规则靠什么抓到的（列表/封面/标题各自的通道），进识别报告 */
        val capabilities: SiteCapabilities = SiteCapabilities()
    )

    data class ChapterGuess(
        val rule: DetailRule,
        val score: Double,
        val items: List<Chapter>,
        /** 标题形如「第 12 话」的比例。用来把章节列表和「相关推荐」区分开 */
        val chapterLikeRatio: Double,
        val capabilities: SiteCapabilities = SiteCapabilities()
    )

    data class ReaderGuess(
        val rule: ReaderRule,
        val score: Double,
        val images: List<String>,
        val capabilities: SiteCapabilities = SiteCapabilities()
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

        // 先回答「这个列表靠什么承载封面和标题」，再按答案挑规则。
        // 探测只用条目自身的事实（多少条带 data-src、多少条带背景图……），
        // 不做猜测，所以规则挑选和报告里写的是同一件事
        val coverCap = CapabilityProbe.cover(members)
        val titleCap = CapabilityProbe.title(members)
        val capabilities = SiteCapabilities(
            list = if (candidate.origin == ListCandidate.Origin.IMAGE_STRIP) {
                Channel.IMAGE_STRIP
            } else {
                Channel.DOM
            },
            listStrength = 1.0,
            cover = coverCap.first,
            coverStrength = coverCap.second,
            title = titleCap.first,
            titleStrength = titleCap.second
        )

        val cover = pickCoverRule(members, capabilities.cover) ?: return null
        val link = pickLinkRule(members, baseUrl) ?: return null
        val title = pickTitleRule(members, link, capabilities.title)

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
            titleValid = members.count { member ->
                isUsableTitle(member, title, member.pickByRule(link))
            },
            urlValid = members.count { member -> isUsableUrl(member, link, baseUrl) },
            distinctTitles = parsed.map { it.title }.distinct().size,
            distinctUrls = parsed.map { it.detailUrl }.distinct().size,
            iconCovers = members.count { member ->
                ImageSignals.isIcon(member.pickByRule(cover).readByRule(cover.attr))
            },
            structureConsistency = signature.consistency(members),
            boilerplate = Dom.isBoilerplate(candidate.container),
            // class 不可靠时（item-123 这种），语义是唯一能把「作品列表」和
            // 「推广位」分开的线索；但它只做小幅偏向，不足以单独决定胜负
            semantic = Semantics.listBonus(candidate.container, members) +
                Semantics.urlBonus(memberUrls(members, baseUrl)),
            originBonus = candidate.origin.bonus
        )
        return HomeGuess(rule, ListScorer.score(facts, ScoreWeights.HOME).total, parsed, capabilities)
    }

    /**
     * 成员的跳转地址，用来做 URL 语义判断。
     *
     * 这里只认常规 `href`，不试 `data-href`：地址语义是可选的加分项，
     * 而 JS 跳转站点的真实地址本来就要靠 [pickUrlRule] 单独试出来，
     * 在这一步重复一遍只会让代码更长、也更容易把封面图地址当成员地址。
     */
    private fun memberUrls(members: List<Element>, baseUrl: String): List<String> =
        members.take(10).mapNotNull { member ->
            val raw = member.attr("href").ifBlank {
                member.selectFirst("a[href]")?.attr("href").orEmpty()
            }
            if (raw.isBlank()) null else UrlResolver.resolve(raw, baseUrl)
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
        val titleCap = CapabilityProbe.title(members)
        val facts = ListFacts(
            matchedCount = members.size,
            itemCount = parsed.size,
            // 章节列表没有封面，这一项在 CHAPTERS 权重里是 0
            imageValid = 0,
            titleValid = members.count { member ->
                TitleNormalizer.merge(
                    member.pickByRule(title).readByRule(title.attr),
                    member.pickByRule(url)?.attr("title").orEmpty()
                ).length in 1..120
            },
            urlValid = members.count { member -> isUsableUrl(member, url, baseUrl) },
            distinctTitles = parsed.map { it.title }.distinct().size,
            distinctUrls = parsed.map { it.url }.distinct().size,
            structureConsistency = signature.consistency(members),
            boilerplate = Dom.isBoilerplate(candidate.container),
            chapterLike = ratio,
            semantic = Semantics.listBonus(candidate.container, members) +
                Semantics.urlBonus(memberUrls(members, baseUrl), chapterMode = true),
            originBonus = candidate.origin.bonus
        )
        return ChapterGuess(
            rule,
            ListScorer.score(facts, ScoreWeights.CHAPTERS).total,
            parsed,
            ratio,
            SiteCapabilities(
                chapters = Channel.DOM,
                title = titleCap.first,
                titleStrength = titleCap.second
            )
        )
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
        // 候选选择器会反复出现在不同分组里，查询结果按选择器缓一份
        val cache = HashMap<String, List<Element>>()
        fun lookup(selector: String): List<Element> = cache.getOrPut(selector) { doc.select(selector) }

        for ((_, members) in groups) {
            val parent = members.first().parent() ?: continue
            if (parent.tagName() in setOf("body", "html")) continue
            val parentSelector = Dom.classSelector(parent)
                ?: Dom.containerSelector(parent)
                ?: continue
            val attrOrder = ImageSignals.attrOrder(members)

            // 除了「容器下的 img」，还要试「载体自身的标签 + class」：
            // 有些站整页用背景图分页（<div class="page" style="background-image:url(...)">），
            // 页面里一张 img 都没有，只试 img 的话正文图恒为空
            val carrierSelectors = members.mapNotNull { Dom.selfSelector(it) }.distinct().take(2)
            // 正文图的选择器同样走「最短且唯一」：`#content > img` 比
            // `div.main.wrap #content img` 耐改版得多，站方换一层包裹也不会失效
            val synthesized = SelectorSynth
                .item(parent, members, "img", emptyList(), "$parentSelector > img", ::lookup)
                // 裸 `img` 会连封面、广告、友链图一起框进来，宁可退回原来的带范围写法
                .takeIf { it != "img" }
            val itemSelectors = LinkedHashSet<String>().apply {
                synthesized?.let { add(it) }
                add("$parentSelector > img")
                add("$parentSelector img")
                addAll(carrierSelectors)
            }
            for (itemSelector in itemSelectors) {
                if (!seen.add(itemSelector)) continue
                val rule = ReaderRule(
                    item = itemSelector,
                    image = FieldRule(select = "", attr = attrOrder),
                    referer = "{pageUrl}"
                )
                val parsed = PageParser.images(doc, rule, baseUrl)
                if (parsed.isEmpty()) continue
                val channel = CapabilityProbe.images(members)
                out += ReaderGuess(
                    rule,
                    scoreImages(parsed, itemSelector),
                    parsed,
                    SiteCapabilities(images = channel)
                )
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
        allowClosest: Boolean,
        preferChapter: Boolean = false
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
        var bestRank = 0.0
        var bestQuality = 0.0
        for (candidate in candidates) {
            val quality = linkQuality(items, candidate, baseUrl)
            // 命中率相同时，用地址语义偏向「像详情页/像章节」的那个属性。
            // 典型场景：卡片里既有封面链接又有标题链接，两者都能取到地址，
            // 但只有标题链接指向详情页；语义加成让同分时候选更倾向它
            val rank = quality + urlSemanticBonus(items, candidate, baseUrl, preferChapter)
            if (rank > bestRank) {
                bestRank = rank
                bestQuality = quality
                best = candidate
            }
        }
        // 阈值只看命中率，不能被语义加成拉过线
        return best?.takeIf { bestQuality >= 0.6 }
    }

    /** 地址语义加成：只做同分时的偏向，最多 0.04 */
    private fun urlSemanticBonus(
        items: List<Element>,
        rule: FieldRule,
        baseUrl: String,
        preferChapter: Boolean
    ): Double {
        val urls = items.take(6).mapNotNull { item ->
            val raw = item.pickByRule(rule).readByRule(rule.attr)
            if (raw.isBlank()) null else UrlResolver.resolve(raw, baseUrl)
        }
        if (urls.isEmpty()) return 0.0
        val hit = urls.count { url ->
            if (preferChapter) Semantics.looksLikeChapterUrl(url) else Semantics.looksLikeDetailUrl(url)
        }
        return hit.toDouble() / urls.size * 0.04
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
    private fun pickCoverRule(items: List<Element>, prefer: Channel = Channel.NONE): FieldRule? {
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

        // 语义候选：条目里那个「名字里写着 cover/thumb/poster」的节点。
        // 它常常比 img 更准 —— 卡片里可能还塞了作者头像、评分星标这类同标签干扰项
        carriers.filter { carrier -> items.none { it === carrier } }
            .filter { carrier -> Semantics.score(carrier, Semantics.Role.COVER) >= 1.0 }
            .groupingBy { Dom.signature(it) }
            .eachCount()
            .filterKeys { it.contains('.') }
            .entries
            .sortedByDescending { it.value }
            .take(2)
            .forEach { (signature, _) ->
                candidates += FieldRule(select = signature, attr = ImageSignals.attrOrder(carriers))
            }

        return candidates
            .maxByOrNull {
                coverQuality(items, it) +
                    semanticBias(items, it, Semantics.Role.COVER, emptyList()) +
                    coverChannelBonus(it, prefer)
            }
            ?.takeIf { coverQuality(items, it) >= 0.5 }
    }

    /**
     * 封面规则的通道偏好。
     *
     * 只在两个候选**都能取到图**时起作用：能力探测说这个站封面写在背景图里，
     * 那 `style` 候选就值得优先；说封面在懒加载属性上，`data-*` 候选就优先。
     * 幅度 0.05，低于「质量差 10%」，翻了不盘。
     */
    private fun coverChannelBonus(rule: FieldRule, prefer: Channel): Double {
        val attrs = rule.attr.split('|').map { it.trim() }
        return when (prefer) {
            Channel.INLINE_BG -> if (attrs.contains("style")) 0.05 else 0.0
            Channel.LAZY_ATTR -> if (attrs.any { it != "src" && it != "style" }) 0.05 else 0.0
            Channel.DOM -> if (attrs == listOf("src")) 0.03 else 0.0
            else -> 0.0
        }
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

    /**
     * 挑书名取值规则。
     *
     * 候选里既有「标签位置」这类结构猜测（h2/h3/a），也有从语义推出来的
     * 「条目里那个叫 title / book-name 的节点」。结构猜测对模板站很准，
     * 但对 class 全是 `item-123` 的站点就完全失效 —— 两条路都要走。
     */
    private fun pickTitleRule(items: List<Element>, link: FieldRule, prefer: Channel = Channel.NONE): FieldRule {
        val candidates = ArrayList<FieldRule>()
        // 语义候选排在前面：同分时优先选「名字里就写着 title」的那个
        semanticElementCandidates(items, Semantics.Role.TITLE).forEach {
            candidates += FieldRule(select = it, attr = "text")
        }
        candidates += FieldRule(select = "[itemprop=name]", attr = "text")
        candidates += FieldRule(select = "img", attr = "alt")
        candidates += FieldRule(select = "", attr = "alt")
        candidates += FieldRule(select = "a", attr = "title")
        candidates += FieldRule(select = "h3", attr = "text")
        candidates += FieldRule(select = "h2", attr = "text")
        candidates += FieldRule(select = "a", attr = "text")
        // 封面 <a> 里没有文字时，标题往往在同一层级的第二个 <a> 上
        candidates += FieldRule(select = "a:nth-of-type(2)", attr = "text")
        candidates += FieldRule(select = "", attr = "title")
        candidates += FieldRule(select = "", attr = "text")

        var best = FieldRule(select = "", attr = "text")
        var bestRank = -1.0
        for (candidate in candidates) {
            val rank = titleQuality(items, candidate, link) + semanticBias(
                items, candidate, good = Semantics.Role.TITLE,
                bad = listOf(Semantics.Role.AUTHOR, Semantics.Role.NAV, Semantics.Role.AD)
            ) + titleChannelBonus(candidate, prefer)
            if (rank > bestRank) {
                bestRank = rank
                best = candidate
            }
        }
        return best
    }

    /**
     * 标题规则的通道偏好：探测到站点把书名放在链接的 `title` 属性上时，
     * `a@title` 这类候选优先。幅度 0.05，且只在直接命中率相同时才体现出来。
     */
    private fun titleChannelBonus(rule: FieldRule, prefer: Channel): Double = when (prefer) {
        Channel.LINK_TITLE -> if (rule.attr == "title") 0.05 else 0.0
        Channel.SEMANTIC_NODE -> if (rule.select.isNotBlank()) 0.03 else 0.0
        Channel.VISIBLE_TEXT -> if (rule.select.isBlank() && rule.attr == "text") 0.02 else 0.0
        else -> 0.0
    }

    /**
     * 从条目内部找出「语义上承担某个角色」的后代，按签名聚成选择器候选。
     *
     * 只看后三代：书名节点几乎不会埋在更深处，而往下翻得越多越容易在
     * 广告条、推荐位里撞到同名 class。
     */
    private fun semanticElementCandidates(
        items: List<Element>,
        role: Semantics.Role
    ): List<String> {
        if (items.isEmpty()) return emptyList()

        val counter = HashMap<String, Int>()
        for (item in items) {
            val seen = LinkedHashSet<String>()
            for (el in descendantsWithin(item, depth = 3)) {
                if (Semantics.score(el, role) < 1.0) continue
                val signature = Dom.signature(el)
                // 没有 class 的后代拼不出稳定选择器，交给结构候选去覆盖
                if (!signature.contains('.')) continue
                seen += signature
            }
            seen.forEach { counter[it] = (counter[it] ?: 0) + 1 }
        }

        val threshold = maxOf(1, (items.size * 0.5).toInt())
        return counter.entries
            .filter { it.value >= threshold }
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }
    }

    private fun descendantsWithin(root: Element, depth: Int): List<Element> {
        val out = ArrayList<Element>()
        var layer = root.children().toList()
        var level = 1
        while (layer.isNotEmpty() && level <= depth) {
            out += layer
            layer = layer.flatMap { it.children() }
            level++
        }
        return out
    }

    /**
     * 语义偏向：目标节点「名字里写着标题」加分，「写着作者/导航/广告」减分。
     *
     * 幅度只有 0.06，低于「质量差 10%」的量级 —— 语义用来打破平局，不是用来翻盘。
     */
    private fun semanticBias(
        items: List<Element>,
        rule: FieldRule,
        good: Semantics.Role,
        bad: List<Semantics.Role>
    ): Double {
        val targets = items.take(8).mapNotNull { it.pickByRule(rule) }
        if (targets.isEmpty()) return 0.0
        val goodHits = targets.count { Semantics.score(it, good) >= 1.0 }
        val badHits = targets.count { target -> bad.any { Semantics.score(target, it) >= 1.0 } }
        return (goodHits - badHits).toDouble() / targets.size * 0.06
    }

    /** 标题里掺了这些字样，说明取到的是作者/简介行，不是书名 */
    private val TITLE_NOISE = Regex("""(作者\s*[:：]|简介\s*[:：]|状态\s*[:：]|标签\s*[:：])""")

    /**
     * 标题规则的质量。
     *
     * 必须把「规则自己取到」和「靠兜底拼出来」分开算：
     * [PageParser.titleText] 在规则取空时会退到条目里第一个有文字的节点，
     * 于是一个**完全匹配不上**的规则（比如站点没有 `[itemprop=name]`）
     * 也能拿到 100% 的合格率，然后靠候选顺序把正确的规则挤掉。
     * 这正是「书名带着『14小时前』」的成因 —— 兜底拼的是整个 `<a>` 的文字，
     * 徽章、角标全在里面。
     *
     * 但**不能**给「取到文字」太大权重：老站表格布局里，正确的书名只存在于
     * 「条目文字去掉作者/更新时间」之后，规则本身取到的往往是一整行脏文本，
     * 反而是靠兜底才拿到干净值。
     *
     * 所以准确率是主项，另外加 0.05 的「这个选择器在页面上真的选得到节点」加分 ——
     * 它的作用是在两条规则准确率相同时，优先选节点真实存在的那条。
     * 否则页面上根本没有的 `[itemprop=name]` 会靠兜底拿到 100% 准确率，
     * 然后因为排在前面而胜出，配置里留下一个永远匹配不上的选择器。
     */
    private fun titleQuality(items: List<Element>, rule: FieldRule, link: FieldRule): Double {
        if (items.isEmpty()) return 0.0
        var resolved = 0
        var usable = 0
        for (item in items) {
            if (rule.select.isBlank() || item.pickByRule(rule) != null) resolved++
            if (isUsableTitle(item, rule, item.pickByRule(link))) usable++
        }
        val n = items.size
        return usable.toDouble() / n + resolved.toDouble() / n * 0.05
    }

    /** 取到的这段文本能不能当书名用：长度合理、有实义字符、不是「作者：xxx」这种整块信息 */
    private fun isUsableTitle(item: Element, rule: FieldRule, link: Element? = null): Boolean {
        // 和运行时用同一个取值函数：评分看到空、运行时却拿到文字（或者反过来），
        // 会让「同一套规则识别时通过、跑起来全叫未命名」
        val text = PageParser.titleText(item, rule, link)
        if (text.length !in 2..90) return false
        if (text.count { it.isLetterOrDigit() } < 2) return false
        if (TitleNormalizer.isGeneric(text)) return false
        return !TITLE_NOISE.containsMatchIn(text)
    }

    private fun pickChapterUrlRule(items: List<Element>, baseUrl: String): FieldRule? =
        pickUrlRule(items = items, baseUrl = baseUrl, allowClosest = true, preferChapter = true)

    private fun pickChapterTitleRule(items: List<Element>): FieldRule {
        val candidates = ArrayList<FieldRule>()
        // 名字里写着 chapter/episode 的节点排在前面：章节名常被书名、更新时间挤在同一个 a 里
        semanticElementCandidates(items, Semantics.Role.CHAPTER).forEach {
            candidates += FieldRule(select = it, attr = "text")
        }
        candidates += FieldRule(select = "", attr = "text")
        candidates += FieldRule(select = "a", attr = "text")
        candidates += FieldRule(select = "a", attr = "title")
        candidates += FieldRule(select = "span", attr = "text")
        candidates += FieldRule(select = "", attr = "title")

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
            // 语义项按同一量纲放大（×10），否则 0.06 的偏差在这个量级里看不见
            val q = filled * 10.0 + chapterish * 20.0 - avgLen * 0.02 +
                semanticBias(items, candidate, Semantics.Role.CHAPTER, emptyList()) * 10.0
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
