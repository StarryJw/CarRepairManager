package com.example.carrepairmanager.ui.photo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.PhotoDraft
import com.example.carrepairmanager.ui.userMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 图片页只持有元数据，删除后由 Room Flow 自动更新页数。 */
data class PhotoViewerUiState(val photos: List<PhotoDraft> = emptyList(), val initialPath: String = "",
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null)

/** 图片文件删除走 Repository，ViewModel 不拼路径或操作文件。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoViewerViewModel(private val repository: CarRepairRepository, savedState: SavedStateHandle) : ViewModel() {
    private val orderId: Long = checkNotNull(savedState["orderId"])
    private val photoId: Long = checkNotNull(savedState["photoId"])
    private val reload = MutableStateFlow(0)
    private val action = MutableStateFlow(PhotoViewerUiState())
    private val records = reload.flatMapLatest {
        repository.observeServiceOrder(orderId).map { detail ->
            val photos = detail?.photos.orEmpty().map { PhotoDraft(it.relativePath, it.type, it.id) }
            PhotoViewerUiState(photos, photos.firstOrNull { it.id == photoId }?.relativePath.orEmpty(), loading = false)
        }.catch { emit(PhotoViewerUiState(loading = false, error = it.userMessage("照片读取失败，请重试"))) }
    }
    val state = combine(records, action) { data, operation -> data.copy(busy = operation.busy, error = operation.error ?: data.error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhotoViewerUiState())

    /** 删除错误保留当前大图，可重试或返回详情。 */
    fun delete(photo: PhotoDraft) {
        if (action.value.busy || photo.id == 0L) return
        action.value = PhotoViewerUiState(busy = true)
        viewModelScope.launch {
            try { repository.deletePhoto(photo.id); action.value = PhotoViewerUiState() }
            catch (error: Exception) { action.value = PhotoViewerUiState(error = error.userMessage("照片删除失败，请重试")) }
        }
    }

    /** 文件恢复或数据库读取失败后重新订阅，不重复执行删除。 */
    fun retry() { if (!action.value.busy) { action.value = PhotoViewerUiState(); reload.value++ } }
}
