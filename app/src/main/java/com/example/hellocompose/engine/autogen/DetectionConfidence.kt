package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.source.ConfidenceLevel
import com.example.hellocompose.data.source.SourceConfidence

/**
 * 一项打分依据。
 *
 * 保留 [note] 是为了「失败可解释」：用户看到首页置信度 0.42，
 * 展开能看到是「列表条目数量 3 部」还是「标题有效率 0.3」拖低的分，
 * 而不是只得到一个莫名其妙的数字。
 */
data class ConfidenceFactor(
    val name: String,
    val weight: Double,
    val score: Double,
    val note: String
)

/** 某一阶段的置信度：加权总分 + 逐项依据 */
data class StageConfidence(
    val score: Double,
    val factors: List<ConfidenceFactor>
) {
    val level: ConfidenceLevel
        get() = when {
            score >= 0.80 -> ConfidenceLevel.HIGH
            score >= 0.55 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

    /** 最拖后腿的一项，用来回答「哪一步最可疑」 */
    val weakest: ConfidenceFactor?
        get() = factors.minByOrNull { it.score * it.weight }
}

/**
 * 一次识别的完整置信度。
 *
 * 结构刻意与写进 JSON 的 [SourceConfidence] 分开：
 * 配置里只存 4 个数（够用、够稳定），而识别过程要保留逐项依据给用户看。
 */
data class DetectionConfidence(
    val home: StageConfidence,
    val detail: StageConfidence,
    val reader: StageConfidence,
    val overall: Double
) {
    val level: ConfidenceLevel
        get() = when {
            overall >= 0.80 -> ConfidenceLevel.HIGH
            overall >= 0.55 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }

    fun toSourceConfidence(): SourceConfidence = SourceConfidence(
        overall = overall,
        home = home.score,
        detail = detail.score,
        reader = reader.score
    )

    /** 三个阶段里最拖后腿的一项，用来回答「先改哪一段」 */
    val weakest: ConfidenceFactor?
        get() = listOf(home, detail, reader)
            .mapNotNull { it.weakest }
            .minByOrNull { it.score * it.weight }

    /** 一句话总结，给识别进度和日志用 */
    fun summary(): String {
        val stage = when (overall) {
            in 0.80..1.0 -> "可以放心用"
            in 0.55..0.80 -> "能用，建议先试抓一次"
            else -> "把握不大，可能需要手改选择器"
        }
        return "置信度 ${(overall * 100).toInt()}%（${stage}）"
    }
}

/**
 * 打分器。
 *
 * 权重集中在这里，别散到各阶段的打分函数里：真实站点校准后要改的是这几个数，
 * 改完能一眼看出影响范围。目前的取值是「够用」的初始值——
 * 首页 5 项各占 20%（与设计稿一致），下游两个阶段按「越接近正文越重要」加权。
 */
object ConfidenceScorer {

    /** 首页 5 项，权重之和为 1 */
    private const val W_ITEM_COUNT = 0.20
    private const val W_TITLE_FILLED = 0.20
    private const val W_LINK_VALID = 0.20
    private const val W_COVER_VALID = 0.20
    private const val W_DOWNSTREAM = 0.20

    /** 三阶段对整体的贡献：正文最重要，因为「目录对、图打不开」等于不能用 */
    private const val W_HOME = 0.30
    private const val W_DETAIL = 0.35
    private const val W_READER = 0.35

    /** 「只有搜索」的配置首页必然为空，整体上扣一点，提醒用户这不是完整站点 */
    private const val SEARCH_ONLY_PENALTY = 0.08

    fun home(
        itemCount: Int,
        titleFilled: Double,
        linkValid: Double,
        coverValid: Double,
        downstreamOk: Boolean
    ): StageConfidence {
        val factors = listOf(
            ConfidenceFactor(
                "列表条目数量",
                W_ITEM_COUNT,
                countScore(itemCount),
                "$itemCount 部作品"
            ),
            ConfidenceFactor(
                "标题有效率",
                W_TITLE_FILLED,
                titleFilled.coerceIn(0.0, 1.0),
                "书名非空的占比 ${percent(titleFilled)}"
            ),
            ConfidenceFactor(
                "URL 有效率",
                W_LINK_VALID,
                linkValid.coerceIn(0.0, 1.0),
                "详情页地址可用的占比 ${percent(linkValid)}"
            ),
            ConfidenceFactor(
                "图片有效率",
                W_COVER_VALID,
                coverValid.coerceIn(0.0, 1.0),
                "封面地址可用的占比 ${percent(coverValid)}"
            ),
            ConfidenceFactor(
                "详情页及章节验证",
                W_DOWNSTREAM,
                if (downstreamOk) 1.0 else 0.0,
                if (downstreamOk) "已实际打开详情页和第一章" else "详情页或第一章没验证通过"
            )
        )
        return StageConfidence(weighted(factors), factors)
    }

    fun detail(
        chapterCount: Int,
        chapterLikeRatio: Double,
        reachable: Boolean
    ): StageConfidence {
        val countScore = when {
            chapterCount >= 10 -> 1.0
            chapterCount >= 3 -> 0.85
            chapterCount >= 1 -> 0.55
            else -> 0.0
        }
        val factors = listOf(
            ConfidenceFactor("章节数量", 0.40, countScore, "$chapterCount 个章节"),
            ConfidenceFactor(
                "章节名有效",
                0.30,
                chapterLikeRatio.coerceIn(0.0, 1.0),
                "像章节名的占比 ${percent(chapterLikeRatio)}"
            ),
            ConfidenceFactor(
                "第一章可达",
                0.30,
                if (reachable) 1.0 else 0.0,
                if (reachable) "第一章能打开且抓到了图片" else "第一章打不开或没有图片"
            )
        )
        return StageConfidence(weighted(factors), factors)
    }

    fun reader(
        imageCount: Int,
        distinctRatio: Double,
        reachable: Boolean
    ): StageConfidence {
        val countScore = when {
            imageCount >= 15 -> 1.0
            imageCount >= 5 -> 0.90
            imageCount >= 1 -> 0.70
            else -> 0.0
        }
        val factors = listOf(
            ConfidenceFactor("图片数量", 0.45, countScore, "$imageCount 张"),
            ConfidenceFactor(
                "文件名去重率",
                0.35,
                distinctRatio.coerceIn(0.0, 1.0),
                "去重率 ${percent(distinctRatio)}（低说明抓到的是封面墙）"
            ),
            ConfidenceFactor(
                "图片地址有效",
                0.20,
                if (reachable) 1.0 else 0.0,
                if (reachable) "地址可访问" else "地址为空或不是绝对地址"
            )
        )
        return StageConfidence(weighted(factors), factors)
    }

    fun overall(
        home: StageConfidence,
        detail: StageConfidence,
        reader: StageConfidence,
        searchOnly: Boolean = false
    ): Double {
        val raw = home.score * W_HOME + detail.score * W_DETAIL + reader.score * W_READER
        val adjusted = if (searchOnly) raw - SEARCH_ONLY_PENALTY else raw
        return adjusted.coerceIn(0.0, 1.0)
    }

    private fun weighted(factors: List<ConfidenceFactor>): Double =
        factors.sumOf { it.weight * it.score.coerceIn(0.0, 1.0) }.coerceIn(0.0, 1.0)

    /** 条目数量不是越多越好，够用就满分；低于 4 条基本可以认为结构找错了 */
    private fun countScore(count: Int): Double = when {
        count >= 20 -> 1.0
        count >= 8 -> 0.85
        count >= 4 -> 0.60
        count >= 1 -> 0.35
        else -> 0.0
    }

    private fun percent(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100).toInt()}%"
}
