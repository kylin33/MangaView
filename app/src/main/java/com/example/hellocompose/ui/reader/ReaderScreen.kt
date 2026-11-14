package com.example.hellocompose.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.SubcomposeAsyncImage
import com.example.hellocompose.data.image.MangaImageRequests
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.ui.LocalAppContainer
import com.example.hellocompose.ui.LocalImageLoader
import com.example.hellocompose.ui.components.ErrorView
import com.example.hellocompose.ui.components.LoadingView
import com.example.hellocompose.ui.components.PreloadSettingsDialog
import com.example.hellocompose.ui.components.SourceSwitchSheet
import com.example.hellocompose.ui.theme.ReaderAccent
import com.example.hellocompose.ui.theme.ReaderBackground
import com.example.hellocompose.ui.theme.ReaderStatusBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    chapterTitle: String,
    chapterUrl: String,
    detailUrl: String,
    mangaTitle: String,
    coverUrl: String,
    onBack: () -> Unit,
    /** 换源：切到目标站点；chapter 不为空时直接落到对应章节 */
    onSwitchSource: (SourceConfig, Manga, Chapter?) -> Unit
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val imageLoader = LocalImageLoader.current

    val viewModel: ReaderViewModel = viewModel(
        // 按「详情页 + 起始章节」建 VM：不同的书各自维护自己的目录和进度
        key = "reader_${detailUrl}_$chapterUrl",
        factory = viewModelFactory {
            initializer {
                ReaderViewModel(
                    initialChapterUrl = chapterUrl,
                    initialChapterTitle = chapterTitle,
                    mangaTitle = mangaTitle,
                    coverUrl = coverUrl,
                    detailUrl = detailUrl,
                    repository = container.mangaRepository,
                    sources = container.sourceRepository,
                    settings = container.settingsRepository,
                    history = container.historyRepository,
                    context = context.applicationContext,
                    imageLoader = container.imageLoader
                )
            }
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 加载完成后定位：上次看到的位置优先，没有记录再回顶部
    LaunchedEffect(state.chapterUrl, state.isLoading) {
        if (!state.isLoading && state.images.isNotEmpty()) {
            listState.scrollToItem(state.restoreIndex.coerceAtLeast(0))
            viewModel.onRestoreConsumed()
        }
    }

    // 当前屏幕内最后一张可见页的索引
    val lastVisibleIndex by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(lastVisibleIndex) {
        viewModel.onVisibleIndexChanged(lastVisibleIndex)
    }

    Scaffold(
        containerColor = ReaderBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.chapterTitle.ifBlank { chapterTitle },
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // 从历史续读进来时，光看章节名不知道是哪本书
                        if (mangaTitle.isNotBlank()) {
                            Text(
                                text = mangaTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        // 进来会跳到上次的位置，想重看这一章就得有个回到开头的入口
                        DropdownMenuItem(
                            text = { Text("回到本章开头") },
                            leadingIcon = {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                            },
                            enabled = state.images.isNotEmpty(),
                            onClick = {
                                showMenu = false
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("预加载设置") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                showSettings = true
                            }
                        )
                        // 站点卡、图加载不出来时的出口：换个站点看同一本书
                        DropdownMenuItem(
                            text = { Text("换源") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                showSwitch = true
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ReaderStatusBar,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            if (state.images.isNotEmpty()) {
                ReaderStatusBarContent(
                    currentPage = lastVisibleIndex + 1,
                    totalPages = state.images.size,
                    preloadCount = state.preloadCount
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ReaderBackground)
                .padding(innerPadding)
        ) {
            when {
                state.isLoading -> LoadingView(text = "正在获取本章图片…")

                state.error != null -> ErrorView(
                    error = state.error!!,
                    onRetry = viewModel::load
                )

                state.images.isEmpty() -> Text(
                    text = "本章没有图片",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center)
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = state.images,
                        key = { index, url -> "$index-$url" }
                    ) { index, imageUrl ->
                        SubcomposeAsyncImage(
                            model = MangaImageRequests.build(
                                context = context,
                                imageUrl = imageUrl,
                                referer = state.referer.ifBlank { state.chapterUrl }
                            ),
                            imageLoader = imageLoader,
                            contentDescription = "第 ${index + 1} 页",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            loading = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(280.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(strokeWidth = 2.dp)
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "${index + 1} / ${state.images.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        )
                    }

                    // 滑到本章末尾就能直接翻章，不用退回目录再选一次
                    if (state.chapterCount > 0) {
                        item(key = "chapter_nav") {
                            ChapterNavFooter(
                                hasPrev = state.hasPrev,
                                hasNext = state.hasNext,
                                onPrev = viewModel::openPrev,
                                onNext = viewModel::openNext
                            )
                        }
                    }
                }
            }
        }

        if (showSettings) {
            PreloadSettingsDialog(
                currentCount = state.preloadCount,
                onDismiss = { showSettings = false },
                onConfirm = viewModel::setPreloadCount
            )
        }

        if (showSwitch) {
            SourceSwitchSheet(
                // 极少数入口拿不到书名，用章节名兜底，至少能搜到东西
                mangaTitle = mangaTitle.ifBlank { state.chapterTitle },
                chapterTitle = state.chapterTitle.ifBlank { chapterTitle },
                onDismiss = { showSwitch = false },
                onOpen = { selection -> 
                    showSwitch = false
                    onSwitchSource(selection.source, selection.manga, selection.chapter)
                }
            )
        }
    }
}

@Composable
private fun ReaderStatusBarContent(
    currentPage: Int,
    totalPages: Int,
    preloadCount: Int
) {
    val preloadedTo = (currentPage + preloadCount).coerceAtMost(totalPages)

    Surface(color = ReaderStatusBar, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "第 $currentPage / $totalPages 页",
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = if (preloadCount > 0 && currentPage < totalPages) {
                    "已预载至 $preloadedTo"
                } else {
                    "预载已关闭"
                },
                style = MaterialTheme.typography.labelSmall,
                color = ReaderAccent
            )
        }
    }
}

/**
 * 本章末尾的翻章入口。
 *
 * 之前要翻下一章必须退回目录再点，看完一章至少多三次操作；
 * 现在滑到底就能继续，这是长篇连载最关键的一个交互。
 */
@Composable
private fun ChapterNavFooter(
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "本章结束",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPrev, enabled = hasPrev) {
                Text("上一章")
            }
            Button(onClick = onNext, enabled = hasNext) {
                Text("下一章")
            }
        }

        if (!hasNext) {
            Text(
                text = "已经是最后一章",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
