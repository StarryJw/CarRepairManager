package com.example.carrepairmanager.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.data.AppSettings
import com.example.carrepairmanager.data.AppSettingsRepository
import com.example.carrepairmanager.data.VehicleSort
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

/** 配置写入完成后再展示成功状态，错误可重试。 */
data class SettingsUiState(val settings: AppSettings = AppSettings(), val busy: Boolean = false, val error: String? = null)

/** 设置只操作 DataStore，不保存导航页签；重开应用仍从首页开始。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val repository: AppSettingsRepository) : ViewModel() {
    private val reload = MutableStateFlow(0)
    private val operation = MutableStateFlow<Pair<Boolean, String?>>(false to null)
    val state = combine(reload.flatMapLatest {
        repository.settings.map { SettingsUiState(it) }.catch {
            emit(SettingsUiState(error = it.userMessage("设置读取失败，请重试")))
        }
    }, operation) { data, (busy, error) -> data.copy(busy = busy, error = error ?: data.error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
    /** 车辆列表立即观察该排序偏好，首页服务日期顺序保持不变。 */
    fun setSort(sort: VehicleSort) = write { repository.setVehicleSort(sort) }
    /** 质量配置为图片功能预留，不在界面假装已经具备拍照流程。 */
    fun setQuality(quality: Int) = write { repository.setImageQuality(quality) }
    /** 重启失败的配置读取。 */
    fun retry() { operation.value = false to null; reload.value++ }
    /** 防止快速重复写入，失败时显示明确提示。 */
    private fun write(block: suspend () -> Unit) {
        if (operation.value.first) return
        operation.value = true to null
        viewModelScope.launch {
            try { block(); operation.value = false to null }
            catch (error: Exception) { operation.value = false to error.userMessage("设置保存失败，请重试") }
        }
    }
}
