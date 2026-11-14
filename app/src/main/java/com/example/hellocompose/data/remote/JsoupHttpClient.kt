package com.example.hellocompose.data.remote

import com.example.hellocompose.core.AppConfig
import com.example.hellocompose.data.source.SourceConfig
import org.jsoup.Jsoup
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 统一的 HTTP 入口，所有参数来自站点配置。
 *
 * 之所以要包一层：jsoup 1.17 起移除了 Connection.ignoreSslErrors()，
 * 只能通过 sslSocketFactory() 注入自定义工厂；UA / Referer / 超时也在这里统一，
 * 避免每个抓取方法各写一遍。换站点时这些值跟着 [SourceConfig] 走，不用改代码。
 */
class JsoupHttpClient {

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
        trustAllCerts = source.trustAllCerts
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
        trustAllCerts: Boolean = false
    ): Document {
        var lastError: Exception? = null

        // 5xx / 超时重试一次。
        // 不少漫画站前面挂着不稳定的反代，同一个地址偶发 502 是常态，
        // 不重试的话用户翻页会随机看到"加载失败"。4xx 是确定性的（地址错、被拦），重试没意义。
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return request(url, userAgent, referer, timeoutMs, trustAllCerts)
            } catch (e: HttpStatusException) {
                if (e.statusCode !in 500..599) throw e
                lastError = e
            } catch (e: SocketTimeoutException) {
                lastError = e
            }

            if (attempt < MAX_ATTEMPTS - 1) {
                // 退避一下再试，避免连续两次都撞在同一个抖动上
                runCatching { Thread.sleep(RETRY_DELAY_MS) }
            }
        }
        throw lastError ?: IllegalStateException("请求失败：$url")
    }

    private fun request(
        url: String,
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

        if (trustAllCerts) {
            connection.sslSocketFactory(trustAllSocketFactory)
        }
        return connection.get()
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 700L
    }
}
