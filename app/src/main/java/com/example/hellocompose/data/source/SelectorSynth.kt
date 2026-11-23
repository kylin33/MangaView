package com.example.hellocompose.data.source

import org.jsoup.nodes.Element

/**
 * 生成「最短且刚好命中目标」的条目选择器。
 *
 * 为什么要有这一层：自动识别出来的规则是给用户长期用的，站点却一直在改版。
 * 一个 `html > body > div:nth-child(2) > ul > li` 式的选择器，站方在中间插一条
 * 活动横幅就整站失效 —— 而用户看到的现象只是「突然抓不到漫画了」，无从判断原因。
 *
 * 所以这里不做「拼一条看起来对的路径」，而是**先有目标元素、再反推选择器**，
 * 每条候选都要拿整篇文档重新查一遍：结果必须**恰好**是这批元素（不多不少）。
 * 相等就说明这条选择器没有依赖兄弟位置，页面加广告位、调整顺序都不受影响。
 *
 * 优先级按「稳定性」而不是「简短」排：
 * 1. `#id > 条目` —— id 是站方给的锚点，最不容易随版式漂移
 * 2. `条目标签.语义 class` —— 单独就能唯一命中时最短、也最耐改版
 * 3. `#外层id 条目` —— 条目不是直接子元素时，用祖先 id 划范围
 * 4. `外层.语义class > 条目` —— 没有 id 时的退路，仍只保留一个 class
 * 5. 调用方给的兜底（老逻辑拼出来的路径）
 *
 * 任何一条都不含 `:nth-child` / `:nth-of-type`：位置类选择器是这类失效的根源，
 * 不是修复手段。
 */
internal object SelectorSynth {

    /** 纯状态类：今天挂在当前项上，明天就换了对象，绝不能进选择器 */
    private val STATE_CLASS = Regex(
        "^(active|selected|current|hover|focus|open|opened|show|shown|hidden|on|off|" +
            "first|last|odd|even|disabled|checked|is-.*|has-.*)$",
        RegexOption.IGNORE_CASE
    )

    /** 布局工具类：随版式调整而变，语义为零 */
    private val UTILITY_CLASS = Regex(
        "^(row|col|container|wrapper|wrap|inner|outer|clearfix|float-.*|pull-.*|" +
            "col-.*|d-.*|m[tbxy]?-.*|p[tbxy]?-.*|text-.*|bg-.*|w-\\d+|h-\\d+|" +
            "visible-.*|hidden-.*|mx-.*|my-.*|mt-.*|mb-.*|ml-.*|mr-.*)$",
        RegexOption.IGNORE_CASE
    )

    /** 内容语义：带这些词的 class 通常就是这个板块的名字 */
    private val SEMANTIC_HINT = Regex(
        "item|list|card|post|comic|book|manga|chapter|thumb|cover|entry|article|" +
            "product|cell|unit|media|box|block|main|content|detail|info|grid|panel",
        RegexOption.IGNORE_CASE
    )

    /** 以数字结尾的 class 多半是序号/栅格宽度（`item-123`、`col-4`），换页就变 */
    private val DIGIT_TAIL = Regex("\\d+$")

    private val SKIP_TAGS = setOf("html", "head", "body")

    /**
     * 推导条目选择器。
     *
     * [members] 是识别阶段认定的那批条目，[tag] 是它们的标签名，
     * [requiredClasses] 是它们共有的 class（可能为空），[fallback] 是调用方兜底。
     * [lookup] 负责把选择器变成元素列表 —— 由调用方提供并缓存，
     * 因为同一个页面里大量容器会重复尝试同样的几条候选。
     */
    fun item(
        container: Element,
        members: List<Element>,
        tag: String,
        requiredClasses: List<String>,
        fallback: String,
        lookup: (String) -> List<Element>
    ): String {
        // 目标集合只算带齐 requiredClasses 的那些成员。
        // 容器里常常混着两种东西（12 张作品卡 + 8 条横幅），按 tag 分组时它们在同一组里；
        // 把横幅也算作目标，等价于「验证通过」的选择器必须连横幅一起选中，短选择器永远过不了
        val targets = members
            .filter { el -> requiredClasses.all { it in el.classNames() } }
            .toHashSet()
        if (targets.isEmpty()) return fallback
        val tails = tails(tag, requiredClasses)
        val scopes = scopes(container)

        for (candidate in candidates(scopes, tails, hasClasses = requiredClasses.isNotEmpty())) {
            val found = lookup(candidate)
            if (found.size == targets.size && found.all { it in targets }) return candidate
        }
        return fallback
    }

