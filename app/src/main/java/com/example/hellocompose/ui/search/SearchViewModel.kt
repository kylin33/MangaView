package com.example.hellocompose.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hellocompose.core.AppError
import com.example.hellocompose.core.AppResult
import com.example.hellocompose.core.ErrorType
import com.example.hellocompose.data.model.Manga
import com.example.hellocompose.data.repository.MangaRepository
import com.example.hellocompose.data.source.SourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val keyword: String = "",
    val loading: Boolean = false,
    val results: List<Manga> = emptyList(),
    val error: AppError? = null,
    /** 已经搜过一次（用来区分「还没搜」和「搜了但没结果」） */
    val searched: Boolean = false,
    val sourceName: String = "",
    /** 图片防盗链用：跟当前站点配置走 */
    val referer: String = "",
    /** 当前站点没有配置 search 节点 */
    val unsupported: Boolean = false
)

/**
 * 站内搜索。
 *
 * 搜索地址与选择器同样来自站点配置的 search 节点，所以这里不关心是哪个站点。
 * 站点没配 search 时直接给出提示并引导去补配置，而不是发一个必然失败的请求。
 */
class SearchViewModel(
    private val repository: MangaRepository,
    private val sources: SourceRepository,
    /** 从历史记录「搜同书」进来时带上书名，省掉用户再敲一遍 */
    private val initialKeyword: String = ""
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val source = runCatching { sources.current() }.getOrNull()
            _uiState.update {
                it.copy(
                    keyword = initialKeyword,
                    sourceName = source?.name.orEmpty(),
                    referer = source?.refererFor().orEmpty(),
                    unsupported = source?.search == null
                )
            }
            // 带着书名进来就直接出结果，不用用户再点一次搜索
            if (initialKeyword.isNotBlank()) submit()
        }
    }

    fun onKeywordChange(value: String) {
        _uiState.update { it.copy(keyword = value) }
    }

    fun submit() {
        val keyword = _uiState.value.keyword.trim()
        if (keyword.isEmpty() || _uiState.value.loading) return

        if (_uiState.value.unsupported) {
            _uiState.update {
                it.copy(
                    error = AppError(
                        ErrorType.CONFIG,
                        "当前站点没有配置搜索规则。可以在站点配置里补一个 search 节点，" +
                            "或者直接重新自动识别一次"
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, searched = true) }
            when (val result = repository.search(keyword)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(loading = false, results = result.data, error = null)
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(loading = false, results = emptyList(), error = result.error)
                }
            }
        }
    }

    fun retry() = submit()
}
