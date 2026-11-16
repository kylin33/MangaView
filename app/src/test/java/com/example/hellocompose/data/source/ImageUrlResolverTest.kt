package com.example.hellocompose.data.source

import com.example.hellocompose.data.remote.UrlResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片地址解析的回归测试。
 *
 * 这些用例全部来自真实站点的写法：占位 `src` + 真实 `data-src`、只有 `srcset`、
 * 背景图、协议相对地址、base64 占位。每一种写错的后果都一样 ——
 * 选择器命中了、抓回来的地址是空的或者是 1×1 的透明图，而配置看起来完全正常。
 */
class ImageUrlResolverTest {

    private fun element(html: String, base: String = "https://site.example/"): Element =
        Jsoup.parse(html, base).body().child(0)

    // ==================== 占位图 ====================

    @Test
    fun placeholderSrcLosesToDataOriginal() {
        val el = element(
            """<img src="/static/1x1.gif" data-original="/upload/cover/1.jpg">"""
        )
        val best = ImageUrlResolver.best(el)!!

        assertEquals("data-original", best.attr)
        assertEquals("/upload/cover/1.jpg", best.raw)
    }

    @Test
    fun base64SourceIsTreatedAsPlaceholder() {
        val el = element(
            """<img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" data-lazy-src="/upload/1.jpg">"""
        )
        assertEquals("/upload/1.jpg", ImageUrlResolver.best(el)?.raw)
    }

    @Test
    fun spacerAndBlankNamesArePlaceholders() {
        assertTrue(ImageUrlResolver.isPlaceholder("/static/blank.gif"))
        assertTrue(ImageUrlResolver.isPlaceholder("/images/spacer.png"))
        assertTrue(ImageUrlResolver.isPlaceholder("data:image/png;base64,AAA"))
        assertFalse(ImageUrlResolver.isPlaceholder("/upload/1.jpg"))
    }

    // ==================== srcset 与背景图 ====================

    @Test
    fun srcsetPicksTheLargestDensity() {
        val el = element(
            """<img srcset="/upload/cover/1.jpg 1x, /upload/cover/1@2x.jpg 2x">"""
        )
        assertEquals("/upload/cover/1@2x.jpg", ImageUrlResolver.best(el)?.raw)
    }

    @Test
    fun srcsetPicksTheWidestCandidate() {
        assertEquals(
            "/upload/2-1280.jpg",
            ImageUrlResolver.fromSrcset("/upload/2-640.jpg 640w, /upload/2-1280.jpg 1280w")
        )
    }

    @Test
    fun backgroundImageIsReadFromInlineStyle() {
        val el = element("""<div style="background-image:url('/upload/cover/3.jpg')"></div>""")
        val best = ImageUrlResolver.best(el)!!

        assertEquals("style", best.attr)
        assertEquals("/upload/cover/3.jpg", ImageUrlResolver.clean(best.raw))
    }

    @Test
    fun backgroundImageHandlesDoubleQuotesAndSpaces() {
        assertEquals(
            "/upload/4.jpg",
            ImageUrlResolver.fromStyle("""background: url( "/upload/4.jpg" ) no-repeat""")
        )
    }

    @Test
    fun pictureSourceIsConsidered() {
        val el = element(
            """<picture><source srcset="/upload/5.webp 2x"><img src="/upload/5.jpg"></picture>"""
        )
        val values = ImageUrlResolver.candidates(el).map { it.raw }
        assertTrue("picture 里的 source 也应该被读到", "/upload/5.webp" in values)
    }

    // ==================== 图标 ====================

    @Test
    fun logoAndAvatarAreNotCovers() {
        assertTrue(ImageUrlResolver.isIcon("/static/logo.png"))
        assertTrue(ImageUrlResolver.isIcon("/upload/avatar/12.jpg"))
        assertFalse(ImageUrlResolver.isIcon("/upload/cover/12.jpg"))
        assertNull(
            "整个元素上只有图标时不应该有可用图",
            ImageUrlResolver.best(element("""<img src="/static/logo.png">"""))
        )
    }

    // ==================== 取值链 ====================

