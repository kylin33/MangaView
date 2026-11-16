package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.remote.PageParser
import com.example.hellocompose.data.source.SourceParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 自动识别的回归基线。
 *
 * 每个夹具跑一遍完整的 `generate()`：首页 → 详情 → 章节 → 阅读页，全部由本地 HTML 驱动，
 * 不联网、不受站点改版影响。断言分两层：
 *
 * 1. **识别结果**：条数、标题质量、章节像不像章节、图片是不是封面墙、baseUrl 有没有对齐；
 * 2. **产物自洽**：拿生成的配置回放同一批 HTML，必须抓出和识别时一样多的数据。
 *
 * 第二层是关键。只断言「识别成功」很容易退化成「随便生成一份看起来完整的配置」——
 * 回放能证明这份配置真的能抓到东西，也就是用户在 App 里会看到的那些内容。
 */
@RunWith(Parameterized::class)
class AutoGeneratorFixtureTest(private val site: FixtureSite) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = FixtureSite.loadAll().map { arrayOf<Any>(it) }
    }

    @Test
    fun matchesFixtureExpectation() = runBlocking {
        val generator = SourceAutoGenerator(FixturePageFetcher(site))
        val steps = mutableListOf<String>()
        val outcome = generator.generate(site.input) { steps += it }

        println("=== ${site.id} ===")
        steps.forEach { println("  · $it") }

        when (site.expect.outcome) {
            "failed" -> assertFailsAsExpected(outcome)
            else -> assertSucceedsAsExpected(outcome)
        }
    }

    // ==================== 成功路径 ====================

    private fun assertSucceedsAsExpected(outcome: SourceAutoGenerator.Outcome) {
        if (outcome is SourceAutoGenerator.Outcome.NeedsConfirmation) {
            fail("夹具是 http 的本地页面，不该要求不安全连接确认：${outcome.request.message}")
        }
        if (outcome is SourceAutoGenerator.Outcome.Failed) {
            fail("识别失败：${outcome.error.type} / ${outcome.error.detail}")
        }

        val result = (outcome as SourceAutoGenerator.Outcome.Success).result
        val config = result.config

        // 识别阶段生成的配置永远不允许自带安全例外
        assertFalse("生成的配置不应关闭证书校验", config.trustAllCerts)
        assertFalse("生成的配置不应允许明文 HTTP", config.allowPlainHttp)

        site.expect.baseUrl?.let {
            assertEquals("baseUrl 没对齐到作品链接所在的域名", it, config.baseUrl)
        }
        site.expect.searchOnly?.let {
            assertEquals("isSearchOnly 判定不对", it, config.isSearchOnly)
        }
        site.expect.usesScriptDetail?.let {
            assertEquals("这份配置的章节应该走脚本取数", it, config.detail.script != null)
        }

        // 产物必须能被正式解析器读回来，否则用户保存时才会炸
        val reparsed = SourceParser.parse(result.json, config.id)
        assertEquals("生成的 JSON 回读后 baseUrl 变了", config.baseUrl, reparsed.baseUrl)

        assertSearch(config)
        assertWarnings(result)

        // ---- 回放：用生成的配置再抓一遍同样的 HTML ----
        val home = PageParser.list(
            site.document("home.html", site.baseUrl + "/"),
            config.home,
            config.baseUrl
        )
        assertEquals("report.homeCount 与回放结果不一致", home.size, result.report.homeCount)
        site.expect.homeMin?.let {
            assertTrue("首页只抓到 ${home.size} 部，少于预期的 $it", home.size >= it)
        }
        site.expect.homeTitleMaxUntitled?.let { max ->
            val untitled = home.count { it.title == PageParser.UNTITLED }
            assertTrue(
                "有 $untitled 部作品的标题是占位符「${PageParser.UNTITLED}」，标题规则没选对",
                untitled <= max
            )
        }
        site.expect.homeCoverHost?.let { host ->
            val wrong = home.filterNot { SourceAutoDetector.hostOf(it.coverUrl) == host }
            assertTrue(
                "封面地址域名不对：${wrong.take(3).map { it.coverUrl }}",
                wrong.isEmpty()
            )
        }
        site.expect.homeTitleForbid.forEach { needle ->
            val bad = home.filter { it.title.contains(needle) }
            assertTrue(
                "首页抓到了 ${bad.size} 条「$needle」开头的条目（${bad.take(3).map { it.title }}），" +
                    "选中的多半是推广位而不是作品列表",
                bad.isEmpty()
            )
        }
        site.expect.homeCoverForbid.forEach { needle ->
            val bad = home.filter { it.coverUrl.contains(needle) }
            assertTrue(
                "封面地址里出现了「$needle」：${bad.take(3).map { it.coverUrl }}",
                bad.isEmpty()
            )
        }

        val chapters = PageParser.chapters(
            site.document("detail.html", site.baseUrl + "/"),
            config.detail,
            config.baseUrl
        )
        assertEquals("report.chapterCount 与回放结果不一致", chapters.size, result.report.chapterCount)
        site.expect.chapterMin?.let {
            assertTrue("只抓到 ${chapters.size} 个章节，少于预期的 $it", chapters.size >= it)
        }
        site.expect.chapterTitleLike?.let { min ->
            assertTrue("没有章节可判断标题质量", chapters.isNotEmpty())
            val ratio = chapters.count { SourceAutoDetector.isChapterLike(it.title) }
                .toDouble() / chapters.size
            assertTrue(
                "只有 ${(ratio * 100).toInt()}% 的章节标题像「第 N 话」，" +
                    "抓到的可能是作者/题材/推荐位链接",
                ratio >= min
            )
        }

        val images = PageParser.images(
            site.document("reader.html", site.baseUrl + "/"),
            config.reader,
            config.baseUrl
        )
        assertEquals("report.imageCount 与回放结果不一致", images.size, result.report.imageCount)
        site.expect.imageMin?.let {
            assertTrue("只抓到 ${images.size} 张图片，少于预期的 $it", images.size >= it)
        }
        site.expect.imageDistinctRatio?.let { min ->
            assertTrue("没有图片可判断", images.isNotEmpty())
            val ratio = images.map { it.substringAfterLast('/') }.distinct().size.toDouble() / images.size
            assertTrue(
                "图片文件名去重率只有 ${(ratio * 100).toInt()}%，抓到的更像封面墙而不是正文",
                ratio >= min
            )
        }
        site.expect.imageLast?.let { suffix ->
            assertTrue(
                "最后一张图片应该以 $suffix 结尾，实际是 ${images.lastOrNull()}，取值链可能少了某个属性",
                images.lastOrNull()?.endsWith(suffix) == true
            )
        }
        site.expect.imageHost?.let { host ->
            val wrong = images.filterNot { SourceAutoDetector.hostOf(it) == host }
            assertTrue("图片地址域名不对：${wrong.take(3)}", wrong.isEmpty())
        }
        site.expect.imageForbid.forEach { needle ->
            val bad = images.filter { it.contains(needle) }
            assertTrue(
                "正文图里出现了「$needle」：${bad.take(3)}。占位图/预览图被当成真实地址了",
                bad.isEmpty()
            )
        }
    }

    private fun assertSearch(config: com.example.hellocompose.data.source.SourceConfig) {
        if (!site.expect.expectsSearchPath) return
        val expected = site.expect.searchPath
        if (expected == null) {
            assertNull(
                "这个站的搜索探不通，不应该写搜索规则（写进去就是永远返回热门列表的假搜索）",
                config.search
            )
        } else {
            assertEquals("搜索地址推断错误", expected, config.search?.path)
        }
    }

    private fun assertWarnings(result: SourceAutoGenerator.Result) {
        site.expect.warningContains?.let { needle ->
            assertTrue(
                "警告里应该出现「$needle」，实际是：${result.report.warnings}",
                result.report.warnings.any { it.contains(needle) }
            )
        }
    }

    // ==================== 失败路径 ====================

    private fun assertFailsAsExpected(outcome: SourceAutoGenerator.Outcome) {
        if (outcome is SourceAutoGenerator.Outcome.Success) {
            fail(
                "这个站应该识别失败，却生成了配置：home=${outcome.result.config.home.item}, " +
                    "detail=${outcome.result.config.detail.item}, " +
                    "reader=${outcome.result.config.reader.item}"
            )
        }
        if (outcome is SourceAutoGenerator.Outcome.NeedsConfirmation) {
            fail("该失败在证书/明文这一层之前就应该发生，实际却要求确认：${outcome.request.message}")
        }

        val error = (outcome as SourceAutoGenerator.Outcome.Failed).error
        println("  · 失败：${error.type} / ${error.detail}")
        site.expect.failureContains?.let { needle ->
            assertTrue(
                "失败原因应该说明「$needle」，实际是：${error.detail}",
                error.detail.contains(needle)
            )
        }
    }
}
