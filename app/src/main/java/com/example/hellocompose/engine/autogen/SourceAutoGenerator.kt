package com.example.hellocompose.engine.autogen

import com.example.hellocompose.core.AppConfig
import com.example.hellocompose.core.AppError
import com.example.hellocompose.core.EmptyPageException
import com.example.hellocompose.core.toAppError
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.remote.JsoupHttpClient
import com.example.hellocompose.data.remote.PageParser
import com.example.hellocompose.data.remote.TlsException
import com.example.hellocompose.data.remote.TlsProblem
import com.example.hellocompose.data.source.DetailRule
import com.example.hellocompose.data.source.Dom
import com.example.hellocompose.data.source.FieldRule
import com.example.hellocompose.data.source.ListRule
import com.example.hellocompose.data.source.ReaderRule
import com.example.hellocompose.data.source.ScriptData
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.data.source.SourceJson
import com.example.hellocompose.data.source.SourceParser
import com.example.hellocompose.data.source.NO_HOME_LIST_SELECTOR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/**
 * 自动生成站点配置：用户只输入一个首页地址，剩下的选择器由程序推出来。
 *
 * 走三步，每一步都用上一页的真实数据推进：
 * 首页 → 找列表 → 点进第一本 → 找章节列表 → 点进第一章 → 找图片。
 *
 * 三个设计原则：
 * 1. **必须真连真抓**。识别只靠静态 HTML，站点结构再规范也可能被 JS 渲染或反爬拦住，
 *    所以任何一步失败都要如实告诉用户，而不是生成一份看起来完整、实际抓不到东西的配置。
 * 2. **不安全连接绝不自动降级**。以前这里会依次尝试 https → https(放宽证书) → http →
 *    http(放宽证书)，把中间人攻击的入口开在了最常走的失败路径上。现在只走严格 HTTPS，
 *    一旦证书不可信或站点根本不支持 HTTPS，就把证据和选择权交回用户（[Outcome.NeedsConfirmation]）。
 * 3. **产物要能自检**。生成的 JSON 会再走一遍 [SourceParser]，确保用户保存时不会遇到
 *    在识别阶段没暴露出来的格式问题。
 */
class SourceAutoGenerator(private val pages: PageFetcher) {

    /** 常规构造：真连真抓。测试用注入的 [PageFetcher] 走同一套识别代码 */
    constructor(http: JsoupHttpClient) : this(PageFetcher.http(http))

    data class Report(
        val baseUrl: String,
        val siteName: String,
        val homeCount: Int,
        val homeSamples: List<String>,
        val chapterCount: Int,
        val chapterSamples: List<String>,
        val imageCount: Int,
        val imageSamples: List<String>,
        /** 是否推出了可用的搜索地址 */
        val searchPath: String? = null,
        /** 非空表示这份配置带着用户确认过的安全例外，界面必须醒目提示 */
        val insecure: InsecurePolicy?,
        /** 这套选择器有多可信。分阶段保留依据，方便回答「哪一步最可疑」 */
        val confidence: DetectionConfidence,
        val warnings: List<String>
    )

    data class Result(
        val config: SourceConfig,
        val json: String,
        val report: Report
    )

    /**
     * 识别结果。
     *
     * 比 `AppResult` 多一种状态：[NeedsConfirmation]。证书问题不是"失败"——
     * 用户可能有正当理由（自己的站点用自签证书），系统不该替他决定，
     * 也不该把这件事混进"重试"里。
     */
    sealed interface Outcome {
        data class Success(val result: Result) : Outcome
        data class NeedsConfirmation(val request: InsecureRequest) : Outcome
        data class Failed(val error: AppError) : Outcome
    }