    /**
     * 条目自身的写法：先试单个 class（越多 class 越容易随改版失配），再试组合。
     *
     * 顺序按 [classRank] 排：语义类在前，状态类、布局类垫底。
     * 但**不排除**低分 class —— 「class 全是 g-1 / i-9 这种无语义命名」的站点是真实存在的，
     * 把低分项直接扔掉，这类站点就只剩裸标签可选了。分低只代表排在后面，
     * 能不能用由「重新查询是否恰好命中」决定。
     */
    private fun tails(tag: String, required: List<String>): List<String> {
        val ranked = required
            .filter { Dom.isSafeClass(it) }
            .distinct()
            .sortedByDescending { classRank(it) }

        val out = LinkedHashSet<String>()
        ranked.forEach { out += "$tag.$it" }
        if (ranked.size >= 2) out += ranked.joinToString(".") { "$tag.$it" }
        // 没有 class 可依托时才用裸标签。有 class 却退到裸标签，等于把同标签的
        // 横幅、导航项一并框进来 —— 这类选择器「能解析出更多条」，反而更容易赢评分
        if (ranked.isEmpty()) out += tag
        return out.toList()
    }

    /**
     * 容器的候选范围，从内到外。
     *
     * 每个祖先最多贡献两个：`#id` 和一个 class。带三个 class 的写法
     * （`div.list.hot.news`）看着精确，实际站方多挂一个 class 就失配，
     * 而多挂 class 比改 id 常见得多。
     */
    private fun scopes(container: Element): List<String> {
        val out = LinkedHashSet<String>()
        var cur: Element? = container
        var depth = 0
        while (cur != null && depth <= 3) {
            if (cur.tagName() !in SKIP_TAGS) {
                val id = cur.id()
                if (id.isNotBlank() && Dom.isSafeClass(id)) out += "#$id"

                cur.classNames()
                    .filter { Dom.isSafeClass(it) }
                    .sortedByDescending { classRank(it) }
                    .take(2)
                    .forEach { out += "${cur.tagName()}.$it" }
            }
            cur = cur.parent()
            depth++
        }
        return out.toList()
    }

    /**
     * 候选组合顺序。
     *
     * 条目自带语义 class 时，「`div.comic-item` 这种不带范围的写法」优先：
     * 它短、只依赖一个语义类，是最耐改版的形态。
     *
     * 条目只剩裸标签时（老站的 `<ul><li>` 目录）必须反过来 —— 先找有 class 的祖先，
     * 最后才用 `<li>`。裸标签能通过「当下恰好唯一」的验证，但站方加一个推荐列表、
     * 加一条面包屑就会开始抓多，那时用户看到的是「目录里混进了别的东西」。
     */
    private fun candidates(scopes: List<String>, tails: List<String>, hasClasses: Boolean): List<String> {
        val ids = scopes.filter { it.startsWith("#") }
        val classes = scopes.filterNot { it.startsWith("#") }
        val out = LinkedHashSet<String>()

        // 1) 容器/近祖先的 id 划范围
        for (scope in ids) {
            for (tail in tails) out += "$scope > $tail"
            for (tail in tails) out += "$scope $tail"
        }

        if (hasClasses) {
            // 2) 只靠条目本身：能唯一命中就不需要范围
            out += tails
            // 3) 带 class 的祖先。也试后代写法：条目常常隔着一层 ul/div，
            //    只试直接子元素会把「能用的短路径」漏掉
            for (scope in classes) {
                for (tail in tails) out += "$scope > $tail"
                for (tail in tails) out += "$scope $tail"
            }
        } else {
            for (scope in classes) {
                for (tail in tails) out += "$scope > $tail"
                for (tail in tails) out += "$scope $tail"
            }
            out += tails
        }
        return out.toList()
    }

    /**
     * class 的稳定性。只用来排序，不用来过滤 ——
     * 判断标准是「站方改版时哪个更容易保留」。
     */
    fun classRank(name: String): Int {
        val lower = name.lowercase()
        var score = 0
        if (STATE_CLASS.matches(lower)) score -= 8
        if (UTILITY_CLASS.matches(lower)) score -= 5
        if (DIGIT_TAIL.containsMatchIn(lower)) score -= 4
        if (SEMANTIC_HINT.containsMatchIn(lower)) score += 3
        return score
    }

    /**
     * 选择器的脆弱程度，用于识别报告里的提醒。
     *
     * 这里不追求精确评分：只要能把「靠位置」和「靠一长串 class」这两种
     * 迟早会坏的选择器挑出来，让用户在还能用的时候就先知道。
     */
    fun fragility(selector: String): String? {
        if (selector.isBlank()) return null
        if (selector.contains(":nth-")) return "依赖元素位置（:nth-*），站点加一个广告位就会失效"
        val classCount = Regex("\\.[A-Za-z_][A-Za-z0-9_-]*").findAll(selector).count()
        if (classCount >= 3) return "选择器叠了 $classCount 个 class，站点改版容易失配"
        if (selector.count { it == '>' } >= 2) return "选择器层级偏深（${selector.count { it == '>' }} 层）"
        return null
    }
}
