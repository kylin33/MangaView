package com.example.hellocompose.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

/**
 * 什么错误值得重试。
 *
 * 预加载的重试是「用户看不见」的行为，做错了只能表现为
 * 「这一段特别费流量」或者「证书坏了还在疯狂重连」。
 */
class RetryPolicyTest {

    @Test
    fun transientNetworkErrorIsRetried() {
        assertTrue(RetryPolicy.shouldRetry(1, IOException("connection reset")))
        assertTrue(RetryPolicy.shouldRetry(1, SocketTimeoutException("timeout")))
    }

    @Test
    fun certificateErrorIsNeverRetried() {
        // 证书不对，重试一百次也还是那张证书，只会拖慢后面的图
        assertFalse(RetryPolicy.shouldRetry(1, SSLHandshakeException("expired")))
    }

    @Test
    fun forbiddenIsNotRetried() {
        // 403 基本就是防盗链，该换源，不该重试
        assertFalse(RetryPolicy.shouldRetry(1, IOException("403"), httpCode = 403))
        assertFalse(RetryPolicy.shouldRetry(1, IOException("404"), httpCode = 404))
    }

    @Test
    fun serverErrorAndRateLimitAreRetried() {
        assertTrue(RetryPolicy.shouldRetry(1, IOException("502"), httpCode = 502))
        assertTrue(RetryPolicy.shouldRetry(1, IOException("429"), httpCode = 429))
    }

    @Test
    fun stopsAtMaxAttempts() {
        assertFalse(RetryPolicy.shouldRetry(RetryPolicy.MAX_ATTEMPTS, IOException("oops")))
        assertTrue(RetryPolicy.shouldRetry(RetryPolicy.MAX_ATTEMPTS - 1, IOException("oops")))
    }

    @Test
    fun unknownFailureIsNotRetried() {
        assertFalse(RetryPolicy.shouldRetry(1, null))
        assertFalse(RetryPolicy.shouldRetry(1, IllegalStateException("解码失败")))
    }

    @Test
    fun backoffGrowsAndEnds() {
        assertEquals(400L, RetryPolicy.delayMs(1))
        assertEquals(800L, RetryPolicy.delayMs(2))
        assertNull(RetryPolicy.delayMs(RetryPolicy.MAX_ATTEMPTS))
    }
}
