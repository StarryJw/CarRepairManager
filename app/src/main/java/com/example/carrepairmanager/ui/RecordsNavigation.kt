package com.example.carrepairmanager.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.carrepairmanager.CarRepairApplication
import androidx.compose.ui.layout.layout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.example.carrepairmanager.data.AppSettingsRepository
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.PhotoFileStore
import com.example.carrepairmanager.ui.photo.PhotoViewerViewModel
import com.example.carrepairmanager.ui.photo.PhotoViewerScreen
import com.example.carrepairmanager.ui.home.HomeScreen
import com.example.carrepairmanager.ui.home.HomeViewModel
import com.example.carrepairmanager.ui.service.ServiceDetailScreen
import com.example.carrepairmanager.ui.service.ServiceDetailViewModel
import com.example.carrepairmanager.ui.service.ServiceEditScreen
import com.example.carrepairmanager.ui.service.ServiceEditViewModel
import com.example.carrepairmanager.ui.settings.SettingsScreen
import com.example.carrepairmanager.ui.settings.SettingsViewModel
import com.example.carrepairmanager.ui.settings.BackupViewModel
import com.example.carrepairmanager.ui.vehicle.VehicleDetailScreen
import com.example.carrepairmanager.ui.vehicle.VehicleDetailViewModel
import com.example.carrepairmanager.ui.vehicle.VehicleEditScreen
import com.example.carrepairmanager.ui.vehicle.VehicleEditViewModel
import com.example.carrepairmanager.ui.vehicle.VehicleListScreen
import com.example.carrepairmanager.ui.vehicle.VehicleListViewModel
import com.example.carrepairmanager.ui.ocr.PlateOcrViewModel
import com.example.carrepairmanager.ui.ocr.PlateOcrScreen

private const val OCR_PLATE_RESULT = "ocr_plate_result"

