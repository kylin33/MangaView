package com.example.hellocompose.ui.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.ui.LocalAppContainer
import com.example.hellocompose.ui.components.ContinueReadingCard
import com.example.hellocompose.ui.components.EmptyView
import com.example.hellocompose.ui.components.ErrorView
import com.example.hellocompose.ui.components.LoadingView
import com.example.hellocompose.ui.components.MangaCoverCard
import com.example.hellocompose.ui.components.PreloadSettingsDialog
import com.example.hellocompose.ui.components.SourceDrawer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMangaClick: (Manga) -> Unit,
    onSearchClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onContinueReading: (ReadingRecord) -> Unit,
    onAddSource: () -> Unit,
    onEditSource: (SourceConfig) -> Unit,
    onStatsClick: () -> Unit
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    repository = container.mangaRepository,
                    sources = container.sourceRepository,
                    settings = container.settingsRepository,
                    history = container.historyRepository
                )
            }
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SourceConfig?>(null) }

    // 从历史页、搜索结果切过站点后回到这里，标题和数据都要跟着换
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.syncSource() }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SourceDrawer(
                sources = state.sources,
                currentId = state.currentSource?.id.orEmpty(),
                preloadCount = state.preloadCount,
                configErrors = state.configErrors,
                builtinIds = state.builtinIds,
                onSelect = { source ->
                    scope.launch { drawerState.close() }
                    viewModel.selectSource(source)
                },
                onAddSource = {
                    scope.launch { drawerState.close() }
                    onAddSource()
                },
                onEditSource = { source ->
                    scope.launch { drawerState.close() }
                    onEditSource(source)
                },
                onDeleteSource = { source ->
                    // 删配置不可撤销，先确认再动手
                    pendingDelete = source
                },
                onShareSource = { source ->
                    scope.launch {
                        val text = viewModel.rawText(source)
                        if (text == null) {
                            Toast.makeText(context, "读不到这份配置的内容", Toast.LENGTH_SHORT).show()
                        } else {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "${source.name}.json")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "分享站点配置")
                            )
                        }
                    }
                },
                onPreloadClick = {
                    scope.launch { drawerState.close() }
                    showSettings = true
                },
                onStatsClick = {
                    scope.launch { drawerState.close() }
                    onStatsClick()
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "站点菜单")
                        }
                    },
                    title = {
                        Column {
                            Text("漫画库")
                            Text(
                                text = state.currentSource?.name ?: "未选择站点",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onHistoryClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = "阅读历史"
                            )
                        }
                        IconButton(onClick = onSearchClick) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    }
                )
            }
        ) { innerPadding ->
            val modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)

            when {
                state.isLoading && state.mangas.isEmpty() -> LoadingView(modifier = modifier)

                // 一份启用的配置都没有：这是全新安装最常见的第一屏。
                // 只给「重试」没有意义——没配置重试多少次都是空，得直接给添加入口。
                state.sources.none { it.enabled } -> EmptyView(
                    text = "还没有添加任何漫画站点。\n填一个站点首页地址，程序会自动识别出抓取规则。",
                    modifier = modifier,
                    actionText = "添加站点",
                    onAction = onAddSource
                )

                state.error != null && state.mangas.isEmpty() -> ErrorView(
                    error = state.error!!,
                    onRetry = viewModel::refresh,
                    modifier = modifier
                )

                state.mangas.isEmpty() -> if (state.currentSource?.isSearchOnly == true) {
                    // 该站点抓不到排行榜（整屏脚本渲染），配置是「仅搜索」的。
                    // 直接给个搜索入口，比只说一句「这里还没有漫画」有用。
                    EmptyView(
                        text = "这个站点暂时找不到排行榜，\n请通过搜索观看漫画",
                        modifier = modifier,
                        actionText = "去搜索",
                        onAction = onSearchClick
                    )
                } else {
                    EmptyView(text = "这里还没有漫画", modifier = modifier)
                }

                else -> Column(modifier = modifier) {
                    // 最近读过的一本置顶：打开 App 最常见的动作是「接着上次看」
                    state.lastRead?.let { record ->
                        ContinueReadingCard(
                            record = record,
                            referer = state.sources
                                .firstOrNull { it.id == record.sourceId }
                                ?.refererFor()
                                .orEmpty(),
                            onClick = { onContinueReading(record) }
                        )
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(items = state.mangas, key = { it.detailUrl }) { manga ->
                            MangaCoverCard(
                                manga = manga,
                                onClick = { onMangaClick(manga) },
                                referer = state.currentSource?.refererFor().orEmpty()
                            )
                        }
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

    pendingDelete?.let { target ->
        val restoresBuiltin = target.id in state.builtinIds
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (restoresBuiltin) "删除并恢复内置？" else "删除这份配置？") },
            text = {
                Text(
                    if (restoresBuiltin) {
                        "「${target.name}」是覆盖内置配置的自定义副本，删除后会恢复内置版本。"
                    } else {
                        "「${target.name}」将从本机删除，删除后不可恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val error = viewModel.deleteSource(target)
                            Toast.makeText(
                                context,
                                error ?: "已删除「${target.name}」",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}
