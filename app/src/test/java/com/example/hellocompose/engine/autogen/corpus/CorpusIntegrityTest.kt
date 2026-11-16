package com.example.hellocompose.engine.autogen.corpus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语料自检：标准答案本身对不对。
 *
 * 这套语料是长期资产，会不断加样本、改站点结构。如果标准答案里写错一个条数，
 * 准确率统计就会无声地偏低（或者偏高），而人只会以为「算法又退化了」。
 * 所以先把「标准答案是不是自洽」单独测一遍：
 *
 * 1. `expectedCount` 必须等于按标准选择器真的能选出来的条数 —— 防止改了 HTML 忘了改数字；
 * 2. 两个详情页的书名必须等于首页第一、第二部作品 —— 防止 detail_00x 贴错书，
 *    那种错误会让「章节提取」在无辜的地方失败；
 * 3. 标准答案里不能出现空标题/空地址 —— 空值会让比对悄悄变成「两边都空也算对」。
 */
class CorpusIntegrityTest {

    private val sites = CorpusSite.loadAll()

    @Test
    fun corpusIsNotEmpty() {
        assertTrue("语料库不应该为空", sites.size >= 9)
        val types = sites.map { it.type }.toSet()
        listOf(
            "standard", "korean", "japanese", "legacy", "spa",
            "wordpress", "gallery", "antibot", "multidomain"
        ).forEach { required ->
            assertTrue("语料缺少「$required」类型的样本，至少要有 9 类各一个", required in types)
        }
    }

    @Test
    fun homeGoldMatchesDeclaredCount() {
        for (site in sites) {
            if (site.blocked) continue
            val gold = site.goldHome()
            assertEquals("${site.id}: home.expectedCount 与实际能选出的条数不符", site.home.expectedCount, gold.size)
            gold.forEach { item ->
                assertTrue("${site.id}: 标准答案里出现了空标题（${item.url}）", item.title.isNotBlank())
                assertTrue("${site.id}: 标准答案里出现了空封面（${item.url}）", item.cover.isNotBlank())
                assertTrue("${site.id}: 标准答案里出现了相对地址（${item.url}）", item.url.startsWith("http"))
            }
        }
    }

    @Test
    fun detailPagesMatchTheBooksTheyClaim() {
        for (site in sites) {
            if (site.blocked) continue
            val titles = site.goldHome().map { it.title }
            site.detail.pages.forEachIndexed { index, page ->
                val bookTitle = site.goldBookTitle(page.page)
                assertEquals(
                    "${site.id}: ${page.page} 的书名和首页第 ${index + 1} 部对不上，" +
                        "说明 detail_00x 贴错了书（首页标题是「${titles.getOrNull(index)}」）",
                    titles.getOrNull(index),
                    bookTitle
                )
            }
        }
    }

    @Test
    fun chapterAndImageGoldMatchDeclaredCounts() {
        for (site in sites) {
            if (site.blocked) continue
            for (page in site.detail.pages) {
                val chapters = site.goldChapters(page.page)
                assertEquals(
                    "${site.id}: ${page.page} 的 expectedChapterCount 与实际不符",
                    page.expectedChapterCount,
                    chapters.size
                )
            }
            assertEquals(
                "${site.id}: expectedImageCount 与实际不符",
                site.reader.expectedImageCount,
                site.goldImages().size
            )
        }
    }

    @Test
    fun everyRoutePointsAtAnExistingPage() {
        for (site in sites) {
            assertTrue("${site.id}: 没有登记路由表", site.routes.isNotEmpty())
            for (route in site.routes) {
                val page = runCatching { site.page(route.page) }
                assertTrue("${site.id}: 路由 ${route.match} 指向的 ${route.page} 读不出来", page.isSuccess)
                assertTrue("${site.id}: ${route.page} 是空文件", page.getOrThrow().isNotBlank())
            }
        }
    }
}