    @Test
    fun attrChainPutsLazyAttributesBeforeSrc() {
        val els = (1..6).map {
            element("""<img src="/static/1x1.gif" data-original="/upload/$it.jpg">""")
        }
        val chain = ImageUrlResolver.attrChain(els)
        assertEquals("data-original|src", chain)
    }

    @Test
    fun attrChainToleratesMixedLazyStyles() {
        // 同一个列表里老页面用 data-original、新页面只用 srcset：
        // 门槛卡 50% 会把两种都排除，最后只剩 src（也就是占位图）
        val els = listOf(
            element("""<img src="/static/blank.gif" data-original="/upload/1.jpg">"""),
            element("""<img src="/static/blank.gif" data-original="/upload/2.jpg">"""),
            element("""<img srcset="/upload/3.jpg 2x">"""),
            element("""<img srcset="/upload/4.jpg 2x">"""),
            element("""<img src="//cdn.example.com/upload/5.jpg">"""),
        )
        val chain = ImageUrlResolver.attrChain(els)

        assertTrue("取值链应保留 data-original，实际：$chain", chain.startsWith("data-original"))
        assertTrue("取值链应保留 srcset，实际：$chain", chain.contains("srcset"))
        assertTrue("src 必须留作兜底，实际：$chain", chain.endsWith("|src"))
    }

    @Test
    fun attrChainFallsBackToSrcForPlainImages() {
        val els = (1..4).map { element("""<img src="/upload/$it.jpg">""") }
        assertEquals("src", ImageUrlResolver.attrChain(els))
    }

    // ==================== 运行时兜底 ====================

    @Test
    fun imageUrlFallsBackWhenConfiguredAttrIsPlaceholder() {
        // 配置里写的是 src（识别时它覆盖率 100%），但这一条恰好换了懒加载属性
        val el = element("""<img src="/static/lazyload.gif" data-src="/upload/9.jpg">""")
        assertEquals(
            "https://site.example/upload/9.jpg",
            ImageUrlResolver.imageUrl(el, "src", "https://site.example")
        )
    }

    @Test
    fun imageUrlResolvesSrcsetAtRuntime() {
        val el = element("""<img srcset="/upload/10.jpg 1x, /upload/10@2x.jpg 2x">""")
        assertEquals(
            "https://site.example/upload/10@2x.jpg",
            ImageUrlResolver.imageUrl(el, "srcset", "https://site.example")
        )
    }

    @Test
    fun imageUrlResolvesBackgroundImageAtRuntime() {
        val el = element("""<div style="background-image:url(/upload/11.jpg)"></div>""")
        assertEquals(
            "https://site.example/upload/11.jpg",
            ImageUrlResolver.imageUrl(el, "style", "https://site.example")
        )
    }

    @Test
    fun emptyWhenNothingUsable() {
        val el = element("""<div class="card"><span>没有图</span></div>""")
        assertEquals("", ImageUrlResolver.imageUrl(el, "src", "https://site.example"))
    }

    // ==================== 地址归一化 ====================

    @Test
    fun normalizeDropsVolatileParamsAndFragment() {
        assertEquals(
            "https://img.example/1.jpg?w=800",
            ImageUrlResolver.normalize("https://img.example/1.jpg?w=800&_t=1699999999#page")
        )
        assertEquals(
            "https://img.example/1.jpg",
            ImageUrlResolver.normalize("https://img.example/1.jpg?v=2")
        )
    }

    @Test
    fun normalizeKeepsMeaningfulParams() {
        // 很多站的图片要靠签名参数才能取到，归一化不能删它们
        val url = "https://img.example/1.jpg?w=800&sign=abc"
        assertEquals(url, ImageUrlResolver.normalize(url))
    }

    // ==================== 协议相对 ====================

    @Test
    fun protocolRelativeFollowsSiteScheme() {
        assertEquals(
            "http://cdn.example/1.jpg",
            UrlResolver.resolve("//cdn.example/1.jpg", "http://site.example")
        )
        assertEquals(
            "https://cdn.example/1.jpg",
            UrlResolver.resolve("//cdn.example/1.jpg", "https://site.example")
        )
    }
}
