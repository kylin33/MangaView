package com.example.hellocompose.data.image

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 图片加载失败时给用户看的一句话。
 *
 * 和正文抓取共用一套思路：能说清原因就说清，说不清才说「失败了」。
 * 尤其是 403 —— 绝大多数是防盗链，用户真正能做的事是换个源，
 * 而不是反复点重试。
 */
object ImageErrorText {

    fun of(error: Throwable?, httpCode: Int? = null): String = when {
        httpCode == 401 || httpCode == 403 -> "站点拒绝了图片请求（可能是防盗链），可以试试换源"
        httpCode != null -> "图片请求失败（HTTP $httpCode）"
        error is SSLException -> "图片连接证书校验失败"
        error is SocketTimeoutException -> "图片加载超时"
        error is UnknownHostException -> "网络不可用，请检查连接"
        error is IOException -> "图片没下完，网络中断了"
        else -> "图片加载失败"
    }
}
