package com.example.hellocompose.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hellocompose.core.AppError
import com.example.hellocompose.core.AppResult
import com.example.hellocompose.data.history.HistoryRepository
import com.example.hellocompose.data.history.ReadingRecord
import com.example.hellocompose.data.model.Chapter
import com.example.hellocompose.data.repository.MangaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val isLoading: Boolean = true,
    val chapters: List<Chapter> = emptyList(),
    val error: AppError? = null,
    /** 这本书的阅读记录：用来标记已读章节、显示「继续阅读」 */
    val record: ReadingRecord? = null
)

class DetailViewModel(
    private val detailUrl: String,
    private val repository: MangaRepository,
    private val history: HistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        observeProgress()
        load()
    }

    /**
     * 订阅而不是只读一次：从阅读器退回来时进度已经变了，
     * 用户应该立刻看到「上次看到第 8 话」和新的已读标记。
     */
    private fun observeProgress() {
        viewModelScope.launch {
            history.records.collect { records ->
                _uiState.update { it.copy(record = records.firstOrNull { r -> r.detailUrl == detailUrl }) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.getChapters(detailUrl)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isLoading = false, chapters = result.data, error = null)
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }
}
