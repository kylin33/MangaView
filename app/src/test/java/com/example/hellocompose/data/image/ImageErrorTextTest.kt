package com.example.hellocompose.data.image

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 图片失败时的那句话。
 *
 * 403 和超时对用户来说是两件完全不同的事：一个该换源，一个该等一会儿。
 * 都笼统说「加载失败」，用户只能一直点重试。
 */
class ImageErrorTextTest {

    @Test
    fun forbiddenSuggestsSwitchingSource() {
        val text = ImageErrorText.of(IOException("403"), httpCode = 403)
        assertTrue(text.contains("换源"))
    }

    @Test
    fun otherHttpCodesShowStatus() {
        assertTrue(ImageErrorText.of(IOException("500"), httpCode = 500).contains("500"))
    }

    @Test
    fun certificateFailureIsNamed() {
        assertTrue(ImageErrorText.of(SSLHandshakeException("expired")).contains("证书"))
    }

    @Test
    fun networkFailuresAreDistinguished() {
        assertTrue(ImageErrorText.of(SocketTimeoutException("timeout")).contains("超时"))
        assertTrue(ImageErrorText.of(UnknownHostException("dns")).contains("网络"))
        assertTrue(ImageErrorText.of(IOException("broken pipe")).contains("中断"))
    }

    @Test
    fun unknownFailureStillReadable() {
        assertTrue(ImageErrorText.of(IllegalStateException("?")).isNotEmpty())
    }
}
