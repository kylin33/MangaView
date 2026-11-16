package com.example.hellocompose.engine.autogen

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评分模型与结构相似度的单元测试。
 *
 * 这里全部用数字和几行 HTML，不跑识别流程：调权重、加惩罚项时，
 * 需要的是「30 部作品是否真的赢过 5 条广告」这种可以精确断言的结论，
 * 而不是一个跑 15 秒、失败了只知道某站点红了的集成测试。
 */
class ListScoringTest {

    // ==================== 权重与正向分量 ====================

    @Test
    fun weightsAreNormalized() {
        assertEquals(1.0, sum(ScoreWeights.HOME), 1e-9)
        assertEquals(1.0, sum(ScoreWeights.CHAPTERS), 1e-9)
    }

    @Test
    fun moreItemsWinWhenEverythingElseIsEqual() {
        val big = ListScorer.score(facts(matched = 30, item = 30))
        val small = ListScorer.score(facts(matched = 8, item = 8))

        assertTrue("30 部作品应该赢过 8 部：${big.total} vs ${small.total}", big.total > small.total)
    }

    /**
     * 用户给的例子：`.book-item` 30 条全字段有效、`.recommend-item` 8 条全有效、
     * `.ad-item` 5 条只有 20% 有标题。数量优势要能压过「推荐位标题更整齐」，
     * 广告位则必须垫底。
     */
    @Test
    fun bookListBeatsRecommendAndAds() {
        val book = ListScorer.score(
            facts(matched = 30, item = 30, titleValid = 29)
        )
        val recommend = ListScorer.score(
            facts(matched = 8, item = 8)
        )
        val ads = ListScorer.score(
            facts(matched = 5, item = 5, titleValid = 1)
        )

        assertTrue("作品列表应不输于推荐位：${book.total} vs ${recommend.total}", book.total >= recommend.total)
        assertTrue("推荐位应赢过广告位：${recommend.total} vs ${ads.total}", recommend.total > ads.total)
    }

    @Test
    fun countGainSaturates() {
        val thirty = ListScorer.score(facts(matched = 30, item = 30)).count
        val sixty = ListScorer.score(facts(matched = 60, item = 60)).count

        assertEquals(1.0, thirty, 1e-9)
        assertEquals("超过饱和点后不再加分", 1.0, sixty, 1e-9)
    }

    // ==================== 惩罚项 ====================

    @Test
    fun structureInconsistencyCostsMoreThanItsWeight() {
        val clean = ListScorer.score(facts(matched = 20, item = 20, consistency = 1.0))
        val messy = ListScorer.score(facts(matched = 20, item = 20, consistency = 0.4))

        assertTrue("结构不一致要额外扣分，不能只按 0.1 的权重线性掉", clean.total - messy.total >= 0.10)
        assertTrue("结构不一致必须留下惩罚理由", messy.penalties.any { it.reason.contains("结构") })
    }

    @Test
    fun duplicateUrlsArePenalized() {
        val unique = ListScorer.score(facts(matched = 12, item = 12, distinctUrls = 12))
        val repeated = ListScorer.score(facts(matched = 12, item = 12, distinctUrls = 6))

        assertTrue(repeated.total < unique.total)
        assertTrue(repeated.penalties.any { it.reason == "地址重复" })
    }

    @Test
    fun duplicateTitlesArePenalized() {
        val unique = ListScorer.score(facts(matched = 20, item = 20, distinctTitles = 20))
        val repeated = ListScorer.score(facts(matched = 20, item = 20, distinctTitles = 10))

        assertTrue(repeated.total < unique.total)
        assertTrue(repeated.penalties.any { it.reason == "标题重复" })
    }

    @Test
    fun iconCoversArePenalized() {
        val normal = ListScorer.score(facts(matched = 10, item = 10))
        val logos = ListScorer.score(facts(matched = 10, item = 10, iconCovers = 6))

        assertTrue(logos.total < normal.total)
        assertTrue(logos.penalties.any { it.reason.contains("图标") })
    }

    @Test
    fun boilerplateContainerIsPenalized() {
        val content = ListScorer.score(facts(matched = 10, item = 10))
        val footer = ListScorer.score(facts(matched = 10, item = 10, boilerplate = true))

        assertTrue(footer.total < content.total)
        assertTrue(footer.penalties.any { it.reason.contains("导航") })
    }

    @Test
    fun overWideSelectorIsPenalized() {
        // 命中 120 个节点，只解析出 10 条：剩下 110 个是杂质
        val narrow = ListScorer.score(facts(matched = 10, item = 10))
        val wide = ListScorer.score(facts(matched = 120, item = 10))

        assertTrue(wide.total < narrow.total)
        assertTrue(wide.penalties.any { it.reason.contains("过宽") })
    }

