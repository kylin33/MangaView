package com.example.hellocompose.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.data.source.SourceOrigin

/**
 * 左侧站点抽屉。
 *
 * 站点列表完全来自 JSON 配置 —— 加一份配置就多一条，这里没有任何写死的站点名称或地址。
 * 每一条都带操作菜单：内置配置可编辑（另存为同 id 的覆盖副本）和分享，
 * 用户配置额外可以删除。
 */
@Composable
fun SourceDrawer(
    sources: List<SourceConfig>,
    currentId: String,
    preloadCount: Int,
    configErrors: List<String>,
    builtinIds: Set<String>,
    onSelect: (SourceConfig) -> Unit,
    onAddSource: () -> Unit,
    onEditSource: (SourceConfig) -> Unit,
    onDeleteSource: (SourceConfig) -> Unit,
    onShareSource: (SourceConfig) -> Unit,
    onPreloadClick: () -> Unit
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(text = "漫画库", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "共 ${sources.count { it.enabled }} 个可用站点",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item {
                NavigationDrawerItem(
                    label = { Text("添加站点") },
                    selected = false,
                    onClick = onAddSource,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            items(items = sources, key = { it.id }) { source ->
                if (source.enabled) {
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text(
                                    text = source.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (source.description.isNotBlank()) {
                                    Text(
                                        text = source.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        selected = source.id == currentId,
                        onClick = { onSelect(source) },
                        icon = if (source.id == currentId) {
                            { Icon(Icons.Default.Check, contentDescription = "当前站点") }
                        } else {
                            null
                        },
                        badge = {
                            SourceActions(
                                source = source,
                                overridesBuiltin = builtinIds.contains(source.id),
                                onEdit = { onEditSource(source) },
                                onDelete = { onDeleteSource(source) },
                                onShare = { onShareSource(source) }
                            )
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                } else {
                    // 未启用的配置照样列出来，方便对照着改 enabled 字段；
                    // 菜单保留，否则「示例模板」这种 disabled 的内置项就成了死行
                    DisabledSourceRow(
                        source = source,
                        overridesBuiltin = builtinIds.contains(source.id),
                        onEdit = { onEditSource(source) },
                        onDelete = { onDeleteSource(source) },
                        onShare = { onShareSource(source) }
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                NavigationDrawerItem(
                    label = { Text("预加载张数") },
                    selected = false,
                    onClick = onPreloadClick,
                    badge = {
                        Text(
                            text = "$preloadCount 张",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }

            if (configErrors.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
                        Text(
                            text = "有 ${configErrors.size} 份配置没能加载",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        configErrors.forEach { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "内置配置随 App 打包，改不了；编辑内置配置会生成一份同 id 的副本覆盖它。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp)
        )
    }
}

/** 每条站点右侧的「更多」菜单 */
@Composable
private fun SourceActions(
    source: SourceConfig,
    overridesBuiltin: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "${source.name} 的操作",
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("编辑配置") },
                onClick = {
                    expanded = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text("分享 JSON") },
                onClick = {
                    expanded = false
                    onShare()
                }
            )
            if (source.origin == SourceOrigin.USER) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (overridesBuiltin) "删除并恢复内置" else "删除配置",
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        expanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun DisabledSourceRow(
    source: SourceConfig,
    overridesBuiltin: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.45f)
            .padding(start = 28.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = source.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "${source.id} · enabled=false",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SourceActions(
            source = source,
            overridesBuiltin = overridesBuiltin,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare
        )
    }
}
