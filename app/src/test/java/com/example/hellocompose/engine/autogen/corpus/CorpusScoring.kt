package com.example.hellocompose.engine.autogen.corpus

import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.remote.PageParser
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.engine.autogen.PageFetcher
import com.example.hellocompose.engine.autogen.SourceAutoGenerator
import kotlinx.coroutines.runBlocking
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document

/**
 * 识别器的一个阶段。
 *
 * 拆成六个而不是只统计「整站成功/失败」，是因为失败会传染：首页选错列表，
 * 后面章节和图片必然一起错，只看最终成功率永远只能得出「这站不行」，
 * 无法回答「到底是首页还是正文拖后腿」。分阶段之后，同一个 60% 会立刻暴露成
 * 「首页 9/9、标题 6/9」，也就是标题规则该改。
 */
enum class Stage(val label: String) {
    HOME("首页列表"),
    TITLE("标题提取"),
    COVER("封面提取"),
    CHAPTERS("章节提取"),
    IMAGES("图片地址"),
    CONFIG("完整配置");

    /** 排序后的前五项是「数据阶段」，最后一项是汇总 */
    val isData: Boolean get() = this != CONFIG
}

/**
 * 一个阶段的判定结果。
 *
 * `passed` 是这一关的通过与否，`numerator / denominator` 是条目级准确率，
 * 两者都要看：通过但只有 0.96，说明「勉强过」；不通过而 0.85，说明差一点点。
 */
data class StageResult(
    val passed: Boolean,
    val numerator: Int,
    val denominator: Int,
    val detail: String
) {
    val accuracy: Double
        get() = if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    companion object {
        fun fail(detail: String) = StageResult(false, 0, 1, detail)
    }
}

data class SiteRun(
    val site: CorpusSite,
    val stages: Map<Stage, StageResult>,
    val steps: List<String>,
    val configJson: String?,
    /** 反爬样本：识别确实失败了，而且原因说得对 */
    val blockedAsExpected: Boolean
) {
    /** 第一个没过的数据阶段 —— 这一站的主要卡点 */
    val blocker: Stage?
        get() = Stage.entries.firstOrNull { it.isData && stages[it]?.passed == false }

    val fullPassed: Boolean get() = stages[Stage.CONFIG]?.passed == true
}

/**
 * 把「识别结果」和「人工标准答案」逐项比对。
 *
 * 判定按**效果**而不是**选择器字符串**：`div.list > div.item` 和 `.list .item`
 * 选中的是同一批节点，只要抓出来的数据一样就是对的。比字符串会逼着实现去猜
 * 某一个人的写法，改一点结构就红一片，那种测试跑几次就没人看了。
 */
object CorpusScoring {

    /** 首页允许的误差：漏一两部或者多几条推荐位，不影响使用 */
    private const val HOME_MIN_JACCARD = 0.90

    /** 标题/封面是逐条比对的，允许极少量脏数据 */
    private const val ITEM_MIN_RATIO = 0.95

    private const val CHAPTER_MIN_JACCARD = 0.90

    fun run(site: CorpusSite): SiteRun {
        val steps = mutableListOf<String>()
        val generator = SourceAutoGenerator(CorpusPageFetcher(site))
        val outcome = runBlocking { generator.generate(site.input) { steps += it } }

        if (site.blocked) {
            val error = (outcome as? SourceAutoGenerator.Outcome.Failed)?.error
            val reason = error?.detail.orEmpty()
            val ok = error != null && BLOCK_HINTS.any { reason.contains(it) }
            val fail = StageResult.fail(
                if (error == null) "应当识别失败，却生成了配置" else "已按预期拒绝：$reason"
            )
            return SiteRun(
                site = site,
                stages = Stage.entries.associateWith { fail },
                steps = steps,
                configJson = null,
                blockedAsExpected = ok
            )
        }

        if (outcome !is SourceAutoGenerator.Outcome.Success) {
            val reason = when (outcome) {
                is SourceAutoGenerator.Outcome.Failed ->
                    "${outcome.error.type}：${outcome.error.detail}"
                is SourceAutoGenerator.Outcome.NeedsConfirmation -> "要求确认不安全连接：${outcome.request.message}"
                else -> "未知结果"
            }
            val fail = StageResult.fail(reason)
            return SiteRun(
                site = site,
                stages = Stage.entries.associateWith { fail },
                steps = steps,
                configJson = null,
                blockedAsExpected = false
            )
        }

        val result = outcome.result
        val config = result.config
        val gold = site.goldHome(config.baseUrl)

        val home = scoreHome(site, config, gold)
        val title = scoreItems(gold, home.matched, title = true)
        val cover = scoreItems(gold, home.matched, title = false)
        val chapters = scoreChapters(site, config)
        val images = scoreImages(site, config)

        val stages = linkedMapOf(
            Stage.HOME to home.stage,
            Stage.TITLE to title,
            Stage.COVER to cover,
            Stage.CHAPTERS to chapters,
            Stage.IMAGES to images
        )
        val allPassed = stages.values.all { it.passed }
        val firstFail = stages.entries.firstOrNull { !it.value.passed }
        stages[Stage.CONFIG] = StageResult(
            passed = allPassed,
            numerator = if (allPassed) 1 else 0,
            denominator = 1,
            detail = if (allPassed) {
                "首页 → 目录 → 正文全部对得上"
            } else {
                "卡在${firstFail!!.key.label}：${firstFail.value.detail}"
            }
        )
        return SiteRun(site, stages, steps, result.json, blockedAsExpected = false)
    }

