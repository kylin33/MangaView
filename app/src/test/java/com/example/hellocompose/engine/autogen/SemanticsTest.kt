package com.example.hellocompose.engine.autogen

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语义特征的回归测试。
 *
 * 重点不是「认得多少关键词」，而是三条边界：
 * 1. 无意义的命名（`item-123`、`box-a1`）不能被瞎猜成任何角色；
 * 2. 精确词（`ad`、`pic`）不能靠包含匹配误伤 `load`、`picture`；
 * 3. 语义只能做小幅偏向，不能压过结构证据 —— 所以加成上限必须很小。
 */
class SemanticsTest {

    private fun element(html: String): Element = Jsoup.parse(html).body().child(0)

    // ==================== 分词 ====================

    @Test
    fun splitsSnakeKebabAndCamelCase() {
        assertEquals(listOf("book", "list"), Semantics.tokens("book-list"))
        assertEquals(listOf("book", "list"), Semantics.tokens("book_list"))
        assertEquals(listOf("book", "list", "item"), Semantics.tokens("bookList_item"))
    }

    @Test
    fun camelCaseIsSplitBeforeMatching() {
        assertEquals(listOf("book", "title"), Semantics.tokens("bookTitle"))
        assertTrue(
            "bookTitle 应该被认成标题节点",
            Semantics.roleScores(Semantics.tokens("bookTitle")).containsKey(Semantics.Role.TITLE)
        )
    }

    // ==================== 角色识别 ====================

    @Test
    fun titleAndCoverRolesAreDetected() {
        val title = element("""<span class="book-title">夜色霓虹</span>""")
        val cover = element("""<img class="cover" src="/upload/1.jpg">""")

        assertTrue(Semantics.has(title, Semantics.Role.TITLE))
        assertTrue(Semantics.has(cover, Semantics.Role.COVER))
    }

    @Test
    fun chapterAndListRolesAreDetected() {
        val roles = Semantics.roleScores(Semantics.tokens("chapter-list"))
        assertTrue((roles[Semantics.Role.CHAPTER] ?: 0.0) > 0.0)
        assertTrue((roles[Semantics.Role.LIST] ?: 0.0) > 0.0)
    }

    @Test
    fun opaqueNamesCarryNoFieldSemantics() {
        // 这正是需要语义分析的场景：class 名完全不可读，不能凭空猜出字段角色。
        // （`item` / `list` 这类通用词会被算成「列表」，但它不指向任何字段）
        for (name in listOf("item-123", "list_456", "box-a1", "t-9", "c-7")) {
            val el = element("""<div class="$name"></div>""")
            for (role in listOf(
                Semantics.Role.TITLE,
                Semantics.Role.COVER,
                Semantics.Role.CHAPTER,
                Semantics.Role.AUTHOR,
                Semantics.Role.AD,
                Semantics.Role.NAV
            )) {
                assertFalse("「$name」不该被认成 $role", Semantics.has(el, role))
            }
        }
    }

    @Test
    fun shortWordsDoNotMatchInsideOtherWords() {
        // ad 做包含匹配会误伤 load / shadow，所以它只做精确匹配
        val roles = Semantics.roleScores(Semantics.tokens("loading-indicator shadow-box"))
        assertEquals(0.0, roles[Semantics.Role.AD] ?: 0.0, 0.0)
        assertTrue(Semantics.roleScores(Semantics.tokens("ad-slot")).containsKey(Semantics.Role.AD))
    }

    @Test
    fun altTextAlsoCarriesSemantics() {
        val img = element("""<img alt="封面图" src="/upload/1.jpg">""")
        assertTrue(
            "alt 里写着「封面」时，即使 class 不可读也应该认出封面",
            Semantics.has(img, Semantics.Role.COVER)
        )
    }

    @Test
    fun chineseClassNamesAreUnderstood() {
        assertTrue(Semantics.has(element("""<div class="章节列表"></div>"""), Semantics.Role.CHAPTER))
        assertTrue(Semantics.has(element("""<div class="作品封面"></div>"""), Semantics.Role.COVER))
    }

    // ==================== URL 语义 ====================

    @Test
    fun detailUrlIsRecognized() {
        assertTrue(Semantics.looksLikeDetailUrl("https://site.example/comic/123"))
        assertTrue(Semantics.looksLikeDetailUrl("https://site.example/detail/9174.html"))
        assertFalse(
            "推广位地址不该被当成详情页",
            Semantics.looksLikeDetailUrl("https://site.example/promo/8")
        )
        assertFalse(
            "作者页地址不该被当成详情页",
            Semantics.looksLikeDetailUrl("https://site.example/author/11")
        )
    }

    @Test
    fun chapterUrlIsRecognized() {
        assertTrue(Semantics.looksLikeChapterUrl("https://site.example/chapter/45"))
        assertTrue(Semantics.looksLikeChapterUrl("https://site.example/read/101/5"))
        assertFalse(Semantics.looksLikeChapterUrl("https://site.example/comic/123"))
    }

    @Test
    fun urlBonusPrefersContentUrlsAndPunishesPromotions() {
        val good = Semantics.urlBonus(listOf("https://s.example/comic/1", "https://s.example/comic/2"))
        val bad = Semantics.urlBonus(listOf("https://s.example/promo/1", "https://s.example/promo/2"))
        val author = Semantics.urlBonus(listOf("https://s.example/author/1"))

        assertTrue("作品列表应该拿到正向加成", good > 0)
        assertTrue("推广位应该被扣分", bad < 0)
        assertTrue("作者链接应该被扣分", author < 0)
    }

    @Test
    fun urlBonusForChaptersLooksForReaderPaths() {
        val chapters = Semantics.urlBonus(
            listOf("https://s.example/read/1/1", "https://s.example/read/1/2"),
            chapterMode = true
        )
        val works = Semantics.urlBonus(
            listOf("https://s.example/comic/1", "https://s.example/comic/2"),
            chapterMode = true
        )
        assertTrue(chapters > works)
    }

    // ==================== 列表加成 ====================

    @Test
    fun listBonusRewardsListNamingAndPunishesAds() {
        val list = element("""<div class="book-list"><div class="item"></div></div>""")
        val ad = element("""<div class="promo-banner"><div class="item"></div></div>""")

        val listBonus = Semantics.listBonus(list, list.children())
        val adBonus = Semantics.listBonus(ad, ad.children())

        assertTrue("book-list 应该有正向加成，实际 $listBonus", listBonus > 0)
        assertTrue("推广位应该被扣分，实际 $adBonus", adBonus < 0)
        assertTrue("加成幅度必须很小，否则语义会盖过结构证据", adBonus >= -0.20)
        assertTrue(listBonus <= 0.12)
    }

    @Test
    fun opaqueContainerGetsNoBonus() {
        val el = element("""<div class="box-a1"><div class="x7"></div></div>""")
        assertEquals(0.0, Semantics.listBonus(el, el.children()), 0.0)
    }
}
