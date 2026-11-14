package com.example.hellocompose.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.ui.LocalAppContainer
import com.example.hellocompose.ui.components.EmptyView
import com.example.hellocompose.ui.components.ErrorView
import com.example.hellocompose.ui.components.LoadingView
import com.example.hellocompose.ui.components.SourceSwitchSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    title: String,
    detailUrl: String,
    onBack: () -> Unit,
    onChapterClick: (Chapter) -> Unit,
    /** 换源：切到目标站点并打开那边的同一本书 */
    onSwitchSource: (SourceConfig, Manga) -> Unit
) {
    val container = LocalAppContainer.current
    // 以 detailUrl 作为 key：切换不同漫画时复用同一个 ViewModel 实例会导致展示错乱
    val viewModel: DetailViewModel = viewModel(
        key = detailUrl,
        factory = viewModelFactory {
            initializer {
                DetailViewModel(
                    detailUrl = detailUrl,
                    repository = container.mangaRepository,
                    history = container.historyRepository
                )
            }
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val record = state.record
    var showSwitch by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 这个站点打不开、缺章、图看不了的时候的出口
                    TextButton(
                        onClick = { showSwitch = true },
                        enabled = title.isNotBlank()
                    ) { Text("换源") }
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
                state.isLoading -> LoadingView()

                state.error != null -> ErrorView(
                    error = state.error!!,
                    onRetry = viewModel::load
                )

                state.chapters.isEmpty() -> EmptyView("没有解析到章节")

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 有记录就先给一条「接着看」，比让用户在几十话里找上次那一话省事
                    if (record != null && record.chapterUrl.isNotBlank()) {
                        item(key = "resume") {
                            ResumeBanner(
                                record = record,
                                onClick = {
                                    onChapterClick(Chapter(record.chapterTitle, record.chapterUrl))
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    item(key = "count") {
                        Text(
                            text = buildString {
                                append("共 ${state.chapters.size} 章")
                                val read = record?.readChapterTitles?.size ?: 0
                                if (read > 0) append("，已读 $read 章")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }

                    items(items = state.chapters, key = { it.url }) { chapter ->
                        val isCurrent = record != null && sameChapter(record, chapter)
                        val isRead = record?.hasRead(chapter.title) == true

                        Card(
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChapterClick(chapter) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isCurrent) {
                                    Text(
                                        text = "上次看到",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else if (isRead) {
                                    Text(
                                        text = "已读",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSwitch) {
        SourceSwitchSheet(
            mangaTitle = title,
            onDismiss = { showSwitch = false },
            onOpen = { selection ->
                showSwitch = false
                onSwitchSource(selection.source, selection.manga)
            }
        )
    }
}

/** 记录里的章节地址和标题都可能带站点自己的编码差异，任一对得上就算同一章 */
private fun sameChapter(record: ReadingRecord, chapter: Chapter): Boolean =
    record.chapterUrl == chapter.url ||
        (record.chapterTitle.isNotBlank() && record.chapterTitle == chapter.title)

@Composable
private fun ResumeBanner(record: ReadingRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "继续阅读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = record.chapterTitle.ifBlank { "上次读到这一章" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (record.pageLabel.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = record.pageLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
