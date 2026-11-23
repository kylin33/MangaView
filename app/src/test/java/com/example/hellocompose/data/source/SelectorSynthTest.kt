package com.example.hellocompose.data.source

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择器合成：目标只有一个 —— 生成的规则在站点改版后还能用。
 *
 * 所以这里的用例几乎都是「改一下页面，再看选择器是否仍然命中同一批元素」，
 * 而不是断言字符串长什么样。字符串只是手段，命中集合才是契约。
 */
class SelectorSynthTest {

    private fun doc(html: String): Document = Jsoup.parse(html, "https://example.com/")

    private fun pick(doc: Document, tag: String, classes: List<String> = emptyList()): String {
        val members = doc.select(tag)
        assertTrue("夹具里没有 $tag", members.isNotEmpty())
        val first = requireNotNull(members.firstOrNull())
        val cache = HashMap<String, List<Element>>()
        return SelectorSynth.item(
            container = requireNotNull(first.parent()),
            members = members,
            tag = tag,
            requiredClasses = classes,
            fallback = "div > $tag",
            lookup = { sel -> cache.getOrPut(sel) { doc.select(sel) } }
        )
    }

    @Test
    fun `深层嵌套时用容器 id 而不是整条祖先链`() {
        val d = doc(
            """
            <html><body><div id="app"><div class="layout"><main class="content">
              <ul id="comic-list">
                <li class="item">A</li><li class="item">B</li><li class="item">C</li>
              </ul>
            </main></div></div></body></html>
            """.trimIndent()
        )
        val selector = pick(d, "li", listOf("item"))

        assertFalse("不该出现 html/body 这种绝对路径：$selector", selector.contains("html"))
        assertFalse("不该依赖元素位置：$selector", selector.contains(":nth"))
        assertEquals(3, d.select(selector).size)
    }

    @Test
    fun `容器没有 id 和 class 时向上找有语义的祖先`() {
        val d = doc(
            """
            <html><body>
              <div id="main">
                <div><div><ul>
                  <li class="item">A</li><li class="item">B</li><li class="item">C</li>
                </ul></div></div>
              </div>
            </body></html>
            """.trimIndent()
        )
        val selector = pick(d, "li", listOf("item"))

        assertFalse("退化成绝对路径了：$selector", selector.startsWith("html") || selector.contains("body"))
        assertEquals(3, d.select(selector).size)
    }

    @Test
    fun `页面里另有一个同 class 的板块时选择器不越界`() {
        val d = doc(
            """
            <html><body>
              <div class="comic-list"><ul>
                <li class="item">A</li><li class="item">B</li><li class="item">C</li>
              </ul></div>
              <div class="sidebar-list"><ul>
                <li class="item">推荐1</li><li class="item">推荐2</li><li class="item">推荐3</li>
              </ul></div>
            </body></html>
            """.trimIndent()
        )
        val panel = requireNotNull(d.select("div.comic-list ul").firstOrNull()).children()
        val cache = HashMap<String, List<Element>>()
        val selector = SelectorSynth.item(
            container = requireNotNull(panel.firstOrNull()?.parent()),
            members = panel.toList(),
            tag = "li",
            requiredClasses = listOf("item"),
            fallback = "div > li",
            lookup = { sel -> cache.getOrPut(sel) { d.select(sel) } }
        )

        assertEquals("选择器把侧栏也框进来了：$selector", 3, d.select(selector).size)
        assertEquals(listOf("A", "B", "C"), d.select(selector).map { it.text() })
    }

    @Test
    fun `容器里插入广告位后仍然命中同一批条目`() {
        val html = """
            <html><body><div id="app"><div class="wrap">
              <ul id="list">
                <li class="item">A</li><li class="item">B</li><li class="item">C</li>
              </ul>
            </div></div></body></html>
        """.trimIndent()
        val before = doc(html)
        val selector = pick(before, "li", listOf("item"))

        // 站方在列表前后各插了一个推广位（真实站点最常见的改版方式）
        val after = doc(
            html.replace("<ul id=\"list\">", "<div class=\"ad-banner\">广告</div>\n<ul id=\"list\">")
                .replace("</ul>", "  <li class=\"promo\">推广</li>\n</ul>")
        )
        assertEquals("改版后选择器失效或越界：$selector", listOf("A", "B", "C"), after.select(selector).map { it.text() })
    }

    @Test
    fun `条目顺序调换不影响命中`() {
        val d = doc(
            """
            <html><body><div id="main"><ul class="comic-list">
              <li class="item">C</li><li class="item">A</li><li class="item">B</li>
            </ul></div></body></html>
            """.trimIndent()
        )
        val selector = pick(d, "li", listOf("item"))
        assertEquals(3, d.select(selector).size)
        assertFalse(selector.contains(":nth"))
    }

    @Test
    fun `状态类不参与选择器`() {
        val d = doc(
            """
            <html><body><div id="main"><ul class="comic-list">
              <li class="item active">A</li><li class="item">B</li>
              <li class="item">C</li><li class="item is-new">D</li>
            </ul></div></body></html>
            """.trimIndent()
        )
        // 共同 class 只有 item —— active / is-new 只在个别条目上，本来就不该进交集
        val selector = pick(d, "li", listOf("item"))
        assertFalse("状态类进了选择器：$selector", selector.contains("active") || selector.contains("is-new"))
        assertEquals(4, d.select(selector).size)
    }

    @Test
    fun `只剩布局工具类时不拿它当范围`() {
        val d = doc(
            """
            <html><body><div id="root">
              <div class="row"><div class="col-md-9"><div class="clearfix"><ul>
                <li class="media-item">A</li><li class="media-item">B</li><li class="media-item">C</li>
              </ul></div></div></div>
            </div></body></html>
            """.trimIndent()
        )
        val selector = pick(d, "li", listOf("media-item"))
        assertFalse("布局类被当成了稳定锚点：$selector", selector.contains("col-md") || selector.contains("clearfix"))
        assertEquals(3, d.select(selector).size)
    }

    @Test
    fun `class 排序偏好语义类`() {
        assertTrue("语义类应该排在状态类前面", SelectorSynth.classRank("comic-item") > SelectorSynth.classRank("active"))
        assertTrue("语义类应该排在布局类前面", SelectorSynth.classRank("post-card") > SelectorSynth.classRank("col-md-6"))
        assertTrue("带序号的 class 稳定性更差", SelectorSynth.classRank("item") > SelectorSynth.classRank("item-123"))
    }

    @Test
    fun `脆弱选择器会被点名`() {
        assertNotNull(SelectorSynth.fragility("div:nth-child(2) > ul > li"))
        assertNotNull(SelectorSynth.fragility("div.list.hot.news > li.item"))
        assertNull(SelectorSynth.fragility("#list > li.item"))
        assertNull(SelectorSynth.fragility("li.item"))
    }
}
