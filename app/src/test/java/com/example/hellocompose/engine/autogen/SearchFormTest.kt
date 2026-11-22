package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.source.HttpMethod
import com.example.hellocompose.data.source.ListRule
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表单探测的回归测试。
 *
 * 这一层只做「读页面上的 `<form>`」，不发请求，所以每条断言都是对一份固定 HTML 的
 * 纯函数结果。它决定了两件事：
 * 1. 搜索用 GET 还是 POST、关键字参数叫什么、要不要带隐藏域；
 * 2. 哪些表单**不能**当成搜索框（登录、注册、评论）。
 *
 * 第 2 点比第 1 点更要紧：把登录表单当成搜索框，识别器会拿着书名去登录接口请求，
 * 返回的「密码错误」页面被当作搜索结果，最后写进配置的是一条永远搜不到东西的规则。
 */
class SearchFormTest {

    private fun page(html: String): org.jsoup.nodes.Document =
        Jsoup.parse("<html><body>$html</body></html>", "https://site.example/list/hot")

    private fun scan(html: String): List<SearchForm> =
        SearchFormScanner.scan(page(html), "https://site.example/list/hot", "https://site.example")

    // ==================== 正常的搜索表单 ====================

    @Test
    fun readsGetFormWithHiddenFields() {
        val forms = scan(
            """
            <form action="/index.php" method="get">
              <input type="hidden" name="m" value="search">
              <input type="hidden" name="a" value="init">
              <input type="text" name="wd" placeholder="请输入关键词">
              <button type="submit">搜索</button>
            </form>
            """
        )
        assertEquals(1, forms.size)
        val form = forms.single()
        assertEquals(HttpMethod.GET, form.method)
        assertEquals("wd", form.keywordParam)
        assertEquals(mapOf("m" to "search", "a" to "init"), form.fields)
        // 隐藏域必须进 path：少一个参数，站点就把请求重定向回首页
        assertEquals("/index.php?m=search&a=init&wd={keyword}", form.pathTemplate())
    }

    @Test
    fun keepsPostMethodAndKeywordOutOfUrl() {
        val forms = scan(
            """
            <form class="search-box" action="/search.php" method="post">
              <input type="text" name="searchword">
              <input type="submit" value="搜索">
            </form>
            """
        )
        val form = forms.single()
        assertEquals(HttpMethod.POST, form.method)
        assertEquals("searchword", form.keywordParam)
        // POST 的关键字不能出现在地址里，只能靠 keywordParam 进请求体
        assertEquals("/search.php", form.pathTemplate())
        assertEquals(emptyMap<String, String>(), form.fields)
    }

    @Test
    fun relativeActionResolvesAgainstPageNotSiteRoot() {
        // 首页在 /list/hot 时，action="search.php" 指的是 /list/search.php
        val forms = scan(
            """<form action="search.php"><input type="search" name="q"></form>"""
        )
        assertEquals("/list/search.php?q={keyword}", forms.single().pathTemplate())
    }

    @Test
    fun emptyActionSubmitsToCurrentPage() {
        val forms = scan("""<form><input type="text" name="keyword"></form>""")
        assertEquals("/list/hot?keyword={keyword}", forms.single().pathTemplate())
    }

    @Test
    fun inputWithoutTypeIsStillText() {
        // 老站常写 <input name="wd">，不写 type。按 type 过滤会漏掉一半站点
        val forms = scan("""<form action="/search"><input name="wd"></form>""")
        assertEquals("wd", forms.single().keywordParam)
    }

    @Test
    fun queryInActionIsFoldedIntoFields() {
        // action 自带查询串时，POST 的固定参数要跟着进请求体，否则查的就是全部
        val forms = scan(
            """<form action="/index.php?m=search" method="post"><input name="kw"></form>"""
        )
        val form = forms.single()
        assertEquals("/index.php", form.action)
        assertEquals(mapOf("m" to "search"), form.fields)
    }

