package com.example.carrepairmanager.ui.service

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.local.ServiceOrderDetails
import com.example.carrepairmanager.data.local.VehicleWithCustomer
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

/** 删除结果是可消费状态，避免数据库变为空时先显示错误页面。 */
data class ServiceDetailUiState(val detail: ServiceOrderDetails? = null, val vehicle: VehicleWithCustomer? = null,
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null, val deleted: Boolean = false)

/** 观察服务和所属车辆，编辑项目或车辆后均自动刷新。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceDetailViewModel(private val repository: CarRepairRepository, private val savedState: SavedStateHandle) : ViewModel() {
    val orderId: Long = checkNotNull(savedState["orderId"])
    private val reload = MutableStateFlow(0)
    private val action = MutableStateFlow(ServiceDetailUiState(deleted = savedState["deleted"] ?: false))
    private val records = reload.flatMapLatest {
        repository.observeServiceOrder(orderId).flatMapLatest { detail ->
            if (detail == null) kotlinx.coroutines.flow.flowOf(ServiceDetailUiState(loading = false)) else
                repository.observeVehicle(detail.order.vehicleId).map { ServiceDetailUiState(detail, it, loading = false) }
        }.catch { emit(ServiceDetailUiState(loading = false, error = it.userMessage("服务详情读取失败，请重试"))) }
    }
    val state = combine(records, action) { data, operation -> data.copy(busy = operation.busy,
        error = operation.error ?: data.error, deleted = operation.deleted) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServiceDetailUiState())
    /** 读写失败后重新订阅，保留当前详情 ID。 */
    fun retry() { action.value = ServiceDetailUiState(); reload.value++ }
    /** 界面二次确认后由 Repository 同步清理照片文件和关联数据。 */
    fun delete() {
        if (action.value.busy) return
        action.value = ServiceDetailUiState(busy = true)
        viewModelScope.launch {
            try {
                repository.deleteServiceOrder(orderId); savedState["deleted"] = true
                action.value = ServiceDetailUiState(deleted = true)
            } catch (error: Exception) { action.value = ServiceDetailUiState(error = error.userMessage("删除服务失败，请重试")) }
        }
    }
    /** 导航完成后清除结果，重组不再重复返回。 */
    fun consumeDeleted() { savedState.remove<Boolean>("deleted"); action.value = ServiceDetailUiState() }
}