    suspend fun generate(
        input: String,
        policy: InsecurePolicy = InsecurePolicy.STRICT,
        onStep: (String) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            Outcome.Success(detect(input, policy, onStep))
        } catch (e: CancellationException) {
            throw e
        } catch (e: InsecureRequiredException) {
            Outcome.NeedsConfirmation(e.request)
        } catch (e: Exception) {
            Outcome.Failed(e.toAppError())
        }
    }

    private fun detect(input: String, policy: InsecurePolicy, onStep: (String) -> Unit): Result {
        val (baseUrl, path) = normalize(input)
        onStep("打开首页")

        val (homeDoc, resolvedBase, activePolicy) = loadHome(baseUrl, path, policy, onStep)
        val warnings = mutableListOf<String>()

        val homeGuesses = SourceAutoDetector.detectHome(homeDoc, resolvedBase)

        // ---- 首页没有可用的漫画列表 ----
        // 不直接判死：有些站首页（或它的"排行榜"页）整屏由脚本渲染，列表在 HTML 里根本不存在，
        // 但站内搜索是好用的。这种情况下让用户通过搜索看，比只报一句"找不到列表"有用得多。
        // 前提是目录和图片必须真的能抓 —— 否则就是给用户一个空壳配置。
        if (homeGuesses.isEmpty()) {
            onStep("首页没找到列表，试试搜索")
            val entry = detectSearchEntry(homeDoc, resolvedBase, activePolicy, onStep)
                ?: throw EmptyPageException(
                    "首页没有可用的漫画列表，站内搜索也拿不到结果。" +
                        "这个站点的内容可能完全由脚本动态渲染，当前版本抓不到"
                )

            val chapterGuess = detectChapterStage(entry.items.map { it.detailUrl }, resolvedBase, activePolicy, onStep)
            val readerGuess = detectReaderStage(chapterGuess.items.first().url, resolvedBase, activePolicy, onStep)

            warnings += NO_HOME_LIST_HINT
            warnings += activePolicy.warnings

            val host = SourceAutoDetector.hostOf(resolvedBase) ?: "site"
            val config = SourceConfig(
                id = slug(host),
                name = siteName(homeDoc, host),
                baseUrl = resolvedBase,
                enabled = true,
                description = "自动识别生成（仅搜索）。该站点首页列表由脚本渲染，抓不到排行，请用搜索找漫画。",
                userAgent = AppConfig.USER_AGENT,
                timeoutMs = AppConfig.TIMEOUT_MS,
                referer = "{baseUrl}/",
                trustAllCerts = activePolicy.trustAllCerts,
                allowPlainHttp = activePolicy.allowPlainHttp,
                // 首页给一个永不匹配的选择器：抓不到内容，但节点合法，
                // App 首页会走空状态，引导用户去搜索
                home = ListRule(path = path, item = NO_LIST_SELECTOR),
                search = entry.rule,
                detail = chapterGuess.rule,
                reader = readerGuess.rule
            )
            return finish(config, resolvedBase, 0, emptyList(), chapterGuess, readerGuess, activePolicy, warnings)
        }

        val homeGuess = homeGuesses.first()
        // 镜像站的常见情况：首页在 .top，作品链接全指向 .cc。
        // 以作品链接的实际域名为准，否则详情页里的相对地址会被解析到错误域名。
        val effectiveBase = alignBase(resolvedBase, homeGuess, onStep)
        val finalHome = if (effectiveBase != resolvedBase) {
            SourceAutoDetector.detectHome(homeDoc, effectiveBase).firstOrNull() ?: homeGuess
        } else {
            homeGuess
        }
        onStep("首页识别到 ${finalHome.items.size} 部作品")

        // ---- 搜索：试几个常见的搜索地址，能用就写进配置 ----
        val searchRule = detectSearch(finalHome, effectiveBase, activePolicy, onStep)

        // ---- 详情页：找章节列表 ----
        val detailUrls = finalHome.items.map { it.detailUrl }.filter { it.startsWith("http") }
        if (detailUrls.isEmpty()) {
            throw EmptyPageException("首页作品没有可用的详情页链接，无法继续验证目录")
        }

        val chapterGuess = detectChapterStage(detailUrls, effectiveBase, activePolicy, onStep)
        val readerGuess = detectReaderStage(chapterGuess.items.first().url, effectiveBase, activePolicy, onStep)

        if (finalHome.items.size < 8) {
            warnings += "首页只识别到 ${finalHome.items.size} 部作品，列表可能不完整"
        }
        warnings += activePolicy.warnings

        val host = SourceAutoDetector.hostOf(effectiveBase) ?: "site"
        val config = SourceConfig(
            id = slug(host),
            name = siteName(homeDoc, host),
            baseUrl = effectiveBase,
            enabled = true,
            description = "自动识别生成。列表或图片不对时，直接改对应节点的选择器。",
            userAgent = AppConfig.USER_AGENT,
            timeoutMs = AppConfig.TIMEOUT_MS,
            referer = "{baseUrl}/",
            trustAllCerts = activePolicy.trustAllCerts,
            allowPlainHttp = activePolicy.allowPlainHttp,
            home = finalHome.rule.copy(path = path),
            search = searchRule,
            detail = chapterGuess.rule,
            reader = readerGuess.rule
        )
        return finish(
            config, effectiveBase, finalHome.items.size, finalHome.items,
            chapterGuess, readerGuess, activePolicy, warnings
        )
    }

    /**
     * 详情页必须真的能拿到章节列表。
     *
     * 这是硬条件而不是警告：章节拿不到，配置就是空壳，用户点进详情页只会看到空白，
     * 还得自己回来猜是哪一步错了。失败时说清楚卡在哪一步，比给一份"能保存但用不了"的配置有用。
     */
    private fun detectChapterStage(
        detailUrls: List<String>,
        baseUrl: String,
        policy: InsecurePolicy,
        onStep: (String) -> Unit
    ): SourceAutoDetector.ChapterGuess {
        var lastError: Exception? = null
        // 一部作品抓不到不代表整站不行：新上架的书、付费书、章节还没放出来的书，
        // 详情页可能就是空的。换一部再试，最多试 3 部
        for (detailUrl in detailUrls.take(3)) {
            try {
                return detectChaptersFor(detailUrl, baseUrl, policy, onStep)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw if (lastError is EmptyPageException) {
            lastError
        } else {
            EmptyPageException(
                "详情页没有可用的章节列表。该站点的章节可能是异步接口返回的，" +
                    "或者需要在 detail 节点手动补一个选择器"
            )
        }
    }

    private fun detectChaptersFor(
        detailUrl: String,
        baseUrl: String,
        policy: InsecurePolicy,
        onStep: (String) -> Unit
    ): SourceAutoDetector.ChapterGuess {
        onStep("打开详情页")
        val detailDoc = fetch(detailUrl, "$baseUrl/", policy)

        SourceAutoDetector.detectChapters(detailDoc, baseUrl).firstOrNull()?.let { guess ->
            onStep("识别到 ${guess.items.size} 个章节")
            return guess
        }

        // DOM 里抓不到：不少站把章节写在内联脚本的 JSON 里，页面上只留一个空容器。
        // 数据是真的，只是不在 DOM 里，所以换一条取值通道。
        val scriptGuesses = SourceAutoDetector.detectScriptChapters(detailDoc, baseUrl)
        if (scriptGuesses.isNotEmpty()) {
            onStep("章节在脚本数据里，验证 ${scriptGuesses.size} 种地址规则")
        }
        for (guess in scriptGuesses) {
            // 地址模板是猜出来的，必须真的能打开第一章、并且抓得到图才算数
            val reachable = runCatching {
                val doc = fetch(guess.items.first().url, detailUrl, policy)
                SourceAutoDetector.detectReaders(doc, baseUrl).any { it.images.isNotEmpty() }
            }.getOrDefault(false)
            if (!reachable) continue

            onStep("识别到 ${guess.items.size} 个章节（脚本数据）")
            return SourceAutoDetector.ChapterGuess(
                rule = DetailRule(item = "", script = guess.rule),
                score = 0.0,
                items = guess.items,
                chapterLikeRatio = 1.0
            )
        }

        throw EmptyPageException(
            "详情页没有可用的章节列表。该站点的章节可能是异步接口返回的，" +
                "或者需要在 detail 节点手动补一个选择器"
        )
    }

    /** 阅读页必须真的能拿到图片，否则"能搜索、能进目录、就是看不了图"，没有意义 */
    private fun detectReaderStage(
        chapterUrl: String,
        baseUrl: String,
        policy: InsecurePolicy,
        onStep: (String) -> Unit
    ): SourceAutoDetector.ReaderGuess {
        onStep("打开第一章")
        // Referer 用章节页自身：多数防盗链站点校验的是同源
        val chapterDoc = fetch(chapterUrl, chapterUrl, policy)
        val guess = SourceAutoDetector.detectReaders(chapterDoc, baseUrl).firstOrNull()
            ?.takeIf { it.images.isNotEmpty() }
            ?: throw EmptyPageException(readerFailureReason(chapterDoc))
        onStep("识别到 ${guess.images.size} 张图片")
        return guess
    }

    /**
     * 阅读页抓不到图时，说清楚是哪一种情况 —— 用户能不能自己补选择器，取决于原因。
     *
     * 网页正文容器是空的、同时页面里引着 `reader_grant` / `loadContent(` 这类脚本入口，
     * 说明正文是接口另行下发（付费站常见，有的还会加密），补选择器也没用。
     */
    private fun readerFailureReason(doc: Document): String {
        val html = doc.html()
        val scripted = SCRIPTED_READER.containsMatchIn(html)
        val emptyBody = doc.select("article, #article, #content, #chaptercontent, .content, #booktxt")
            .any { it.text().isBlank() && it.select("img").isEmpty() }

        return if (scripted && emptyBody) {
            "章节正文由站点接口另行下发（部分站点还会加密），HTML 里没有图片。" +
                "这类站点当前版本抓不到，不是你填错了地址"
        } else {
            "章节页没有可用的图片。图片可能是相对路径或由脚本延迟加载，" +
                "可以在 reader 节点手动补一个选择器"
        }
    }

    /** 统一收尾：带上自检和置信度，保证产物能被正式解析器读回来 */
    private fun finish(
        config: SourceConfig,
        baseUrl: String,
        homeCount: Int,
        homeItems: List<Manga>,
        chapterGuess: SourceAutoDetector.ChapterGuess,
        readerGuess: SourceAutoDetector.ReaderGuess,
        policy: InsecurePolicy,
        warnings: List<String>
    ): Result {
        // 置信度在编码之前算：它要跟着配置一起落进 JSON，
        // 以后排查「这份配置当时是怎么来的」不用再靠记忆
        val confidence = confidenceOf(homeItems, chapterGuess, readerGuess, config.isSearchOnly)
        val withConfidence = config.copy(confidence = confidence.toSourceConfidence())

        val json = SourceJson.encode(withConfidence)
        SourceParser.parse(json, withConfidence.id)

        return Result(
            config = withConfidence,
            json = json,
            report = Report(
                baseUrl = baseUrl,
                siteName = withConfidence.name,
                homeCount = homeCount,
                homeSamples = homeItems.take(3).map { it.title },
                chapterCount = chapterGuess.items.size,
                chapterSamples = chapterGuess.items.take(3).map { it.title },
                imageCount = readerGuess.images.size,
                imageSamples = readerGuess.images.take(2).map { it.substringAfterLast('/') },
                searchPath = withConfidence.search?.path,
                insecure = policy.takeIf { !it.isStrict },
                confidence = confidence,
                warnings = warnings
            )
        )
    }

    /**
     * 汇总各阶段的置信度。
     *
     * 所有输入都是「已经真实发生的事实」（解析出的条数、填满率、是否打开过第一章），
     * 不做任何基于页面猜测的加分 —— 猜出来的分数比没有分数更容易误导人。
     */
    private fun confidenceOf(
        homeItems: List<Manga>,
        chapterGuess: SourceAutoDetector.ChapterGuess,
        readerGuess: SourceAutoDetector.ReaderGuess,
        searchOnly: Boolean
    ): DetectionConfidence {
        val size = homeItems.size
        fun ratio(predicate: (Manga) -> Boolean): Double =
            if (size == 0) 0.0 else homeItems.count(predicate).toDouble() / size

        val chapters = chapterGuess.items
        // 只有正文图也拿到，「首页 → 目录 → 图片」才算真的打通
        val downstreamOk = chapters.isNotEmpty() && readerGuess.images.isNotEmpty()

        val home = ConfidenceScorer.home(
            itemCount = size,
            titleFilled = ratio { it.title != PageParser.UNTITLED },
            linkValid = ratio { it.detailUrl.startsWith("http") },
            coverValid = ratio { it.coverUrl.startsWith("http") },
            downstreamOk = downstreamOk
        )
        val detail = ConfidenceScorer.detail(
            chapterCount = chapters.size,
            chapterLikeRatio = chapterGuess.chapterLikeRatio,
            reachable = readerGuess.images.isNotEmpty()
        )
        val images = readerGuess.images
        val distinct = if (images.isEmpty()) {
            0.0
        } else {
            images.map { it.substringAfterLast('/') }.distinct().size.toDouble() / images.size
        }
        val reader = ConfidenceScorer.reader(
            imageCount = images.size,
            distinctRatio = distinct,
            reachable = images.isNotEmpty()
        )

        return DetectionConfidence(
            home = home,
            detail = detail,
            reader = reader,
            overall = ConfidenceScorer.overall(home, detail, reader, searchOnly)
        )
    }

    /**
     * 首页没有列表时的入口：用站内搜索找一部作品，拿它去验证目录和图片能不能抓。
     *
     * 试验词的选法很关键。站名（域名里那段字母）看着最合理，实际经常一条都搜不到 ——
     * 站内搜索匹配的是作品名/作者，不是站点名。所以站名之后要退到「站里一定有作品的通用词」，
     * 只要有任意一个词能搜出结果，就说明这个搜索地址是通的。
     */
    private fun detectSearchEntry(
        homeDoc: Document,
        baseUrl: String,
        policy: InsecurePolicy,
        onStep: (String) -> Unit
    ): SearchEntry? {
        val name = siteName(homeDoc, SourceAutoDetector.hostOf(baseUrl) ?: "")
        val probes = buildList {
            add(name.removeSuffix("漫画").removeSuffix("漫畫").removeSuffix("在线"))
            add(name)
            // 通用词：任何一个能搜出结果就够了，用来确认"搜索地址通不通"
            addAll(GENERIC_PROBES)
        }.map { it.trim() }
            .filter { it.length in 2..12 }
            .distinct()

        for (template in SEARCH_PATHS) {
            for (probe in probes) {
                val attempt = searchOnce(template, probe, null, minSize = 2, baseUrl, policy)
                // 关键词在最终地址里没保住 → 该参数不被支持，返回的是默认列表
                if (!attempt.fetched || !attempt.keywordKept) continue
                val entry = attempt.entry ?: continue
                if (entry.items.size < 2) continue

                // 自举验证：首页没作品、手上没有第二本书名，就拿结果里第二本书的名字再搜一次。
                // 换了个词结果却一模一样，说明这个地址根本没用到关键词 ——
                // 很多站对不认识的参数直接返回热门列表，那页面和真实结果长得完全一样。
                val echo = probeKeyword(entry.items.getOrNull(1)?.title.orEmpty())
                if (echo.isNotBlank() && echo != probe) {
                    val verdict = keywordVerdict(template, echo, entry.items, null, baseUrl, policy)
                    // 证明不了关键词生效就不写进配置：宁可没有搜索，也不要一个永远返回热门列表的假搜索
                    if (verdict != KeywordVerdict.MATTERS) continue
                }

                onStep("搜索可用（$template），找到 ${entry.items.size} 部")
                return entry
            }
        }
        return null
    }

    /**
     * 从搜索页取出结果，DOM 和脚本两条路都试。
     *
     * 有的站搜索结果直接写死在 DOM 里，有的站只给一个空的 `<div id="serch">`
     * 和数据数组（只给空容器的那种就是后者）。对用户来说都是"能搜到"，不该区别对待。
     */
    private fun searchResultOf(
        doc: Document,
        baseUrl: String,
        template: String,
        minSize: Int
    ): SearchEntry? {
        val domGuess = SourceAutoDetector.detectHome(doc, baseUrl, minSize).firstOrNull()
        if (domGuess != null && domGuess.items.size >= minSize) {
            return SearchEntry(domGuess.rule.copy(path = template), domGuess.items)
        }

        for (scriptRule in SourceAutoDetector.detectScriptLists(doc, baseUrl, minSize)) {
            val items = ScriptData.list(doc, scriptRule, baseUrl)
            if (items.size < minSize) continue
            return SearchEntry(
                ListRule(path = template, item = "", script = scriptRule),
                items
            )
        }
        return null
    }

    private data class SearchEntry(val rule: ListRule, val items: List<Manga>)

    /**
     * 判断某个搜索地址到底有没有用上关键字。
     *
     * 只看「结果像个列表」是判不出来的：忽略关键字的站点会返回一屏热门/默认列表，
     * 长得和真实结果一模一样，而且热门里往往正好有首页那本书，看起来更像成功了。
     *
     * 唯一可靠的信号是**换个词，结果变不变**：
     * - 变 → 关键字生效（`MATTERS`）；哪怕变成空结果也算变，说明查无此书
     * - 不变 → 关键字被丢掉（`IGNORED`）
     * - 第二次没请求成功 → 无法判断（`UNKNOWN`），不能当成通过
     */
    private enum class KeywordVerdict { MATTERS, IGNORED, UNKNOWN }

    /**
     * 推出搜索地址。
     *
     * 三个难点：
     * 1. **怎么知道搜索真的生效**。有的站搜不到时重定向回首页，有的站给一屏无关推荐，
     *    还有的站（`/search?keyword=` 这种最常见形态）干脆忽略关键字、永远返回热门列表 ——
     *    这些页面看起来都是「一个正常的列表」。
     * 2. **搜索结果往往只有一两条**。识别器要求同一容器下至少 4 个条目才算「列表结构」，
     *    用识别器去看搜索结果页会直接判成「没有列表」。所以优先复用首页已验证的列表规则，
     *    不行再走识别器和内联脚本。
     * 3. 所以判据分两层：先用「换一个关键字结果变不变」做反向排除，
     *    再用「能不能搜回那本书」做正向确认。
     *
     * 试验关键字直接取首页前两部的标题：站点上确定存在这两本书。
     */
    private fun detectSearch(
        home: SourceAutoDetector.HomeGuess,
        baseUrl: String,
        policy: InsecurePolicy,
        onStep: (String) -> Unit
    ): ListRule? {
        val first = home.items.getOrNull(0)?.title?.trim().orEmpty()
        val second = home.items.getOrNull(1)?.title?.trim().orEmpty()
        if (first.isBlank()) return null

        val homeUrls = home.items.map { it.detailUrl }.toSet()
        if (homeUrls.isEmpty()) return null

        val keywordA = probeKeyword(first)
        val keywordB = probeKeyword(second)
        // 两本书名如果互相包含，就不是独立的对照组，换词验证没有意义
        val canCompare = keywordB.isNotBlank() &&
            !keywordB.contains(keywordA.take(3)) && !keywordA.contains(keywordB.take(3))

        for (template in SEARCH_PATHS) {
            val attemptA = searchOnce(template, keywordA, home, minSize = 1, baseUrl, policy)
            if (!attemptA.fetched) {
                onStep("跳过 $template：打不开")
                continue
            }
            // 关键词在最终地址里都没保住，说明这个参数不被支持（或跳去了登录页）
            if (!attemptA.keywordKept) {
                onStep("跳过 $template：参数被丢掉")
                continue
            }
            val itemsA = attemptA.entry?.items.orEmpty()
            if (itemsA.isEmpty()) {
                onStep("跳过 $template：没解析出结果")
                continue
            }

            // 结果条数够多、且几乎就是首页那一屏 → 关键字被忽略（多半重定向回首页）
            val overlap = itemsA.count { it.detailUrl in homeUrls }.toDouble() / itemsA.size
            if (itemsA.size >= 5 && overlap >= 0.7) {
                onStep("跳过 $template：结果就是首页那一屏")
                continue
            }

            val verdict = if (canCompare) keywordVerdict(template, keywordB, itemsA, home, baseUrl, policy)
            else KeywordVerdict.UNKNOWN

            val hitTarget = itemsA.any { it.detailUrl in homeUrls }
            when (verdict) {
                KeywordVerdict.IGNORED -> {
                    onStep("跳过 $template：换个关键词结果没变，关键字被忽略")
                    continue
                }
                KeywordVerdict.MATTERS -> {
                    if (hitTarget || itemsA.any { it.title.contains(first.take(3)) }) {
                        onStep("搜索可用（$template）")
                        return attemptA.entry!!.rule
                    }
                    // 关键字生效但连测试书都搜不回来，说明这个地址搜的不是作品名
                    onStep("跳过 $template：关键字生效但搜不到目标作品")
                    continue
                }
                KeywordVerdict.UNKNOWN -> {
                    // 无法换词验证时只剩一条硬证据：结果里确实有目标书，
                    // 且结果规模明显小于首页（默认列表一般不少于首页全部作品）。
                    if (hitTarget && itemsA.size < home.items.size) {
                        onStep("搜索可用（$template，未能换词验证）")
                        return attemptA.entry!!.rule
                    }
                    onStep("跳过 $template：无法验证关键字是否生效")
                    continue
                }
            }
        }
        return null
    }

    /** 用第二个关键词再搜一次，看结果是否随关键词变化 */
    private fun keywordVerdict(
        template: String,
        keywordB: String,
        itemsA: List<Manga>,
        home: SourceAutoDetector.HomeGuess?,
        baseUrl: String,
        policy: InsecurePolicy
    ): KeywordVerdict {
        val attemptB = searchOnce(template, keywordB, home, minSize = 1, baseUrl, policy)
        // 请求没成功 ≠ 关键字无效：这两种情况必须分开，否则会把真搜索判成假的
        if (!attemptB.fetched || !attemptB.keywordKept) return KeywordVerdict.UNKNOWN

        val itemsB = attemptB.entry?.items.orEmpty()
        // 换词后一条都搜不到，本身就是「关键字起作用了」的证据
        if (itemsB.isEmpty()) return KeywordVerdict.MATTERS
        return if (similarResults(itemsA, itemsB)) KeywordVerdict.IGNORED else KeywordVerdict.MATTERS
    }

    /** 用某个模板 + 关键词真的搜一次，同时处理 DOM / 内联脚本两种页面 */
    private fun searchOnce(
        template: String,
        keyword: String,
        home: SourceAutoDetector.HomeGuess?,
        minSize: Int,
        baseUrl: String,
        policy: InsecurePolicy
    ): SearchAttempt {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val pageUrl = baseUrl + template.replace("{keyword}", encoded)
        val doc = runCatching { fetch(pageUrl, "$baseUrl/", policy) }.getOrNull()
            ?: return SearchAttempt(null, keywordKept = false, fetched = false)

        // 最终的 URL 里还得留着关键词。
        // 这条比"结果看起来对不对"直接得多：不支持该参数的站点往往 301 到 /search，
        // 参数被丢掉，之后返回的都是热门/默认列表 —— 那种页面和真实结果长得一模一样。
        val finalUrl = runCatching { doc.location() }.getOrNull().orEmpty()
        val kept = finalUrl.contains(encoded) || finalUrl.contains(keyword)

        // 搜索结果页通常就是首页那套模板，先复用已验证的规则
        if (home != null) {
            val byHomeRule = PageParser.list(doc, home.rule, baseUrl)
            if (byHomeRule.isNotEmpty()) {
                return SearchAttempt(SearchEntry(home.rule.copy(path = template), byHomeRule), kept, true)
            }
        }
        return SearchAttempt(searchResultOf(doc, baseUrl, template, minSize), kept, true)
    }

    /** `fetched` 区分「请求就没成功」和「请求成功但没结果」——两者的含义完全相反 */
    private data class SearchAttempt(
        val entry: SearchEntry?,
        val keywordKept: Boolean,
        val fetched: Boolean
    )

    /**
     * 两次搜索的结果是不是同一批作品。
     *
     * 用 Jaccard（交集 / 并集）而不是「交集 / 较小的那个集合」：
     * 精确搜索常常只返回一两条，如果拿它当分母，只要这一条也在第一次结果里，
     * 相似度就是 100%，真搜索反而会被判成「关键字被忽略」。
     */
    private fun similarResults(a: List<Manga>, b: List<Manga>): Boolean {
        val setA = a.map { it.detailUrl }.toSet()
        val setB = b.map { it.detailUrl }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return false
        val union = setA.union(setB).size
        if (union == 0) return false
        return setA.intersect(setB).size.toDouble() / union >= 0.7
    }

    /**
     * 从列表标题里取出适合当搜索词的部分。
     *
     * 列表标题常常掺着别的东西：「姨见钟情 作者:FOE,JINGG」。
     * 直接截前几个字会得到「姨见钟情 作」，拿去搜什么也搜不到 —— 于是整站被判成没有搜索。
     */
    private fun probeKeyword(title: String, max: Int = 6): String {
        val t = title.trim()
        val head = t.substringBefore(' ').substringBefore('\u3000')
        val word = if (head.length >= 2) head else t
        // 截断时别把右半个括号留下（「意外的邂逅（真人漫画）」→「意外的邂逅（」搜不到「意外的邂逅」）
        return word.take(max).trimEnd('（', '(', '「', '《', '【', ' ', '\u3000')
    }

    /**
     * 让 baseUrl 对齐到作品链接实际所在的域名。
     *
     * 只在一个域名占绝对多数时切换，避免被页面里零星的外链带偏。
     */
    private fun alignBase(
        baseUrl: String,
        home: SourceAutoDetector.HomeGuess,
        onStep: (String) -> Unit
    ): String {
        val currentHost = SourceAutoDetector.hostOf(baseUrl) ?: return baseUrl
        val dominant = home.items
            .mapNotNull { SourceAutoDetector.hostOf(it.detailUrl) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?: return baseUrl

        if (dominant.key == currentHost) return baseUrl
        if (dominant.value < home.items.size * 0.6) return baseUrl

        onStep("作品链接在 ${dominant.key}，按这个域名继续")
        return "${baseUrl.substringBefore("://")}://${dominant.key}"
    }

    // ==================== 请求 ====================

    /**
     * 打开首页。**只走严格 HTTPS**，走不通时把「为什么走不通」交回给用户。
     *
     * 旧实现按 https → https(放宽证书) → http → http(放宽证书) 依次尝试，等于在任何一次
     * 证书异常上自动把校验关掉；甚至一个 502 都能让后续所有请求落到明文 HTTP。
     * 现在：
     * - 证书不可信 → 抛 [InsecureRequiredException]，带上证书详情，等用户确认；
     * - 站点不支持 HTTPS（443 上不讲 TLS / 连不上）→ 先探测明文 HTTP 是否可用，
     *   可用才提示用户「只能明文访问」，绝不自己切过去；
     * - 用户确认过策略后（policy 非严格），才按确认的方式连接。
     *
     * 返回的 policy 就是写进配置的那一份，保证正式抓取和识别走同一条路。
     */
    private fun loadHome(
        baseUrl: String,
        path: String,
        policy: InsecurePolicy,
        onStep: (String) -> Unit
    ): Triple<Document, String, InsecurePolicy> {
        val host = baseUrl.substringAfter("://")
        val pathPart = if (path.startsWith("/")) path else "/$path"
        val httpsUrl = "https://$host$pathPart"
        val httpUrl = "http://$host$pathPart"

        // 用户已经确认过：按确认的方式连，不再偷偷先试别的
        if (!policy.isStrict) {
            val url = if (policy.allowPlainHttp) httpUrl else httpsUrl
            val label = if (policy.allowPlainHttp) "明文 HTTP（已确认）" else "HTTPS，已确认放宽证书"
            val doc = fetch(url, "${url.substringBefore("://")}://$host/", policy)
            onStep("已连接（$label）")
            return Triple(doc, landedBase(doc, url, host), policy)
        }

        try {
            val doc = pages.get(httpsUrl, "https://$host/", trustAllCerts = false, allowPlainHttp = false)
            onStep("已连接（HTTPS）")
            // 以页面最终落地的地址为准。
            // 很多站把 http 301 到 https（也有反过来的），如果这里还用请求时的 scheme，
            // 后面每个相对地址都要再跳一次，偶尔跳转失败就会表现成"首页能开、搜索打不开"。
            return Triple(doc, landedBase(doc, httpsUrl, host), policy)
        } catch (e: HttpStatusException) {
            // 状态码是站点给出的答复，不是协议问题，绝不能拿它当降级到 http 的理由
            throw blockedMessage(e)
        } catch (e: TlsException) {
            // 证书不可信：不自动放宽，也不自动降级
            // 「不是 TLS」和「握手就断了」这两种，很可能只是这个站点根本不提供 HTTPS
            // （例如 443 上跑着明文服务），先确认明文通不通，再把结论告诉用户
            val maybeNoTls = e.problem == TlsProblem.NOT_TLS || e.problem == TlsProblem.HANDSHAKE_FAILED
            if (maybeNoTls && httpReachable(httpUrl)) {
                throw InsecureRequiredException(plainHttpRequest(host, tls = e))
            }
            throw InsecureRequiredException(certRequest(host, e))
        } catch (e: IOException) {
            // 443 根本没通（站点只监听 80、或者被拦）。同样不自己切，
            // 先确认明文能不能用，再让用户决定
            if (httpReachable(httpUrl)) throw InsecureRequiredException(plainHttpRequest(host, tls = null))
            throw e
        }
    }

    /**
     * 探测明文 HTTP 是否可用。
     *
     * **只用于判断，内容一律丢弃**：用户还没同意以明文访问，这里拿到的页面不能当成数据用；
     * 确认之后会按策略重新取一次。
     */
    private fun httpReachable(url: String): Boolean = runCatching {
        pages.get(url, referer = "", trustAllCerts = false, allowPlainHttp = true)
        true
    }.getOrDefault(false)

    private fun certRequest(host: String, e: TlsException): InsecureRequest {
        val detail = buildString {
            append(e.detailText)
            append("\n\n").append(e.problem.advice)
            append("\n\n继续的话，App 会只对这个站点关闭证书校验：内容可能被第三方看到或篡改。")
        }
        return InsecureRequest(
            kind = InsecureRequest.Kind.CERT_INVALID,
            host = host,
            message = e.userMessage,
            detail = detail,
            policy = InsecurePolicy(trustAllCerts = true)
        )
    }

    private fun plainHttpRequest(host: String, tls: TlsException?): InsecureRequest {
        val detail = buildString {
            append("站点：").append(host).append('\n')
            if (tls != null) append("HTTPS 失败原因：").append(tls.problem.title).append('\n')
            append("这个站点没有提供可用的 HTTPS，但明文 HTTP 是通的。\n")
            append("继续访问意味着：链路上的任何人都能看到你在看什么，也能替换页面和图片的内容。")
        }
        return InsecureRequest(
            kind = InsecureRequest.Kind.HTTPS_UNSUPPORTED,
            host = host,
            message = "「$host」不支持 HTTPS，只能以明文访问",
            detail = detail,
            policy = InsecurePolicy(allowPlainHttp = true)
        )
    }

    /** 从响应最终地址里取出 «scheme://host»，取不到就退回请求时用的那个 */
    private fun landedBase(doc: Document, requestedUrl: String, host: String): String {
        val finalUrl = runCatching { doc.location() }.getOrNull().orEmpty()
        if (!finalUrl.startsWith("http")) return "${requestedUrl.substringBefore("://")}://$host"

        val scheme = finalUrl.substringBefore("://")
        val hostPart = finalUrl.substringAfter("://").substringBefore('/')
        return if (hostPart.isBlank()) "${requestedUrl.substringBefore("://")}://$host" else "$scheme://$hostPart"
    }

    private fun fetch(url: String, referer: String, policy: InsecurePolicy): Document =
        try {
            // 5xx/超时的重试在客户端里统一做，这里只负责把错误翻译成人话
            pages.get(
                url = url,
                referer = referer,
                trustAllCerts = policy.trustAllCerts,
                allowPlainHttp = policy.allowPlainHttp
            )
        } catch (e: HttpStatusException) {
            throw blockedMessage(e)
        }

    private fun blockedMessage(e: HttpStatusException): IllegalStateException =
        IllegalStateException(
            "站点返回 HTTP ${e.statusCode}。可能是人机验证（Cloudflare 之类）或需要登录，自动识别无法继续"
        )

    // ==================== 地址与命名 ====================

    /** 把用户输入整理成「根地址 + 首页路径」，允许只写域名或带子路径 */
    private fun normalize(input: String): Pair<String, String> {
        var raw = input.trim()
        require(raw.isNotEmpty()) { "先填站点首页地址" }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://$raw"
        }

        val uri = runCatching { URI(raw) }.getOrNull()
            ?: throw IllegalArgumentException("地址格式不对：$input")

        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: raw.substringAfter("://").substringBefore('/').substringBefore('?')
                .takeIf { it.contains('.') }
            ?: throw IllegalArgumentException("地址里没有域名：$input")

        val scheme = uri.scheme?.lowercase() ?: "https"
        val path = uri.path?.takeIf { it.isNotBlank() } ?: "/"
        return "$scheme://$host" to path
    }

    private fun slug(host: String): String {
        val s = host.removePrefix("www.")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .trim('-')
        return s.ifBlank { "site" }
    }

    private fun siteName(doc: Document, host: String): String {
        doc.selectFirst("meta[property=og:site_name]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.length in 2..30 }
            ?.let { return it }

        val title = doc.title().trim()
        if (title.isEmpty()) return host

        val first = title
            .split('|', '｜', '—', '·', '_', '-', '，', ',')
            .map { it.trim() }
            .firstOrNull { it.length in 2..30 }

        return (first ?: title).take(20)
    }

    private companion object {
        /**
         * 正文由脚本/接口下发的痕迹。
         *
         * 命中这些特征的阅读页，正文容器是空的，图片在接口返回里（有的还加密），
         * 靠 CSS 选择器怎么补都补不出来 —— 报错时要说清这一点，别让用户白折腾。
         */
        val SCRIPTED_READER = Regex(
            """reader_grant|loadContent\s*\(|chapter_loader|/api/[a-z_]*reader|getChapterContent""",
            RegexOption.IGNORE_CASE
        )

        /**
         * 常见的搜索地址形态。
         *
         * 顺序按「真实站点里出现的频率」排，不是按美观排 —— 每试一个都是一次真实请求，
         * 排在前面的命中率高，总请求数就少，被站点限流的概率也低。
         * 每个候选都会被「换个关键词结果变不变」验证，所以顺序不影响正确性，只影响代价。
         */
        val SEARCH_PATHS = listOf(
            // 查询串形式
            "/search?keyword={keyword}",
            "/search?q={keyword}",
            "/search?key={keyword}",
            "/search?wd={keyword}",
            // 路径形式：苹果CMS / qTcms 系模板常把关键字直接拼进 path
            "/msearch/{keyword}",
            "/search/{keyword}",
            "/search/{keyword}/",
            "/s/{keyword}",
            "/search.php?keyword={keyword}",
            "/index.php/search?keyword={keyword}",
            "/index.php/search/{keyword}"
        )

        /**
         * 首页列表抓不到时用的占位选择器。
         * 故意取一个不会命中任何元素的名字：home 节点必须是合法的，
         * 但抓不到内容，App 首页会落到空状态、引导用户去搜索。
         */
        const val NO_LIST_SELECTOR = NO_HOME_LIST_SELECTOR

        /**
         * 首页抓不到列表时用来试探搜索的通用词。
         *
         * 选词标准：漫画站里几乎必然存在、且不会只命中零条的内容词。
         * 它们只用来证明「这个搜索地址真的会按关键词返回结果」，不关心搜的是什么。
         */
        val GENERIC_PROBES = listOf("漫画", "韩漫", "恋爱", "全彩")

        /** 没有排行榜时给用户的提示语 */
        const val NO_HOME_LIST_HINT =
            "暂时找不到排行榜，请自行通过搜索观看漫画（目录和图片已确认可用）"

        /**
         * 识别失败时的兜底规则。宁可给一份「结构完整但抓不到东西」的配置让用户改，
         * 也不要给一份缺节点的配置 —— 后者在解析阶段就直接报错，用户反而无从下手。
         */
        val FALLBACK_DETAIL = DetailRule(
            item = "a[href]",
            url = FieldRule(select = "", attr = "href"),
            title = FieldRule(select = "", attr = "text")
        )

        val FALLBACK_READER = ReaderRule(
            item = "img",
            image = FieldRule(select = "", attr = "data-original|data-src|src"),
            referer = "{pageUrl}"
        )
    }
}
