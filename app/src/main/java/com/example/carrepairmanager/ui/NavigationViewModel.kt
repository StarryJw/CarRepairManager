package com.example.carrepairmanager.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

private const val CURRENT_ROUTE_KEY = "current_route"

/** 标签属于当前任务的临时状态，全新任务不应读取上次启动的页面位置。 */
class NavigationViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    init {
        val restoredRoute = savedStateHandle.get<String>(CURRENT_ROUTE_KEY)
        if (AppDestination.entries.none { it.route == restoredRoute }) {
            savedStateHandle[CURRENT_ROUTE_KEY] = AppDestination.HOME.route
        }
    }

    val currentRoute: StateFlow<String> = savedStateHandle.getStateFlow(
        key = CURRENT_ROUTE_KEY,
        initialValue = AppDestination.HOME.route,
    )

    /** 同步保存实际目的地，系统重建已有任务时恢复，完全退出后的新任务仍默认首页。 */
    fun onDestinationChanged(route: String) {
        if (AppDestination.entries.any { it.route == route }) {
            savedStateHandle[CURRENT_ROUTE_KEY] = route
        }
    }
}
