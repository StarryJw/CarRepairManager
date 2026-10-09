package com.example.carrepairmanager.ui.vehicle

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.local.ServiceHistoryEntry
import com.example.carrepairmanager.data.local.VehicleWithCustomer
import com.example.carrepairmanager.data.local.VehicleDeletionSummary
import com.example.carrepairmanager.ui.userMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 详情分页与完整删除范围分别读取，删除结果可消费以退出已失效档案。 */
data class VehicleDetailUiState(val record: VehicleWithCustomer? = null, val history: List<ServiceHistoryEntry> = emptyList(),
    val hasMore: Boolean = false, val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val deletionSummary: VehicleDeletionSummary? = null, val deleted: Boolean = false)

/** 观察编辑和删除范围，写操作防重复提交，成功删除以可恢复结果触发返回。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleDetailViewModel(private val repository: CarRepairRepository, private val savedState: SavedStateHandle) : ViewModel() {
    val vehicleId: Long = checkNotNull(savedState["vehicleId"])
    private val limit = MutableStateFlow(50)
    private val reload = MutableStateFlow(0)
    private val action = MutableStateFlow(VehicleDetailUiState(deleted = savedState["deleted"] ?: false))
    private val records = reload.flatMapLatest {
        combine(repository.observeVehicle(vehicleId), limit.flatMapLatest { count ->
            repository.observeServiceHistoryEntries(vehicleId, count + 1)
        }, repository.observeVehicleDeletionSummary(vehicleId)) { vehicle, history, summary ->
            VehicleDetailUiState(vehicle, history.take(limit.value), history.size > limit.value, loading = false, deletionSummary = summary)
        }
            .catch { emit(VehicleDetailUiState(loading = false, error = it.userMessage("车辆详情读取失败，请重试"))) }
    }
    val state = combine(records, action) { data, operation -> data.copy(busy = operation.busy,
        error = operation.error ?: data.error, deleted = operation.deleted) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleDetailUiState(deleted = action.value.deleted))
    /** 按用户操作增加一批历史摘要。 */
    fun loadMore() { limit.value += 50 }
    /** 写操作结束后才能重新读取，避免重试提前解除提交保护。 */
    fun retry() { if (action.value.busy || action.value.deleted) return; action.value = VehicleDetailUiState(); reload.value++ }
    /** 档案归档和恢复均保留客户、服务及照片元数据。 */
    fun setArchived(archived: Boolean) {
        if (action.value.busy || action.value.deleted) return
        action.value = VehicleDetailUiState(busy = true)
        viewModelScope.launch {
            try { repository.setVehicleArchived(vehicleId, archived); action.value = VehicleDetailUiState() }
            catch (error: Exception) { action.value = VehicleDetailUiState(error = error.userMessage("归档状态保存失败，请重试")) }
        }
    }

    /** 用户明确确认后仍由 Repository 校验当前车牌和归档状态，失败保留详情及确认输入。 */
    fun delete(confirmedPlate: String) {
        if (action.value.busy || action.value.deleted) return
        action.value = VehicleDetailUiState(busy = true)
        viewModelScope.launch {
            try {
                repository.deleteVehicle(vehicleId, confirmedPlate)
                savedState["deleted"] = true
                action.value = VehicleDetailUiState(deleted = true)
            } catch (error: Exception) { action.value = VehicleDetailUiState(error = error.userMessage("删除车辆失败，请重试")) }
        }
    }

    /** 返回完成后清除结果，避免重组再次弹出返回栈。 */
    fun consumeDeleted() { savedState.remove<Boolean>("deleted"); action.value = VehicleDetailUiState() }
}
