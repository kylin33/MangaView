package com.example.hellocompose.engine.autogen

import kotlin.math.ln

/**
 * 候选列表的评分模型。
 *
 * 与 [ListCandidates] 的分工：那边只负责「页面上有哪些可能成列表的节点集合」，
 * 这边只负责「给定一批事实，这组节点有多像一个真正的作品/章节列表」。
 * 分开的好处是调权重不用碰 DOM 遍历，加候选来源也不用重算分数。
 *
 * 五个正向分量（0..1 各自归一）：
 *
 * | 分量 | 含义 | 首页权重 | 章节权重 |
 * | --- | --- | --- | --- |
 * | N | 条目数量（对数饱和，30 条封顶） | 0.20 | 0.30 |
 * | I | 图片有效率 | 0.25 | —（章节没有图） |
 * | T | 标题有效率 | 0.25 | 0.30 |
 * | U | 地址有效率 | 0.20 | 0.25 |
 * | D | DOM 结构一致性 | 0.10 | 0.15 |
 *
 * 再叠加惩罚项：结构不一致、标题/地址大量重复、封面指向图标广告、
 * 落在导航页脚里、选择器过宽、章节名根本不像章节。
 *
 * 惩罚项用「减分」而不是「乘系数」：一个 30 条、只有 20% 有标题的推荐位，
 * 乘系数会把它的数量优势也一起抹掉，减分只削掉它不该得的那部分。
 */
data class ScoreWeights(
    val count: Double,
    val image: Double,
    val title: Double,
    val url: Double,
    val structure: Double
) {
    companion object {
        /** 首页：封面墙，图片与标题同样重要 */
        val HOME = ScoreWeights(count = 0.20, image = 0.25, title = 0.25, url = 0.20, structure = 0.10)

        /** 章节列表：没有封面，数量和标题更能区分「目录」与「相关推荐」 */
        val CHAPTERS = ScoreWeights(count = 0.30, image = 0.0, title = 0.30, url = 0.25, structure = 0.15)
    }
}

/**
 * 打分需要的全部事实，全部由识别层数出来。
 *
 * 这里刻意只放整数和比例，不放 Element：评分逻辑要能在纯 JVM 单测里
 * 用几行数字验证，不用造 HTML。
 */
data class ListFacts(
    /** 选择器命中的节点数（含取不到字段的） */
    val matchedCount: Int,
    /** 真正解析出的条目数（取不到地址/覆盖的会被丢掉） */
    val itemCount: Int,
    val imageValid: Int,
    val titleValid: Int,
    val urlValid: Int,
    val distinctTitles: Int,
    val distinctUrls: Int,
    /** 封面地址里带 logo/icon/banner 字样的条目数 */
    val iconCovers: Int = 0,
    /** 同组条目的结构一致性，见 [StructureSignature] */
    val structureConsistency: Double = 1.0,
    val boilerplate: Boolean = false,
    /** 章节场景：标题像「第 N 话」的比例。首页为 null */
    val chapterLike: Double? = null,
    /** 语义加成（class/id/alt/URL 是否在说「这是个作品或章节列表」），见 [Semantics] */
    val semantic: Double = 0.0,
    /** 候选来源的微弱加分，用于同分时偏向结构化规则 */
    val originBonus: Double = 0.0
)

/** 一条减分理由，保留文案是为了能解释「为什么这个候选输了」 */
data class Penalty(val reason: String, val value: Double)

data class ListScore(
    val total: Double,
    val count: Double,
    val image: Double,
    val title: Double,
    val url: Double,
    val structure: Double,
    val penalties: List<Penalty>
) {
    val penalty: Double get() = penalties.sumOf { it.value }
}

object ListScorer {

    /** 条目数到这个量级就算「够多」，再多不加分：首页 30 部已经翻不到底了 */
    private const val COUNT_FULL = 30.0

    /** 命中数是解析结果的三倍以上，说明选择器太宽，框进了大量取不到字段的节点 */
    private const val WIDE_RATIO = 3.0

