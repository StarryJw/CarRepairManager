package com.example.carrepairmanager.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.local.RecentServiceEntry
import com.example.carrepairmanager.ui.userMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 首页最近到店最多显示二十条摘要，详情通过车辆档案进入。 */
data class HomeUiState(val recentServices: List<RecentServiceEntry> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** 观察数据库真实到店记录，避免回到首页后显示陈旧卡片。 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(repository: CarRepairRepository) : ViewModel() {
    private val reload = MutableStateFlow(0)
    val state = reload.flatMapLatest {
        repository.observeRecentServices().map { HomeUiState(it, loading = false) }
            .catch { emit(HomeUiState(loading = false, error = it.userMessage("到店记录读取失败，请重试"))) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
    /** 读取失败后重新订阅数据库。 */
    fun retry() { reload.value++ }
}
