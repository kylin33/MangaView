package com.example.hellocompose.data.source

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * DOM 结构分析的底层工具：把「重复出现的子元素」抽象成可用的 CSS 选择器。
 *
 * 页面结构千差万别，但列表型内容有一个共同点：同一容器下有一批结构相同的子元素。
 * 所以先按「父容器 + 子元素签名」枚举候选，再由调用方用真实解析器打分。
 */
internal object Dom {

    /** 一个候选列表块：itemSelector 与它对应的容器 */
    data class Block(
        val itemSelector: String,
        val container: Element,
        val members: List<Element>
    )

    /** CSS 选择器里能直接用的类名/ID，其余（带冒号、点号、数字开头）会被丢弃 */
    private val SAFE_CLASS = Regex("[A-Za-z_][A-Za-z0-9_-]*")
    private val SAFE_ID = Regex("[A-Za-z_][A-Za-z0-9_-]*")

    private val BOILERPLATE = Regex(
        "(^|[-_ ])(nav|navbar|menu|header|footer|breadcrumb|copyright|comment|share|related)$",
        RegexOption.IGNORE_CASE
    )

    private val SKIP_TAGS = setOf("html", "head", "body")

    fun signature(el: Element): String = buildString {
        append(el.tagName())
        el.classNames()
            .filter { SAFE_CLASS.matches(it) }
            .sorted()
            .forEach { append('.').append(it) }
    }

    /** 元素自身可用的选择器；没有可用的 id/class 时返回 null */
    fun selfSelector(el: Element): String? {
        val id = el.id()
        if (id.isNotBlank() && SAFE_ID.matches(id)) return "#$id"
        val classes = el.classNames().filter { SAFE_CLASS.matches(it) }.sorted().take(3)
        return if (classes.isEmpty()) {
            null
        } else {
            el.tagName() + classes.joinToString("") { ".$it" }
        }
    }

    /**
     * 只用标签 + class 拼选择器，故意不掺 id。
     *
     * 同一类容器常常带着每页唯一的 id（如 `#pic_5824012`、`#pic_5824015`），
     * 用 id 拼出来的选择器只能命中一个，正文图片会被拆成一组一张。
     */
    fun classSelector(el: Element): String? {
        val classes = el.classNames().filter { SAFE_CLASS.matches(it) }.sorted().take(3)
        if (classes.isEmpty()) return null
        return el.tagName() + classes.joinToString("") { ".$it" }
    }

    fun isSafeClass(name: String): Boolean = SAFE_CLASS.matches(name)

    /**
     * 容器选择器：id 优先，其次**一个**稳定的 class，再往上两代找锚点。
     *
     * 以前这里会把元素上最多三个 class 全拼进去（`div.list.hot.news`），
     * 看着精确，实际站方多挂一个 `is-dark` 这种主题类就失配 ——
     * 而「多挂一个 class」比「改 id」常见得多。
     * 具体拼哪条最终由 [SelectorSynth] 用真实查询验证，这里只给兜底写法。
     */
    fun containerSelector(el: Element): String? {
        var cur: Element? = el
        var depth = 0
        while (cur != null && depth <= 2) {
            val id = cur.id()
            if (id.isNotBlank() && SAFE_ID.matches(id)) return "#$id"

            val cls = cur.classNames()
                .filter { SAFE_CLASS.matches(it) }
                .maxByOrNull { SelectorSynth.classRank(it) }
                // 只剩状态类/布局类时，拿它当范围比不写还糟，继续往上找
            if (cls != null && SelectorSynth.classRank(cls) >= 0) {
                return if (cur === el) {
                    "${el.tagName()}.$cls"
                } else {
                    "${cur.tagName()}.$cls ${el.tagName()}"
                }
            }
            cur = cur.parent()
            depth++
        }
        return el.tagName()
    }

    fun isBoilerplate(el: Element): Boolean {
        var cur: Element? = el
        var depth = 0
        while (cur != null && depth < 4) {
            val tag = cur.tagName()
            if (tag == "nav" || tag == "header" || tag == "footer") return true
            val className = cur.className()
            val id = cur.id()
            if (BOILERPLATE.containsMatchIn(className) || BOILERPLATE.containsMatchIn(id)) return true
            cur = cur.parent()
            depth++
        }
        return false
    }

    /**
     * 枚举候选列表块。
     *
     * 对每个容器，按其子元素的标签分组，再用「共同 class」或「出现最多的单个 class」
     * 拼出条目选择器。两种都试是因为条目的 class 里常夹着 first/last/odd 这类可变量，
     * 只取交集会退化，只取单个 class 又可能太窄。
     */
    fun blocks(doc: Document, minSize: Int): List<Block> {
        val out = ArrayList<Block>()
        // 同一页里大量容器会尝试同样的候选选择器，缓存一次查询结果能省掉绝大部分遍历
        val cache = HashMap<String, List<Element>>()
        fun lookup(selector: String): List<Element> = cache.getOrPut(selector) { doc.select(selector) }

        for (container in doc.allElements) {
            if (container.tagName() in SKIP_TAGS) continue
            if (container.childrenSize() < minSize || container.childrenSize() > 400) continue

            val byTag = container.children().groupBy { it.tagName() }
            for ((tag, members) in byTag) {
                if (members.size < minSize) continue

                val containerSelector = containerSelector(container) ?: continue

                val common = members
                    .map { it.classNames() }
                    .reduce { acc, set -> acc.intersect(set) }
                    .filter { SAFE_CLASS.matches(it) }
                    .sorted()
                    .take(2)

                // 两种 class 取法都试：条目 class 里常夹着 first/last/odd 这类可变量，
                // 只取交集会退化，只取单个 class 又可能太窄
                val classSets = LinkedHashSet<List<String>>()
                if (common.isNotEmpty()) classSets.add(common)
                members.flatMap { it.classNames() }
                    .filter { SAFE_CLASS.matches(it) }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?.let { classSets.add(listOf(it)) }
                if (classSets.isEmpty()) classSets.add(emptyList())

                val seen = HashSet<String>()
                for (classes in classSets) {
                    val fallback = "$containerSelector > $tag" + classes.joinToString("") { ".$it" }
                    val selector = SelectorSynth.item(
                        container = container,
                        members = members,
                        tag = tag,
                        requiredClasses = classes,
                        fallback = fallback,
                        lookup = ::lookup
                    )
                    if (seen.add(selector)) out += Block(selector, container, members)
                }
            }
        }
        return out
    }
}
