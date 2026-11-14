package com.example.hellocompose.data.source

import com.example.hellocompose.data.model.Chapter

/**
 * 换源时的章节定位。
 *
 * 同一本书在 A 站叫「第 8 话 觉醒」，到 B 站可能叫「第08话」，甚至「Vol.2 Ch.8」。
 * 标题没法直接比，但章号几乎总是对得上，所以：
 * 1. 标题完全相同就用它；
 * 2. 否则比章号，从标题里抠出「第 N 话 / 章 / 回 / 集」里的 N；
 * 3. 再不行放弃，让用户自己去目录里挑。
 *
 * 宁可返回 null 也不要猜错章节 —— 猜错比不跳还难受。
 */
fun matchChapter(targetTitle: String, candidates: List<Chapter>): Chapter? {
    val target = targetTitle.trim()
    if (target.isEmpty() || candidates.isEmpty()) return null

    candidates.firstOrNull { it.title.trim() == target }?.let { return it }

    val number = chapterNumber(target) ?: return null
    return candidates.firstOrNull { chapterNumber(it.title) == number }
}

/** 「第 8 话」里的 8；抓不到就退化成标题里的第一个数字 */
fun chapterNumber(title: String): Double? {
    CHAPTER_PATTERN.find(title)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
    return FIRST_NUMBER.find(title)?.value?.toDoubleOrNull()
}

/** 优先认「第 N 话」这种写法，避免「Vol.2 第 80 话」被认成第 2 话 */
private val CHAPTER_PATTERN = Regex("""第\s*(\d+(?:\.\d+)?)\s*[话話章回集]""")

private val FIRST_NUMBER = Regex("""\d+(?:\.\d+)?""")
