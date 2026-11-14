package com.example.hellocompose.ui.source

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.hellocompose.data.source.ConfidenceLevel
import com.example.hellocompose.ui.LocalAppContainer

/**
 * 添加 / 编辑站点。
 *
 * 设计取舍：不做「表单化编辑器」（4 组规则 × 3 个字段，表单会长到没法用），
 * 而是「导入 + 校验 + 试抓取」。用户几乎不会手写选择器，但一定能判断
 * 「抓出来的是不是这个站点的东西」，所以把确认权交给试抓取结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceEditorScreen(
    editingId: String,
    onBack: () -> Unit
) {
    val container = LocalAppContainer.current
    val appContext = LocalContext.current.applicationContext

    val viewModel: SourceEditorViewModel = viewModel(
        key = "source_editor_$editingId",
        factory = viewModelFactory {
            initializer {
                SourceEditorViewModel(
                    editingId = editingId,
                    context = appContext,
                    sources = container.sourceRepository,
                    probe = container.sourceProbe,
                    generator = container.sourceAutoGenerator,
                    settings = container.settingsRepository
                )
            }
        }
    )

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 保存或删除成功后统一退出，不区分是哪一种
    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    // SAF 选文件：不需要任何存储权限，只拿到这一次读取的授权
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }

    var showUrlDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    if (state.canDelete) {
                        IconButton(onClick = viewModel::requestDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "删除这个配置")
                        }
                    }
                    TextButton(
                        onClick = viewModel::requestSave,
                        enabled = !state.busy
                    ) {
                        Text("保存")
                    }
                }
            )
        }
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AutoDetectPanel(
                    state = state.auto,
                    enabled = !state.busy,
                    onUrlChange = viewModel::onAutoUrlChange,
                    onRun = viewModel::runAutoDetect
                )

                Text(
                    text = "识别完可以直接保存；也可以从别处导入一份调好的 JSON，或手动改下面的选择器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::importFromClipboard,
                        enabled = !state.busy
                    ) { Text("粘贴") }

                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = !state.busy
                    ) { Text("选文件") }

                    OutlinedButton(
                        onClick = { showUrlDialog = true },
                        enabled = !state.busy
                    ) { Text("从链接") }
                }

                state.notice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::onTextChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                    label = { Text("站点配置 JSON") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    isError = state.error != null,
                    minLines = 12
                )

                state.error?.let { message ->
                    MessagePanel(
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        title = "配置有问题",
                        body = message
                    )
                }

                state.probe?.let { probe ->
                    MessagePanel(
                        container = if (probe.ok) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        content = if (probe.ok) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        title = if (probe.ok) "试抓成功" else "试抓失败",
                        body = probe.message,
                        lines = probe.samples.map { it.title }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::validate,
                        enabled = !state.busy
                    ) { Text("校验格式") }

                    FilledTonalButton(
                        onClick = viewModel::runProbe,
                        enabled = !state.busy
                    ) { Text("试抓首页") }
                }

                state.parsed?.let { config ->
                    Text(
                        text = "当前解析：${config.name}（id=${config.id}）\n${config.baseUrl}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (state.busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text("处理中…", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (state.savingOverridesBuiltin) {
                    Text(
                        text = "这是内置配置。保存会在你的配置目录里生成一份同 id 的副本覆盖它，" +
                            "内置文件本身不会改动；以后删掉这份副本就能恢复内置版本。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (state.deleteRestoresBuiltin) {
                    Text(
                        text = "这份配置覆盖着同名内置配置，删除后会恢复内置版本。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "字段说明：home 是首页列表，detail 是章节列表，reader 是章节图片；" +
                        "item 填 CSS 选择器，attr 支持 text / href / src，" +
                        "多个候选属性用 | 分隔（如 data-src|src）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (state.askOverwrite) {
        AlertDialog(
            onDismissRequest = viewModel::cancelOverwrite,
            title = { Text("id 已存在") },
            text = {
                Text("已经有一份 id 相同的配置，继续保存会覆盖它。确定要覆盖吗？")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmOverwrite) { Text("覆盖保存") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelOverwrite) { Text("取消") }
            }
        )
    }

    if (state.askDelete) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("删除这份配置？") },
            text = {
                Text(
                    if (state.deleteRestoresBuiltin) {
                        "将从本机删除这份自定义配置，之后会恢复同名的内置配置。"
                    } else {
                        "将从本机删除这份站点配置，删除后不可恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text("取消") }
            }
        )
    }

    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("从链接导入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "填一个能直接返回 JSON 的地址。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("https://…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUrlDialog = false
                        viewModel.importFromUrl(url)
                    },
                    enabled = url.isNotBlank()
                ) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 统一样式的结果面板：错误和试抓结果共用一套排版 */
@Composable
private fun MessagePanel(
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
    lines: List<String> = emptyList()
) {
    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = content
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = content
            )
            lines.forEach { line ->
                Text(
                    text = "· $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (lines.isNotEmpty()) {
                Text(
                    text = "上面是前 ${lines.size} 条，标题取对了基本就能用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = content
                )
            }
        }
    }
}

/**
 * 自动识别入口。
 *
 * 这是添加站点的主路径：用户只需要知道站点首页地址，不需要懂 CSS 选择器。
 * 识别过程逐步显示（打开首页 → 识别到 N 部 → 打开详情页…），
 * 一来让等待有进度感，二来出问题时能直接看出卡在哪一步。
 */
@Composable
private fun AutoDetectPanel(
    state: AutoUiState,
    enabled: Boolean,
    onUrlChange: (String) -> Unit,
    onRun: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "自动识别站点",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "填站点首页地址，程序会自己打开首页、详情页和第一章，" +
                    "把列表、章节、图片的选择器推出来。识别完直接保存即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("站点地址") },
                placeholder = { Text("https://example.com") },
                singleLine = true,
                enabled = !state.running,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { if (enabled) onRun() })
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = onRun, enabled = enabled) {
                    Text(if (state.generated) "重新识别" else "开始识别")
                }
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                }
            }

            state.steps.takeLast(6).forEach { step ->
                Text(
                    text = "· $step",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            state.report?.let { report ->
                val confidence = report.confidence
                Text(
                    text = "置信度 ${(confidence.overall * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = when (confidence.level) {
                        ConfidenceLevel.HIGH -> MaterialTheme.colorScheme.primary
                        ConfidenceLevel.MEDIUM -> MaterialTheme.colorScheme.tertiary
                        ConfidenceLevel.LOW -> MaterialTheme.colorScheme.error
                    }
                )
                // 不到「可以放心用」就把最拖后腿的一项指出来，用户知道该去改哪一段
                if (confidence.level != ConfidenceLevel.HIGH) {
                    confidence.weakest?.let { factor ->
                        Text(
                            text = "最弱环节：${factor.name} —— ${factor.note}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Text(
                    text = "首页 ${report.homeCount} 部 · 章节 ${report.chapterCount} 个 · " +
                        "图片 ${report.imageCount} 张",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = if (report.searchPath != null) {
                        "搜索：可用（${report.searchPath}）"
                    } else {
                        "搜索：没识别到，需要的话在配置里补 search 节点"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (report.homeSamples.isNotEmpty()) {
                    Text(
                        text = "抓到的是：" + report.homeSamples.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                report.warnings.forEach { warning ->
                    Text(
                        text = "注意：$warning",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
