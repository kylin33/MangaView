package com.example.hellocompose.data.source

import com.example.hellocompose.core.ErrorType
import com.example.hellocompose.data.remote.JsoupHttpClient
import com.example.hellocompose.data.remote.MangaScraper
import com.example.hellocompose.engine.autogen.SourceAutoGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * 用真实站点验证「自动识别」整条链路。
 *
 * 这个测试存在的理由：选择器识别是启发式的，靠读代码判断不了对不对。
 * 唯一可信的证据是「生成的配置拿去正式抓取，能抓到东西」——
 * 所以这里直接走一遍生成 → 抓首页 → 抓章节 → 抓图片。
 *
 * 仓库里不写任何具体站点，域名从系统属性传入；不传就跳过：
 *
 * ```bash
 * ./gradlew testDebugUnitTest --tests "*SourceAutoGeneratorLiveTest*" -DliveSite=https://某站.com
 * ```
 *
 * 需要联网。站点不可达时按跳过处理，不让它变成随机失败的测试。
 */
class SourceAutoGeneratorLiveTest {

    /**
     * 端到端：识别一个真实站点，再用生成的配置跑正式抓取链路。
     *
     * 断言的重点不是「跑通」，而是**识别结果和实际抓取一致**：
     * 首页条数、章节数、图片数、图片不是封面墙、搜索能搜回同一本书。
     * 这些正是用户在 App 里会碰到的路径。
     */
    @Test
    fun detectsAndScrapesLiveSite() = runBlocking {
        val url = liveSiteOrSkip()

        val generator = SourceAutoGenerator(JsoupHttpClient())
        val result = generator.generate(url) { step -> println("[step] $step") }

        if (result is SourceAutoGenerator.Outcome.Failed) {
            val error = result.error
            println("[fail] ${error.type} / ${error.detail}")
            // 网络问题与被测逻辑无关，跳过；识别器自己的失败必须让测试红掉
            if (error.type == ErrorType.NETWORK || error.type == ErrorType.TIMEOUT) {
                Assume.assumeTrue("站点不可达，跳过：${error.detail}", false)
                return@runBlocking
            }
            throw AssertionError("识别失败：${error.type} / ${error.detail}")
        }

        // 需要用户确认才能继续（证书异常 / 只支持明文）。测试里没有人会点「继续」，
        // 这本身就是正确行为：识别器不会自己放宽。这里按跳过处理，但把原因打出来
        if (result is SourceAutoGenerator.Outcome.NeedsConfirmation) {
            println("[需要确认] ${result.request.message}")
            println("[详情] ${result.request.detail}")
            Assume.assumeTrue("站点要求不安全连接，跳过（需人工确认）", false)
            return@runBlocking
        }

        val data = (result as SourceAutoGenerator.Outcome.Success).result
        println("---------- 生成的配置 ----------")
        println(data.json)
        println("---------- 识别报告 ----------")
        println("首页 ${data.report.homeCount} 部：${data.report.homeSamples}")
        println("章节 ${data.report.chapterCount} 个：${data.report.chapterSamples}")
        println("图片 ${data.report.imageCount} 张：${data.report.imageSamples}")
        println("搜索：${data.report.searchPath}")
        println("安全例外：${data.report.insecure}")
        println("警告：${data.report.warnings}")

        // 严格模式下生成的配置不允许带任何例外
        assertTrue("默认策略下不应该生成带安全例外的配置", data.report.insecure == null)
        assertTrue("生成的配置不应关闭证书校验", !data.config.trustAllCerts)
        assertTrue("生成的配置不应允许明文 HTTP", !data.config.allowPlainHttp)

        // 只有「仅搜索」配置才允许首页为空，其余情况首页必须有内容
        assertTrue(
            "既不是仅搜索配置，又没识别出首页列表",
            data.config.isSearchOnly || data.report.homeCount > 0
        )
        assertTrue("没识别出目录", data.report.chapterCount >= 2)
        assertTrue("没识别出正文图片", data.report.imageCount >= 1)

        val scraper = MangaScraper(JsoupHttpClient())

        if (!data.config.isSearchOnly) {
            val home = scraper.fetchHome(data.config)
            println("正式抓取首页：${home.size} 部，首条=${home.firstOrNull()?.title}")
            assertTrue("生成的配置抓不到首页列表", home.size >= 4)
            assertTrue(
                "封面地址没取到：${home.first().coverUrl}",
                home.first().coverUrl.startsWith("http")
            )

            // 拿首页第一部去搜一次：搜索规则必须能搜回同一本书，
            // 否则说明识别器给了个「忽略关键字、永远返回热门列表」的假地址
            val keyword = home.first().title.take(4)
            if (data.report.searchPath != null && keyword.isNotBlank()) {
                val searched = scraper.search(data.config, keyword)
                println("搜索「$keyword」：${searched.size} 条，首条=${searched.firstOrNull()?.title}")
                assertTrue("搜索规则抓不到结果", searched.isNotEmpty())
                assertTrue(
                    "搜「$keyword」没搜回这本书，搜索地址可能忽略了关键字",
                    searched.any { it.detailUrl == home.first().detailUrl }
                )
            }

            val chapters = scraper.fetchChapters(data.config, home.first().detailUrl)
            println("正式抓取章节：${chapters.size} 个，首个=${chapters.firstOrNull()?.title}")
            assertTrue("生成的配置抓不到章节列表", chapters.size >= 2)

            val images = scraper.fetchChapterImages(data.config, chapters.first().url)
            println("正式抓取图片：${images.size} 张，首张=${images.firstOrNull()}")
            assertTrue("生成的配置抓不到章节图片", images.size >= 1)

            // 光看数量不够：推荐位也有几十张封面，必须确认抓到的是正文图
            val distinctRatio = images.map { it.substringAfterLast('/') }.distinct().size.toDouble() / images.size
            println("图片文件名去重率：$distinctRatio")
            assertTrue("抓到的更像封面墙（文件名高度重复），不是正文图", distinctRatio >= 0.8)
        } else {
            println("仅搜索配置：首页无排行榜，靠搜索进入")
        }
    }

