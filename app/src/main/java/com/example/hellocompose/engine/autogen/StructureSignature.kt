package com.example.hellocompose.engine.autogen

import org.jsoup.nodes.Element

/**
 * DOM 结构相似度。
 *
 * 候选选择器是不是「真的列表」，光看条数和字段填满率不够：一个过宽的选择器
 * （比如 `div > div`）能把半页不相干的块都框进来，条数很漂亮，字段也勉强取得到，
 * 但条目之间结构完全不同 —— 用户拿到的是「12 部作品里 6 部没封面」。
 *
 * 所以给每个条目算一份结构签名，再看这一组条目有多少个长得像：
 *
 * ```
 * Consistency = 结构相似的节点数 / 总节点数
 * ```
 *
 * 签名是「标签名 + 深度」的先序序列（`div@0/a@1/img@2`）。不掺 class 和文本：
 * 主题模板常给条目加 `first` / `last` / `odd` 这类可变 class，掺进去会把
 * 正常的列表误判成不一致；而文本本来就每条都不同。
 *
 * 相似判定用序列编辑距离而不是全等：真实模板常在某些条目上多一个「NEW」角标，
 * 全等比较会把它算成另一种结构，一致性瞬间掉到 0.7 以下。距离 ≤ 20% 就算同构。
 */
class StructureSignature(private val maxDepth: Int = 3) {

    /** 元素 → 标签序列。同一次识别里会被多个候选复用，按元素缓存 */
    private val tokenCache = HashMap<Element, List<String>>()

    fun tokens(el: Element): List<String> = tokenCache.getOrPut(el) { flatten(el, 0) }

    fun signature(el: Element): String = tokens(el).joinToString("/")

    /** 一组条目里「和主流结构相似」的比例，0..1 */
    fun consistency(members: List<Element>): Double {
        if (members.isEmpty()) return 0.0

        val groups = members.groupingBy { signature(it) }.eachCount()
        val dominantSignature = groups.entries.maxByOrNull { it.value }?.key ?: return 0.0
        val reference = members.firstOrNull { signature(it) == dominantSignature } ?: return 0.0
        val referenceTokens = tokens(reference)

        val similar = members.count { similarity(referenceTokens, tokens(it)) >= SIMILAR_ENOUGH }
        return similar.toDouble() / members.size
    }

    /**
     * 两个元素的结构相似度，0..1。
     * 编辑距离除以较长序列长度：只是「多一个角标」时距离 1、长度 7，相似度 0.86。
     */
    fun similarity(a: Element, b: Element): Double = similarity(tokens(a), tokens(b))

    private fun similarity(a: List<String>, b: List<String>): Double {
        if (a == b) return 1.0
        val longest = maxOf(a.size, b.size)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    private fun flatten(el: Element, depth: Int): List<String> {
        val head = el.tagName() + "@" + depth
        if (depth >= maxDepth) return listOf(head)

        val children = el.children()
        if (children.isEmpty()) return listOf(head)

        val out = ArrayList<String>(8)
        out += head
        children.forEach { out += flatten(it, depth + 1) }
        return out
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size

        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)

        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val substitution = previous[j] + if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(substitution, previous[j + 1] + 1, current[j] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.size]
    }

    private companion object {
        /**
         * 相似阈值。取 0.8 是因为「多一个角标」这种最常见的模板差异会落在 0.85 左右，
         * 而「条目里没有标题节点」这种真差异会掉到 0.6 以下。
         */
        const val SIMILAR_ENOUGH = 0.8
    }
}
