package com.example.hellocompose.engine.autogen

import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document

/**
 * 用本地夹具代替网络：按 [FixtureSite.routes] 把请求地址映射到固定 HTML。
 *
 * 没登记的地址返回 404，而不是「静默返回首页」—— 静默兜底会掩盖「搜索地址根本没有被实现」
 * 这类问题，测试看起来全绿，实际只是所有请求都撞在同一份 HTML 上。
 */
class FixturePageFetcher(private val site: FixtureSite) : PageFetcher {

    /** 记录实际请求过的地址，方便排查「为什么没走到某一步」 */
    val requests = mutableListOf<String>()

    override fun get(
        url: String,
        referer: String,
        trustAllCerts: Boolean,
        allowPlainHttp: Boolean
    ): Document {
        requests += url
        val pathAndQuery = pathAndQueryOf(url)
        val route = site.routes.firstOrNull { it.matches(pathAndQuery) }
            ?: throw HttpStatusException("夹具没有登记 $pathAndQuery", 404, url)
        return site.document(route.page, url)
    }

    /** 去掉 scheme 和 host，保留 path + query（含开头的 /） */
    private fun pathAndQueryOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "/" else afterScheme.substring(slash).ifEmpty { "/" }
    }
}
