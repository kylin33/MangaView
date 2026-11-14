package com.example.hellocompose.data.remote

import java.net.InetSocketAddress
import java.net.URI
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 证书校验失败的具体原因。
 *
 * 有了这一层，UI 才能说「证书已过期」而不是笼统的「证书校验失败」——
 * 这两句话对用户的意义完全不同：前者意味着站点维护有问题，后者可能是中间人。
 */
enum class TlsProblem(val title: String, val advice: String) {
    EXPIRED(
        title = "证书已过期",
        advice = "站点忘记续期。除非这是你自己的站点，否则先别继续"
    ),
    NOT_YET_VALID(
        title = "证书尚未生效",
        advice = "先检查设备时间是否正确；时间正常说明证书的生效时间在未来"
    ),
    HOSTNAME_MISMATCH(
        title = "证书与域名不匹配",
        advice = "证书签发给了别的域名。域名换绑后忘换证书会出现这种情况，也可能是有人在中间冒充"
    ),
    SELF_SIGNED(
        title = "自签名证书",
        advice = "证书不是由公开 CA 签发的。只有你自己的站点才应该出现这种情况"
    ),
    UNTRUSTED_ISSUER(
        title = "签发机构不被系统信任",
        advice = "证书链找不到可信任的根。常见于免费证书配置错误，也可能是伪造证书"
    ),
    INCOMPLETE_CHAIN(
        title = "证书链不完整",
        advice = "服务器少下发了中间证书。这不代表站点是恶意的，但无法确认身份"
    ),
    NOT_TLS(
        title = "该端口不是 HTTPS",
        advice = "这个地址上跑的不是 TLS，站点很可能只支持明文 HTTP"
    ),
    HANDSHAKE_FAILED(
        title = "TLS 握手失败",
        advice = "协议版本或加密套件对不上，通常是站点太旧"
    ),
    OTHER(
        title = "TLS 校验未通过",
        advice = "系统拒绝了这个连接，具体原因见下方"
    )
}

/** 服务器实际出示的证书。只在报错时展示，不参与任何信任判断 */
data class CertInfo(
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
    val hostNames: List<String>
) {
    fun describe(): String = buildString {
        append("证书主体：").append(subject)
        append("\n签发者：").append(issuer)
        append("\n有效期：").append(notBefore).append(" ～ ").append(notAfter)
        if (hostNames.isNotEmpty()) append("\n证书域名：").append(hostNames.joinToString("、"))
    }
}

/**
 * 证书校验失败的统一异常。
 *
 * 继承 [SSLException] 是为了让所有既有的 `is SSLException` 判断继续成立，
 * 但调用方应当优先识别这个类型，才能拿到原因和证书详情。
 */
class TlsException(
    val problem: TlsProblem,
    val host: String,
    val certificate: CertInfo?,
    val reason: String,
    cause: Throwable? = null
) : SSLException("$host：${problem.title}（$reason）", cause) {

    /** 一句话结论，给标题用 */
    val userMessage: String get() = "证书校验失败：${problem.title}"

    /** 面向用户的多行详情：域名 + 证书事实 + 系统原始原因 */
    val detailText: String
        get() = buildString {
            append("站点：").append(host)
            certificate?.let { append("\n").append(it.describe()) }
            if (reason.isNotBlank()) append("\n系统原因：").append(reason)
        }
}

/**
 * 把底层 TLS 异常翻译成可展示的 [TlsException]，并在需要时探一次证书。
 *
 * 分类只依据异常链里的文本 —— 这确实是字符串匹配，但各平台（OpenJDK / Conscrypt）
 * 的 TLS 错误没有稳定的类型体系，文本是唯一共同点。匹配不上时退回 [TlsProblem.OTHER]，
 * 仍然会把原始原因展示出来，不会把原因藏起来。
 */
