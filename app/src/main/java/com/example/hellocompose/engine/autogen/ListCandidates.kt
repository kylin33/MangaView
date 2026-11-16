package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.source.Dom
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.ln

/**
 * 一个「可能是一个列表」的节点集合。
 *
 * 只描述事实（哪些节点、来自哪种推法），不带分数 —— 分数由 [ListScorer] 另外算。
 * 这样调整权重、加惩罚项都不用动 DOM 遍历；反过来加一种新的候选来源，
 * 也不用重算已有的评分逻辑。
 */
internal data class ListCandidate(
    val itemSelector: String,
    val container: Element,
    val members: List<Element>,
    val origin: Origin,
    /** 章节场景：标题像「第 N 话」的比例。首页候选为 null */
    val chapterLike: Double? = null
) {
    /**
     * 候选来源。[bonus] 是同分时的偏向：结构化的「容器 + 重复子元素」比
     * 平铺的 `img[data-src]` 更可能真的是列表，因为它同时描述了容器边界。
     */
    enum class Origin(val bonus: Double) {
        BLOCK(0.03),
        IMAGE_STRIP(0.0),
        MERGED(0.02)
    }
}

/**
 * 候选生成。只回答「页面上哪些节点集合可能是列表」，不判断好坏。
 *
 * 一个页面往往同时存在热门、最新、推荐、广告好几组「图片 + 链接 + 文本」结构，
 * 全都满足最朴素的判断。所以这里不做「找到第一个就返回」，而是尽量都生成出来，
 * 交给 [ListScorer] 用真实解析结果排序 —— 早期版本就是在这里取第一条，
 * 页面上先出现哪个板块就抓哪个，广告位放前面时整站就废了。
 *
 * 预排序只用便宜信号（节点数、图片比例、结构一致性、章节名比例），
 * 目的是把「解析成本」留给最有希望的 20 组，而不是把所有候选都跑一遍 PageParser。
 */
internal object ListCandidates {

    private const val MAX_CANDIDATES = 20

    // ==================== 首页 ====================

    fun home(doc: Document, minSize: Int, signature: StructureSignature): List<ListCandidate> {
        val bySelector = LinkedHashMap<String, Dom.Block>()
        for (block in Dom.blocks(doc, minSize = minSize)) {
            bySelector.putIfAbsent(block.itemSelector, block)
        }

        val ranked = ArrayList<Pair<ListCandidate, Double>>()
        for ((selector, block) in bySelector) {
            val sample = block.members
            if (sample.size < minSize) continue

            // 首页是封面墙。条目里没有图的基本是导航/文字列表，先筛掉。
            // 「有图」不能只认 <img>：大量站点把封面写成 <a data-original>，靠 CSS 背景显示。
            val imageRatio = sample.count { ImageSignals.carrierIn(it) != null }.toDouble() / sample.size
            if (imageRatio < 0.5) continue

            val consistency = signature.consistency(sample)
            // 选择器太宽时节点数会虚高，一致性会先把它压下去
            val preScore = consistency * 1.2 + imageRatio + countScore(sample.size, 30)
            ranked += ListCandidate(selector, block.container, sample, ListCandidate.Origin.BLOCK) to preScore
        }

        // 封面图条带的预分要和其他候选同一量纲：它们的成员都是一张图，
        // 一致性天然是 1、图片比例也是 1。直接拿条数当预分会把「40 张封面图」
        // 顶到最前面，反而挤掉真正的列表候选
        ranked += coverStrips(doc, minSize).map { candidate ->
            val preScore = 1.2 + 1.0 + countScore(candidate.members.size, 30)
            candidate to preScore
        }

        return ranked
            .sortedByDescending { it.second }
            .take(MAX_CANDIDATES)
            .map { (candidate, _) -> withGlobalMembers(doc, candidate) }
    }

    /**
     * 另一种首页形态：整页没有统一的列表容器，而是十几个小板块各自成列表。
     * 这时「选中某一个板块」只能拿到一小部分，反倒是「所有封面图」最接近用户想看的首页。
     * 手写配置里那句 `img[data-original]` 就是这个思路。
     */
    private fun coverStrips(doc: Document, minSize: Int): List<ListCandidate> {
        val images = ImageSignals.contentImages(doc)
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

        return selectors.mapNotNull { selector ->
            val matched = doc.select(selector)
            if (matched.size < minSize) return@mapNotNull null
            val container = matched.firstOrNull()?.parent() ?: doc.body()
            ListCandidate(selector, container, matched, ListCandidate.Origin.IMAGE_STRIP)
        }
    }

    // ==================== 章节列表 ====================

