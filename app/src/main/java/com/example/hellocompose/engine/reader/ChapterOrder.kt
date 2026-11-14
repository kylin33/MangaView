package com.example.hellocompose.engine.reader

import com.example.hellocompose.data.model.Chapter

/**
 * 章节目录的顺序归一化。
 *
 * 有的站点按「最新在前」排，直接 +1 会翻回上一章。这里比较相邻标题里的数字，
 * 整体递减就反转一次，之后统一按「从早到晚」处理。
 *
 * 独立成纯函数而不是塞在 ViewModel 里：它和网络、UI 都无关，
 * 但错了会表现为「下一章按钮跳到上一章」，最容易回归的地方值得单独测。
 */
object ChapterOrder {

    private val NUMBER = Regex("\\d+")

    fun normalize(list: List<Chapter>): List<Chapter> {
        if (list.size < 2) return list

        val comparisons = list
            .map { NUMBER.find(it.title)?.value?.toIntOrNull() }
            .zipWithNext()
            .mapNotNull { (a, b) -> if (a == null || b == null) null else a.compareTo(b) }

        val descending = comparisons.count { it > 0 }
        val ascending = comparisons.count { it < 0 }
        return if (descending > ascending) list.reversed() else list
    }

    /** 标题里第一个数字，用来判断章节先后（没有就返回 null） */
    fun numberOf(title: String): Int? = NUMBER.find(title)?.value?.toIntOrNull()
}

/**
 * 章节地址比较。
 *
 * 同一章在不同位置可能带不同的查询串或末尾斜杠（列表里 `/c/12`、翻页后 `/c/12?p=2`），
 * 严格相等会认不出来，导致「明明在目录里却显示没有上一章」。
 */
object ChapterUrls {
    fun same(a: String, b: String): Boolean =
        a == b || a.substringBefore('?').trimEnd('/') == b.substringBefore('?').trimEnd('/')
}