    // ==================== 首页 ====================

    private data class HomeScore(val stage: StageResult, val matched: List<Pair<Manga, CorpusSite.GoldItem>>)

    private fun scoreHome(
        site: CorpusSite,
        config: SourceConfig,
        gold: List<CorpusSite.GoldItem>
    ): HomeScore {
        val doc = site.document("home.html", site.input)
        val generated = PageParser.list(doc, config.home, config.baseUrl)

        val genByUrl = generated.associateBy { key(it.detailUrl) }
        val goldByUrl = gold.associateBy { key(it.url) }
        val shared = goldByUrl.keys.intersect(genByUrl.keys)
        val union = goldByUrl.keys.union(genByUrl.keys)

        val matched = shared.mapNotNull { url -> genByUrl[url]?.let { it to goldByUrl.getValue(url) } }
        val jaccard = if (union.isEmpty()) 0.0 else shared.size.toDouble() / union.size
        val missing = goldByUrl.keys - genByUrl.keys
        val extra = genByUrl.keys - goldByUrl.keys

        // baseUrl 必须落到作品链接真正所在的域名上：镜像站的首页在 portal、作品在 mirror，
        // baseUrl 认错就会让所有相对地址解析到错误主机，而条数看起来完全正常
        val dominantHost = gold.mapNotNull { hostOf(it.url) }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
        val baseAligned = dominantHost == null || hostOf(config.baseUrl) == dominantHost

        val detail = buildString {
            append("生成 ${generated.size} / 标准 ${gold.size}，命中 ${shared.size}")
            if (missing.isNotEmpty()) append("，漏 ${missing.size}（${missing.take(2).joinToString()}）")
            if (extra.isNotEmpty()) append("，多 ${extra.size}（${extra.take(2).joinToString()}）")
            if (!baseAligned) append("，baseUrl 应为 $dominantHost 实为 ${hostOf(config.baseUrl)}")
        }
        val passed = gold.isNotEmpty() &&
            baseAligned &&
            generated.size >= site.home.expectedCount * HOME_MIN_JACCARD &&
            jaccard >= HOME_MIN_JACCARD
        return HomeScore(StageResult(passed, shared.size, union.size.coerceAtLeast(1), detail), matched)
    }

    // ==================== 标题 / 封面 ====================

    private fun scoreItems(
        gold: List<CorpusSite.GoldItem>,
        matched: List<Pair<Manga, CorpusSite.GoldItem>>,
        title: Boolean
    ): StageResult {
        val total = gold.size
        val hits = matched.count { (gen, g) ->
            val actual = if (title) gen.title else gen.coverUrl
            val expected = if (title) g.title else g.cover
            CorpusSite.norm(actual) == CorpusSite.norm(expected)
        }
        val wrong = matched.filter { (gen, g) ->
            val actual = if (title) gen.title else gen.coverUrl
            val expected = if (title) g.title else g.cover
            CorpusSite.norm(actual) != CorpusSite.norm(expected)
        }
        val ratio = if (total == 0) 0.0 else hits.toDouble() / total
        val detail = buildString {
            append("$hits/$total 条一致")
            if (wrong.isNotEmpty()) {
                val (gen, g) = wrong.first()
                val actual = if (title) gen.title else gen.coverUrl
                val expected = if (title) g.title else g.cover
                append("，首例：期望「$expected」实得「$actual」")
            }
        }
        return StageResult(total > 0 && ratio >= ITEM_MIN_RATIO, hits, total.coerceAtLeast(1), detail)
    }

