package com.example.carrepairmanager

import androidx.lifecycle.SavedStateHandle
import com.example.carrepairmanager.ui.NavigationViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** 区分全新启动与已有任务恢复，避免持久化的旧标签影响默认首页。 */
class NavigationViewModelTest {
    /** 全新任务没有系统恢复状态，应从首页开始。 */
    @Test
    fun newTaskStartsAtHome() {
        val viewModel = NavigationViewModel(SavedStateHandle())
        assertEquals("home", viewModel.currentRoute.value)
    }

    /** 上一个任务停在设置也不能影响完全退出后的新任务。 */
    @Test
    fun newTaskStartsAtHomeAfterPreviousTaskVisitedSettings() {
        val previousTask = NavigationViewModel(SavedStateHandle())
        previousTask.onDestinationChanged("settings")
        assertEquals("settings", previousTask.currentRoute.value)

        val newTask = NavigationViewModel(SavedStateHandle())
        assertEquals("home", newTask.currentRoute.value)
    }

    /** 系统传回任务状态时继续保留当前标签，覆盖车辆与设置两个入口。 */
    @Test
    fun existingTaskRestoresCurrentTab() {
        listOf("vehicles", "settings").forEach { route ->
            val handle = SavedStateHandle()
            val previousInstance = NavigationViewModel(handle)
            previousInstance.onDestinationChanged(route)
            val restoredHandle = SavedStateHandle(
                handle.keys().associateWith { key -> handle.get<String>(key) },
            )
            assertEquals(route, NavigationViewModel(restoredHandle).currentRoute.value)
        }
    }

    /** 旧版本或无效状态中的未知路由应回首页，避免恢复不存在的页面。 */
    @Test
    fun invalidRestoredRouteFallsBackToHome() {
        val viewModel = NavigationViewModel(
            SavedStateHandle(mapOf("current_route" to "unknown_route")),
        )
        assertEquals("home", viewModel.currentRoute.value)
    }
}
