package com.example.carrepairmanager.ui

import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.carrepairmanager.R
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.AppSettingsRepository
import com.example.carrepairmanager.data.PhotoFileStore

/** 顶层入口与产品文档一致，业务详情页通过车辆档案进入。 */
internal enum class AppDestination(
    val route: String,
    @param:StringRes val label: Int,
    val icon: Int,
) {
    HOME("home", R.string.nav_home, R.drawable.ic_home),
    VEHICLES("vehicles", R.string.nav_vehicles, R.drawable.ic_vehicle),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_settings),
}

/** 统一处理系统边距和底部导航，避免切换标签时重复堆叠返回栈。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarRepairApp(
    repository: CarRepairRepository,
    settingsRepository: AppSettingsRepository,
    photoFiles: PhotoFileStore,
    restoredRoute: String = AppDestination.HOME.route,
    onDestinationChanged: (String) -> Unit = {},
) {
    val navController = rememberNavController()
    val reportDestination by rememberUpdatedState(onDestinationChanged)
    LaunchedEffect(navController) {
        // 优先使用 NavController 恢复的导航栈；任务状态只补足默认首页上的恢复。
        if (navController.currentDestination?.route == AppDestination.HOME.route &&
            restoredRoute != AppDestination.HOME.route &&
            AppDestination.entries.any { it.route == restoredRoute }
        ) {
            navController.navigate(restoredRoute) { launchSingleTop = true }
        }
        navController.currentBackStackEntryFlow.collect { entry ->
            entry.destination.route?.let(reportDestination)
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = AppDestination.entries.firstOrNull {
        it.route == backStackEntry?.destination?.route
    } ?: AppDestination.HOME
    val isRoot = AppDestination.entries.any { it.route == backStackEntry?.destination?.route }

    // 沿用已验收的首页返回栈根规则，不用 restoreState 恢复首页关联的其他标签栈。
    val selectTab: (AppDestination) -> Unit = { destination ->
        if (navController.currentDestination?.route != destination.route) {
            if (destination == AppDestination.HOME) {
                navController.popBackStack(AppDestination.HOME.route, inclusive = false, saveState = true)
            } else {
                navController.navigate(destination.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (isRoot) TopAppBar(title = {
                Text(stringResource(
                    if (currentDestination == AppDestination.HOME) R.string.app_name
                    else currentDestination.label
                ))
            })
        },
        bottomBar = {
            // 底栏默认渐变旧选中底纹；即时换页时同步更新，避免上一标签的高亮残留。
            if (isRoot) key(currentDestination) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    AppDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination == destination,
                            onClick = { selectTab(destination) },
                            icon = {
                                Icon(painterResource(destination.icon), contentDescription = null)
                            },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
        contentWindowInsets = if (isRoot) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
            // 禁用淡化；退场页面的实际隐藏由 instantPage 处理，避免切换首帧文字叠加。
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            recordPages(navController, repository, settingsRepository, photoFiles) { selectTab(AppDestination.VEHICLES) }
        }
    }
}