/** OCR 回填原页面，车辆删除完成后退出失效详情；保存与查询仍沿用各页面流程。 */
internal fun NavGraphBuilder.recordPages(nav: NavHostController, repository: CarRepairRepository,
    settings: AppSettingsRepository, photoFiles: PhotoFileStore, onShowVehicles: () -> Unit) {
    instantPage(nav, AppDestination.HOME.route) { entry ->
        val home = recordViewModel { HomeViewModel(repository) }
        val search = recordViewModel { VehicleListViewModel(repository, settings, it) }
        ConsumePlateResult(entry, search::setQuery)
        val state by home.state.collectAsStateWithLifecycle()
        val searchState by search.state.collectAsStateWithLifecycle()
        HomeScreen(state, searchState, search::setQuery, { nav.openRecord("vehicle/edit") },
            { nav.openRecord("vehicle/$it") }, onShowVehicles, home::retry, search::retry, search::loadMore,
            { nav.openRecord("plate/ocr") })
    }
    instantPage(nav, AppDestination.VEHICLES.route) { entry ->
        val model = recordViewModel { VehicleListViewModel(repository, settings, it) }
        ConsumePlateResult(entry, model::setQuery)
        val state by model.state.collectAsStateWithLifecycle()
        VehicleListScreen(state, model::setQuery, model::setBrand, model::showArchived, { nav.openRecord("vehicle/edit") },
            { nav.openRecord("vehicle/$it") }, model::loadMore, model::retry, { nav.openRecord("plate/ocr") })
    }
    instantPage(nav, AppDestination.SETTINGS.route) {
        val model = recordViewModel { SettingsViewModel(settings) }
        val app = LocalContext.current.applicationContext as CarRepairApplication
        val backup = recordViewModel { BackupViewModel(app, it) }
        val backupState by backup.state.collectAsStateWithLifecycle()
        LaunchedEffect(backupState.restored) {
            if (backupState.restored) {
                backup.consumeRestored()
                // 恢复后旧 ID 可能属于不同档案，清理缓存的详情与表单避免误编辑。
                AppDestination.entries.forEach { nav.clearBackStack(it.route) }
                nav.navigate(AppDestination.HOME.route) { popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true }
            }
        }
        val state by model.state.collectAsStateWithLifecycle()
        SettingsScreen(state, model::setSort, model::setQuality, model::retry, backup)
    }
    instantPage(nav, "plate/ocr") {
        val app = LocalContext.current.applicationContext as CarRepairApplication
        val model = recordViewModel {
            PlateOcrViewModel(it, null, loadOcr = { app.ocrRepository })
        }
        val state by model.state.collectAsStateWithLifecycle()
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), model::cameraResult)
        LaunchedEffect(state.cameraRequest) {
            state.cameraRequest?.let { request ->
                model.consumeCameraRequest()
                try { camera.launch(request.uri) }
                catch (_: ActivityNotFoundException) { model.cameraLaunchFailed() }
                catch (_: SecurityException) { model.cameraLaunchFailed() }
            }
        }
        LaunchedEffect(state.confirmedPlate) {
            state.confirmedPlate?.let { plate ->
                nav.previousBackStackEntry?.savedStateHandle?.set(OCR_PLATE_RESULT, plate)
                model.consumeConfirmation()
                nav.popBackStack()
            }
        }
        PlateOcrScreen(state, { model.leave { nav.popBackStack() } }, model::takePhoto, model::recognize, model::cancelCapture,
            model::changePlate, model::selectCandidate, model::confirmPlate,
            fillingVehicle = nav.previousBackStackEntry?.destination?.route?.startsWith("vehicle/edit") == true)
    }
    instantPage(nav, "vehicle/edit?vehicleId={vehicleId}&plateNumber={plateNumber}", arguments = listOf(
        navArgument("vehicleId") { type = NavType.LongType; defaultValue = 0L },
        navArgument("plateNumber") { type = NavType.StringType; defaultValue = "" },
    )) { entry ->
        val model = recordViewModel { VehicleEditViewModel(repository, it) }
        ConsumePlateResult(entry, model::applyRecognizedPlate)
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.savedId) {
            state.savedId?.let { id ->
                model.consumeSaved()
                if (model.vehicleId != 0L) nav.popBackStack() else nav.navigate("vehicle/$id") {
                    popUpTo(entry.destination.id) { inclusive = true }; launchSingleTop = true
                }
            }
        }
        VehicleEditScreen(state, model.vehicleId != 0L, model::change, model::save, { nav.popBackStack() }, model::load,
            { nav.openRecord("plate/ocr") })
    }
    instantPage(nav, "vehicle/{vehicleId}", arguments = listOf(navArgument("vehicleId") { type = NavType.LongType })) {
        val model = recordViewModel { VehicleDetailViewModel(repository, it) }
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.deleted) {
            if (state.deleted) { model.consumeDeleted(); nav.popBackStack() }
        }
        VehicleDetailScreen(state, { nav.popBackStack() }, { nav.openRecord("vehicle/edit?vehicleId=${model.vehicleId}") },
            { nav.openRecord("service/edit?vehicleId=${model.vehicleId}") }, { nav.openRecord("service/$it") },
            model::setArchived, model::loadMore, model::retry, model::delete)
    }
    instantPage(nav, "service/edit?vehicleId={vehicleId}&orderId={orderId}", arguments = listOf(
        navArgument("vehicleId") { type = NavType.LongType; defaultValue = 0L },
        navArgument("orderId") { type = NavType.LongType; defaultValue = 0L },
    )) { entry ->
        val model = recordViewModel { ServiceEditViewModel(repository, it, photoFiles, settings) }
        val state by model.state.collectAsStateWithLifecycle()
        // 注册位置不依赖加载状态，系统重建后仍可把外部相机结果送回原表单。
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), model::cameraResult)
        LaunchedEffect(state.cameraRequest) {
            state.cameraRequest?.let { request ->
                model.consumeCameraRequest()
                try { camera.launch(request.uri) }
                catch (_: ActivityNotFoundException) { model.cameraLaunchFailed() }
                catch (_: SecurityException) { model.cameraLaunchFailed() }
            }
        }
        LaunchedEffect(state.savedId) {
            state.savedId?.let { id ->
                model.consumeSaved()
                if (model.orderId != 0L) nav.popBackStack() else nav.navigate("service/$id") {
                    popUpTo(entry.destination.id) { inclusive = true }; launchSingleTop = true
                }
            }
        }
        ServiceEditScreen(state, model.orderId != 0L, model::change, model::addItem, model::save,
            { model.discard { nav.popBackStack() } }, model::load, model::takePhoto, model::removePhoto,
            model::processCapture, model::cancelCapture)
    }
    instantPage(nav, "service/{orderId}", arguments = listOf(navArgument("orderId") { type = NavType.LongType })) {
        val model = recordViewModel { ServiceDetailViewModel(repository, it) }
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.deleted) {
            if (state.deleted) { model.consumeDeleted(); nav.popBackStack() }
        }
        ServiceDetailScreen(state, { nav.popBackStack() }, { nav.openRecord("service/edit?orderId=${model.orderId}") }, model::delete, model::retry,
            { nav.openRecord("photo/${model.orderId}/$it") })
    }
    instantPage(nav, "photo/{orderId}/{photoId}", arguments = listOf(
        navArgument("orderId") { type = NavType.LongType }, navArgument("photoId") { type = NavType.LongType },
    )) {
        val model = recordViewModel { PhotoViewerViewModel(repository, it) }
        val state by model.state.collectAsStateWithLifecycle()
        PhotoViewerScreen(state, { nav.popBackStack() }, model::delete, model::retry)
    }
}

/** 结果只消费一次且保留同一 StateFlow，连续拍照回填和系统重建都不会覆盖后续手动输入。 */
@Composable
private fun ConsumePlateResult(entry: NavBackStackEntry, onPlate: (String) -> Unit) {
    val plate by entry.savedStateHandle.getStateFlow<String?>(OCR_PLATE_RESULT, null).collectAsStateWithLifecycle()
    LaunchedEffect(plate) {
        plate?.let {
            onPlate(it)
            entry.savedStateHandle[OCR_PLATE_RESULT] = null
        }
    }
}

/** NavHost 即使禁用淡化也会短暂保留退场页；只摆放当前页，同时保留旧页的可恢复状态。 */
private fun NavGraphBuilder.instantPage(nav: NavHostController, route: String,
    arguments: List<NamedNavArgument> = emptyList(), content: @Composable (NavBackStackEntry) -> Unit) {
    composable(route, arguments = arguments) { entry ->
        val currentEntry by nav.currentBackStackEntryAsState()
        Box(Modifier.fillMaxSize().layout { measurable, constraints ->
            val page = measurable.measure(constraints)
            layout(page.width, page.height) {
                // 不销毁退场页的 Composition，以免提前丢失滚动位置和 rememberSaveable 草稿。
                if (currentEntry?.id == entry.id) page.placeRelative(0, 0)
            }
        }.background(MaterialTheme.colorScheme.background)) {
            content(entry)
        }
    }
}

/** 快速重复点击同一入口不应叠加相同详情或表单。 */
private fun NavHostController.openRecord(route: String) { navigate(route) { launchSingleTop = true } }
