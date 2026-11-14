package com.example.hellocompose.data.source

import com.example.hellocompose.data.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 换源时的章节对位。
 *
 * 对错了比不跳还难受（用户会以为自己看串了），所以规则要收紧：
 * 只有标题一致或章号一致才认，其余一律交回用户自己挑。
 */
class ChapterMatchTest {

    private fun chapters(vararg titles: String) =
        titles.mapIndexed { index, title -> Chapter(title, "https://x.com/c/$index") }

    @Test
    fun `标题完全一致直接命中`() {
        val list = chapters("第 1 话", "第 8 话", "第 9 话")
        assertEquals("第 8 话", matchChapter("第 8 话", list)?.title)
    }

    @Test
    fun `标题写法不同但章号一致也能对上`() {
        val list = chapters("第 1 话", "第8话 觉醒", "第 9 话")
        // 目标站补了零，源站带副标题，靠章号对上
        assertEquals("第8话 觉醒", matchChapter("第 08 话", list)?.title)
    }

    @Test
    fun `对不上就返回 null，不猜章节`() {
        val list = chapters("第 1 话", "第 2 话")
        assertNull(matchChapter("番外篇", list))
    }

    @Test
    fun `Vol 开头取第 N 话而不是卷号`() {
        // 直接抓第一个数字会得到 2，那就对到第 2 话了
        assertEquals(80.0, chapterNumber("Vol.2 第 80 话") ?: -1.0, 0.0)
    }

    @Test
    fun `没有数字的标题解析为空`() {
        assertNull(chapterNumber("番外篇"))
    }
}
