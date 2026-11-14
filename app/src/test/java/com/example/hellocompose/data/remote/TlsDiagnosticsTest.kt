package com.example.hellocompose.data.remote

import com.example.hellocompose.core.ErrorType
import com.example.hellocompose.core.PlainHttpBlockedException
import com.example.hellocompose.core.toAppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * 证书错误分类的依据是异常文本，所以这里把各平台（OpenJDK / Conscrypt / 各家 CA 库）
 * 真实出现过的措辞都固化成用例。分类错了不会导致连接被放行，但会让用户看到
 * 「TLS 校验未通过」这种等于没说的提示。
 */
class TlsDiagnosticsTest {

    // 证书探针换成 null：分类逻辑不该依赖网络，跑单测时也不能往外发请求
    private val diagnostics = TlsDiagnostics { null }

    @Test
    fun classifiesCommonCertificateFailures() {
        val cases = mapOf(
            "java.security.cert.CertPathValidatorException: Certificate expired at Sat Jan 01" to TlsProblem.EXPIRED,
            "Trust anchor for certification path not found." to TlsProblem.UNTRUSTED_ISSUER,
            "PKIX path building failed: unable to find valid certification path to requested target" to TlsProblem.UNTRUSTED_ISSUER,
            "Hostname example.com not verified: certificate: sha256/abc" to TlsProblem.HOSTNAME_MISMATCH,
            "java.security.cert.CertificateException: No subject alternative names present" to TlsProblem.HOSTNAME_MISMATCH,
            "self signed certificate in certificate chain" to TlsProblem.SELF_SIGNED,
            "Unsupported or unrecognized SSL message" to TlsProblem.NOT_TLS,
            "First record does not look like a TLS handshake" to TlsProblem.NOT_TLS,
            "javax.net.ssl.SSLHandshakeException: Received fatal alert: handshake_failure" to TlsProblem.HANDSHAKE_FAILED,
            "something nobody has seen before" to TlsProblem.OTHER
        )

        cases.forEach { (message, expected) ->
            assertEquals(message, expected, diagnostics.classify(message))
        }
    }

    @Test
    fun diagnoseKeepsOriginalCauseAndHost() {
        val cause = SSLHandshakeException("Trust anchor for certification path not found.")
        val error = diagnostics.diagnose(cause, "https://example.com/list?page=2")

        assertEquals("example.com", error.host)
        assertEquals(TlsProblem.UNTRUSTED_ISSUER, error.problem)
        assertEquals(cause, error.cause)
        // 证书记录读不到时不能影响结论，原因仍然要保留下来
        assertTrue(error.detailText.contains("example.com"))
        assertTrue(error.detailText.contains("Trust anchor"))
    }

    @Test
    fun tlsExceptionIsStillAnSslException() {
        // 老代码里有一堆 `is SSLException`，换成新类型后这些判断不能失效
        val error = diagnostics.diagnose(SSLHandshakeException("certificate expired"), "https://example.com")
        assertTrue(error is SSLException)
    }

    @Test
    fun mapsToTlsAppErrorWithAdvice() {
        val error = diagnostics.diagnose(SSLHandshakeException("Certificate expired"), "https://example.com")
            .toAppError()

        assertEquals(ErrorType.TLS, error.type)
        // 用户要看到"哪一步出错"而不是笼统的"证书校验失败"
        assertTrue(error.userMessage.contains("过期"))
        assertNotNull("证书错误必须带上处置建议", error.hint)
    }

    @Test
    fun blocksPlainHttpWithoutExplicitPermission() {
        val client = JsoupHttpClient()
        val blocked = runCatching { client.get("http://example.com/") }.exceptionOrNull()

        // 在发请求之前就拦住，所以这个用例不需要网络
        assertTrue("明文 http 必须在配置未授权时被拒绝", blocked is PlainHttpBlockedException)
        // 仍然是 IOException：老的 catch(IOException) 逻辑照样接得住，不会漏成崩溃
        assertTrue(blocked is java.io.IOException)
    }

    @Test
    fun plainHttpBlockedMapsToInsecureError() {
        val error = PlainHttpBlockedException("http://example.com/chapter/1").toAppError()
        assertEquals(ErrorType.INSECURE, error.type)
        assertTrue(error.message.orEmpty().isNotEmpty())
        assertNotNull(error.hint)
    }
}
