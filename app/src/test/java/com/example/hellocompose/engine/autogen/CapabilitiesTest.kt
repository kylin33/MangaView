package com.example.hellocompose.engine.autogen

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 能力探测的回归测试。
 *
 * 探测结论会直接进识别报告（「封面=CSS 背景图」这种），也会影响封面/标题规则的
 * 同分排序，所以它必须只反映**页面上真实存在的东西**：
 * 探测错了不会报错，但会让用户看到一句错的解释，或者让规则偏好带偏。
 */
class CapabilitiesTest {

    private fun items(html: String): List<Element> {
        val doc = Jsoup.parse("<html><body><ul>$html</ul></body></html>", "https://site.example/")
        return doc.select("ul > li")
    }

    // ==================== 封面通道 ====================

    @Test
    fun detectsPlainImgSrc() {
        val members = items(
            """<li><a href="/b/1"><img src="/upload/1.jpg" alt="书一"></a></li>
               <li><a href="/b/2"><img src="/upload/2.jpg" alt="书二"></a></li>
               <li><a href="/b/3"><img src="/upload/3.jpg" alt="书三"></a></li>"""
        )
        assertEquals(Channel.DOM, CapabilityProbe.cover(members).first)
    }

    @Test
    fun detectsLazyAttributeWithoutImg() {
        // 苹果CMS 系模板：没有 img，封面地址挂在 <a data-original> 上
        val members = items(
            """<li><a href="/b/1" data-original="/upload/1.webp"></a></li>
               <li><a href="/b/2" data-original="/upload/2.webp"></a></li>
               <li><a href="/b/3" data-original="/upload/3.webp"></a></li>"""
        )
        assertEquals(Channel.LAZY_ATTR, CapabilityProbe.cover(members).first)
    }

    @Test
    fun detectsInlineBackgroundImage() {
        val members = items(
            """<li><a href="/b/1" style="background-image:url(/upload/1.webp)"></a></li>
               <li><a href="/b/2" style="background-image:url(/upload/2.webp)"></a></li>
               <li><a href="/b/3" style="background-image:url(/upload/3.webp)"></a></li>"""
        )
        assertEquals(Channel.INLINE_BG, CapabilityProbe.cover(members).first)
    }

    @Test
    fun placeholderSrcDoesNotCountAsCover() {
        // src 全是占位图、真实地址在 data-src：结论应该是懒加载，不是 DOM
        val members = items(
            """<li><a href="/b/1"><img src="/img/loading.gif" data-src="/upload/1.jpg"></a></li>
               <li><a href="/b/2"><img src="/img/loading.gif" data-src="/upload/2.jpg"></a></li>
               <li><a href="/b/3"><img src="/img/loading.gif" data-src="/upload/3.jpg"></a></li>"""
        )
        assertEquals(Channel.LAZY_ATTR, CapabilityProbe.cover(members).first)
    }

    // ==================== 标题通道 ====================

    @Test
    fun detectsLinkTitleAttribute() {
        val members = items(
            """<li><a href="/b/1" title="书一"><span>14小时前</span></a></li>
               <li><a href="/b/2" title="书二"><span>3天前</span></a></li>
               <li><a href="/b/3" title="书三"><span>1天前</span></a></li>"""
        )
        assertEquals(Channel.LINK_TITLE, CapabilityProbe.title(members).first)
    }

    @Test
    fun fallsBackToVisibleText() {
        val members = items(
            """<li><a href="/b/1">书一</a></li>
               <li><a href="/b/2">书二</a></li>
               <li><a href="/b/3">书三</a></li>"""
        )
        assertEquals(Channel.VISIBLE_TEXT, CapabilityProbe.title(members).first)
    }

    @Test
    fun genericTitleAttributeIsNotACapability() {
        // title="点击查看" 这类通用文案不能当成「标题在属性里」的证据
        val members = items(
            """<li><a href="/b/1" title="点击查看">书一</a></li>
               <li><a href="/b/2" title="点击查看">书二</a></li>
               <li><a href="/b/3" title="点击查看">书三</a></li>"""
        )
        assertEquals(Channel.VISIBLE_TEXT, CapabilityProbe.title(members).first)
    }

    // ==================== 正文图通道 ====================

    @Test
    fun detectsReaderImages() {
        val doc = Jsoup.parse(
            """<html><body><div id="content">
                 <img src="/upload/p1.jpg"><img src="/upload/p2.jpg">
               </div></body></html>""",
            "https://site.example/"
        )
        val imgs = doc.select("img").toList()
        assertEquals(Channel.DOM, CapabilityProbe.images(imgs))
    }

    @Test
    fun summaryMentionsEveryStage() {
        val caps = SiteCapabilities(
            list = Channel.DOM,
            cover = Channel.INLINE_BG,
            title = Channel.LINK_TITLE,
            chapters = Channel.DOM,
            images = Channel.DOM
        )
        val text = caps.summary()
        listOf("列表", "封面", "标题", "章节", "正文图").forEach {
            org.junit.Assert.assertTrue("摘要缺少 $it：$text", text.contains(it))
        }
        // 背景图封面是弱项，必须在 weakPoints 里说出来
        org.junit.Assert.assertTrue(caps.weakPoints.any { it.contains("背景图") })
    }
}
