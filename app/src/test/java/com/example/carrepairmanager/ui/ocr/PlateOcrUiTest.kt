package com.example.carrepairmanager.ui.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.carrepairmanager.CarRepairApplication
import com.example.carrepairmanager.MainActivity
import com.example.carrepairmanager.data.local.entity.Vehicle
import com.example.carrepairmanager.ui.theme.CarRepairManagerTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 真实 Compose 与导航验证两个回填入口；截图和本地数据库仅含虚构档案。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CarRepairApplication::class, qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlateOcrUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: CarRepairApplication
    private val models = ViewModelStore()

    /** 在主线程获取 Activity，后台只清理测试库，不连接手机或真实云服务。 */
    @Before
    fun setUp() = runBlocking {
        app = compose.activity.application as CarRepairApplication
        withContext(Dispatchers.IO) { app.database.clearAllTables() }
        waitForText("暂无到店记录")
    }

    /** 结束页面订阅后关闭数据库，释放自定义候选测试模型。 */
    @After
    fun tearDown() { val database = app.database; models.clear(); compose.activityRule.scenario.close(); database.close() }

    /** 首页只更新搜索框，用户点击搜索结果后才打开车辆详情；不需要 App 内配置。 */
    @Test
    fun homeConfirmationFillsSearchAndNeedsExplicitVehicleSelection() {
        runBlocking { app.repository.createVehicle(Vehicle(plateNumber = "京B88888")) }
        openOcr()
        compose.onNodeWithText("导入 OCR 配置").assertDoesNotExist()
        compose.onNodeWithText("移除 OCR 配置").assertDoesNotExist()
        confirmPlate("京 b88 888")
        waitForText("搜索车牌 / 车主 / 手机号")
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").assertTextContains("京B88888")
        waitForCard("京B88888")
        compose.onNodeWithText("车辆详情").assertDoesNotExist()
        screenshot("26-ocr-search-filled")
        vehicleCard("京B88888").performClick()
        waitForText("车辆详情")
        assertEquals(1, runBlocking { app.repository.withBackupSnapshot { it.vehicles.size } })
    }

    /** 新增表单经 OCR 页面重建后仍保留其他字段，回填后需明确保存才建档。 */
    @Test
    fun newVehicleOcrPreservesOtherFieldsAcrossRecreationAndNeedsSave() {
        compose.onNodeWithText("＋ 新增车辆").performClick()
        waitForText("新增车辆")
        compose.onNodeWithText("车主姓名").performTextInput("回填测试")
        compose.onNodeWithText("手机号").performTextInput("13900001234")
        compose.onNodeWithTag("vehicle_form").performScrollToNode(hasText("车辆备注"))
        compose.onNodeWithText("车辆备注").performTextInput("保留原表单备注")
        compose.onNodeWithTag("vehicle_form").performScrollToNode(hasTestTag("vehicle_ocr"))
        compose.onNodeWithTag("vehicle_ocr").performClick()
        waitForText("识别车牌")
        scrollTo("ocr_plate"); compose.onNodeWithTag("ocr_plate").performTextInput("粤 b d12345")
        compose.activityRule.scenario.recreate()
        waitForText("识别车牌")
        scrollTo("ocr_confirm"); compose.onNodeWithTag("ocr_confirm").performClick()
        waitForText("新增车辆")
        compose.onNodeWithTag("vehicle_form").performScrollToNode(hasText("车牌号 *"))
        compose.onNodeWithText("车牌号 *").assertTextContains("粤BD12345")
        compose.onNodeWithTag("vehicle_form").performScrollToNode(hasText("车主姓名"))
        compose.onNodeWithText("车主姓名").assertTextContains("回填测试")
        compose.onNodeWithText("手机号").assertTextContains("13900001234")
        screenshot("28-ocr-form-filled")
        compose.onNodeWithTag("vehicle_form").performScrollToNode(hasText("车辆备注"))
        compose.onNodeWithText("车辆备注").assertTextContains("保留原表单备注")
        assertNull(runBlocking { app.repository.findVehicleByPlate("粤BD12345") })
        compose.onNodeWithTag("vehicle_form").performScrollToNode(hasText("保存车辆"))
        compose.onNodeWithText("保存车辆").performClick()
        waitForText("车辆详情")
        assertNotNull(runBlocking { app.repository.findVehicleByPlate("粤BD12345") })
        assertEquals(1, runBlocking { app.repository.withBackupSnapshot { it.vehicles.size } })
    }

    /** 车辆页保留品牌及归档筛选，连续回填有效；取消和重建不覆盖后续手动输入。 */
    @Test
    fun vehicleSearchKeepsFiltersAndConsumesEachConfirmationOnlyOnce() {
        runBlocking {
            val archived = app.repository.createVehicle(Vehicle(plateNumber = "冀A12345", brand = "宝马", model = "X3"))
            app.repository.setVehicleArchived(archived, true)
            app.repository.createVehicle(Vehicle(plateNumber = "冀B12345", brand = "丰田", model = "S"))
            app.repository.createVehicle(Vehicle(plateNumber = "粤BD12345", brand = "宝马", model = "X1"))
        }
        compose.onNodeWithText("车辆").performClick()
        waitForText("显示已归档车辆")
        compose.onNodeWithText("显示已归档车辆").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("宝马") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("宝马") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        openOcr(); confirmPlate("冀 a12345")
        waitForText("搜索车牌 / 车主 / 手机号")
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").assertTextContains("冀A12345")
        compose.onNode(hasText("宝马") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
        compose.onNodeWithText("显示已归档车辆").assertIsSelected()
        waitForCard("冀A12345")
        compose.onNodeWithText("车辆详情").assertDoesNotExist()
        compose.onNodeWithTag("vehicle_list").performScrollToNode(hasText("拍车牌搜索"))
        openOcr(); confirmPlate("粤 b d12345")
        waitForText("搜索车牌 / 车主 / 手机号")
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").assertTextContains("粤BD12345")
        waitForCard("粤BD12345")
        vehicleCard("冀A12345").assertDoesNotExist()
        compose.onNodeWithTag("vehicle_list").performScrollToNode(hasText("搜索车牌 / 车主 / 手机号"))
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").performTextReplacement("手动关键词")
        openOcr()
        scrollTo("ocr_plate"); compose.onNodeWithTag("ocr_plate").performTextInput("不应回填")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("搜索车牌 / 车主 / 手机号")
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").assertTextContains("手动关键词")
        compose.activityRule.scenario.recreate()
        waitForText("搜索车牌 / 车主 / 手机号")
        compose.onNodeWithText("搜索车牌 / 车主 / 手机号").assertTextContains("手动关键词")
    }

    /** 窄屏大字体下多候选与修订可滚动操作，确认只返回文字，不查询或保存档案。 */
    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun multipleCandidatesAndManualCorrectionRemainUsableWithLargeFont() {
        val saved = SavedStateHandle(mapOf("ocrDraft" to Bundle().apply {
            putString("plate", "冀A12345"); putStringArrayList("candidates", arrayListOf("冀A12345", "粤BD12345"))
            putDoubleArray("confidence", doubleArrayOf(0.97, Double.NaN))
        }))
        val model = PlateOcrViewModel(saved, null)
        models.put("candidate_fixture", model)
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CarRepairManagerTheme {
                    val state by model.state.collectAsStateWithLifecycle()
                    PlateOcrScreen(state, {}, {}, {}, {}, model::changePlate, model::selectCandidate, model::confirmPlate,
                        fillingVehicle = true)
                }
            }
        } }
        scrollTo("ocr_candidate_1")
        compose.onNodeWithTag("ocr_candidate_1").performClick()
        compose.onNodeWithTag("ocr_list").performScrollToNode(hasText("识别置信度未提供，请仔细核对"))
        waitForText("识别置信度未提供，请仔细核对")
        screenshot("27-ocr-candidates-large-font")
        scrollTo("ocr_plate"); compose.onNodeWithTag("ocr_plate").performTextReplacement("沪 a 12345")
        compose.onNodeWithTag("ocr_list").performScrollToNode(hasText("车牌已手动修改，确认后填入修改值。"))
        compose.onNodeWithText("车牌已手动修改，确认后填入修改值。").assertIsDisplayed()
        assertNull(model.state.value.confirmedPlate)
        assertTrue(runBlocking { app.repository.withBackupSnapshot { it.vehicles.isEmpty() } })
        scrollTo("ocr_confirm"); compose.onNodeWithTag("ocr_confirm").performClick()
        compose.waitUntil(15_000) { model.state.value.confirmedPlate == "沪A12345" }
        assertTrue(runBlocking { app.repository.withBackupSnapshot { it.vehicles.isEmpty() } })
    }

    /** 测试只人工填写确认，不用真实编译凭据发起拍照上传。 */
    private fun openOcr() { compose.onNodeWithText("拍车牌搜索").performClick(); waitForText("识别车牌") }

    /** 通过页面操作模拟人工修订和确认，沿用真实导航结果通道。 */
    private fun confirmPlate(plate: String) {
        scrollTo("ocr_plate"); compose.onNodeWithTag("ocr_plate").performTextInput(plate)
        scrollTo("ocr_confirm"); compose.onNodeWithTag("ocr_confirm").performClick()
    }

    /** 组合卡片语义，区分同名搜索输入框与车辆搜索结果。 */
    private fun vehicleCard(plate: String) = compose.onNode(hasText(plate) and hasText("暂无服务记录"))

    /** 查询经过防抖与 Room 回调，等待真实结果后滚动到卡片。 */
    private fun waitForCard(plate: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(plate) and hasText("暂无服务记录")).fetchSemanticsNodes().isNotEmpty() }
        val list = if (compose.onAllNodes(hasTestTag("vehicle_list")).fetchSemanticsNodes().isNotEmpty()) "vehicle_list" else "home_list"
        compose.onNodeWithTag(list).performScrollToNode(hasText(plate) and hasText("暂无服务记录"))
        vehicleCard(plate).assertIsDisplayed()
    }

    /** 懒列表控件在使用前滚动到可见区域，避免依赖屏幕尺寸。 */
    private fun scrollTo(tag: String) { compose.onNodeWithTag("ocr_list").performScrollToNode(hasTestTag(tag)) }

    /** 等待真实 Compose 重组与 Room 回调，不用固定延时假定完成。 */
    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** Native graphics 绘制真实页面，供检查搜索回填、候选和新增表单布局。 */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(requireNotNull(System.getProperty("ui.screenshot.dir"))).apply { mkdirs() }
        compose.runOnUiThread {
            val view = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
