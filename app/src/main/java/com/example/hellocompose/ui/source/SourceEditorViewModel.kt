package com.example.hellocompose.ui.source

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hellocompose.core.AppError
import com.example.hellocompose.core.AppResult
import com.example.hellocompose.core.ErrorType
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.source.ConfigFetcher
import com.example.hellocompose.engine.autogen.InsecurePolicy
import com.example.hellocompose.engine.autogen.InsecureRequest
import com.example.hellocompose.engine.autogen.SourceAutoGenerator
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.data.source.SourceConflictException
import com.example.hellocompose.data.source.SourceOrigin
import com.example.hellocompose.data.source.SourceParser
import com.example.hellocompose.data.source.SourceProbe
import com.example.hellocompose.data.source.SourceRepository
import com.example.hellocompose.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 试抓取结果 */
data class ProbeUiState(
    val ok: Boolean,
    val count: Int = 0,
    val samples: List<Manga> = emptyList(),
    val message: String = ""
)

/** 自动识别的过程状态。步骤文案直接展示给用户，让等待有进度感 */
data class AutoUiState(
    val url: String = "",
    val running: Boolean = false,
    val steps: List<String> = emptyList(),
    val report: SourceAutoGenerator.Report? = null,
    val error: String? = null,
    /** 识别成功过：此时页面下方是识别产物，用户可以基于它改 */
    val generated: Boolean = false,
    /**
     * 证书/协议问题被拦下，等用户决定要不要以不安全方式继续。
     * 非空时界面必须弹确认框，用户不点「继续」就绝不会发起放宽后的请求。
     */
    val confirm: InsecureRequest? = null
)

data class SourceEditorUiState(
    val loading: Boolean = true,
    val text: String = "",
    val isNew: Boolean = true,
    val title: String = "添加站点",
    val busy: Boolean = false,
    /** 解析失败的具体原因，直接展示给用户 */
    val error: String? = null,
    /** 一次性提示（导入成功、下载中等） */
    val notice: String? = null,
    val parsed: SourceConfig? = null,
    val probe: ProbeUiState? = null,
    /** 保存时会覆盖内置配置 —— 内置文件改不动，实际是新建一份同 id 的用户配置 */
    val savingOverridesBuiltin: Boolean = false,
    /** 删除后内置版本会重新生效 */
    val deleteRestoresBuiltin: Boolean = false,
    val canDelete: Boolean = false,
    val askOverwrite: Boolean = false,
    val askDelete: Boolean = false,
    val finished: Boolean = false,
    /** 自动识别站点的状态 */
    val auto: AutoUiState = AutoUiState()
)

/**
 * 添加 / 编辑站点的状态机。
 *
 * 核心判断：普通用户写不出 CSS 选择器，所以这里把「编写」降级成「导入 + 验证」，
 * 用户的主要动作是从别处拿到一份配置，再用试抓取确认它在这台设备上能用。
 */
