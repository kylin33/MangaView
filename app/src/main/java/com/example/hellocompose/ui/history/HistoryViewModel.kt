package com.example.hellocompose.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hellocompose.data.history.HistoryRepository
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.data.source.SourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val records: List<ReadingRecord> = emptyList(),
    val loading: Boolean = true,
    /** 站点 id -> 封面 Referer：封面是各站点自己的资源，防盗链规则也跟着各自配置走 */
    val referers: Map<String, String> = emptyMap(),
    val currentSourceId: String = ""
)

/**
 * 阅读历史。
 *
 * 列表直接订阅仓库的 Flow，所以在阅读器里翻了几页再退回来，
 * 这里的进度已经是新的，不需要手动 refresh。
 */
class HistoryViewModel(
    private val history: HistoryRepository,
    private val sources: SourceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            history.records.collect { records ->
                _uiState.update { it.copy(records = records, loading = false) }
            }
        }
        viewModelScope.launch {
            val bundle = runCatching { sources.bundle() }.getOrNull()
            val current = runCatching { sources.current() }.getOrNull()
            _uiState.update {
                it.copy(
                    referers = bundle?.configs.orEmpty()
                        .associate { config -> config.id to config.refererFor() },
                    currentSourceId = current?.id.orEmpty()
                )
            }
        }
    }

    fun refererFor(sourceId: String): String = _uiState.value.referers[sourceId].orEmpty()

    fun remove(record: ReadingRecord) {
        viewModelScope.launch { history.remove(record.key) }
    }

    fun clear() {
        viewModelScope.launch { history.clear() }
    }
}
