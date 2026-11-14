package com.example.hellocompose.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史记录的磁盘格式。
 *
 * 这段编解码每次翻页都要跑一遍，而且一旦写坏就是「历史全没了」，
 * 所以单独测：往返不丢字段、脏数据不连坐、空内容不炸。
 */
class ReadingRecordJsonTest {

    private fun record(
        detailUrl: String = "https://a.com/book/1",
        chapterTitle: String = "第 8 话",
        pageIndex: Int = 19
    ) = ReadingRecord(
        mangaTitle = "测试漫画",
        coverUrl = "https://a.com/cover.jpg",
        detailUrl = detailUrl,
        sourceId = "src_a",
        sourceName = "A 站",
        chapterTitle = chapterTitle,
        chapterUrl = "https://a.com/book/1/8",
        pageIndex = pageIndex,
        pageCount = 30,
        chapterIndex = 7,
        chapterCount = 12,
        readChapterTitles = listOf("第 7 话", "第 8 话"),
        updatedAt = 1762400000000L
    )

    @Test
    fun `往返编码解码不丢字段`() {
        val original = record()
        val decoded = ReadingRecordJson.decode(ReadingRecordJson.encode(listOf(original)))

        assertEquals(1, decoded.size)
        assertEquals(original, decoded.first())
    }

    @Test
    fun `坏条目被跳过，好条目照常读出`() {
        val good = ReadingRecordJson.encode(listOf(record()))
        // 第二条缺 detailUrl：既不能续读也不能搜索，属于无效记录
        val mixed = good.dropLast(1) + """,{"t":"缺地址的书","ct":"第 1 话"}]"""

        val decoded = ReadingRecordJson.decode(mixed)

        assertEquals(1, decoded.size)
        assertEquals("测试漫画", decoded.first().mangaTitle)
    }

    @Test
    fun `空内容和非法内容都返回空列表`() {
        assertTrue(ReadingRecordJson.decode(null).isEmpty())
        assertTrue(ReadingRecordJson.decode("").isEmpty())
        assertTrue(ReadingRecordJson.decode("不是 JSON").isEmpty())
    }

    @Test
    fun `页码和章节名拼成一句话`() {
        assertEquals("第 8 话 · 第 20 / 30 页", record().progressLabel)
        // 还没开始看的记录不应该显示「第 0 页」
        assertEquals("", record(pageIndex = -1).pageLabel)
    }
}
