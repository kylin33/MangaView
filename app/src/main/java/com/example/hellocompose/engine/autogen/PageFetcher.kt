package com.example.hellocompose.engine.autogen

import com.example.hellocompose.data.remote.JsoupHttpClient
import com.example.hellocompose.data.source.SearchRequestBuilder
import org.jsoup.nodes.Document

/**
 * 取一页 HTML。识别流程对外的唯一依赖。
 *
 * 抽成接口不是为了「以后可能换实现」，而是为了让识别算法能被离线验证：
 * 识别是启发式的，改一条打分规则很难靠读代码判断有没有让别的站点退化，
 * 唯一靠谱的办法是拿一批固定 HTML 跑完整流程（见 test/resources/fixtures）。
 * 如果生成器直接吃 [JsoupHttpClient]，任何回归测试都得联网，
 * 站点一改版测试就红，人很快就不看它了。
 *
 * 失败时照常抛异常（[org.jsoup.HttpStatusException]、TlsException、IOException），
 * 由调用方翻译成人话 —— 这里不做包装，否则「打不开」和「打开了但不是漫画站」会被混在一起。
 */
fun interface PageFetcher {

    fun get(url: String, referer: String, trustAllCerts: Boolean, allowPlainHttp: Boolean): Document

    /**
     * 表单 POST。站内搜索的表单可能是 `method="post"`，识别时得能真的发出去。
     *
     * 默认实现把表单拼进查询串退回 GET —— 这样只关心 GET 的测试替身不用改。
     * 但**真连真抓**的实现必须覆盖它（见 [Companion.http]），否则「识别时用 GET 探通了、
     * 实际运行却按 POST 发」就成了两边行为不一致。
     */
    fun post(
        url: String,
        form: Map<String, String>,
        referer: String,
        trustAllCerts: Boolean,
        allowPlainHttp: Boolean
    ): Document {
        val query = form.entries.joinToString("&") { (k, v) ->
            "${SearchRequestBuilder.encode(k)}=${SearchRequestBuilder.encode(v)}"
        }
        val separator = if (url.contains('?')) "&" else "?"
        return get(url + separator + query, referer, trustAllCerts, allowPlainHttp)
    }

    companion object {
        /** 默认实现：真连真抓，行为与直接使用 [JsoupHttpClient] 完全一致 */
        fun http(client: JsoupHttpClient = JsoupHttpClient()): PageFetcher = object : PageFetcher {
            override fun get(url: String, referer: String, trustAllCerts: Boolean, allowPlainHttp: Boolean): Document =
                client.get(
                    url = url,
                    referer = referer,
                    trustAllCerts = trustAllCerts,
                    allowPlainHttp = allowPlainHttp
                )

            override fun post(
                url: String,
                form: Map<String, String>,
                referer: String,
                trustAllCerts: Boolean,
                allowPlainHttp: Boolean
            ): Document = client.post(
                url = url,
                form = form,
                referer = referer,
                trustAllCerts = trustAllCerts,
                allowPlainHttp = allowPlainHttp
            )
        }
    }
}
