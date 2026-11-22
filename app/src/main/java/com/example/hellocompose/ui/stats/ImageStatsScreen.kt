package com.example.hellocompose.ui.stats

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hellocompose.core.formatRelativeTime
import com.example.hellocompose.data.image.ImageDeviceProfile
import com.example.hellocompose.data.image.ImageStatsSnapshot
import com.example.hellocompose.ui.LocalAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 图片加载性能面板。
 *
 * 只在「看完之后」进来，不占用阅读界面：阅读页每秒刷新一次数字，除了耗电没有意义，
 * 而且长图滚动时任何额外组合都会影响滚动本身。
 *
 * 数据全部来自 [com.example.hellocompose.data.image.ImageLoadStats]，只存在内存、
 * 只在这台机器上，重启清零 —— 这是调参用的仪表盘，不是上报系统。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageStatsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val images = container.imageRequests
    val profile = container.imageDeviceProfile
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var tick by remember { mutableIntStateOf(0) }
    var diskBytes by remember { mutableLongStateOf(-1L) }
    var clearing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // 每秒一次：面板自己看的时候才动，退出即停
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    // 磁盘占用要走一次文件统计，不能放在组合里算
    LaunchedEffect(tick) {
        diskBytes = withContext(Dispatchers.IO) { images.diskCacheSizeBytes() }
    }

    val snapshot = remember(tick) { images.snapshot() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图片加载统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            images.stats.reset()
                            tick++
                            Toast.makeText(context, "统计已清零", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "清零统计")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (snapshot.isEmpty) {
                item {
                    EmptyCard()
                }
            } else {
                item { MetricGrid(snapshot, profile) }
                item { SessionLine(snapshot) }
                item { LatencyCard(snapshot) }
                item { HitCard(snapshot) }
                item { ConcurrencyCard(snapshot, profile) }
                item { FailureCard(snapshot) }
            }

            item {
                CacheCard(
                    usedBytes = diskBytes.coerceAtLeast(0L),
                    limitBytes = com.example.hellocompose.data.image.ImageRequestManager.DISK_CACHE_LIMIT_BYTES,
                    clearing = clearing,
                    onClear = { confirmClear = true }
                )
            }

            item {
                Text(
                    text = "只统计本机这一次运行；取消的请求不参与成功率 —— " +
                        "滚动时取消预加载是正常行为，算进去会让成功率随滑动变低。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理图片缓存？") },
            text = {
                Text(
                    "会清掉磁盘上的 ${formatBytes(diskBytes.coerceAtLeast(0L))} 图片和全部内存位图。" +
                        "已经看过的章节下次要重新下载。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        scope.launch {
                            clearing = true
                            images.clearCaches()
                            diskBytes = withContext(Dispatchers.IO) { images.diskCacheSizeBytes() }
                            clearing = false
                            Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

// ==================== 大数字 ====================

@Composable
private fun MetricGrid(snapshot: ImageStatsSnapshot, profile: ImageDeviceProfile) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                label = "加载成功率",
                value = percentText(snapshot.successRate),
                caption = "${snapshot.success} / ${snapshot.success + snapshot.failed} 次",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "平均耗时",
                value = formatMs(snapshot.avgMs),
                caption = "p90 ${formatMs(snapshot.p90Ms)}",
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                label = "缓存命中率",
                value = percentText(snapshot.cacheHitRate),
                caption = "内存 ${snapshot.memoryHits} · 磁盘 ${snapshot.diskHits}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = "当前并发",
                value = "${snapshot.currentInFlight}",
                caption = "上限 ${profile.concurrency} 路 · 峰值 ${snapshot.peakInFlight}",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SessionLine(snapshot: ImageStatsSnapshot) {
    Text(
        text = "本次累计 ${snapshot.total} 次加载 · 失败 ${snapshot.failed} 次 · " +
            "取消 ${snapshot.cancelled} 次 · 统计自 ${formatRelativeTime(snapshot.startedAt)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

// ==================== 明细 ====================

@Composable
private fun LatencyCard(snapshot: ImageStatsSnapshot) {
    SectionCard("耗时") {
        StatRow("平均", formatMs(snapshot.avgMs))
        StatRow("中位数 p50", formatMs(snapshot.p50Ms))
        StatRow("p90", formatMs(snapshot.p90Ms))
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        StatRow("可见页平均", formatMs(snapshot.avgVisibleMs))
        StatRow("预加载平均", formatMs(snapshot.avgPreloadMs))
        if (snapshot.windowSize < snapshot.total) {
            Text(
                text = "分位数取最近 ${snapshot.windowSize} 条；平均值取全部",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HitCard(snapshot: ImageStatsSnapshot) {
    SectionCard("图片从哪来") {
        val total = (snapshot.memoryHits + snapshot.diskHits + snapshot.networkLoads).coerceAtLeast(1)
        HitBar(
            memory = snapshot.memoryHits,
            disk = snapshot.diskHits,
            network = snapshot.networkLoads,
            total = total
        )
        Spacer(Modifier.height(10.dp))
        Legend("内存缓存", snapshot.memoryHits, MaterialTheme.colorScheme.primary)
        Legend("磁盘缓存", snapshot.diskHits, MaterialTheme.colorScheme.tertiary)
        Legend("网络下载", snapshot.networkLoads, MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun HitBar(memory: Int, disk: Int, network: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.Start
    ) {
        Segment(memory.toFloat() / total, MaterialTheme.colorScheme.primary)
        Segment(disk.toFloat() / total, MaterialTheme.colorScheme.tertiary)
        Segment(network.toFloat() / total, MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(fraction: Float, color: Color) {
    if (fraction <= 0f) return
    Box(
        modifier = Modifier
            .weight(fraction)
            .fillMaxWidth()
            .height(10.dp)
            .background(color)
    )
}

@Composable
private fun Legend(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConcurrencyCard(snapshot: ImageStatsSnapshot, profile: ImageDeviceProfile) {
    SectionCard("解码并发") {
        StatRow("本机建议", "${profile.concurrency} 路")
        StatRow("当前在飞", "${snapshot.currentInFlight} 个请求")
        StatRow("峰值", "${snapshot.peakInFlight} 个请求")
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(
            text = profile.reason + "。可见页一进场，预加载就停在闸门外 —— " +
                "翻页的等待时间比预加载几章更值钱。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FailureCard(snapshot: ImageStatsSnapshot) {
    SectionCard("失败") {
        if (snapshot.failures.isEmpty()) {
            Text(
                text = "本次没有失败的图片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        snapshot.failures.forEach { bucket ->
            StatRow(bucket.reason, "${bucket.count} 次")
        }

        if (snapshot.recentFailures.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                text = "最近失败",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            snapshot.recentFailures.take(6).forEach { failure ->
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = failure.host,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${failure.reason} · ${formatRelativeTime(failure.at)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ==================== 缓存 ====================

@Composable
private fun CacheCard(
    usedBytes: Long,
    limitBytes: Long,
    clearing: Boolean,
    onClear: () -> Unit
) {
    SectionCard("磁盘缓存") {
        val fraction = if (limitBytes <= 0) 0f else (usedBytes.toFloat() / limitBytes).coerceIn(0f, 1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatBytes(usedBytes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "上限 ${formatBytes(limitBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onClear, enabled = !clearing) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (clearing) "正在清理…" else "清理图片缓存")
        }
        Text(
            text = "磁盘缓存超过上限会自动淘汰最久未用的，平时不用手动清。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("还没有图片加载记录", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "去读几章再回来。这里统计的是加载耗时、成功率、缓存命中率和并发，" +
                    "用来判断是网络慢、图床慢，还是解码把内存压住了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== 小工具 ====================

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun percentText(value: Double): String {
    val p = value * 100
    return when {
        p >= 99.95 -> "100%"
        p <= 0.05 -> "0%"
        else -> String.format(Locale.US, "%.1f%%", p)
    }
}

internal fun formatMs(ms: Long): String = when {
    ms <= 0 -> "—"
    ms < 1_000 -> "${ms}ms"
    else -> String.format(Locale.US, "%.1fs", ms / 1000.0)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024.0).roundToInt()} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
