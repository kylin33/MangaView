package com.example.hellocompose.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置层的安全约束。
 *
 * 这几条是「默认严格」的落地点：证书例外可以存在，但必须显式写在配置里；
 * 明文 HTTP 连"默认放行"都不允许，否则识别器那句"不自动降级"就只是句口号。
 */
class SourceSecurityConfigTest {

    private fun json(
        baseUrl: String,
        allowPlainHttp: Boolean = false,
        trustAllCerts: Boolean = false
    ): String = SourceParser.TEMPLATE
        .replace("\"baseUrl\": \"https://example.com\"", "\"baseUrl\": \"$baseUrl\"")
        .replace("\"allowPlainHttp\": false", "\"allowPlainHttp\": $allowPlainHttp")
        .replace("\"trustAllCerts\": false", "\"trustAllCerts\": $trustAllCerts")

    @Test
    fun templateShipsWithoutAnyException() {
        val config = SourceParser.parse(SourceParser.TEMPLATE, fallbackId = "tpl")

        assertFalse(config.trustAllCerts)
        assertFalse(config.allowPlainHttp)
        assertTrue("模板不该自带安全例外", config.securityWarnings.isEmpty())
    }

    @Test
    fun rejectsPlainHttpBaseUrlWithoutExplicitFlag() {
        val error = runCatching { SourceParser.parse(json("http://plain.example.com"), "plain") }
            .exceptionOrNull()

        assertTrue("明文 baseUrl 必须要求显式授权，实际：$error", error is IllegalArgumentException)
        assertTrue(
            "报错要指出缺哪个字段，否则用户只能猜",
            error?.message.orEmpty().contains("allowPlainHttp")
        )
    }

    @Test
    fun acceptsPlainHttpWhenExplicitlyAllowed() {
        val config = SourceParser.parse(
            json("http://plain.example.com", allowPlainHttp = true),
            "plain"
        )

        assertTrue(config.allowPlainHttp)
        assertEquals(1, config.securityWarnings.size)
        assertTrue(config.securityWarnings.first().contains("明文"))
    }

    @Test
    fun certificateExceptionSurvivesRoundTripWithWarning() {
        val config = SourceParser.parse(
            json("https://weak.example.com", trustAllCerts = true),
            "weak"
        )
        assertTrue(config.trustAllCerts)

        // 例外写进 JSON 时必须自带说明：这份文件会被复制给别人，
        // 光一个 true 下一个人就当成普通开关打开了
        val encoded = SourceJson.encode(config)
        assertTrue(encoded.contains("\"trustAllCerts\": true"))
        assertTrue(encoded.contains("securityWarning"))

        val reparsed = SourceParser.parse(encoded, "weak")
        assertTrue("往返后例外不能丢，否则运行时会突然变成严格模式", reparsed.trustAllCerts)
        assertFalse(reparsed.allowPlainHttp)
    }
}