    // ==================== 章节 ====================

    private fun scoreChapters(site: CorpusSite, config: SourceConfig): StageResult {
        var hits = 0
        var unionSize = 0
        var worst = 1.0
        val notes = mutableListOf<String>()

        for (page in site.detail.pages) {
            val gold = site.goldChapters(page.page, config.baseUrl)
            val doc = site.document(page.page, "${site.baseUrl}/")
            val generated = PageParser.chapters(doc, config.detail, config.baseUrl)
            val genUrls = generated.map { key(it.url) }.toSet()
            val goldUrls = gold.map { key(it) }.toSet()
            val shared = genUrls.intersect(goldUrls)
            val union = genUrls.union(goldUrls)

            hits += shared.size
            unionSize += union.size
            val jaccard = if (union.isEmpty()) 0.0 else shared.size.toDouble() / union.size
            worst = minOf(worst, jaccard)
            if (shared.size != goldUrls.size) {
                notes += "${page.page} 漏 ${goldUrls.size - shared.size} 章（生成 ${genUrls.size}/标准 ${goldUrls.size}）"
            }
        }

        val detail = if (notes.isEmpty()) {
            "${site.detail.pages.size} 个详情页共 $hits 章全部命中"
        } else {
            notes.joinToString("；")
        }
        return StageResult(
            passed = unionSize > 0 && worst >= CHAPTER_MIN_JACCARD,
            numerator = hits,
            denominator = unionSize.coerceAtLeast(1),
            detail = detail
        )
    }

    // ==================== 图片 ====================

    private fun scoreImages(site: CorpusSite, config: SourceConfig): StageResult {
        val gold = site.goldImages(config.baseUrl)
        val doc = site.document(site.reader.page, "${site.baseUrl}/")
        val generated = PageParser.images(doc, config.reader, config.baseUrl)

        // 顺序也算：正文图顺序错了，读起来就是乱的，即便地址一个不差
        val positional = minOf(generated.size, gold.size)
        val same = (0 until positional).count { key(generated[it]) == key(gold[it]) }

        val detail = buildString {
            append("生成 ${generated.size} / 标准 ${gold.size}，位置一致 $same")
            generated.zip(gold).firstOrNull { (a, b) -> key(a) != key(b) }?.let { (a, b) ->
                append("；首次偏离：期望 ${b.substringAfterLast('/')} 实得 ${a.substringAfterLast('/')}")
            }
        }
        val passed = gold.isNotEmpty() && generated.size == gold.size && same == gold.size
        return StageResult(passed, same, gold.size.coerceAtLeast(1), detail)
    }

    // ==================== 工具 ====================

    /** 比对用的地址归一：只去掉末尾斜杠，其他一律不动（查询串是有意义的） */
    private fun key(url: String): String = url.trim().trimEnd('/')

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.removePrefix("www.")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private val BLOCK_HINTS = listOf("403", "人机验证", "Cloudflare", "登录")
}

/**
 * 按路由表把请求映射到固定 HTML，不发网络请求。
 *
 * 反爬样本（`expected: blocked`）一律返回 403：这类站点的关键行为就是
 * 「不要硬猜一份配置」，所以夹具只需要如实复现「打不开」。
 */
private class CorpusPageFetcher(private val site: CorpusSite) : PageFetcher {

    override fun get(
        url: String,
        referer: String,
        trustAllCerts: Boolean,
        allowPlainHttp: Boolean
    ): Document {
        if (site.blocked) throw HttpStatusException("HTTP 403 Forbidden", 403, url)

        val pathAndQuery = pathAndQueryOf(url)
        val route = site.routes.firstOrNull { it.matches(pathAndQuery) }
            ?: throw HttpStatusException("语料没有登记 $pathAndQuery", 404, url)
        return site.document(route.page, url)
    }

    private fun pathAndQueryOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "/" else afterScheme.substring(slash).ifEmpty { "/" }
    }
}