    private const val DUP_TITLE_LIMIT = 0.25
    private const val DUP_URL_LIMIT = 0.10

    /** 低于这个一致性就认为「这组节点不是一个模板渲染出来的」 */
    private const val STRUCTURE_FLOOR = 0.5

    fun score(facts: ListFacts, weights: ScoreWeights = ScoreWeights.HOME): ListScore {
        val count = countScore(facts.itemCount)
        val image = ratio(facts.imageValid, facts.matchedCount)
        val title = ratio(facts.titleValid, facts.matchedCount)
        val url = ratio(facts.urlValid, facts.matchedCount)
        val structure = facts.structureConsistency.coerceIn(0.0, 1.0)

        val penalties = buildList {
            if (facts.boilerplate) {
                add(Penalty("落在导航/页脚区域", 0.30))
            }

            // 选择器过宽：命中 100 个节点只解析出 10 条，剩下 90 个是杂质。
            // 这里必须用原始比值，不能走 ratio() —— 那个函数会截到 0..1，过宽就永远判不出来
            val wide = facts.matchedCount.toDouble() / maxOf(1, facts.itemCount)
            if (wide > WIDE_RATIO) {
                add(Penalty("选择器过宽", minOf(0.15, (wide - WIDE_RATIO) * 0.02)))
            }

            val duplicateTitles = 1.0 - ratio(facts.distinctTitles, facts.itemCount)
            if (duplicateTitles > DUP_TITLE_LIMIT) {
                add(Penalty("标题重复", minOf(0.20, (duplicateTitles - DUP_TITLE_LIMIT) * 0.4)))
            }

            // 地址重复比标题重复更严重：同一个详情页出现两次，用户翻目录会看到重复项
            val duplicateUrls = 1.0 - ratio(facts.distinctUrls, facts.itemCount)
            if (duplicateUrls > DUP_URL_LIMIT) {
                add(Penalty("地址重复", minOf(0.20, (duplicateUrls - DUP_URL_LIMIT) * 0.5)))
            }

            if (facts.iconCovers > 0) {
                add(Penalty("封面是图标/广告", minOf(0.30, ratio(facts.iconCovers, facts.matchedCount) * 0.6)))
            }

            if (structure < STRUCTURE_FLOOR) {
                add(Penalty("条目结构不一致", minOf(0.30, (STRUCTURE_FLOOR - structure) * 0.6)))
            }

            // 章节场景：标题完全不像章节，多半是作者/题材/推荐位链接
            facts.chapterLike?.let { like ->
                if (like < 0.5) add(Penalty("标题不像章节", minOf(0.25, (0.5 - like) * 0.5)))
            }
        }

        val base = weights.count * count +
            weights.image * image +
            weights.title * title +
            weights.url * url +
            weights.structure * structure
        // 只截下限，不截上限：这个分数是排序键，不是概率。
        // 曾经截到 1.0，结果「25 条」和「10 条」两个候选都被加成顶到天花板，
        // 排序退化成插入顺序，页面上先出现的小板块就赢了 —— 和加法本身无关，
        // 是上限把仅剩的区分度抹掉了。
        val total = (base + facts.semantic + facts.originBonus - penalties.sumOf { it.value })
            .coerceAtLeast(0.0)

        return ListScore(total, count, image, title, url, structure, penalties)
    }

    /**
     * 条目数量得分：对数饱和而不是线性。
     * 线性会让「60 条广告位」稳赢「30 部作品」，而对数只把 30 和 60 拉开一点点 ——
     * 反正用户都翻不到底。
     */
    private fun countScore(count: Int): Double {
        if (count <= 0) return 0.0
        return (ln(1.0 + count) / ln(1.0 + COUNT_FULL)).coerceAtMost(1.0)
    }

    private fun ratio(part: Int, whole: Int): Double =
        if (whole <= 0) 0.0 else (part.toDouble() / whole).coerceIn(0.0, 1.0)
}