class TlsDiagnostics(
    /**
     * 取服务器证书的途径。默认走真实握手；测试里换成返回 null，
     * 这样分类逻辑可以在完全离线的情况下被验证。
     */
    private val certificateOf: (String) -> CertInfo? = CertificateProbe()::certOf
) {

    fun diagnose(error: Throwable, url: String): TlsException {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { url }
        val chain = generateSequence(error) { it.cause }.toList()
        val text = chain.mapNotNull { it.message }.joinToString(" | ").ifBlank { error.javaClass.simpleName }

        val problem = classify(text)
        // 不是 TLS 端口就没必要再去握手，探了也是同样的错
        val certificate = if (problem == TlsProblem.NOT_TLS) null else certificateOf(host)

        return TlsException(
            problem = refine(problem, certificate),
            host = host,
            certificate = certificate,
            reason = text.take(300),
            cause = error
        )
    }

    /**
     * 用证书本身修正常见的误判。
     *
     * 自签名证书在 OpenJDK 里报的是 "unable to find valid certification path"，
     * 和"中间证书没下发"一模一样；但主体和签发者是同一个名字时，结论很明确。
     */
    private fun refine(problem: TlsProblem, certificate: CertInfo?): TlsProblem {
        if (certificate == null) return problem
        val untrusted = problem == TlsProblem.UNTRUSTED_ISSUER || problem == TlsProblem.INCOMPLETE_CHAIN
        return if (untrusted && certificate.subject == certificate.issuer) {
            TlsProblem.SELF_SIGNED
        } else {
            problem
        }
    }

    internal fun classify(text: String): TlsProblem {
        val t = text.lowercase(Locale.ROOT)
        return when {
            // 「证书链里有自签名证书」也走这条：对用户来说结论一样 —— 身份无法确认
            "self signed" in t || "self-signed" in t || "selfsigned" in t -> TlsProblem.SELF_SIGNED
            "notafter" in t || "expired" in t -> TlsProblem.EXPIRED
            "notbefore" in t || "not yet valid" in t -> TlsProblem.NOT_YET_VALID
            "no subject alternative" in t || "not verified" in t ||
                "hostname" in t || "no name matching" in t -> TlsProblem.HOSTNAME_MISMATCH
            "trust anchor" in t || "unable to find valid certification path" in t ||
                "pkix path building failed" in t -> TlsProblem.UNTRUSTED_ISSUER
            "certificate_unknown" in t || "unable to find acceptable" in t -> TlsProblem.INCOMPLETE_CHAIN
            "unrecognized ssl message" in t || "not an ssl" in t ||
                "does not look like a tls" in t || "plaintext" in t -> TlsProblem.NOT_TLS
            "no cipher suites" in t || "protocol version" in t || "handshake_failure" in t ->
                TlsProblem.HANDSHAKE_FAILED
            else -> TlsProblem.OTHER
        }
    }
}

/**
 * 读一次服务器出示的证书。
 *
 * **这不是放宽校验**：它只在证书校验已经失败之后调用，握手成功即断开，
 * 既不发送 HTTP 请求、也不读取任何响应内容，唯一用途是把「证书到底签给了谁、
 * 什么时候过期」摆给用户看。真要继续访问仍然必须由用户显式确认。
 */
class CertificateProbe(private val timeoutMs: Int = 4000) {

    private val cache = HashMap<String, CertInfo?>()

    @Synchronized
    fun certOf(host: String, port: Int = 443): CertInfo? {
        if (cache.containsKey(host)) return cache[host]
        val info = handshake(host, port)
        cache[host] = info
        return info
    }

    private fun handshake(host: String, port: Int): CertInfo? = runCatching {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(InspectionOnlyTrustManager), null)
        (context.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.startHandshake()
            (socket.session.peerCertificates.firstOrNull() as? X509Certificate)?.toInfo()
        }
    }.getOrNull()

    /**
     * 仅用于读取证书的 TrustManager。
     *
     * 它的存在只为拿 chain，拿到后连接立即关闭。任何正式请求都不会使用它 ——
     * [JsoupHttpClient] 走的是平台默认 SSLContext，不做任何放宽。
     */
    private object InspectionOnlyTrustManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    }

    private fun X509Certificate.toInfo(): CertInfo {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        // SAN 才是校验证书归属的地方；CN 只作为补充信息展示
        val names = runCatching {
            subjectAlternativeNames.orEmpty()
                .filter { it.size >= 2 && it[0] in setOf(2, 7) }
                .map { it[1].toString() }
        }.getOrDefault(emptyList())

        return CertInfo(
            subject = runCatching { subjectX500Principal.name }.getOrDefault("未知"),
            issuer = runCatching { issuerX500Principal.name }.getOrDefault("未知"),
            notBefore = runCatching { date.format(this.notBefore) }.getOrDefault("未知"),
            notAfter = runCatching { date.format(this.notAfter) }.getOrDefault("未知"),
            hostNames = names
        )
    }
}
