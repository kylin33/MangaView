package com.example.hellocompose.data.remote

import com.example.hellocompose.core.AppConfig
import com.example.hellocompose.core.PlainHttpBlockedException
import com.example.hellocompose.data.source.SourceConfig
import org.jsoup.Jsoup
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 统一的 HTTP 入口，所有参数来自站点配置。
 *
 * 之所以要包一层：jsoup 1.17 起移除了 Connection.ignoreSslErrors()，
 * 只能通过 sslSocketFactory() 注入自定义工厂；UA / Referer / 超时也在这里统一，
 * 避免每个抓取方法各写一遍。换站点时这些值跟着 [SourceConfig] 走，不用改代码。
 *
 * ## 安全策略
 *
 * 默认**严格校验**：不注入任何自定义 SSL 工厂，完全交给平台（系统 CA + 主机名校验）。
 * 只有配置里显式写了 `trustAllCerts: true` / `allowPlainHttp: true` 才会放宽，
 * 而这两个字段只有用户在识别阶段点过「仍然继续」才会被写入。所以：
 *
 * 1. 证书错误**不重试**。握手失败是确定性的结论，重试只会把真实原因拖成一句"网络异常"，
 *    也会让人误以为等一等就好了。
 * 2. 证书错误**不自动降级**。以前这里会依次尝试 https → 放宽证书 → http，
 *    那等于把中间人攻击的入口开在最常见的失败路径上。
 * 3. 明文 http 请求必须有对应的配置授权，否则直接拒绝。
 */
class JsoupHttpClient(
    private val diagnostics: TlsDiagnostics = TlsDiagnostics()
) {

    /** 仅在配置显式开启 trustAllCerts 时使用，见类注释 */
    private val trustAllSocketFactory: SSLSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        })
        SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }.socketFactory
    }

    fun get(source: SourceConfig, url: String): Document = get(
        url = url,
        userAgent = source.userAgent,
        // Referer 取配置里的模板，{baseUrl} / {pageUrl} 会被展开
        referer = source.refererFor(url),
        timeoutMs = source.timeoutMs,
        trustAllCerts = source.trustAllCerts,
        allowPlainHttp = source.allowPlainHttp
    )

    /** 按站点配置发一次表单 POST（站内搜索用） */
    fun post(source: SourceConfig, url: String, form: Map<String, String>): Document = post(
        url = url,
        form = form,
        userAgent = source.userAgent,
        referer = source.refererFor(url),
        timeoutMs = source.timeoutMs,
        trustAllCerts = source.trustAllCerts,
        allowPlainHttp = source.allowPlainHttp
    )

    /**
     * 不带站点配置的请求，供「自动识别站点」在配置尚未生成时使用。
     * 之后正式抓取一律走上面的重载，保证运行时行为只由配置决定。
     */
    fun get(
        url: String,
        userAgent: String = AppConfig.USER_AGENT,
        referer: String = "",
        timeoutMs: Long = AppConfig.TIMEOUT_MS,
        trustAllCerts: Boolean = false,
        allowPlainHttp: Boolean = false
    ): Document = request(url, null, userAgent, referer, timeoutMs, trustAllCerts, allowPlainHttp)

    /**
     * 表单 POST。
     *
     * 站内搜索用 POST 的站点不算多，但一类很常见的 CMS 模板就是这样：
     * `<form action="/index.php" method="post"><input name="searchword">`。
     * 只支持 GET 时，这类站的搜索永远识别不出来。
     *
     * 安全策略与 GET 完全一致（明文拦截、证书严格校验），不能因为换了方法就放宽。
     */
    fun post(
        url: String,
        form: Map<String, String>,
        userAgent: String = AppConfig.USER_AGENT,
        referer: String = "",
        timeoutMs: Long = AppConfig.TIMEOUT_MS,
        trustAllCerts: Boolean = false,
        allowPlainHttp: Boolean = false
    ): Document = request(url, form, userAgent, referer, timeoutMs, trustAllCerts, allowPlainHttp)

    private fun request(
        url: String,
        form: Map<String, String>?,
        userAgent: String,
        referer: String,
        timeoutMs: Long,
        trustAllCerts: Boolean,
        allowPlainHttp: Boolean
    ): Document {
        guardPlainHttp(url, allowPlainHttp)

        var lastError: Exception? = null

        // 5xx / 超时重试一次。
        // 不少漫画站前面挂着不稳定的反代，同一个地址偶发 502 是常态，
        // 不重试的话用户翻页会随机看到"加载失败"。4xx 是确定性的（地址错、被拦），重试没意义。
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return send(url, form, userAgent, referer, timeoutMs, trustAllCerts)
            } catch (e: SSLException) {
                // 证书问题必须原样上抛，不能掉进下面的重试分支：
                // 重试两次得到的是同一个结果，只会让用户多等 1.4 秒并看到更模糊的错误
                throw diagnostics.diagnose(e, url)
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: HttpStatusException) {
                if (e.statusCode !in 500..599) throw e
                lastError = e
            } catch (e: IOException) {
                // 有些平台的 TLS 错误会被包在 IOException 里，再剥一层
                val tls = generateSequence<Throwable>(e.cause) { it.cause }
                    .filterIsInstance<SSLException>()
                    .firstOrNull()
                if (tls != null) throw diagnostics.diagnose(tls, url)
                throw e
            }

            if (attempt < MAX_ATTEMPTS - 1) {
                // 退避一下再试，避免连续两次都撞在同一个抖动上
                runCatching { Thread.sleep(RETRY_DELAY_MS) }
            }
        }
        throw lastError ?: IllegalStateException("请求失败：$url")
    }

    /**
     * 明文 HTTP 的闸门。
     *
     * 校验放宽是"内容可能被伪造"，明文传输是"内容一定暴露在链路上"，后者更严重，
     * 所以不靠"记得别用 http"这种约定，直接在这里拦住。放行凭据只有一个：
     * 配置里写明了 `allowPlainHttp: true`，而那是用户在弹窗里确认过的结果。
     */
    private fun guardPlainHttp(url: String, allowPlainHttp: Boolean) {
        if (allowPlainHttp) return
        if (url.startsWith("http://", ignoreCase = true)) throw PlainHttpBlockedException(url)
    }

    private fun send(
        url: String,
        form: Map<String, String>?,
        userAgent: String,
        referer: String,
        timeoutMs: Long,
        trustAllCerts: Boolean
    ): Document {
        val connection = Jsoup.connect(url)
            .userAgent(userAgent)
            .header("Referer", referer.ifBlank { url })
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .timeout(timeoutMs.toInt())
            // 章节页可能非常长，默认 1MB 上限会截断
            .maxBodySize(0)
            // 少数站会按 Referer 之外的请求头判断，补一组常规值降低被拦概率
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

        // 只有配置显式例外才注入；其余情况保持平台默认的严格校验
        if (trustAllCerts) {
            connection.sslSocketFactory(trustAllSocketFactory)
        }
        return if (form == null) {
            connection.get()
        } else {
            // data() 会按 application/x-www-form-urlencoded 编码，与浏览器提交表单一致
            connection.data(form).post()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 700L
    }
}
