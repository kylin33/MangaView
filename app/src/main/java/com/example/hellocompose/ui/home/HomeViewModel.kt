package com.example.hellocompose.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hellocompose.core.AppConfig
import com.example.hellocompose.core.AppError
import com.example.hellocompose.core.AppResult
import com.example.hellocompose.core.ErrorType
import com.example.hellocompose.data.history.HistoryRepository
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.repository.MangaRepository
import com.example.hellocompose.data.settings.SettingsRepository
import com.example.hellocompose.data.source.SourceConfig
import com.example.hellocompose.data.source.SourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val mangas: List<Manga> = emptyList(),
    val error: AppError? = null,
    val preloadCount: Int = AppConfig.DEFAULT_PRELOAD,
    /** 抽屉里列出的全部站点配置（含未启用的，便于排查） */
    val sources: List<SourceConfig> = emptyList(),
    val currentSource: SourceConfig? = null,
    /** 解析失败的配置文件说明，抽屉里会提示 */
    val configErrors: List<String> = emptyList(),
    /** 内置配置的 id，用来把「删除」显示成「恢复内置」 */
    val builtinIds: Set<String> = emptySet(),
    /** 最近读的一本，用于顶部「继续阅读」卡片 */
    val lastRead: ReadingRecord? = null
)

class HomeViewModel(
    private val repository: MangaRepository,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    private val history: HistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // 从阅读器返回首页时先用内存缓存铺满，避免二次白屏
        _uiState.update { it.copy(mangas = repository.homeCache.value?.items.orEmpty()) }
        observePreloadSetting()
        observeSourceChanges()
        observeLastRead()
        bootstrap()
    }

    /** 阅读器写进度、历史页删记录，卡片都跟着变，不需要页面之间互相通知 */
    private fun observeLastRead() {
        viewModelScope.launch {
            history.lastRead.collect { record ->
                _uiState.update { it.copy(lastRead = record) }
            }
        }
    }

    /**
     * 配置被增删改后重新加载。
     *
     * 用仓库的版本号而不是页面回调：不管用户是在编辑页保存、还是从抽屉里删除，
     * 只要配置变了首页就会跟着刷新，不用每处都记得通知。
     * drop(1) 是为了跳过初始值，避免和 bootstrap 重复抓一次。
     */
    private fun observeSourceChanges() {
        viewModelScope.launch {
            sources.version.drop(1).collect {
                // 缓存绑定了站点 id，配置变了先清掉，免得旧列表冒充新站点
                repository.clearHomeCache()
                _uiState.update { it.copy(mangas = emptyList()) }
                bootstrap()
            }
        }
    }

    /** 读取原始 JSON，供「分享」把可用的配置转给别的设备 */
    suspend fun rawText(source: SourceConfig): String? =
        runCatching { sources.rawText(source) }.getOrNull()

    suspend fun deleteSource(source: SourceConfig): String? =
        runCatching { sources.delete(source) }
            .fold(
                onSuccess = { ok -> if (ok) null else "删除失败，请重试" },
                onFailure = { e -> e.message ?: "删除失败" }
            )

    /** 读取站点配置 -> 确定当前站点 -> 抓首页 */
    private fun bootstrap() {
        viewModelScope.launch {
            val bundle = runCatching { sources.bundle() }.getOrNull()
            val current = runCatching { sources.current() }.getOrNull()

            _uiState.update {
                it.copy(
                    sources = bundle?.configs.orEmpty(),
                    configErrors = bundle?.errors.orEmpty(),
                    builtinIds = bundle?.builtinIds.orEmpty(),
                    currentSource = current
                )
            }

            if (current == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = AppError(
                            ErrorType.CONFIG,
                            "没有可用的站点配置，请打开左侧菜单选择「添加站点」导入一份"
                        )
                    )
                }
            } else {
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.getHome()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, mangas = result.data, error = null)
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    /**
     * 回到首页时对齐一次站点。
     *
     * 历史页续读、搜索页跳转都可能切换过站点，但首页的 currentSource 是启动时读的，
     * 不同步的话会出现「标题写着 A 站、列表其实是 B 站的数据」。
     * 站点没变就直接返回，不产生额外请求。
     */
    fun syncSource() {
        viewModelScope.launch {
            // currentSource 为空说明还在首次启动，交给 bootstrap，别在这里抢着抓一遍
            val known = _uiState.value.currentSource ?: return@launch
            val current = runCatching { sources.current() }.getOrNull() ?: return@launch
            if (current.id == known.id) return@launch
            repository.clearHomeCache()
            _uiState.update {
                it.copy(mangas = emptyList(), isLoading = true, error = null, currentSource = current)
            }
            refresh()
        }
    }

    /**
     * 切换站点：先清缓存并把当前列表清空，再写入偏好、重新抓取。
     * 不清空的话，旧站点的封面会短暂地冒充新站点的数据。
     */
    fun selectSource(source: SourceConfig) {
        if (source.id == _uiState.value.currentSource?.id) return
        viewModelScope.launch {
            repository.clearHomeCache()
            _uiState.update {
                it.copy(mangas = emptyList(), isLoading = true, error = null)
            }
            sources.select(source.id)
            val current = runCatching { sources.current() }.getOrNull()
            _uiState.update { it.copy(currentSource = current ?: source) }
            refresh()
        }
    }

    fun setPreloadCount(count: Int) {
        viewModelScope.launch { settings.setPreloadCount(count) }
    }

    private fun observePreloadSetting() {
        viewModelScope.launch {
            settings.preloadCount.collect { count ->
                _uiState.update { it.copy(preloadCount = count) }
            }
        }
    }
}
