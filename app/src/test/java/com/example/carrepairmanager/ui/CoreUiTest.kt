package com.example.carrepairmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.os.Bundle
import android.os.Parcel
import android.view.inspector.WindowInspector
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipeLeft
import com.example.carrepairmanager.CarRepairApplication
import com.example.carrepairmanager.MainActivity
import com.example.carrepairmanager.WindowsFileProviderShadow
import com.example.carrepairmanager.data.VehicleSort
import com.example.carrepairmanager.data.PhotoDraft
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.data.local.entity.Vehicle
import com.example.carrepairmanager.data.local.entity.Customer
import com.example.carrepairmanager.ui.vehicle.VehicleDraft
import com.example.carrepairmanager.ui.vehicle.VehicleEditViewModel
import com.example.carrepairmanager.ui.service.ServiceDraft
import com.example.carrepairmanager.ui.service.ServiceItemDraft
import com.example.carrepairmanager.ui.service.ServiceEditViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 在本机启动真实 Activity 和 Room，检验完整 UI 数据流并输出布局截图，不操作手机。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CarRepairApplication::class, qualifiers = "zh-rCN-w411dp-h891dp-mdpi", shadows = [WindowsFileProviderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as CarRepairApplication

    /** 仅清理 Robolectric 沙箱内的测试库，配置恢复默认值确保用例独立。 */
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun resetFixture() = runBlocking {
        val application = app
        // Windows 的 Native ImageDecoder 文件接口不可用；仅测试改用 Coil 的真实 BitmapFactory 解码器。
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(application).components { add(BitmapFactoryDecoder.Factory()) }.build())
        withContext(Dispatchers.IO) { application.database.clearAllTables() }
        application.settingsRepository.setVehicleSort(VehicleSort.RECENT_SERVICE)
        application.settingsRepository.setImageQuality(70)
        waitForText("暂无到店记录")
    }

    /** 先结束 Activity 取消观察协程，再关闭测试数据库连接。 */
    @After
    fun closeFixture() {
        val database = app.database
        compose.activityRule.scenario.close()
        database.close()
    }

    /** 从首页查车、清空回最近到店，再组合品牌筛选并恢复任务内搜索状态。 */
    @Test
    fun searchJourneySupportsAllFieldsAndBrandRecovery() {
        val second = runBlocking {
            val first = app.repository.createVehicle(Vehicle(plateNumber = "冀A12345", brand = "宝马"),
                Customer(name = "张师傅", phone = "13800000000"))
            val second = app.repository.createVehicle(Vehicle(plateNumber = "京B87654", brand = "丰田"),
                Customer(name = "A123车主", phone = "13900000000"))
            app.repository.createVehicle(Vehicle(plateNumber = "津C88888", brand = "五菱"),
                Customer(name = "李师傅", phone = "18800001230"))
            app.repository.createServiceOrder(first, "2026-10-01", emptyList())
            app.repository.createServiceOrder(second, "2026-10-03", emptyList())
            second
        }
        waitForText("京B87654")
        val search = compose.onNodeWithText("搜索车牌 / 车主 / 手机号")
        search.performClick().performTextInput("冀 a 12 345")
        waitForText("手机号：13800000000")
        compose.onNodeWithText("京B87654").assertDoesNotExist()
        search.performImeAction()
        search.assertIsNotFocused()
        compose.onNodeWithContentDescription("清空搜索").assertIsDisplayed()
        search.performTextReplacement("李师傅")
        waitForText("手机号：18800001230")
        compose.onNodeWithText("津C88888").assertIsDisplayed()
        search.performTextReplacement("188000")
        waitForText("手机号：18800001230")
        screenshot("19-home-phone-search")
        search.performTextReplacement("没有此档案")
        waitForText("未找到车辆")
        compose.onNodeWithText("津C88888").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空搜索").performClick()
        waitForText("最近到店")
        waitForText("京B87654")

        tab("车辆")
        waitForText("显示已归档车辆")
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").performTextInput("A123")
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").performImeAction()
        brandChip("丰田").performClick()
        waitForText("手机号：13900000000")
        compose.onNodeWithText("冀A12345").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        waitForText("手机号：13900000000")
        brandChip("丰田").assertIsSelected()
        compose.onNodeWithText("A123").assertIsDisplayed()
        compose.onNodeWithContentDescription("清空搜索").assertIsDisplayed()

        runBlocking {
            val record = requireNotNull(app.repository.getVehicle(second))
            app.repository.updateVehicle(record.vehicle.copy(brand = "本田"))
        }
        waitForText("未找到车辆")
        brandChip("丰田").assertIsSelected()
        screenshot("20-search-brand-empty")
        brandChip("全部").performClick()
        waitForText("手机号：13800000000")
        compose.onNodeWithTag("vehicle_list").performScrollToNode(hasText("京B87654"))
        compose.onNodeWithText("京B87654").performClick()
        waitForText("车辆详情")
        waitForText("＋ 新增服务")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("搜索车牌 / 车主 / 手机号")
        compose.onNodeWithContentDescription("清空搜索").performClick()
        waitForText("京B87654")
    }

    /** 用户主动加载下一批，最早档案仍可通过全库搜索找到，不受当前可见批次限制。 */
    @Test
    fun vehicleListLoadsMoreAndSearchesOutsideTheFirstBatch() {
        runBlocking {
            repeat(55) { index ->
                app.repository.createVehicle(Vehicle(plateNumber = "冀A" + index.toString().padStart(5, '0'), brand = "宝马"))
            }
        }
        tab("车辆")
        waitForText("冀A00054")
        compose.onNodeWithTag("vehicle_list").performScrollToNode(hasText("加载更多车辆"))
        compose.onNodeWithText("加载更多车辆").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("加载更多车辆")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("vehicle_list").performScrollToNode(hasText("冀A00000"))
        compose.onNodeWithText("冀A00000").assertIsDisplayed()
        compose.onNodeWithTag("vehicle_list").performScrollToIndex(0)
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").performTextInput("00000")
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").performImeAction()
        waitForText("冀A00000")
        compose.onNodeWithText("加载更多车辆").assertDoesNotExist()
        compose.onNodeWithText("冀A00054").assertDoesNotExist()
        // 输入框退出焦点的装饰有过渡动画，截图前推进帧时钟并检查清除入口仍可见。
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("清空搜索").assertIsDisplayed()
        screenshot("21-vehicle-plate-search")
        compose.onNodeWithContentDescription("清空搜索").performClick()
        waitForText("冀A00054")
    }

    /** 从首页建立车辆、创建两项服务并编辑金额，真实数据库与首页同步。 */
    @Test
    fun vehicleAndServiceJourneySavesAndEditsRecords() {
        screenshot("01-home-empty")
        createVehicle("冀A88888")
        screenshot("03-vehicle-detail")
        compose.onNodeWithText("＋ 新增服务").performClick()
        waitForText("服务日期")
        compose.onNodeWithText("服务日期").performTextReplacement("2026-10-02")
        addServiceItem(0, "更换机油", "300.10")
        addServiceItem(1, "普通洗车", "29.90")
        scroll("service_form", "¥330.00")
        compose.onNodeWithText("¥330.00").assertIsDisplayed()
        scroll("service_form", "保存服务记录")
        compose.onNodeWithText("保存服务记录").performClick()
        waitForServiceDetail()
        screenshot("05-service-detail")
        scroll("service_detail", "编辑服务")
        compose.onNodeWithText("编辑服务").performClick()
        waitForText("服务日期")
        compose.onNodeWithTag("service_form").performScrollToNode(hasTestTag("item_amount_0"))
        compose.onNodeWithTag("item_amount_0").performTextReplacement("100.10")
        scroll("service_form", "¥130.00")
        compose.onNodeWithText("¥130.00").assertIsDisplayed()
        scroll("service_form", "保存服务记录")
        compose.onNodeWithText("保存服务记录").performClick()
        waitForText("合计  ¥130.00")
        runBlocking {
            val vehicle = app.repository.findVehicleByPlate("冀a88888")
            val history = app.repository.observeServiceHistory(requireNotNull(vehicle).vehicle.id).first()
            assertEquals(1, history.size)
            assertEquals(13_000L, history.single().totalAmountCent)
        }
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("历史服务")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("最近到店")
        compose.onNodeWithText("¥130.00").assertIsDisplayed()
        screenshot("06-home-with-service")
        tab("车辆")
        waitForText("最近到店：2026-10-02")
        screenshot("07-vehicle-list")
    }

    /** 真实详情验证归档门槛、跨分页计数、取消、输入恢复、失败重试和成功返回。 */
    @Test
    fun permanentVehicleDeleteJourneyProtectsSharedCustomerAndRecoversFromFailure() {
        createVehicle("冀A12345")
        compose.onNodeWithText("更多操作").assertDoesNotExist()
        val vehicle = runBlocking { requireNotNull(app.repository.findVehicleByPlate("冀A12345")) }
        val otherOrder = runBlocking {
            val other = app.repository.createVehicle(Vehicle(plateNumber = "京B54321", customerId = vehicle.vehicle.customerId))
            val otherOrder = app.repository.createServiceOrder(other, "2026-10-06", emptyList(), photos = listOf(fixturePhoto(PhotoType.OTHER)))
            app.repository.createServiceOrder(vehicle.vehicle.id, "2026-10-05", emptyList(),
                photos = listOf(fixturePhoto(PhotoType.BEFORE), fixturePhoto(PhotoType.AFTER)))
            repeat(50) { app.repository.createServiceOrder(vehicle.vehicle.id, "2026-10-06", emptyList()) }
            requireNotNull(app.repository.getServiceOrder(otherOrder))
        }
        scroll("vehicle_detail", "归档车辆")
        compose.onNodeWithText("归档车辆").performClick()
        compose.onNodeWithText("确认").performClick()
        waitForText("更多操作")
        val before = runBlocking { app.repository.withBackupSnapshot { it } }
        compose.onNodeWithText("更多操作").performClick()
        compose.onNodeWithText("永久删除车辆").performClick()
        waitForText("输入车牌确认")
        compose.onNodeWithText("51 次服务记录和 2 张照片", substring = true).assertIsDisplayed()
        compose.onNodeWithText("确认永久删除").assertIsNotEnabled()
        compose.onNodeWithTag("vehicle_delete_plate").performTextInput("京B54321")
        compose.onNodeWithText("车牌不匹配").assertIsDisplayed()
        compose.onNodeWithText("确认永久删除").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        runBlocking { assertEquals(before, app.repository.withBackupSnapshot { it }) }
        compose.onNodeWithText("更多操作").performClick()
        compose.onNodeWithText("永久删除车辆").performClick()
        compose.onNodeWithTag("vehicle_delete_plate").performTextInput(" 冀 a12 345 ")
        compose.onNodeWithText("确认永久删除").assertIsEnabled()
        compose.activityRule.scenario.recreate()
        waitForText("输入车牌确认")
        compose.onNodeWithTag("vehicle_delete_plate").assert(hasText(" 冀 a12 345 "))
        compose.onNodeWithText("确认永久删除").assertIsEnabled()
        screenshot("32-vehicle-delete-confirm")
        val fixtureDatabase = app.database
        runBlocking { withContext(Dispatchers.IO) {
            fixtureDatabase.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_vehicle_delete BEFORE DELETE ON vehicles BEGIN SELECT RAISE(ABORT, 'fixture_ui_delete_failure'); END")
        } }
        compose.onNodeWithText("确认永久删除").performClick()
        waitForText("删除车辆失败，请重试")
        compose.onNodeWithText("确认永久删除").assertIsEnabled()
        runBlocking { assertEquals(before, app.repository.withBackupSnapshot { it }) }
        assertTrue(before.photos.all { File(app.filesDir, it.relativePath).isFile })
        runBlocking { withContext(Dispatchers.IO) { fixtureDatabase.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_vehicle_delete") } }
        compose.onNodeWithText("确认永久删除").performClick()
        waitForText("最近到店")
        compose.onNodeWithText("车辆详情").assertDoesNotExist()
        val after = runBlocking { app.repository.withBackupSnapshot { it } }
        assertEquals(before.customers, after.customers)
        assertEquals(listOf(otherOrder.order), after.orders)
        assertEquals(otherOrder.photos, after.photos)
        assertTrue(before.photos.filter { it.orderId != otherOrder.order.id }.none { File(app.filesDir, it.relativePath).exists() })
        assertTrue(File(app.filesDir, otherOrder.photos.single().relativePath).isFile)
        assertEquals(null, runBlocking { app.repository.getVehicle(vehicle.vehicle.id) })
        tab("车辆")
        waitForText("京B54321")
        compose.onNodeWithText("显示已归档车辆").performClick()
        waitForText("京B54321")
        compose.onNodeWithText("冀A12345").assertDoesNotExist()
    }

    /** 窄屏大字体下输入和操作固定可见，完整删除说明仍可独立滚动。 */
    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun permanentVehicleDeleteConfirmationSupportsNarrowLargeFont() {
        RuntimeEnvironment.setFontScale(1.8f)
        try { checkVehicleDeleteConfirmationLayout("33-vehicle-delete-large-font") }
        finally { RuntimeEnvironment.setFontScale(1f) }
    }

    /** 横屏低高度确认页面中的说明和车牌输入都能到达，取消不删除档案。 */
    @Test
    @Config(qualifiers = "zh-rCN-w891dp-h411dp-land-mdpi")
    fun permanentVehicleDeleteConfirmationSupportsLandscape() {
        checkVehicleDeleteConfirmationLayout("34-vehicle-delete-landscape")
    }

    /** 布局检查仍通过真实车辆详情打开菜单，并实际输入标准化车牌及取消确认。 */
    private fun checkVehicleDeleteConfirmationLayout(screenshotName: String) {
        createVehicle("冀A12345")
        val id = runBlocking { requireNotNull(app.repository.findVehicleByPlate("冀A12345")).vehicle.id }
        runBlocking { app.repository.setVehicleArchived(id, true) }
        waitForText("更多操作")
        compose.onNodeWithText("更多操作").performClick()
        compose.onNodeWithText("永久删除车辆").performClick()
        waitForText("输入车牌确认")
        compose.onNodeWithTag("vehicle_delete_plate").assertIsDisplayed().performTextInput("冀 a12 345")
        compose.onNodeWithText("确认永久删除").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        screenshot(screenshotName)
        compose.onNodeWithText("取消").performClick()
        assertTrue(runBlocking { requireNotNull(app.repository.getVehicle(id)).vehicle.isArchived })
    }

    /** 完成拍照、保存和删除闭环；等待文件清理完成且返回可用后再离开图库。 */
    @Test
    fun cameraPhotoJourneySavesViewsAndDeletesFiles() {
        createVehicle("冀A88888")
        compose.onNodeWithText("＋ 新增服务").performClick()
        waitForText("服务日期")
        takeFixturePhoto(PhotoType.BEFORE)
        takeFixturePhoto(PhotoType.AFTER)
        compose.onNodeWithTag("service_form").performScrollToNode(hasContentDescription("查看维修前照片1"))
        waitForLoadedPhoto("查看维修前照片1")
        screenshot("16-photo-service-form")
        scroll("service_form", "保存服务记录")
        compose.onNodeWithText("保存服务记录").performClick()
        waitForServiceDetail()
        val original = runBlocking {
            val vehicleId = requireNotNull(app.repository.findVehicleByPlate("冀A88888")).vehicle.id
            val orderId = app.repository.observeServiceHistory(vehicleId).first().single().id
            requireNotNull(app.repository.getServiceOrder(orderId))
        }
        assertEquals(2, original.photos.size)
        assertTrue(original.photos.all { File(app.filesDir, it.relativePath).isFile })
        compose.onNodeWithTag("service_detail").performScrollToNode(hasContentDescription("查看维修后照片1"))
        waitForLoadedPhoto("查看维修后照片1")
        screenshot("17-photo-service-detail")
        compose.onNodeWithContentDescription("查看维修后照片1").performClick()
        waitForText("维修后  ·  2 / 2")
        waitForLoadedPhoto("维修后照片2")
        screenshot("18-photo-viewer")
        compose.onNodeWithText("上一张").performClick()
        waitForText("维修前  ·  1 / 2")
        compose.onNodeWithTag("photo_pager").performTouchInput { doubleClick(center) }
        compose.onNodeWithText("重置缩放").performClick()
        compose.onNodeWithTag("photo_pager").performTouchInput { swipeLeft() }
        waitForText("维修后  ·  2 / 2")
        compose.activityRule.scenario.recreate()
        waitForText("维修后  ·  2 / 2")
        compose.onNodeWithText("删除此照片").performClick()
        compose.onNodeWithText("取消").performClick()
        assertTrue(original.photos.all { File(app.filesDir, it.relativePath).exists() })
        compose.onNodeWithText("删除此照片").performClick()
        compose.onNodeWithText("确认删除").performClick()
        waitForText("维修前  ·  1 / 1")
        assertFalse(File(app.filesDir, original.photos[1].relativePath).exists())
        // Room 页数先更新，文件清理结束后才解除 busy；不能把页数变化当作返回已可用。
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription("返回") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("返回").performClick()
        waitForServiceDetail()
        scroll("service_detail", "删除本次服务")
        compose.onNodeWithText("删除本次服务").performClick()
        compose.onNodeWithText("确认删除").performClick()
        waitForText("暂无服务记录")
        assertTrue(original.photos.none { File(app.filesDir, it.relativePath).exists() })
    }

    /** 相机运行时 Activity 重建不重复发起拍照，照片草稿恢复及放弃修改不会改变原图库。 */
    @Test
    fun cameraAndPhotoDraftsSurviveRecreationAndDiscard() {
        createVehicle("京B54321")
        compose.onNodeWithText("＋ 新增服务").performClick()
        waitForText("服务日期")
        takeFixturePhoto(PhotoType.BEFORE, recreateDuringCamera = true)
        compose.activityRule.scenario.recreate()
        waitForText("新增服务")
        compose.onNodeWithTag("service_form").performScrollToNode(hasContentDescription("查看维修前照片1"))
        compose.onNodeWithContentDescription("查看维修前照片1").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        waitForText("车辆详情")
        assertEquals(0, File(app.filesDir, "photo_drafts").walkTopDown().count { it.isFile })
        val vehicleId = runBlocking { requireNotNull(app.repository.findVehicleByPlate("京B54321")).vehicle.id }
        val capture = runBlocking { app.photoFileStore.prepareCapture(java.util.UUID.randomUUID().toString()) }
        writeFixtureJpeg(capture.uri)
        val order = runBlocking {
            val photo = app.photoFileStore.compressCapture(capture.relativePath, PhotoType.BEFORE, 70)
            val id = app.repository.createServiceOrder(vehicleId, "2026-10-03", emptyList(), photos = listOf(photo))
            requireNotNull(app.repository.getServiceOrder(id))
        }
        // 新建表单放弃后没有保存服务，历史中的这张照片来自独立测试夹具。
        waitForText("2026-10-03")
        compose.onNodeWithText("2026-10-03").performClick()
        waitForServiceDetail()
        scroll("service_detail", "编辑服务")
        compose.onNodeWithText("编辑服务").performClick()
        waitForText("服务日期")
        compose.onNodeWithTag("service_form").performScrollToNode(hasContentDescription("查看维修前照片1"))
        compose.onNodeWithContentDescription("查看维修前照片1").performClick()
        waitForText("移除此照片")
        compose.onNodeWithText("移除此照片").performClick()
        compose.onNodeWithText("确认删除").performClick()
        waitForText("维修前（0 张）")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        waitForServiceDetail()
        assertTrue(File(app.filesDir, order.photos.single().relativePath).isFile)
        runBlocking { assertEquals(order.photos, app.repository.getServiceOrder(order.order.id)?.photos) }
    }

    /** 车辆与服务草稿经 Activity 重建后保留，退出需确认，导航仍能回首页。 */
    @Test
    fun draftsAndNavigationSurviveActivityRecreation() {
        compose.onNodeWithText("＋ 新增车辆").performClick()
        waitForText("车牌号 *")
        compose.onNodeWithText("车牌号 *").performTextInput("京B54321")
        compose.onNodeWithText("车主姓名").performTextInput("测试车主")
        compose.activityRule.scenario.recreate()
        waitForText("京B54321")
        compose.onNodeWithText("测试车主").assertIsDisplayed()
        screenshot("02-vehicle-form")
        scroll("vehicle_form", "保存车辆")
        compose.onNodeWithText("保存车辆").performClick()
        waitForText("车辆详情")
        waitForText("＋ 新增服务")
        compose.onNodeWithText("＋ 新增服务").performClick()
        waitForText("服务日期")
        addServiceItem(0, "补胎", "50.05")
        compose.activityRule.scenario.recreate()
        waitForText("服务日期")
        compose.onNodeWithTag("service_form").performScrollToNode(hasTestTag("item_amount_0"))
        compose.onNodeWithTag("item_amount_0").assertIsDisplayed()
        compose.onNodeWithText("50.05").assertIsDisplayed()
        compose.onNodeWithTag("service_form").performScrollToIndex(0)
        screenshot("04-service-form")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        waitForText("车辆详情")
        compose.onNodeWithContentDescription("返回").performClick()
        tab("车辆")
        compose.activityRule.scenario.recreate()
        waitForText("显示已归档车辆")
        compose.onNode(hasText("车辆") and hasClickAction()).assertIsSelected()
        tab("设置")
        waitForText("应用设置")
        tab("首页")
        waitForText("最近到店")
        compose.onNode(hasText("首页") and hasClickAction()).assertIsSelected()
    }

    /** 重复车牌失败留在表单；删除和归档需确认，设置写入真实 DataStore。 */
    @Test
    fun duplicateArchiveDeleteAndSettingsAreUsable() {
        createVehicle("冀A88888")
        compose.onNodeWithText("＋ 新增服务").performClick()
        waitForText("服务日期")
        scroll("service_form", "保存服务记录")
        compose.onNodeWithText("保存服务记录").performClick()
        waitForServiceDetail()
        scroll("service_detail", "删除本次服务")
        compose.onNodeWithText("删除本次服务").performClick()
        compose.onNodeWithText("确定删除本次服务记录？").assertIsDisplayed()
        compose.onNodeWithText("确认删除").performClick()
        waitForText("暂无服务记录")
        scroll("vehicle_detail", "归档车辆")
        compose.onNodeWithText("归档车辆").performClick()
        compose.onNodeWithText("确认").performClick()
        waitForText("恢复车辆")
        compose.onNodeWithContentDescription("返回").performClick()
        tab("车辆")
        waitForText("暂无车辆")
        compose.onNodeWithText("显示已归档车辆").performClick()
        waitForText("已归档")
        compose.onNodeWithText("冀A88888").performClick()
        waitForText("车辆详情")
        // 返回详情时标题先出现，Room 查询完成后才有归档恢复等操作。
        waitForText("＋ 新增服务")
        scroll("vehicle_detail", "恢复车辆")
        compose.onNodeWithText("恢复车辆").performClick()
        compose.onNodeWithText("确认").performClick()
        waitForText("归档车辆")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("＋ 新增车辆").performClick()
        waitForText("车牌号 *")
        compose.onNodeWithText("车牌号 *").performTextInput("冀 a88 888")
        scroll("vehicle_form", "保存车辆")
        compose.onNodeWithText("保存车辆").performClick()
        waitForText("车牌已存在，请打开已有车辆档案")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        tab("设置")
        waitForText("应用设置")
        compose.onNodeWithText("最近到店优先  ⌄").performClick()
        compose.onNodeWithText("新建车辆优先").performClick()
        waitForText("新建车辆优先  ⌄")
        compose.onNodeWithText("标准 70%  ⌄").performClick()
        compose.onNodeWithText("高清 90%").performClick()
        waitForText("高清 90%  ⌄")
        compose.activityRule.scenario.recreate()
        waitForText("新建车辆优先  ⌄")
        scroll("settings_list", "本地优先")
        screenshot("08-settings")
        runBlocking { assertEquals(90, app.settingsRepository.settings.first().imageQuality) }
        tab("首页")
        waitForText("暂无到店记录")
    }

    /** 留空金额可保存并在三个页面显示待填；重建、补填免费、非法输入和再次清空均可回归。 */
    @Test
    fun optionalServiceAmountCanBeSavedFilledAndCleared() {
        createVehicle("京A12345")
        compose.onNodeWithText("＋ 新增服务").performClick()
        waitForText("服务日期")
        addServiceItem(0, "待报价维修", "")
        addServiceItem(1, "已报价洗车", "19.90")
        scroll("service_form", "合计")
        compose.onNodeWithText("金额待填").assertIsDisplayed()
        screenshot("29-pending-amount-form")
        scroll("service_form", "保存服务记录")
        compose.onNodeWithText("保存服务记录").performClick()
        waitForServiceDetail()
        scroll("service_detail", "合计  金额待填")
        compose.onNodeWithText("合计  金额待填").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitForServiceDetail()
        scroll("service_detail", "合计  金额待填")
        screenshot("30-pending-amount-detail")
        scroll("service_detail", "编辑服务")
        compose.onNodeWithText("编辑服务").performClick()
        waitForText("服务日期")
        compose.onNodeWithTag("service_form").performScrollToNode(hasTestTag("item_amount_0"))
        assertEquals("", compose.onNodeWithTag("item_amount_0").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("item_amount_0").performTextReplacement("0")
        scroll("service_form", "保存服务记录")
        compose.onNodeWithText("保存服务记录").performClick()
        waitForServiceDetail()
        scroll("service_detail", "合计  ¥19.90")
        compose.onNodeWithText("合计  ¥19.90").assertIsDisplayed()
        scroll("service_detail", "编辑服务")
        compose.onNodeWithText("编辑服务").performClick()
        waitForText("服务日期")
        compose.onNodeWithTag("service_form").performScrollToNode(hasTestTag("item_amount_0"))
        compose.onNodeWithTag("item_amount_0").performTextReplacement("1.001")
        scroll("service_form", "保存服务记录")
        compose.onNodeWithText("保存服务记录").performClick()
        waitForText("金额应为非负数字，最多两位小数")
        compose.onNodeWithTag("service_form").performScrollToNode(hasTestTag("item_amount_0"))
        compose.onNodeWithTag("item_amount_0").performTextReplacement("")
        scroll("service_form", "保存服务记录")
        compose.onNodeWithText("保存服务记录").performClick()
        waitForServiceDetail()
        scroll("service_detail", "合计  金额待填")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("＋ 新增服务")
        scroll("vehicle_detail", "金额待填")
        compose.onNodeWithText("金额待填").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("最近到店")
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("金额待填"))
        compose.onNodeWithText("金额待填").assertIsDisplayed()
        screenshot("31-pending-amount-home")
    }

    /** 通过 Parcel 真正序列化 Bundle，再建全新 ViewModel，验证系统回收后的草稿格式。 */
    @Test
    fun savedDraftBundlesCanRebuildNewViewModels() {
        val store = ViewModelStore()
        try {
            val vehicleHandle = SavedStateHandle()
            val vehicle = VehicleEditViewModel(app.repository, vehicleHandle)
            store.put("vehicle", vehicle)
            val vehicleDraft = VehicleDraft(plateNumber = "京B54321", name = "恢复测试", remark = "未保存备注")
            vehicle.change(vehicleDraft)
            val restoredVehicle = VehicleEditViewModel(app.repository, SavedStateHandle(mapOf(
                "draft" to parcelCopy(requireNotNull(vehicleHandle.get<Bundle>("draft"))))))
            store.put("restoredVehicle", restoredVehicle)
            assertEquals(vehicleDraft, restoredVehicle.state.value.draft)
            val id = runBlocking { app.repository.createVehicle(Vehicle(plateNumber = "京B54321")) }
            val serviceHandle = SavedStateHandle(mapOf("vehicleId" to id))
            val service = ServiceEditViewModel(app.repository, serviceHandle)
            store.put("service", service)
            val draft = ServiceDraft(date = "2026-10-02", items = listOf(ServiceItemDraft(7, name = "补胎", amount = "50.05")), remark = "草稿",
                photos = listOf(PhotoDraft("photo_drafts/test/photo_test.jpg", PhotoType.BEFORE), PhotoDraft("images/order_1/test.jpg", PhotoType.AFTER, 2)))
            service.change(draft)
            val restoredService = ServiceEditViewModel(app.repository, SavedStateHandle(mapOf(
                "vehicleId" to id, "draft" to parcelCopy(requireNotNull(serviceHandle.get<Bundle>("draft"))))))
            store.put("restoredService", restoredService)
            assertEquals(draft, restoredService.state.value.draft)
            assertEquals(5005L, restoredService.state.value.totalAmountCent)
        } finally { store.clear() }
    }

    /** 窄屏大字体仍可滚动完成保存，避免底部操作被固定高度布局挡住。 */
    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun narrowScreenWithLargeFontCanSaveVehicle() {
        RuntimeEnvironment.setFontScale(1.5f)
        try {
            compose.activityRule.scenario.recreate()
            waitForText("最近到店")
            createVehicle("京AD12345")
            waitForText("京AD12345")
            screenshot("09-narrow-large-font")
            compose.onNodeWithText("＋ 新增服务").performClick()
            waitForText("服务日期")
            scroll("service_form", "选择日期")
            compose.onNodeWithText("选择日期").performClick()
            waitForText("确定")
            screenshot("14-large-font-date-picker")
            compose.onNodeWithText("确定").performClick()
            scroll("service_form", "保存服务记录")
            compose.onNodeWithText("保存服务记录").assertIsDisplayed()
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    /** 横屏中的日期对话框与表单保存操作都必须可以到达。 */
    @Test
    @Config(qualifiers = "zh-rCN-w891dp-h411dp-land-mdpi")
    fun landscapeDatePickerAndFormRemainUsable() {
        createVehicle("京AD12345")
        compose.onNodeWithText("＋ 新增服务").performClick()
        waitForText("服务日期")
        scroll("service_form", "选择日期")
        compose.onNodeWithText("选择日期").performClick()
        waitForText("确定")
        screenshot("10-landscape-date-picker")
        compose.onNodeWithText("确定").performClick()
        scroll("service_form", "保存服务记录")
        compose.onNodeWithText("保存服务记录").assertIsDisplayed()
        screenshot("11-landscape-form")
        compose.onNodeWithText("保存服务记录").performClick()
        waitForServiceDetail()
    }

    /** 深色模式沿用蓝色主色，并验证背景、卡片和主要操作仍清楚可见。 */
    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-night-mdpi")
    fun darkThemeKeepsCoreControlsReadable() {
        createVehicle("冀A88888")
        screenshot("12-dark-vehicle-detail")
        compose.onNodeWithContentDescription("返回").performClick()
        tab("设置")
        waitForText("应用设置")
        screenshot("13-dark-settings")
    }

    /** 逐帧检查根页面和表单切换，不能只在动画空闲后检查，否则会漏掉短暂的旧页文字。 */
    @Test
    fun navigationDoesNotDisplayOverlappingPages() {
        compose.mainClock.autoAdvance = false
        try {
            tab("车辆")
            assertNoPageOverlap("最近到店", "显示已归档车辆")
            tab("设置")
            assertNoPageOverlap("显示已归档车辆", "应用设置")
            tab("首页")
            assertNoPageOverlap("应用设置", "最近到店")
            compose.onNodeWithText("＋ 新增车辆").performClick()
            assertNoPageOverlap("最近到店", "车牌号 *")
            compose.onNodeWithContentDescription("返回").performClick()
            assertNoPageOverlap("车牌号 *", "最近到店")
        } finally { compose.mainClock.autoAdvance = true }

        // 隐藏退场页不能以丢弃页面状态为代价；再次打开同一标签应恢复查询和滚动。
        tab("车辆")
        waitForText("显示已归档车辆")
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").performTextInput("查询草稿")
        tab("设置")
        waitForText("应用设置")
        scroll("settings_list", "本地优先")
        tab("车辆")
        waitForText("查询草稿")
        compose.onNodeWithText("查询草稿").assertIsDisplayed()
        tab("设置")
        compose.onNodeWithText("本地优先").assertIsDisplayed()
    }

    /** 从切换首帧开始验证，覆盖 NavHost 保留退场页面的短暂窗口。 */
    private fun assertNoPageOverlap(previousText: String, nextText: String) {
        repeat(6) { frame ->
            compose.mainClock.advanceTimeByFrame()
            val previous = compose.onNodeWithText(previousText).isDisplayed()
            val next = compose.onNodeWithText(nextText).isDisplayed()
            check(!previous || !next) { "第 ${frame + 1} 帧同时显示旧页 $previousText 和新页 $nextText" }
            if (frame == 1 && previousText == "最近到店" && nextText == "显示已归档车辆") {
                screenshot("15-tab-switch-second-frame")
            }
        }
        compose.onNodeWithText(previousText).assertIsNotDisplayed()
        compose.onNodeWithText(nextText).assertIsDisplayed()
    }

    /** 子 Bundle 列表需要经过平台 Parcelable 格式，而非共享同一内存对象。 */
    private fun parcelCopy(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle); parcel.setDataPosition(0)
            return requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
    }

    /** 简单录入通过真正的 UI 保存，不把 Repository 写入当作页面流程已验证。 */
    private fun createVehicle(plate: String) {
        compose.onNodeWithText("＋ 新增车辆").performClick()
        waitForText("车牌号 *")
        compose.onNodeWithText("车牌号 *").performTextInput(plate)
        compose.onNodeWithText("车主姓名").performTextInput("测试车主")
        scroll("vehicle_form", "保存车辆")
        compose.onNodeWithText("保存车辆").performClick()
        waitForText("车辆详情")
        waitForText("＋ 新增服务")
    }
    /** 添加后使用 LazyColumn 的滚动语义找到尚未组成的项目控件。 */
    private fun addServiceItem(key: Long, name: String, amount: String) {
        scroll("service_form", "＋ 添加服务项目")
        compose.onNodeWithText("＋ 添加服务项目").performClick()
        compose.onNodeWithTag("service_form").performScrollToNode(hasTestTag("item_name_$key"))
        compose.onNodeWithTag("item_name_$key").performTextInput(name)
        compose.onNodeWithTag("service_form").performScrollToNode(hasTestTag("item_amount_$key"))
        compose.onNodeWithTag("item_amount_$key").performTextInput(amount)
    }

    /** 使用 Activity Result 的真实拍照请求和 FileProvider URI，测试相机应用的输出契约。 */
    @Suppress("DEPRECATION")
    private fun takeFixturePhoto(type: PhotoType, recreateDuringCamera: Boolean = false) {
        compose.onNodeWithTag("service_form").performScrollToNode(hasTestTag("take_${type.name}"))
        compose.onNodeWithTag("take_${type.name}").performClick()
        var started: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
        compose.waitUntil(15_000) {
            if (started == null) started = Shadows.shadowOf(compose.activity).nextStartedActivityForResult
            started != null
        }
        val request = requireNotNull(started)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.intent.action)
        val uri = requireNotNull(request.intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT))
        if (recreateDuringCamera) {
            compose.activityRule.scenario.recreate()
            waitForText("新增服务")
            assertEquals(null, Shadows.shadowOf(compose.activity).nextStartedActivityForResult)
        }
        writeFixtureJpeg(uri)
        compose.runOnUiThread { check(compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent())) }
        val label = "查看${type.displayName()}照片1"
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** 测试照片仅含色块，通过相机获授权的 URI 写入。 */
    private fun writeFixtureJpeg(uri: Uri) {
        val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.BLUE)
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally { bitmap.recycle() }
    }
    /** 生成真实色块 JPEG 作为删除关联文件夹具，不调用外部相机或真实账号。 */
    private suspend fun fixturePhoto(type: PhotoType): PhotoDraft {
        val capture = app.photoFileStore.prepareCapture(java.util.UUID.randomUUID().toString())
        writeFixtureJpeg(capture.uri)
        return app.photoFileStore.compressCapture(capture.relativePath, type, 70)
    }
    /** 导航栏与标题文本分开匹配，避免同名控件造成错误点击。 */
    private fun tab(label: String) { compose.onNode(hasText(label) and hasClickAction()).performClick() }
    /** 按品牌文字及可选择语义定位芯片，避免与车辆卡片的品牌文字混淆。 */
    private fun brandChip(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
    /** 数据库写入并非 Espresso 空闲资源，因此显式等待可见结果。 */
    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    /** 标题在查询完成前已出现，必须等实际详情数据加载后再断言或截图。 */
    private fun waitForServiceDetail() {
        waitForText("服务详情")
        compose.waitUntil(timeoutMillis = 15_000) { compose.onAllNodes(hasTestTag("service_detail_ready")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    /** 等待真实 JPEG 解码成功，而不是只用缩略图节点存在来认定图片已显示。 */
    private fun waitForLoadedPhoto(description: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription(description) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "图片已加载"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
    /** 长表单和大字体都通过真实滚动语义找到目标。 */
    private fun scroll(tag: String, text: String) { compose.onNodeWithTag(tag).performScrollToNode(hasText(text)) }
    /** Native graphics 中直接绘制已布局的 View，避开真机 PixelCopy 的重绘等待；只含测试数据。 */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // 日期弹窗属于独立 Window，需要绘制当前最上层窗口而非其后的 Activity。
        val view = WindowInspector.getGlobalWindowViews().lastOrNull() ?: compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val directory = File(checkNotNull(System.getProperty("ui.screenshot.dir")))
        check(directory.exists() || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
