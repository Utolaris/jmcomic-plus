package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.repository.ComicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExtractCodeUiState(
    val extractedCode: String? = null,
    val previewComic: Comic? = null,
    val loading: Boolean = false,
)

/**
 * 提取编码 Coordinator（L2）。
 *
 * Screen 只提交「提取」事件并渲染状态；数字提取、详情拉取、错误 toast 都在这里，
 * 避免 Entry 直连 ComicRepository。
 */
class ExtractCodeViewModel(
    private val comicRepository: ComicRepository,
    private val toastManager: ToastManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExtractCodeUiState())
    val uiState = _uiState.asStateFlow()

    private var fetchJob: Job? = null

    fun extractAndFetch(text: String) {
        val digits = text.filter { it.isDigit() }
        if (digits.isBlank()) {
            toastManager.showAsync("未检测到数字，无法提取编码")
            return
        }
        fetchJob?.cancel()
        _uiState.update {
            it.copy(extractedCode = digits, previewComic = null, loading = true)
        }
        fetchJob = viewModelScope.launch {
            try {
                when (val result = comicRepository.getComicDetail(digits.toInt())) {
                    is NetWorkResult.Success -> {
                        _uiState.update { state ->
                            state.copy(previewComic = result.data, loading = false)
                        }
                    }

                    is NetWorkResult.Error -> {
                        toastManager.showAsync("获取漫画详情失败：${result.message}")
                        _uiState.update { state ->
                            state.copy(extractedCode = null, loading = false)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // 协程取消必须原样上抛，不能被当成「获取失败」吞掉。
                throw cancelled
            } catch (error: Exception) {
                toastManager.showAsync("获取漫画详情异常")
                _uiState.update { state ->
                    state.copy(extractedCode = null, loading = false)
                }
            }
        }
    }

    /** 关闭预览弹窗：清掉 preview 与已提取编码，保留输入框文本由 Screen 自行管理。 */
    fun dismissPreview() {
        _uiState.update {
            it.copy(previewComic = null, extractedCode = null)
        }
    }
}