    /**
     * 非漫画站必须干净地失败，而不是生成一份「看着完整、其实抓不到」的配置。
     * 这是自动识别最容易走偏的地方：只要生成成功，用户就会以为能用了。
     */
    @Test
    fun failsCleanlyOnNonMangaSite() = runBlocking {
        val result = SourceAutoGenerator(JsoupHttpClient()).generate("example.com") { step ->
            println("[step] $step")
        }

        println("[结果] $result")
        if (result is SourceAutoGenerator.Outcome.Failed &&
            (result.error.type == ErrorType.NETWORK || result.error.type == ErrorType.TIMEOUT)
        ) {
            Assume.assumeTrue("网络不可用，跳过：${result.error.detail}", false)
            return@runBlocking
        }

        assertTrue(
            "非漫画站不应该生成出配置",
            result is SourceAutoGenerator.Outcome.Failed || result is SourceAutoGenerator.Outcome.NeedsConfirmation
        )
        if (result is SourceAutoGenerator.Outcome.NeedsConfirmation) return@runBlocking

        val error = (result as SourceAutoGenerator.Outcome.Failed).error
        println("[失败信息] ${error.type} / ${error.detail}")
        assertTrue(
            "报错要说明是「没找到列表结构」，实际是：${error.detail}",
            error.detail.contains("列表")
        )
    }

    /** 没有传 -DliveSite 就整体跳过：仓库里不含任何站点，默认不该向外部发请求 */
    private fun liveSiteOrSkip(): String {
        val url = System.getProperty("liveSite")?.trim().orEmpty()
        if (url.isBlank()) {
            Assume.assumeTrue(
                "未指定 -DliveSite=<站点首页地址>，跳过实连测试",
                false
            )
        }
        return url
    }
}
