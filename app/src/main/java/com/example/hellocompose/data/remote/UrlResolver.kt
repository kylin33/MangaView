package com.example.hellocompose.data.remote

import java.net.URI
import java.net.URL

/**
 * 把页面里出现的各种相对地址补全成绝对地址。
 *
 * 站点根地址由调用方传入（来自站点配置），这里不再持有任何全局常量。
 */
object UrlResolver {

    fun resolve(raw: String, base: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return ""
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> base.trimEnd('/') + clean
            else -> runCatching { URI(base.trimEnd('/') + "/").resolve(clean).toString() }
                .getOrElse { "${base.trimEnd('/')}/$clean" }
        }
    }

    /** 取站点根地址（scheme + host），解析失败时返回空串 */
    fun originOf(url: String): String = runCatching {
        val parsed = URL(url)
        "${parsed.protocol}://${parsed.host}"
    }.getOrElse { "" }
}
