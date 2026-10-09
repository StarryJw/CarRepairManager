package com.example.carrepairmanager.ui.vehicle

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.data.AppSettingsRepository
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.VehicleSort
import com.example.carrepairmanager.data.local.VehicleListEntry
import com.example.carrepairmanager.ui.userMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 筛选属于任务内状态，不持久化为下次启动的导航偏好。 */
data class VehicleFilters(val query: String = "", val brand: String = "", val includeArchived: Boolean = false, val limit: Int = 50)

/** 只持有可见一批车辆与真实品牌，列表异常可重试。 */
data class VehicleListUiState(val filters: VehicleFilters = VehicleFilters(), val entries: List<VehicleListEntry> = emptyList(),
    val brands: List<String> = emptyList(), val hasMore: Boolean = false, val loading: Boolean = true, val error: String? = null)

/** 首页与车辆列表共用搜索；新条件立即隐藏旧结果，非空输入稳定后再查询。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleListViewModel(private val repository: CarRepairRepository, settings: AppSettingsRepository,
    private val savedState: SavedStateHandle) : ViewModel() {
    private val filters = MutableStateFlow(VehicleFilters(savedState["query"] ?: "", savedState["brand"] ?: "",
        savedState["includeArchived"] ?: false, savedState["limit"] ?: 50))
    private val reload = MutableStateFlow(0)
    private val sort = settings.settings.map { it.vehicleSort }.distinctUntilChanged()
    private val records = reload.flatMapLatest {
        combine(filters, sort, repository.observeBrands()) { filter, sort, brands -> Triple(filter, sort, brands) }
            .flatMapLatest { (filter, sort, brands) ->
                flow {
                    // 在 latest 内等待可立即取消旧读取；清空搜索不等待，照片质量变化也不重查车辆。
                    if (filter.query.isNotBlank()) delay(250)
                    emitAll(repository.observeVehicleList(filter.query, filter.brand, filter.includeArchived,
                        sort == VehicleSort.RECENT_SERVICE, filter.limit + 1).map {
                        VehicleListUiState(filter, it.take(filter.limit), brands, it.size > filter.limit, loading = false)
                    })
                }
            }.catch { emit(VehicleListUiState(filters = filters.value, loading = false, error = it.userMessage("车辆读取失败，请重试"))) }
    }
    // 输入立即更新，但只有条件一致的查询结果才可点击；加载下一批时保留同条件的旧卡片。
    val state = combine(records, filters) { data, filter ->
        if (data.filters.copy(limit = filter.limit) == filter) data.copy(filters = filter,
            loading = data.loading || data.filters.limit != filter.limit)
        else VehicleListUiState(filters = filter, brands = data.brands)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleListUiState(filters = filters.value))

    /** 搜索与筛选变化时回到首批，避免查询结果不必要地扩大。 */
    fun setQuery(query: String) = update(filters.value.copy(query = query, limit = 50))
    /** 真实品牌精确筛选，空字符串表示全部。 */
    fun setBrand(brand: String) = update(filters.value.copy(brand = brand, limit = 50))
    /** 归档车辆可显式查看和恢复，默认不混入工作列表。 */
    fun showArchived(show: Boolean) = update(filters.value.copy(includeArchived = show, limit = 50))
    /** 加载期间拒绝重复扩批，保留同一筛选下已显示的车辆和滚动位置。 */
    fun loadMore() {
        val current = state.value
        if (current.loading || !current.hasMore || current.filters != filters.value) return
        update(filters.value.copy(limit = filters.value.limit + 50))
    }
    /** 重启失败的观察流，而不是只清除错误文字。 */
    fun retry() { reload.value++ }
    /** 简单值写入 SavedStateHandle，进程重建后恢复当前筛选。 */
    private fun update(value: VehicleFilters) {
        savedState["query"] = value.query; savedState["brand"] = value.brand
        savedState["includeArchived"] = value.includeArchived; savedState["limit"] = value.limit
        filters.value = value
    }
}
