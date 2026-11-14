package com.example.hellocompose.engine.reader

import com.example.hellocompose.data.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录顺序归一化。
 *
 * 这个逻辑错了不会崩溃，只会表现成「点下一章跳到上一章」，
 * 而且只在「最新在前」的站点上才复现——所以必须单独测。
 */
class ChapterOrderTest {

    @Test
    fun keepsAscendingListUntouched() {
        val list = listOf(
            Chapter("第 1 话", "/c/1"),
            Chapter("第 2 话", "/c/2"),
            Chapter("第 3 话", "/c/3")
        )
        assertEquals(list, ChapterOrder.normalize(list))
    }

    @Test
    fun reversesDescendingList() {
        val list = listOf(
            Chapter("第 3 话", "/c/3"),
            Chapter("第 2 话", "/c/2"),
            Chapter("第 1 话", "/c/1")
        )
        assertEquals(
            listOf("/c/1", "/c/2", "/c/3"),
            ChapterOrder.normalize(list).map { it.url }
        )
    }

    @Test
    fun keepsTitlesWithoutNumbersAsIs() {
        val list = listOf(
            Chapter("序章", "/c/a"),
            Chapter("番外", "/c/b"),
            Chapter("后记", "/c/c")
        )
        assertEquals(list, ChapterOrder.normalize(list))
    }

    @Test
    fun toleratesPartiallyNumberedTitles() {
        // 一半有数字一半没有：比较不出趋势，保持原顺序，不要瞎反转
        val list = listOf(
            Chapter("第 5 话", "/c/5"),
            Chapter("特别篇", "/c/sp"),
            Chapter("第 6 话", "/c/6")
        )
        assertEquals(list, ChapterOrder.normalize(list))
    }

    @Test
    fun singleChapterIsStable() {
        val list = listOf(Chapter("第 1 话", "/c/1"))
        assertEquals(list, ChapterOrder.normalize(list))
    }

    @Test
    fun comparesUrlsIgnoringQueryAndSlash() {
        assertEquals(true, ChapterUrls.same("/c/12", "/c/12/"))
        assertEquals(true, ChapterUrls.same("/c/12?p=2", "/c/12"))
        assertEquals(false, ChapterUrls.same("/c/12", "/c/13"))
    }
}
