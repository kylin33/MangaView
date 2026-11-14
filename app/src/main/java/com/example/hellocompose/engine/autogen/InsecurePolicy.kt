package com.example.hellocompose.engine.autogen

/**
 * 连接安全策略。
 *
 * 默认是 [STRICT]：该校验的都校验，该走 HTTPS 就走 HTTPS。任何放宽都必须先由
 * 识别器把具体原因（证书签给了谁、什么时候过期、站点是不是根本不支持 HTTPS）
 * 摆给用户看，用户点过「仍然继续」之后才构造出带 flag 的实例，并写进生成的配置。
 *
 * 之所以做成一个显式对象而不是几个 boolean 参数：boolean 会随着调用链一路传下去，
 * 传到第五层时已经没人记得它为什么是 true 了。
 */
data class InsecurePolicy(
    val trustAllCerts: Boolean = false,
    val allowPlainHttp: Boolean = false
) {
    val isStrict: Boolean get() = !trustAllCerts && !allowPlainHttp

    /** 写进配置和界面的警告。措辞要让人意识到代价，而不是"已切换"这种中性描述 */
    val warnings: List<String>
        get() = buildList {
            if (trustAllCerts) {
                add("已按你的确认关闭证书校验：这条连接无法防范中间人，页面和图片都可能被第三方篡改")
            }
            if (allowPlainHttp) {
                add("已按你的确认使用明文 HTTP：链路上的任何人都能读取和修改内容")
            }
        }

    companion object {
        val STRICT = InsecurePolicy()
    }
}

/**
 * 一次「需要用户明确确认才能继续」的拦截。
 *
 * 识别器不会自己决定放宽，也不会把证书错误伪装成网络错误让用户去点「重试」——
 * 它把结论和证据交回来，由界面弹窗呈现，用户的选择再变成 [policy] 继续识别。
 */
data class InsecureRequest(
    val kind: Kind,
    val host: String,
    /** 一句话结论，弹窗标题 */
    val message: String,
    /** 证据：证书主体/签发者/有效期，或"这个站没有 HTTPS" */
    val detail: String,
    /** 用户确认后应当采用的策略 */
    val policy: InsecurePolicy
) {
    enum class Kind {
        /** 证书链校验不通过（过期/自签名/域名不匹配/签发者不受信任…） */
        CERT_INVALID,

        /** 站点在 443 上根本不讲 TLS，只能明文访问 */
        HTTPS_UNSUPPORTED
    }
}

/** 内部信号：把「需要确认」从识别流程里抛出来，避免每层都返回三态结果 */
internal class InsecureRequiredException(val request: InsecureRequest) :
    Exception(request.message)
