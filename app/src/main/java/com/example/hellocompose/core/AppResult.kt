package com.example.hellocompose.core

import kotlinx.coroutines.CancellationException
import org.jsoup.HttpStatusException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 失败原因分类。UI 层据此给出人话提示，而不是把异常堆栈甩给用户。
 */
enum class ErrorType {
    NETWORK,   // 断网、DNS 解析失败、连接被拒
    TIMEOUT,   // 超时
    TLS,       // 证书 / SSL 握手失败
    HTTP,      // 服务器返回 4xx / 5xx
    PARSE,     // 页面结构变了，选择器匹配不到
    CONFIG,    // 站点配置缺失或非法（assets/sources 下没有可用配置）
    UNKNOWN
}

data class AppError(
    val type: ErrorType,
    val detail: String
) {
    /** 面向用户的一句话说明 */
    val userMessage: String
        get() = when (type) {
            ErrorType.NETWORK -> "网络连接失败，请检查网络后重试"
            ErrorType.TIMEOUT -> "请求超时，站点响应过慢"
            ErrorType.TLS -> "站点证书校验失败"
            ErrorType.HTTP -> "站点返回异常状态"
            ErrorType.PARSE -> "页面结构已变化，解析规则需要更新"
            ErrorType.CONFIG -> "没有可用的站点配置"
            ErrorType.UNKNOWN -> "发生未知错误"
        }
}

/**
 * 页面能打开、但没有解析出内容 —— 几乎总是选择器失效，
 * 单独拎出来是为了让 UI 提示「改配置」而不是「检查网络」。
 */
class EmptyPageException(message: String) : Exception(message)

/** 一个可用的站点配置都没有 */
class NoSourceAvailableException(message: String = "没有可用的站点配置") : IllegalStateException(message)

/**
 * 统一的结果封装。不再让 UI 层去 try/catch 异常。
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/** 把任意异常映射成可展示的错误 */
fun Throwable.toAppError(): AppError = when (this) {
    is NoSourceAvailableException -> AppError(ErrorType.CONFIG, message ?: "没有可用的站点配置")
    is EmptyPageException -> AppError(ErrorType.PARSE, message ?: "页面未解析到内容")
    is HttpStatusException -> AppError(ErrorType.HTTP, "HTTP $statusCode")
    is UnknownHostException -> AppError(ErrorType.NETWORK, message ?: "域名解析失败")
    is SocketTimeoutException -> AppError(ErrorType.TIMEOUT, message ?: "连接超时")
    is SSLException -> AppError(ErrorType.TLS, message ?: "SSL 握手失败")
    is IOException -> AppError(ErrorType.NETWORK, message ?: "网络异常")
    else -> AppError(ErrorType.UNKNOWN, message ?: this::class.java.simpleName)
}

/**
 * 包裹一次可能失败的调用。CancellationException 必须原样抛出，
 * 否则协程取消会被当成普通错误吞掉。
 */
suspend fun <T> appRunCatching(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppResult.Failure(e.toAppError())
}
