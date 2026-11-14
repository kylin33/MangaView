package com.example.hellocompose.data.source

import com.example.hellocompose.core.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从链接下载一份配置 JSON。
 *
 * 用 HttpURLConnection 而不是再引一个网络库：这里只需要读一段文本，
 * 而且它不参与业务请求，独立于 jsoup 反而更省心。
 */
object ConfigFetcher {

    /** 配置都是几 KB，超过这个体积说明下到的多半是网页而不是 JSON */
    private const val MAX_BYTES = 512 * 1024

    suspend fun download(url: String, timeoutMs: Int = 15_000): String =
        withContext(Dispatchers.IO) {
            val trimmed = url.trim()
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                "链接必须以 http:// 或 https:// 开头"
            }

            val connection = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", AppConfig.USER_AGENT)
            }

            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("服务器返回 HTTP $code")

                connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    val builder = StringBuilder()
                    var total = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            throw IOException("下载内容超过 ${MAX_BYTES / 1024} KB，可能不是配置文件")
                        }
                        builder.append(buffer, 0, read)
                    }
                    builder.toString()
                }
            } finally {
                connection.disconnect()
            }
        }
}
