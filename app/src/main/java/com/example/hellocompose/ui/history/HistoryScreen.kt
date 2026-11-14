package com.example.hellocompose.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.SubcomposeAsyncImage
import com.example.hellocompose.core.formatRelativeTime
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.data.image.MangaImageRequests
import com.example.hellocompose.ui.LocalAppContainer
import com.example.hellocompose.ui.LocalImageLoader
import com.example.hellocompose.ui.components.EmptyView
import com.example.hellocompose.ui.components.LoadingView

/**
 * 阅读历史。
 *
 * 这一页解决两件事：
 * 1. 我看过什么、每本看到哪 —— 点一条直接回到上次停下的那一页；
 * 2. 原站点关掉之后还能继续找 —— 每条都留着书名，可以拿它去当前站点再搜一次。
 *
 * 所以条目上的信息顺序是「书名 → 章节/页码 → 来自哪个站点」，站点名必须显眼：
 * 点进去会自动切回那个站点，用户得先知道自己要切到哪。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenRecord: (ReadingRecord) -> Unit,
    onSearchTitle: (String) -> Unit
) {
    val container = LocalAppContainer.current
    val viewModel: HistoryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HistoryViewModel(
                    history = container.historyRepository,
                    sources = container.sourceRepository
                )
            }
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ReadingRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.records.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空") }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                state.loading -> LoadingView()

                state.records.isEmpty() -> EmptyView(
                    text = "还没有阅读记录。\n看过几页，这里就会记下看到哪一章、第几张。"
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item(key = "hint") {
                        Text(
                            text = "按最近阅读排序。点一条继续看，右上角搜索图标可以拿书名去当前站点再找一次。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(items = state.records, key = { it.key }) { record ->
                        HistoryRow(
                            record = record,
                            referer = viewModel.refererFor(record.sourceId),
                            onClick = { onOpenRecord(record) },
                            onSearch = { onSearchTitle(record.mangaTitle) },
                            onDelete = { pendingDelete = record }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记录？") },
            text = { Text("「${target.mangaTitle}」的阅读位置会被清掉，只删本地记录，不影响站点。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.remove(target)
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部记录？") },
            text = { Text("${state.records.size} 条阅读记录将被删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clear()
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HistoryRow(
    record: ReadingRecord,
    referer: String,
    onClick: () -> Unit,
    onSearch: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = LocalImageLoader.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 76.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (record.coverUrl.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = MangaImageRequests.build(context, record.coverUrl, referer),
                    imageLoader = imageLoader,
                    contentDescription = record.mangaTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    // 封面只是锦上添花，站点关掉后加载失败也不必额外占位
                    loading = {},
                    error = {}
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.mangaTitle.ifBlank { "未命名漫画" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = record.progressLabel.ifBlank { "还没开始看" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append("来自「")
                    append(record.sourceName.ifBlank { "未知站点" })
                    append("」")
                    val time = formatRelativeTime(record.updatedAt)
                    if (time.isNotBlank()) append(" · $time")
                    if (record.readChapterTitles.size > 1) {
                        append(" · 已读 ${record.readChapterTitles.size} 章")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onSearch, enabled = record.mangaTitle.isNotBlank()) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "用这个书名搜索",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除这条记录",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
