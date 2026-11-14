package com.example.hellocompose.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.ui.LocalAppContainer
import com.example.hellocompose.ui.components.EmptyView
import com.example.hellocompose.ui.components.ErrorView
import com.example.hellocompose.ui.components.LoadingView
import com.example.hellocompose.ui.components.MangaCoverCard

/**
 * 站内搜索页。
 *
 * 搜索是「知道看什么名字」时的主路径：首页只能按站点推荐的顺序翻，
 * 想找某一部必须能搜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onMangaClick: (Manga) -> Unit,
    initialKeyword: String = ""
) {
    val container = LocalAppContainer.current
    val viewModel: SearchViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    repository = container.mangaRepository,
                    sources = container.sourceRepository,
                    initialKeyword = initialKeyword
                )
            }
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("搜索") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.keyword,
                    onValueChange = viewModel::onKeywordChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("漫画名") },
                    placeholder = {
                        Text(
                            if (state.sourceName.isBlank()) {
                                "输入关键字"
                            } else {
                                "在「${state.sourceName}」里搜索"
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submit() })
                )
                IconButton(onClick = viewModel::submit, enabled = !state.loading) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
            }

            val bodyModifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)

            when {
                state.loading -> LoadingView(text = "正在搜索…", modifier = bodyModifier)

                state.error != null -> ErrorView(
                    error = state.error!!,
                    onRetry = viewModel::retry,
                    modifier = bodyModifier
                )

                state.results.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = bodyModifier
                ) {
                    items(items = state.results, key = { it.detailUrl }) { manga ->
                        MangaCoverCard(
                            manga = manga,
                            onClick = { onMangaClick(manga) },
                            referer = state.referer
                        )
                    }
                }

                state.searched -> EmptyView(
                    text = "没有搜到「${state.keyword.trim()}」",
                    modifier = bodyModifier
                )

                else -> Box(modifier = bodyModifier, contentAlignment = Alignment.TopCenter) {
                    Text(
                        text = if (state.unsupported) {
                            "当前站点没有配置搜索规则，可以在站点配置里补 search 节点。"
                        } else {
                            "输入漫画名后回车。搜不到时换个更短的关键字试试。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            }
        }
    }
}