    fun chapters(
        doc: Document,
        signature: StructureSignature,
        minSize: Int = 2
    ): List<ListCandidate> {
        val bySelector = LinkedHashMap<String, Dom.Block>()
        for (block in Dom.blocks(doc, minSize = minSize)) {
            bySelector.putIfAbsent(block.itemSelector, block)
        }

        val ranked = ArrayList<Pair<ListCandidate, Double>>()
        for ((selector, block) in bySelector) {
            val sample = block.members
            if (sample.isEmpty()) continue

            // 章节条目一定要能点到下一页。这里只认「有跳转载体」，具体取哪个属性
            // 由 FieldRule 的候选去试（href 可能是 javascript:void(0)，真实地址在 data-href）
            val jumpRatio = sample.count { hasJumpTarget(it) }.toDouble() / sample.size
            if (jumpRatio < 0.5) continue

            val like = sample.count { SourceAutoDetector.isChapterLike(it.text().trim()) }
                .toDouble() / sample.size
            // 章节名像不像「第 N 话」在预排序里权重最高：详情页里作者/题材/推荐位的
            // 链接也长成「一堆同类 a 标签」，只有章节名能先把它们分开
            val preScore = signature.consistency(sample) + like * 1.8 + countScore(sample.size, 60)
            ranked += ListCandidate(
                selector, block.container, sample, ListCandidate.Origin.BLOCK, like
            ) to preScore
        }

        return ranked
            .sortedByDescending { it.second }
            .take(MAX_CANDIDATES)
            .map { (candidate, _) -> withGlobalMembers(doc, candidate) }
    }

    private fun hasJumpTarget(el: Element): Boolean =
        el.hasAttr("href") || el.hasAttr("data-href") || el.hasAttr("data-url") || el.hasAttr("data-link") ||
            el.selectFirst("a[href], a[data-href], a[data-url], a[data-link]") != null

    /**
     * 把「被拆成多块的章节列表」合并成一条规则。
     *
     * 真实站点常把连载和番外、或者不同卷拆成多个 tab，每块结构一样、容器不同
     * （`#chapter-list-1 > ul > li`、`#chapter-list-2 > ul > li`）。逐块打分时每块都只覆盖
     * 一部分，排序后取第一名就会漏掉另一半 —— 用户看到的是「目录只有 12 章」，
     * 而站点上明明有 24 章。这种漏抓比报错更麻烦：配置看起来完全正常。
     *
     * 合并发生在打分之前：合并后的条目集合应该作为一个整体参与评分，
     * 否则「24 章」仍然带着「12 章」的分数，排序时体现不出补全的收益。
     *
     * 合并条件故意收得很紧：条目选择器的末段相同（说明是同一种条目），
     * 且每块本身就「像章节」。宁可少合并，也不要把推荐位并进目录。
     */
    fun mergeSplit(doc: Document, candidates: List<ListCandidate>): List<ListCandidate> {
        if (candidates.size < 2) return candidates

        val groups = LinkedHashMap<String, MutableList<ListCandidate>>()
        for (candidate in candidates) {
            val like = candidate.chapterLike ?: continue
            if (like < 0.5) continue
            val key = candidate.itemSelector.substringAfterLast('>').trim()
            if (key.isEmpty()) continue
            groups.getOrPut(key) { mutableListOf() } += candidate
        }

        val merged = ArrayList<ListCandidate>()
        val replaced = HashSet<String>()

        for (members in groups.values) {
            if (members.size < 2) continue
            val combined = members.map { it.itemSelector }.distinct().joinToString(", ")
            // 选择器拼得太长多半说明分组本身就是错的，不如不合并
            if (combined.length > 320) continue

            val elements = doc.select(combined)
            if (elements.size <= members.maxOf { it.members.size }) continue

            val best = members.maxByOrNull { it.chapterLike ?: 0.0 } ?: continue
            replaced += members.map { it.itemSelector }
            merged += best.copy(
                itemSelector = combined,
                members = elements,
                origin = ListCandidate.Origin.MERGED,
                chapterLike = elements.count { SourceAutoDetector.isChapterLike(it.text().trim()) }
                    .toDouble() / elements.size
            )
        }

        if (merged.isEmpty()) return candidates
        return candidates.filter { it.itemSelector !in replaced } + merged
    }

    // ==================== 工具 ====================

    /**
     * 用同一个选择器在整篇文档里重新取一次成员。
     *
     * `Dom.blocks` 给出的成员只覆盖「第一个匹配到该选择器的容器」。页面上常常有
     * 好几个同 class 的板块（`.book-list` 出现三次），只取第一个会漏掉后两个板块，
     * 而选择器本身在运行时是全局查询的 —— 两边必须一致，否则识别时的条数
     * 和 App 里看到的条数对不上。
     */
    private fun withGlobalMembers(doc: Document, candidate: ListCandidate): ListCandidate {
        val members = doc.select(candidate.itemSelector)
        if (members.size == candidate.members.size) return candidate
        return candidate.copy(
            members = members,
            // 成员变了，「像章节」的比例要按新成员重算，否则合并后仍带着旧比例
            chapterLike = if (candidate.chapterLike == null) {
                null
            } else {
                members.count { SourceAutoDetector.isChapterLike(it.text().trim()) }
                    .toDouble() / members.size.coerceAtLeast(1)
            }
        )
    }

    private fun countScore(count: Int, full: Int): Double {
        if (count <= 0) return 0.0
        return (ln(1.0 + count) / ln(1.0 + full)).coerceAtMost(1.0)
    }
}
