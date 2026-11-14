package com.example.hellocompose.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hellocompose.core.AppConfig
import kotlin.math.roundToInt

/**
 * 预加载张数设置。首页与阅读器共用同一个组件，
 * 数值通过 SettingsRepository 持久化，两边始终同步。
 */
@Composable
fun PreloadSettingsDialog(
    currentCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var sliderValue by remember(currentCount) { mutableFloatStateOf(currentCount.toFloat()) }
    val rounded = sliderValue.roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("预加载设置") },
        text = {
            Column {
                Text(
                    text = "阅读时提前下载后续 $rounded 张图片",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "· 0：只加载当前屏幕可见的图，最省流量\n" +
                        "· 3~6：推荐，兼顾滑动流畅与流量\n" +
                        "· 10 以上：适合快速翻阅或网络较慢的场景",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(text = "$rounded 张", fontSize = 13.sp)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..AppConfig.MAX_PRELOAD.toFloat(),
                    steps = AppConfig.MAX_PRELOAD - 1
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(rounded) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
