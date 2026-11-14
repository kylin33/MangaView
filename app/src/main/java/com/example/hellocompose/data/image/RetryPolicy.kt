package com.example.hellocompose.data.image

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * 图片请求的重试策略。
 *
 * 预加载是「顺手做」的事，失败了用户看不到，但重试错了类型只会更糟：
 * - 证书不对：重试一百次还是那张证书，只会拖慢后面的图；
 * - 403 / 404：防盗链或图已删除，再试也是白费流量；
 * - 超时、断流、5xx：这种重试才真的有用。
 *
 * 抽成纯函数是为了能不联网就把这些边界测干净。
 */
object RetryPolicy {

    /** 含首次请求在内，最多尝试几次 */
    const val MAX_ATTEMPTS = 3

    private const val BASE_DELAY_MS = 400L

    /** [attempt] 是已经试过的次数（首次请求后为 1） */
    fun shouldRetry(attempt: Int, error: Throwable?, httpCode: Int? = null): Boolean {
        if (attempt >= MAX_ATTEMPTS) return false
        if (error == null && httpCode == null) return false

        if (httpCode != null) {
            // 服务端错误和限流值得等一会儿再试，其余 4xx 不值得
            return httpCode >= 500 || httpCode == 429
        }

        if (error is SSLException || error is CertificateException) return false
        return error is IOException
    }

    /** 第 [attempt] 次失败后等多久再试；返回 null 表示不再重试 */
    fun delayMs(attempt: Int): Long? {
        if (attempt >= MAX_ATTEMPTS) return null
        return BASE_DELAY_MS shl (attempt - 1).coerceAtLeast(0)
    }
}
