package com.example.hellocompose.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.hellocompose.data.image.MangaImageRequests
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.ui.LocalAppContainer
import com.example.hellocompose.ui.LocalImageLoader

/**
 * 换源面板。
 *
 * 目录页和阅读页共用：站点卡、图裂了、这站缺章的时候，从这里换到别的站点继续看。
 *
 * 流程是三步而不是一步：先挑站点、再挑书、最后才切。
 * 搜索用目标站点自己的配置发请求，选中之前不动全局站点，
 * 免得用户只是「看一眼有没有」就把首页也换掉了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSwitchSheet(
    mangaTitle: String,
    /** 从阅读页打开时传当前章节名，会在目标站点自动对到同一章；目录页传空 */
    chapterTitle: String = "",
    onDismiss: () -> Unit,
    onOpen: (SwitchSelection) -> Unit
) {
    val container = LocalAppContainer.current
    val viewModel: SourceSwitchViewModel = viewModel(
        // key 带上书名和章节：换一本漫画再打开面板时不能沿用上一本的搜索状态
        key = "switch:$mangaTitle:$chapterTitle",
        factory = viewModelFactory {
            initializer {
                SourceSwitchViewModel(
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    repository = container.mangaRepository,
                    sources = container.sourceRepository
                )
            }
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 每次打开都复位到站点列表，然后才开始接收选定事件
    LaunchedEffect(Unit) {
        viewModel.reset()
        viewModel.selection.collect { onOpen(it) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text("换源", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("用《")
                    append(mangaTitle)
                    append("》去别的站点搜同一本")
                    if (chapterTitle.isNotBlank()) {
                        append("，选中后会自动对到「")
                        append(chapterTitle)
                        append("」")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            when (val step = state.step) {
                SwitchStep.Listing -> Column {
                    // 同一本书跨站可能是繁体/简体或不同译名，允许就地改词再搜
                    OutlinedTextField(
                        value = state.keyword,
                        onValueChange = viewModel::onKeywordChange,
                        label = { Text("搜索书名") },
                        supportingText = { Text("搜不到时换个写法试试，比如繁体/简体或更短的词") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    if (state.loadingSites) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            LoadingView(text = "读取站点…")
                        }
                    } else {
                        SiteList(
                            sites = state.sites,
                            onPick = viewModel::search
                        )
                    }
                }

                is SwitchStep.Busy -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    LoadingView(text = step.text)
                }

                is SwitchStep.Results -> ResultList(
                    sourceName = step.source.name,
                    referer = step.source.refererFor(),
                    keyword = state.keyword.trim(),
                    keywordMatched = step.keywordMatched,
                    items = step.items,
                    onChoose = { manga -> viewModel.choose(step.source, manga) },
                    onBack = viewModel::backToList
                )

                is SwitchStep.Message -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = step.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = viewModel::backToList) { Text("换一个站点") }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SiteList(
    sites: List<SourceSite>,
    onPick: (SourceSite) -> Unit
) {
    if (sites.isEmpty()) {
        Text(
            text = "没有可用的站点配置。先去左侧菜单「添加站点」导入一份。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp)
        )
        return
    }

    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        // 只有一个站点时列表里全是「当前」，用户会以为界面坏了
        if (sites.none { it.searchable && !it.isCurrent }) {
            item(key = "no_other") {
                Text(
                    text = "现在没有可以切换的站点。先回首页打开左上角菜单，用「添加站点」导入一份别的站点配置，再来这里换源。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        items(items = sites, key = { it.config.id }) { site ->
            val enabled = site.searchable && !site.isCurrent
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onPick(site) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = site.config.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled || site.isCurrent) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            site.isCurrent -> "当前站点，换一个再看"
                            !site.searchable -> "没有搜索规则，无法在这里定位这本书"
                            else -> site.config.baseUrl
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (site.isCurrent) {
                    Text(
                        text = "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun ResultList(
    sourceName: String,
    referer: String,
    keyword: String,
    keywordMatched: Boolean,
    items: List<Manga>,
    onChoose: (Manga) -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        item(key = "header") {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "「$sourceName」找到 ${items.size} 个结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onBack) { Text("换站点") }
                }
                // 站点没命中时经常塞一堆「推荐位」，不提醒用户很容易点错书
                if (!keywordMatched) {
                    Text(
                        text = "没有一条标题跟「$keyword」对得上，这些多半是站点给的推荐。" +
                            "先确认书名再点，或者回去换个关键词。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        items(items = items, key = { it.detailUrl }) { manga ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(manga) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 58.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (manga.coverUrl.isNotBlank()) {
                        SubcomposeAsyncImage(
                            model = MangaImageRequests.build(
                                LocalContext.current,
                                manga.coverUrl,
                                referer
                            ),
                            imageLoader = LocalImageLoader.current,
                            contentDescription = manga.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            loading = {},
                            error = {}
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = manga.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 同名基本可以确认是同一本，标题不一样的要用户自己看一眼
                    if (manga.title.trim() != keyword.trim()) {
                        Text(
                            text = "标题与原名不同，确认后再选",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
