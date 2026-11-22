package com.example.hellocompose.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索请求构造的回归测试。
 *
 * 这是「识别出来的规则」和「运行时真的发出的请求」之间唯一的连接点。
 * 两边各写一遍拼地址的逻辑，就会出现识别时说可用、用户搜出来一片空白的情况 ——
 * 那种错误读代码看不出来，只能靠这里把每种形态钉死。
 */
class SearchRequestTest {

    private val base = "https://site.example"

    private fun rule(
        path: String,
        method: HttpMethod = HttpMethod.GET,
        keywordParam: String = "keyword",
        fields: Map<String, String> = emptyMap()
    ) = ListRule(
        path = path,
        item = ".item",
        method = method,
        keywordParam = keywordParam,
        fields = fields
    )

    @Test
    fun replacesKeywordPlaceholder() {
        val request = SearchRequestBuilder.build(rule("/search?keyword={keyword}"), "夜色霓虹", base)
        assertEquals("https://site.example/search?keyword=%E5%A4%9C%E8%89%B2%E9%9C%93%E8%99%B9", request.url)
        assertEquals(HttpMethod.GET, request.method)
        assertTrue(request.body.isEmpty())
    }

    @Test
    fun keepsFixedFieldsBeforeKeyword() {
        val request = SearchRequestBuilder.build(
            rule("/index.php?m=search&a=init&wd={keyword}"),
            "夜色",
            base
        )
        assertEquals(
            "https://site.example/index.php?m=search&a=init&wd=%E5%A4%9C%E8%89%B2",
            request.url
        )
    }

    @Test
    fun postSendsKeywordInBody() {
        val request = SearchRequestBuilder.build(
            rule("/search.php", method = HttpMethod.POST, keywordParam = "searchword"),
            "夜色霓虹",
            base
        )
        // 地址里不能有关键字：POST 的语义就是把参数放请求体
        assertEquals("https://site.example/search.php", request.url)
        assertEquals(HttpMethod.POST, request.method)
        // 请求体保持原文，编码交给表单编码（jsoup 的 data()）
        assertEquals(mapOf("searchword" to "夜色霓虹"), request.body)
    }

    @Test
    fun postMergesHiddenFields() {
        val request = SearchRequestBuilder.build(
            rule(
                "/index.php",
                method = HttpMethod.POST,
                keywordParam = "kw",
                fields = mapOf("m" to "search", "a" to "init")
            ),
            "夜色",
            base
        )
        assertEquals(mapOf("m" to "search", "a" to "init", "kw" to "夜色"), request.body)
    }

    @Test
    fun keywordParamUsedWhenPathHasNoPlaceholder() {
        // GET 但地址里没有占位符：关键字要作为独立参数拼上去
        val request = SearchRequestBuilder.build(
            rule("/search", keywordParam = "wd", fields = mapOf("type" to "1")),
            "夜色",
            base
        )
        assertEquals("https://site.example/search?type=1&wd=%E5%A4%9C%E8%89%B2", request.url)
    }

    @Test
    fun absolutePathIsKept() {
        val request = SearchRequestBuilder.build(
            rule("https://img.example/search?q={keyword}"),
            "x",
            base
        )
        assertEquals("https://img.example/search?q=x", request.url)
    }

    @Test
    fun relativePathGetsSlash() {
        val request = SearchRequestBuilder.build(rule("search?q={keyword}"), "x", base)
        assertEquals("https://site.example/search?q=x", request.url)
    }

    @Test
    fun relativizeStripsOwnOriginOnly() {
        assertEquals(
            "/search?q=x",
            SearchRequestBuilder.relativize("https://site.example/search?q=x", base)
        )
        // 别的域名（API 子站、图片站）不能缩成相对路径，否则会指向错误的主机
        assertEquals(
            "https://api.example/search?q=x",
            SearchRequestBuilder.relativize("https://api.example/search?q=x", base)
        )
    }

    @Test
    fun spaceEncodedAsPlus() {
        val request = SearchRequestBuilder.build(rule("/search?q={keyword}"), "a b", base)
        assertEquals("https://site.example/search?q=a+b", request.url)
    }
}