    @Test
    fun picksSearchFormOverLoginWhenBothPresent() {
        val forms = scan(
            """
            <form action="/user/login" method="post">
              <input type="text" name="username"><input type="password" name="password">
            </form>
            <form action="/search" method="get"><input type="text" name="keyword"></form>
            """
        )
        assertEquals(1, forms.size)
        assertEquals("/search?keyword={keyword}", forms.single().pathTemplate())
    }

    @Test
    fun sortsByConfidence() {
        // 两个都能当搜索框，但 /search 那个有 action 和输入框提示两条证据
        val forms = scan(
            """
            <form action="/s"><input type="text" name="k" placeholder="搜索"></form>
            <form action="/search" role="search">
              <input type="search" name="keyword" placeholder="搜索漫画">
            </form>
            """
        )
        assertEquals(2, forms.size)
        assertEquals("/search?keyword={keyword}", forms.first().pathTemplate())
        assertTrue("分数应该降序", forms[0].score >= forms[1].score)
    }

    // ==================== 不能当搜索框的表单 ====================

    @Test
    fun rejectsLoginFormWithPassword() {
        val forms = scan(
            """
            <form action="/login" method="post">
              <input type="text" name="user"><input type="password" name="pass">
            </form>
            """
        )
        assertTrue("带密码框的表单不可能是站内搜索", forms.isEmpty())
    }

    @Test
    fun rejectsFormWithoutTextInput() {
        val forms = scan(
            """
            <form action="/search"><input type="hidden" name="keyword" value="x">
              <input type="submit" value="搜索"></form>
            """
        )
        assertTrue("没有文本输入框，用户输入不了关键词", forms.isEmpty())
    }

    @Test
    fun rejectsNewsletterAndEmailForms() {
        val forms = scan(
            """
            <form action="/subscribe" method="post"><input name="email"><input type="submit"></form>
            """
        )
        assertTrue(forms.isEmpty())
    }

    @Test
    fun rejectsFilterFormWithTooManyInputs() {
        val inputs = (1..12).joinToString("") { """<input type="text" name="f$it">""" }
        val forms = scan("""<form action="/search">$inputs</form>""")
        assertTrue("十几个输入框的是筛选器，不是搜索框", forms.isEmpty())
    }

    @Test
    fun rejectsJavascriptAction() {
        // 提交逻辑在 JS 里，action 写 javascript:; —— 拿这个地址发请求只会得到一片空白
        val forms = scan(
            """<form action="javascript:;"><input name="keyword"></form>"""
        )
        assertTrue(forms.isEmpty())
    }

    // ==================== 参数名推断 ====================

    @Test
    fun paramHintsReadsInputsOutsideForms() {
        // 没有 form、提交逻辑写在 JS 里：参数名仍然在 DOM 里，可以用来拼地址
        val hints = SearchFormScanner.paramHints(
            page(
                """
                <input id="sk" name="k" type="text">
                <input id="mail" name="email">
                """
            )
        )
        assertEquals(listOf("k"), hints)
    }

    @Test
    fun paramHintsPrefersStrongNames() {
        val hints = SearchFormScanner.paramHints(
            page(
                """
                <input name="title">
                <input name="wd">
                <input name="keyword">
                """
            )
        )
        // 同权重按 DOM 顺序排：三种都是常见搜索参数，没有理由认为哪个更可信
        assertEquals(listOf("wd", "keyword", "title"), hints)
    }

    @Test
    fun scanIgnoresPagesWithoutForms() {
        assertNull(scan("<nav><a href=\"/\">首页</a></nav>").firstOrNull())
    }

    @Test
    fun ruleOverKeepsHomeSelectors() {
        val home = ListRule(path = "/", item = ".book-list .item")
        val form = scan("""<form action="/search"><input type="text" name="wd"></form>""").single()
        val rule = form.ruleOver(home)
        assertEquals("/search?wd={keyword}", rule.path)
        assertEquals(".book-list .item", rule.item)
    }
}
