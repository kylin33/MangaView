package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.source.ConfidenceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 置信度打分。
 *
 * 这里断言的是「方向」和「边界」，不是小数点后几位：
 * 权重会被真实站点校准改掉，写死精确值只会让测试变成修改记录。
 */
class ConfidenceScorerTest {

    @Test
    fun perfectSiteScoresFull() {
        val home = ConfidenceScorer.home(60, 1.0, 1.0, 1.0, true)
        val detail = ConfidenceScorer.detail(20, 1.0, true)
        val reader = ConfidenceScorer.reader(200, 1.0, true)

        assertEquals(1.0, home.score, 0.001)
        assertEquals(1.0, detail.score, 0.001)
        assertEquals(1.0, reader.score, 0.001)
        assertEquals(1.0, ConfidenceScorer.overall(home, detail, reader), 0.001)
        assertEquals(ConfidenceLevel.HIGH, home.level)
    }

    @Test
    fun emptyListScoresZeroAndIsLow() {
        val home = ConfidenceScorer.home(0, 0.0, 0.0, 0.0, false)
        assertEquals(0.0, home.score, 0.001)
        assertEquals(ConfidenceLevel.LOW, home.level)
    }

    @Test
    fun fewItemsDragsHomeDown() {
        val few = ConfidenceScorer.home(3, 1.0, 1.0, 1.0, true)
        val many = ConfidenceScorer.home(30, 1.0, 1.0, 1.0, true)
        assertTrue("3 部作品不该和 30 部同分", few.score < many.score)
    }

    @Test
    fun brokenReaderIsTheWeakestLink() {
        val home = ConfidenceScorer.home(40, 1.0, 1.0, 1.0, true)
        val detail = ConfidenceScorer.detail(15, 1.0, true)
        val reader = ConfidenceScorer.reader(2, 0.3, false)

        val confidence = DetectionConfidence(
            home = home,
            detail = detail,
            reader = reader,
            overall = ConfidenceScorer.overall(home, detail, reader)
        )

        assertEquals(ConfidenceLevel.LOW, reader.level)
        assertNotNull(confidence.weakest)
        assertTrue(
            "最弱项应该出在正文阶段，实际是 ${confidence.weakest?.name}",
            confidence.weakest!!.name.contains("图片") || confidence.weakest!!.name.contains("去重")
        )
    }

    @Test
    fun searchOnlyConfigIsPenalized() {
        val home = ConfidenceScorer.home(0, 0.0, 0.0, 0.0, true)
        val detail = ConfidenceScorer.detail(10, 1.0, true)
        val reader = ConfidenceScorer.reader(30, 1.0, true)

        val normal = ConfidenceScorer.overall(home, detail, reader, searchOnly = false)
        val searchOnly = ConfidenceScorer.overall(home, detail, reader, searchOnly = true)

        assertTrue("仅搜索配置要扣分", searchOnly < normal)
        assertEquals(0.08, normal - searchOnly, 0.001)
    }

    @Test
    fun overallStaysInRange() {
        val home = ConfidenceScorer.home(100, 1.0, 1.0, 1.0, true)
        val detail = ConfidenceScorer.detail(100, 1.0, true)
        val reader = ConfidenceScorer.reader(500, 1.0, true)
        val value = ConfidenceScorer.overall(home, detail, reader, searchOnly = true)
        assertTrue(value in 0.0..1.0)
    }

    @Test
    fun confidenceIsSerializedForStorage() {
        val confidence = DetectionConfidence(
            home = ConfidenceScorer.home(60, 1.0, 1.0, 1.0, true),
            detail = ConfidenceScorer.detail(20, 1.0, true),
            reader = ConfidenceScorer.reader(200, 1.0, true),
            overall = 0.92
        )
        val stored = confidence.toSourceConfidence()

        assertEquals(0.92, stored.overall, 0.001)
        assertEquals(92, stored.percent)
        assertEquals(ConfidenceLevel.HIGH, stored.level)
    }
}