    @Test
    fun chapterListWithoutChapterTitlesIsPenalized() {
        val chapters = ListScorer.score(
            facts(matched = 30, item = 30, chapterLike = 0.95),
            ScoreWeights.CHAPTERS
        )
        val tags = ListScorer.score(
            facts(matched = 30, item = 30, chapterLike = 0.05),
            ScoreWeights.CHAPTERS
        )

        assertTrue(tags.total < chapters.total)
        assertTrue(tags.penalties.any { it.reason.contains("章节") })
    }

    @Test
    fun cleanCandidateHasNoPenalty() {
        val score = ListScorer.score(facts(matched = 24, item = 24))

        assertEquals(emptyList<Penalty>(), score.penalties)
        assertTrue("干净候选应该有高分：${score.total}", score.total >= 0.98)
    }

    // ==================== DOM 结构相似度 ====================

    @Test
    fun identicalCardsAreConsistent() {
        val members = parse(
            """
            <div class="grid">
              <div class="card"><a href="/1"><img src="/1.jpg"></a><h3><a href="/1">甲</a></h3></div>
              <div class="card"><a href="/2"><img src="/2.jpg"></a><h3><a href="/2">乙</a></h3></div>
              <div class="card"><a href="/3"><img src="/3.jpg"></a><h3><a href="/3">丙</a></h3></div>
            </div>
            """.trimIndent(),
            "div.card"
        )

        assertEquals(1.0, StructureSignature().consistency(members), 1e-9)
    }

    @Test
    fun classAndTextDoNotAffectStructure() {
        val members = parse(
            """
            <ul>
              <li class="first odd"><a href="/1">第 1 话</a></li>
              <li class="last"><a href="/2">第 2 话</a></li>
            </ul>
            """.trimIndent(),
            "li"
        )

        assertEquals("class 与文本不该影响结构判定", 1.0, StructureSignature().consistency(members), 1e-9)
    }

    @Test
    fun optionalBadgeStillCountsAsSameStructure() {
        val members = parse(
            """
            <div class="grid">
              <div class="card"><a href="/1"><img src="/1.jpg"></a><h3><a href="/1">甲</a></h3></div>
              <div class="card"><a href="/2"><img src="/2.jpg"></a><h3><a href="/2">乙</a></h3></div>
              <div class="card"><a href="/3"><img src="/3.jpg"></a><h3><a href="/3">丙</a></h3><span class="badge">NEW</span></div>
            </div>
            """.trimIndent(),
            "div.card"
        )

        assertEquals("多一个角标不该把整组判成异构", 1.0, StructureSignature().consistency(members), 1e-9)
    }

    @Test
    fun mixedShapesDropConsistency() {
        val members = parse(
            """
            <div class="grid">
              <div class="card"><a href="/1"><img src="/1.jpg"></a><h3><a href="/1">甲</a></h3></div>
              <div class="card"><a href="/2"><img src="/2.jpg"></a><h3><a href="/2">乙</a></h3></div>
              <div class="banner"><a href="/3"><img src="/3.jpg"></a></div>
              <div class="banner"><a href="/4"><img src="/4.jpg"></a></div>
            </div>
            """.trimIndent(),
            "div.grid > div"
        )

        assertEquals(0.5, StructureSignature().consistency(members), 1e-9)
    }

    @Test
    fun emptyGroupHasZeroConsistency() {
        assertEquals(0.0, StructureSignature().consistency(emptyList()), 1e-9)
    }

    // ==================== 工具 ====================

    private fun sum(weights: ScoreWeights): Double =
        weights.count + weights.image + weights.title + weights.url + weights.structure

    /** 默认造一个「全字段有效」的候选，只改需要关注的项 */
    private fun facts(
        matched: Int,
        item: Int,
        imageValid: Int = matched,
        titleValid: Int = matched,
        urlValid: Int = matched,
        distinctTitles: Int = item,
        distinctUrls: Int = item,
        iconCovers: Int = 0,
        consistency: Double = 1.0,
        boilerplate: Boolean = false,
        chapterLike: Double? = null
    ) = ListFacts(
        matchedCount = matched,
        itemCount = item,
        imageValid = imageValid,
        titleValid = titleValid,
        urlValid = urlValid,
        distinctTitles = distinctTitles,
        distinctUrls = distinctUrls,
        iconCovers = iconCovers,
        structureConsistency = consistency,
        boilerplate = boilerplate,
        chapterLike = chapterLike
    )

    private fun parse(html: String, selector: String): List<Element> =
        Jsoup.parse(html).select(selector)
}
