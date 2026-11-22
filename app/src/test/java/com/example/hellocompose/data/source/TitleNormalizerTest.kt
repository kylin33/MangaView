package com.example.hellocompose.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标题归一化的回归测试。
 *
 * 这里的每条用例都对应一个真实站点的写法，而且后果都很具体：
 * 书名带「14小时前」时搜索搜不到、章节名被截断成「第1话-阿姨，我」时用户分不清是哪一话、
 * 把「书名-第1话」当章节名时每一行都重复显示书名。
 */
class TitleNormalizerTest {

    // ==================== 徽章剥离 ====================

    @Test
    fun stripsRelativeTimeBadge() {
        assertEquals("姨见钟情", TitleNormalizer.stripBadges("14小时前 姨见钟情"))
        assertEquals("猎艷管理员", TitleNormalizer.stripBadges("3天前 猎艷管理员"))
        assertEquals("秘密教学", TitleNormalizer.stripBadges("刚刚 秘密教学"))
    }

    @Test
    fun stripsAbsoluteDateAndStatusBadges() {
        assertEquals("雪山飞狐外传", TitleNormalizer.stripBadges("2026-11-20 雪山飞狐外传"))
        assertEquals("三侠五义", TitleNormalizer.stripBadges("[连载] 三侠五义"))
        assertEquals("说岳全传", TitleNormalizer.stripBadges("说岳全传 更新至第18话"))
    }

    /**
     * 数字开头的书名不能被当成时间徽章。这类误伤比不去徽章更糟：
     * 用户会看到书名少了一截，而且完全猜不到为什么。
     */
    @Test
    fun keepsTitlesThatStartWithNumbers() {
        assertEquals("14岁的夏天", TitleNormalizer.stripBadges("14岁的夏天"))
        assertEquals("3月的狮子", TitleNormalizer.stripBadges("3月的狮子"))
        assertEquals("86-不存在的战区", TitleNormalizer.stripBadges("86-不存在的战区"))
    }

    @Test
    fun cutsTrailingAuthorAndUpdateFields() {
        assertEquals(
            "雪山飞狐外传",
            TitleNormalizer.stripBadges("雪山飞狐外传 作者：金先生 更新：第30回")
        )
        assertEquals(
            "斗破苍穹",
            TitleNormalizer.stripBadges("斗破苍穹 人气：1234万 状态：连载中")
        )
    }

    // ==================== 与 title 属性合并 ====================

    @Test
    fun prefersTitleAttributeWhenVisibleTextIsTruncated() {
        // 站点把长章节名截断在可见文本里，完整名在 title 属性上
        assertEquals(
            "第1话-阿姨，我们结婚吧",
            TitleNormalizer.merge("第1话-阿姨，我", "第1话-阿姨，我们结婚吧")
        )
    }

    @Test
    fun prefersTitleAttributeWhenVisibleTextCarriesBadge() {
        assertEquals(
            "姨见钟情",
            TitleNormalizer.merge("14小时前\n姨见钟情", "姨见钟情")
        )
    }

    /**
     * 反向情形：`title` 是「书名-第N话」，可见文字才是干净的章节名。
     * 这里必须保留可见文字，否则章节列表每一行都会重复一遍书名。
     */
    @Test
    fun keepsVisibleTextWhenTitleAttributePrefixesBookName() {
        assertEquals("第1话", TitleNormalizer.merge("第1话", "某漫画-第1话"))
        assertEquals(
            "第12话 决战",
            TitleNormalizer.merge("第12话 决战", "斗破苍穹-第12话 决战")
        )
    }

    @Test
    fun ignoresGenericTitleAttribute() {
        assertEquals("斗破苍穹", TitleNormalizer.merge("斗破苍穹", "点击查看"))
        assertEquals("斗破苍穹", TitleNormalizer.merge("斗破苍穹", ""))
    }

    @Test
    fun fallsBackToTitleAttributeWhenVisibleTextIsEmpty() {
        // 图片型条目：可见文字是空的，书名只在 alt / title 上
        assertEquals("某漫画", TitleNormalizer.merge("", "某漫画"))
        assertEquals("", TitleNormalizer.merge("", ""))
    }

    @Test
    fun rejectsMetadataOnlyTitleAttribute() {
        // title 比可见文字长，但多出来的全是「作者/更新」，不是被截断的书名
        assertEquals(
            "雪山飞狐外传",
            TitleNormalizer.merge("雪山飞狐外传", "雪山飞狐外传 作者：金先生 更新：第30回")
        )
    }

    @Test
    fun identifiesGenericText() {
        assertTrue(TitleNormalizer.isGeneric("点击查看"))
        assertTrue(TitleNormalizer.isGeneric("暂无"))
        assertFalse(TitleNormalizer.isGeneric("姨见钟情"))
    }
}
