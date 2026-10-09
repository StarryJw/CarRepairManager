package com.example.carrepairmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.carrepairmanager.ui.CarRepairApp
import com.example.carrepairmanager.ui.NavigationViewModel
import com.example.carrepairmanager.ui.theme.CarRepairManagerTheme

/** 单 Activity 承载 Compose 页面，由导航组件管理页面与返回栈。 */
class MainActivity : ComponentActivity() {
    private val navigationViewModel: NavigationViewModel by viewModels()

    /** 页面实例复用应用数据入口，只在创建时读取当前任务的导航恢复状态。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 只读取本次创建时的任务状态；后续切换标签不会再次触发启动恢复。
        val restoredRoute = navigationViewModel.currentRoute.value
        val app = application as CarRepairApplication
        setContent {
            CarRepairManagerTheme {
                CarRepairApp(
                    repository = app.repository,
                    settingsRepository = app.settingsRepository,
                    photoFiles = app.photoFileStore,
                    restoredRoute = restoredRoute,
                    onDestinationChanged = navigationViewModel::onDestinationChanged,
                )
            }
        }
    }
}
