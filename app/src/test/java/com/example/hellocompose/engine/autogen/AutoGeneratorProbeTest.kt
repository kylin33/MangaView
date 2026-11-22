package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.remote.JsoupHttpClient
import com.example.hellocompose.data.remote.MangaScraper
import com.example.hellocompose.data.source.ImageUrlResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test

/**
 * 多站点实连体检单：一次跑完一批站点，逐项打印识别结果与图片地址。
 *
 * 用途不是「断言通过」，而是**量化**：
 *
 * ```bash
 * ./gradlew :app:testDebugUnitTest --tests "*AutoGeneratorProbeTest*" \
 *   -DliveSite="a.com,b.com,https://c.com/"
 * ```
 *
 * 每站输出：识别出的规则、能力通道（靠什么抓到的）、首页条数与空封面/可疑标题计数、
 * 章节数、正文图数量与去重率。把这些数字贴出来，就能判断这次改动是变好还是变差。
 *
 * 不传 `-DliveSite` 时整测跳过；单站网络异常只跳过该站，不影响其余站点。
 */
class AutoGeneratorProbeTest {

    @Test
    fun probe() = runBlocking {
        val raw = System.getProperty("liveSite").orEmpty()
        Assume.assumeTrue("未指定 -DliveSite=a,b,c", raw.isNotBlank())
        val sites = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val scraper = MangaScraper(JsoupHttpClient())

        for (site in sites) {
            println("\n########## $site ##########")
            val outcome = runCatching {
                SourceAutoGenerator(JsoupHttpClient()).generate(site) { }
            }.getOrElse { println("异常: $it"); continue }
            if (outcome !is SourceAutoGenerator.Outcome.Success) {
                println("非成功: $outcome")
                continue
            }
            val d = outcome.result
            println("base=${d.config.baseUrl} searchOnly=${d.config.isSearchOnly}")
            println(
                "home item=${d.config.home.item}\n  cover=${d.config.home.cover.select}@${d.config.home.cover.attr}" +
                    "\n  title=${d.config.home.title.select}@${d.config.home.title.attr}"
            )
            println("detail item=${d.config.detail.item} title=${d.config.detail.title.select}@${d.config.detail.title.attr}")
            println("reader item=${d.config.reader.item} image=${d.config.reader.image.select}@${d.config.reader.image.attr}")
            println("能力=${d.report.capabilities?.summary()}")
            println("弱项=${d.report.capabilities?.weakPoints}")

            // 搜索规则单列打印 + 真的搜一次：识别阶段说「可用」和用户拿着配置能搜出东西
            // 是两件事，中间隔着「method/参数名/隐藏域有没有被完整写进配置」
            val search = d.config.search
            if (search == null) {
                println("搜索：未识别到")
            } else {
                println(
                    "搜索：${search.method.token.uppercase()} path=${search.path} " +
                        "param=${search.keywordParam} fields=${search.fields} " +
                        "（${d.report.searchNote ?: "—"}）"
                )
                val keyword = d.report.homeSamples.firstOrNull()?.take(6).orEmpty()
                if (keyword.isNotBlank()) {
                    runCatching { scraper.search(d.config, keyword) }
                        .onSuccess { hits ->
                            println("  用「$keyword」搜到 ${hits.size} 条：${hits.take(3).map { it.title }}")
                        }
                        .onFailure { println("  用「$keyword」搜索失败：$it") }
                }
            }

            val home = runCatching { scraper.fetchHome(d.config) }.getOrElse {
                println("抓首页异常: $it"); continue
            }
            println("首页 ${home.size} 条")
            val noCover = home.count { it.coverUrl.isBlank() }
            val dupCover = home.size - home.map { it.coverUrl }.distinct().size
            val weirdTitle = home.count { it.title.length > 24 || it.title.contains("小时前") || it.title.contains("分钟前") }
            println("  空封面=$noCover 重复封面=$dupCover 可疑标题=$weirdTitle 无标题=${home.count { it.title.isBlank() || it.title.contains("未命名") }}")
            home.take(6).forEach { println("   · [${it.title}] ${it.coverUrl.take(90)}") }
            home.take(3).forEach { println("COVER $site|${it.coverUrl}") }

            val ch = runCatching { scraper.fetchChapters(d.config, home.first().detailUrl) }.getOrElse {
                println("抓章节异常: $it"); continue
            }
            println("章节 ${ch.size} 条，首=${ch.firstOrNull()?.title} 末=${ch.lastOrNull()?.title}")
            println("  截断嫌疑=${ch.count { it.title.length <= 8 }} / 无标题=${ch.count { it.title.isBlank() }}")

            val imgs = runCatching { scraper.fetchChapterImages(d.config, ch.first().url) }.getOrElse {
                println("抓图片异常: $it"); continue
            }
            println("正文图 ${imgs.size} 张")
            println("  占位=${imgs.count { ImageUrlResolver.isPlaceholder(it) }} 图标=${imgs.count { ImageUrlResolver.isIcon(it) }}")
            println("  去重率=${imgs.map { it.substringAfterLast('/') }.distinct().size.toDouble() / imgs.size.coerceAtLeast(1)}")
            imgs.take(3).forEach { println("   > ${it.take(110)}") }
            imgs.take(3).forEach { println("IMG $site|$it") }
        }
    }
}