class SourceEditorViewModel(
    private val editingId: String,
    private val context: Context,
    private val sources: SourceRepository,
    private val probe: SourceProbe,
    private val generator: SourceAutoGenerator,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourceEditorUiState())
    val uiState: StateFlow<SourceEditorUiState> = _uiState.asStateFlow()

    /** 正在编辑的配置对象，删除时要用它的文件名和来源 */
    private var editing: SourceConfig? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (editingId.isBlank()) {
            _uiState.update {
                it.copy(
                    loading = false,
                    isNew = true,
                    title = "添加站点",
                    text = SourceParser.TEMPLATE
                )
            }
            return
        }

        val bundle = runCatching { sources.bundle() }.getOrNull()
        val source = bundle?.configs?.firstOrNull { it.id == editingId }
        if (source == null) {
            _uiState.update {
                it.copy(
                    loading = false,
                    title = "编辑站点",
                    error = "找不到 id 为「$editingId」的站点配置，可能已被删除"
                )
            }
            return
        }

        editing = source
        _uiState.update {
            it.copy(
                loading = false,
                isNew = false,
                title = "编辑 ${source.name}",
                text = sources.rawText(source) ?: SourceParser.TEMPLATE,
                canDelete = source.origin == SourceOrigin.USER,
                savingOverridesBuiltin = source.origin == SourceOrigin.BUILTIN,
                deleteRestoresBuiltin = source.origin == SourceOrigin.USER &&
                    bundle.hasBuiltin(source.id)
            )
        }
    }

    fun onTextChange(value: String) {
        // 内容一改，之前的试抓结论就失效了，必须一起清掉
        _uiState.update { it.copy(text = value, error = null, probe = null) }
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    fun onAutoUrlChange(value: String) {
        _uiState.update { it.copy(auto = it.auto.copy(url = value, error = null)) }
    }

    /**
     * 只凭一个首页地址生成整份配置。
     *
     * 成功后把生成的 JSON 直接铺进编辑框：用户可以直接保存，
     * 也能看到程序推出的选择器长什么样，需要时自己微调 ——
     * 这是「自动生成」相对「导入别人配好的 JSON」最大的好处。
     */
    fun runAutoDetect() {
        val url = _uiState.value.auto.url.trim()
        if (url.isEmpty()) {
            _uiState.update {
                it.copy(auto = it.auto.copy(error = "先填站点首页地址，例如 example.com"))
            }
            return
        }
        if (_uiState.value.auto.running) return

        // 默认严格：证书不通过就停下来问，不自己放宽
        launchDetect(url, InsecurePolicy.STRICT)
    }

    /** 用户看过证书错误后仍然选择继续。带上刚确认的策略重跑，别的路径都走不进来 */
    fun confirmInsecure() {
        val request = _uiState.value.auto.confirm ?: return
        if (_uiState.value.auto.running) return
        launchDetect(
            url = _uiState.value.auto.url.trim(),
            policy = request.policy,
            note = "已按你的确认继续（不安全连接）"
        )
    }

    fun cancelInsecure() {
        _uiState.update {
            it.copy(
                auto = it.auto.copy(
                    confirm = null,
                    error = "已取消：没有建立不安全连接，这个站点暂时添加不了"
                )
            )
        }
    }

    private fun launchDetect(url: String, policy: InsecurePolicy, note: String? = null) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    error = null,
                    probe = null,
                    auto = it.auto.copy(
                        running = true,
                        steps = listOfNotNull(note),
                        report = null,
                        error = null,
                        confirm = null
                    )
                )
            }

            val outcome = generator.generate(url, policy) { step ->
                // 识别在 IO 线程跑，StateFlow 的 update 是线程安全的，这里直接追加步骤
                _uiState.update { it.copy(auto = it.auto.copy(steps = it.auto.steps + step)) }
            }

            _uiState.update { state ->
                when (outcome) {
                    is SourceAutoGenerator.Outcome.Success -> {
                        val result = outcome.result
                        state.copy(
                            busy = false,
                            text = result.json,
                            parsed = result.config,
                            error = null,
                            notice = null,
                            auto = state.auto.copy(
                                running = false,
                                generated = true,
                                report = result.report,
                                // 识别时可能换成 http（用户确认过），回填真实地址免得用户以为填错了
                                url = result.report.baseUrl
                            )
                        )
                    }

                    // 不做任何决定，只把证据交出来等用户选
                    is SourceAutoGenerator.Outcome.NeedsConfirmation -> state.copy(
                        busy = false,
                        auto = state.auto.copy(running = false, confirm = outcome.request)
                    )

                    is SourceAutoGenerator.Outcome.Failed -> state.copy(
                        busy = false,
                        auto = state.auto.copy(running = false, error = describe(outcome.error))
                    )
                }
            }
        }
    }

    /** 识别阶段的报错要能指路，不能把「解析规则需要更新」这种运行期文案套上来 */
    private fun describe(error: AppError): String = when (error.type) {
        ErrorType.NETWORK -> "连不上这个站点：${error.detail}"
        ErrorType.TIMEOUT -> "站点响应超时，稍后重试"
        // 证书错误要把「签给了谁、什么时候过期」一起说清楚，否则用户无法判断该不该继续
        ErrorType.TLS -> "${error.userMessage}\n${error.detail}"
        else -> error.detail
    }


    fun importFromClipboard() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context).toString()
        } else {
            ""
        }

        if (text.isBlank()) {
            _uiState.update { it.copy(notice = "剪贴板里没有文本") }
            return
        }
        applyImported(text, "已从剪贴板导入 ${text.length} 个字符")
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }

            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader().use { it.readText() }
                    }
                }
            }.getOrNull()

            _uiState.update { it.copy(busy = false) }

            if (text.isNullOrBlank()) {
                _uiState.update { it.copy(notice = "文件读不出内容，换一个文件试试") }
            } else {
                applyImported(text, "已从文件导入 ${text.length} 个字符")
            }
        }
    }

    fun importFromUrl(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, notice = "正在下载配置…") }

            val result = runCatching { ConfigFetcher.download(url) }
            _uiState.update { it.copy(busy = false) }

            result.fold(
                onSuccess = { text -> applyImported(text, "已从链接导入 ${text.length} 个字符") },
                onFailure = { e ->
                    _uiState.update { it.copy(error = "下载失败：${e.message}") }
                }
            )
        }
    }

    fun validate() {
        parseOrReport()
    }

    /** 试抓取：用当前配置真跑一次首页，把结果直接摆出来 */
    fun runProbe() {
        viewModelScope.launch {
            val config = parseOrReport() ?: return@launch
            _uiState.update { it.copy(busy = true, error = null, probe = null) }

            val result = probe.run(config)

            _uiState.update { state ->
                state.copy(
                    busy = false,
                    probe = when (result) {
                        is AppResult.Success -> ProbeUiState(
                            ok = true,
                            count = result.data.size,
                            samples = result.data.take(3),
                            message = "首页解析到 ${result.data.size} 部作品"
                        )

                        is AppResult.Failure -> ProbeUiState(
                            ok = false,
                            message = "${result.error.userMessage}（${result.error.detail}）"
                        )
                    }
                )
            }
        }
    }

    fun requestSave() {
        viewModelScope.launch {
            // 解析不通过就不必写盘，避免目录里出现一个读不出来的文件
            parseOrReport() ?: return@launch
            doSave(overwrite = false)
        }
    }

    fun confirmOverwrite() {
        _uiState.update { it.copy(askOverwrite = false) }
        viewModelScope.launch { doSave(overwrite = true) }
    }

    fun cancelOverwrite() = _uiState.update { it.copy(askOverwrite = false) }

    fun requestDelete() {
        if (!_uiState.value.canDelete) return
        _uiState.update { it.copy(askDelete = true) }
    }

    fun cancelDelete() = _uiState.update { it.copy(askDelete = false) }

    fun confirmDelete() {
        val target = editing ?: return
        _uiState.update { it.copy(askDelete = false) }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val ok = runCatching { sources.delete(target) }.getOrDefault(false)
            _uiState.update {
                it.copy(
                    busy = false,
                    // 删成功才离开页面；失败留在原地，用户至少能看到原因
                    finished = ok,
                    error = if (ok) null else "删除失败，文件可能已被占用"
                )
            }
        }
    }

    private suspend fun doSave(overwrite: Boolean) {
        _uiState.update { it.copy(busy = true, error = null) }
        try {
            val saved = sources.save(_uiState.value.text, overwrite = overwrite)
            // 新建后自动切过去：用户添加站点的目的就是用上它
            if (_uiState.value.isNew) settings.setSelectedSourceId(saved.id)
            _uiState.update { it.copy(busy = false, finished = true) }
        } catch (e: SourceConflictException) {
            _uiState.update { it.copy(busy = false, askOverwrite = true) }
        } catch (e: Exception) {
            _uiState.update { it.copy(busy = false, error = e.message ?: "保存失败") }
        }
    }

    /** 解析一次：成功返回配置，失败把原因写进状态供界面展示 */
    private fun parseOrReport(): SourceConfig? =
        runCatching { SourceParser.parse(_uiState.value.text, fallbackId = "custom") }
            .onSuccess { config ->
                _uiState.update { it.copy(parsed = config, error = null) }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(parsed = null, error = e.message ?: "配置格式不对")
                }
            }
            .getOrNull()

    private fun applyImported(text: String, message: String) {
        _uiState.update {
            it.copy(text = text, error = null, probe = null, notice = message)
        }
        // 导入后立刻解析一次，用户不用再点一下「校验」
        parseOrReport()
    }
}
