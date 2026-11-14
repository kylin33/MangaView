package com.example.hellocompose.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把时间戳变成「刚刚 / 3 小时前 / 昨天 / 2026-11-02」。
 *
 * 没用 java.time：minSdk 是 24，那套 API 要 26 以上或者开 desugaring，
 * 为一句相对时间不值得改构建配置。
 */
fun formatRelativeTime(
    millis: Long,
    now: Long = System.currentTimeMillis()
): String {
    if (millis <= 0L) return ""

    val diff = (now - millis).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 2 * 86_400_000L -> "昨天"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }
}
