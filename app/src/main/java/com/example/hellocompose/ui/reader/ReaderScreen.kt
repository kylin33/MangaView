package com.example.hellocompose.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.network.HttpException
import com.example.hellocompose.data.image.ImageErrorText
import com.example.hellocompose.data.image.ImageRequestManager
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.engine.source.Page
import com.example.hellocompose.ui.LocalAppContainer
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
    onSwitchSource: (SourceConfig, Manga, Chapter?) -> Unit,
    /** 看完之后去查图片加载性能，不在阅读页实时显示 */
    onOpenStats: () -> Unit
) {
    val container = LocalAppContainer.current
    /** 图片请求层：请求构造、预加载、缓存释放都走它，UI 只负责显示 */
    val images = container.imageRequests

    val viewModel: ReaderViewModel = viewModel(
        // 按「详情页 + 起始章节」建 VM：不同的书各自维护自己的目录和进度
        key = "reader_${detailUrl}_$chapterUrl",
        factory = viewModelFactory {
            initializer {
                ReaderViewModel(
                    initialChapter = Chapter(chapterTitle, chapterUrl),
                    mangaTitle = mangaTitle,
                    coverUrl = coverUrl,
                    detailUrl = detailUrl,
                    repository = container.mangaRepository,
                    sources = container.sourceRepository,
                    settings = container.settingsRepository,
                    history = container.historyRepository,
                    images = container.imageRequests
                )
            }
        }
    )

    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }

    /**
     * 当前失败的页（地址 → 原因）。单张重试在页内，这里汇总是为了给「整章重试」用：
     * 一张一张点太慢，尤其是图床整体挂了的时候。
     */
    var failedPages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var retryEpoch by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 退出阅读页时把还在飞的预加载取消掉。已经解好的位图留在缓存里，
    // 所以下次进来还是快的，取消的只是「已经没人要的下半章」
    DisposableEffect(Unit) {
        onDispose { images.cancelPreloads() }
    }

    // 加载完成后定位：上次看到的位置优先，没有记录再回顶部
    LaunchedEffect(state.chapterUrl, state.isLoading) {
        if (!state.isLoading && state.pages.isNotEmpty()) {
            listState.scrollToItem(state.restoreIndex.coerceAtLeast(0))
            viewModel.onRestoreConsumed()
        }
    }

    // 当前屏幕内最后一张可见页的索引
    val lastVisibleIndex by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(lastVisibleIndex) {
        viewModel.onVisiblePage(lastVisibleIndex)
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
                            enabled = state.pages.isNotEmpty(),
                            onClick = {
                                showMenu = false
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        )
                        // 图床整体抽风时，一张张点重试太慢；这里一次性重来
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (failedPages.isEmpty()) {
                                        "重试加载失败的图"
                                    } else {
                                        "重试失败的 ${failedPages.size} 张图"
                                    }
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            },
                            enabled = failedPages.isNotEmpty(),
                            onClick = {
                                showMenu = false
                                failedPages = emptyMap()
                                retryEpoch += 1
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("图片加载统计") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onOpenStats()
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
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
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
            if (state.pages.isNotEmpty()) {
                ReaderStatusBarContent(
                    currentPage = lastVisibleIndex + 1,
                    totalPages = state.pages.size,
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
                    onRetry = viewModel::retry
                )

                state.pages.isEmpty() -> Text(
                    text = "本章没有图片",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center)
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = state.pages,
                        key = { index, page -> "$index-${page.url}" }
                    ) { index, page ->
                        PageImage(
                            page = page,
                            index = index,
                            total = state.pages.size,
                            images = images,
                            retryEpoch = retryEpoch,
                            onFailure = { url, reason ->
                                if (reason == null) {
                                    if (failedPages.containsKey(url)) {
                                        failedPages = failedPages - url
                                    }
                                } else if (failedPages[url] != reason) {
                                    failedPages = failedPages + (url to reason)
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

/** 还不知道页面比例时先占的高度，避免图片到达后把滚动位置顶跑 */
private val PagePlaceholderHeight = 280.dp

/**
 * 一页漫画。
 *
 * 用 `AsyncImage` 而不是 `SubcomposeAsyncImage`：后者每次加载都要开一次子组合，
 * 长列表滚动时开销明显；加载指示器用一个覆盖层就够了。
 *
 * 高度先按记下来的宽高比占好（见 PageSizeCache），图片到达只是往框里填内容，
 * 不再引发布局跳动——长图连续滚动的场景里，这比加载动画重要得多。
 *
 * 解码尺寸、Referer、缓存 key 全在 [ImageRequestManager.pageRequest] 里，
 * 这里只管显示和失败重试。
 */
@Composable
private fun PageImage(
    page: Page,
    index: Int,
    total: Int,
    images: ImageRequestManager,
    /** 整章重试的信号：变化时，失败的页自己再试一次 */
    retryEpoch: Int,
    onFailure: (url: String, reason: String?) -> Unit
) {
    var attempt by remember(page.url) { mutableIntStateOf(0) }
    var aspect by remember(page.url) { mutableFloatStateOf(images.sizes.aspectOf(page.url) ?: 0f) }
    var error by remember(page.url) { mutableStateOf<String?>(null) }

    // 只重试真的失败的页：整章重来会把几十张已经好好的图重新解一遍
    LaunchedEffect(retryEpoch) {
        if (retryEpoch > 0 && error != null) {
            error = null
            attempt += 1
        }
    }

    // 重试时换一个请求实例，AsyncImage 才会真的重新发起加载
    val request = remember(page.url, attempt) { images.pageRequest(page) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (aspect > 0f) {
                    Modifier.aspectRatio(aspect)
                } else {
                    Modifier.height(PagePlaceholderHeight)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            imageLoader = images.imageLoader,
            contentDescription = "第 ${index + 1} 页",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxSize(),
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> {
                        error = null
                        onFailure(page.url, null)
                        val drawable = state.result.drawable
                        if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                            images.sizes.put(
                                page.url,
                                drawable.intrinsicWidth,
                                drawable.intrinsicHeight
                            )
                            aspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                        }
                    }

                    is AsyncImagePainter.State.Error -> {
                        val throwable = state.result.throwable
                        val message = ImageErrorText.of(
                            throwable,
                            (throwable as? HttpException)?.response?.code
                        )
                        error = message
                        onFailure(page.url, message)
                    }

                    else -> Unit
                }
            }
        )

        when {
            error != null -> PageError(
                message = error.orEmpty(),
                onRetry = {
                    error = null
                    attempt += 1
                }
            )

            // 还不知道这张图多大，先给个进度，别让整页空白
            aspect <= 0f -> PageLoading(index + 1, total)
        }
    }
}

@Composable
private fun PageLoading(current: Int, total: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "$current / $total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PageError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRetry) {
            Text("重试")
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
