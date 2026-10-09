package com.example.carrepairmanager.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.example.carrepairmanager.CarRepairApplication
import com.example.carrepairmanager.MainActivity
import com.example.carrepairmanager.data.local.entity.Vehicle
import com.example.carrepairmanager.data.backup.BackupScope
import com.example.carrepairmanager.data.backup.WebDavClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 实际 Compose 点击与系统文档结果验证备份交互，截图只含虚构档案。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CarRepairApplication::class, qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: CarRepairApplication

    /** 清理本机测试沙箱中的 Room，保留生产安装数据完全隔离。 */
    @Before
    fun setUp() = runBlocking {
        app = compose.activity.application as CarRepairApplication
        withContext(Dispatchers.IO) { app.database.clearAllTables() }
        waitForText("暂无到店记录")
        compose.onNodeWithText("设置").performClick()
        waitForText("导出备份")
    }

    /** 先结束界面观察再关闭测试数据库。 */
    @After
    fun tearDown() { val database = app.database; compose.activityRule.scenario.close(); database.close() }

    /** 导出文档、恢复预览、取消、Activity 重建后再次确认与整份恢复均经过真实页面。 */
    @Test
    fun documentExportRestorePreviewCancelAndRecreationRequireConfirmation() = runBlocking {
        val first = app.repository.createVehicle(Vehicle(plateNumber = "测试备份车"))
        val file = File(app.cacheDir, "ui_export.zip")
        val exportRequestedAt = System.currentTimeMillis()
        compose.onNodeWithTag("backup_export").performClick()
        val export = startedDocument()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.intent.action)
        val filename = requireNotNull(export.intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(WebDavClient.isBackupName(filename))
        val filenameTime = requireNotNull(SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.parse(filename.removePrefix("CarShopBackup_").take(19))).time
        assertTrue(filenameTime in exportRequestedAt..System.currentTimeMillis())
        returnDocument(export.requestCode, Uri.fromFile(file))
        waitForText("本地备份已导出")
        assertTrue(file.isFile)
        val extra = app.repository.createVehicle(Vehicle(plateNumber = "测试当前车"))
        selectImport(file)
        waitForText("确认整份恢复？")
        assertNotNull(app.repository.getVehicle(extra))
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("确认整份恢复？")).fetchSemanticsNodes().isEmpty() }
        assertNotNull(app.repository.getVehicle(extra))
        selectImport(file)
        waitForText("确认整份恢复？")
        compose.activityRule.scenario.recreate()
        waitForText("确认整份恢复？")
        assertNotNull(app.repository.getVehicle(extra))
        screenshot("22-backup-restore-confirmation")
        compose.onNodeWithText("确认替换并恢复").performClick()
        waitForText("最近到店")
        assertNotNull(app.repository.getVehicle(first)); assertNull(app.repository.getVehicle(extra))
        compose.onNodeWithText("车辆").performClick()
        waitForText("测试备份车")
    }

    /** 缺少结构的 ZIP 显示错误，无确认弹窗且原档案仍能查询。 */
    @Test
    fun brokenBackupShowsErrorAndKeepsExistingVehicle() = runBlocking {
        val id = app.repository.createVehicle(Vehicle(plateNumber = "测试保护车"))
        val file = File(app.cacheDir, "ui_broken.zip").apply { writeText("invalid zip") }
        selectImport(file)
        waitForText("ZIP 备份文件损坏或不完整")
        compose.onNodeWithText("ZIP 备份文件损坏或不完整").assert(hasAnyAncestor(hasTestTag("backup_local")))
        assertNotNull(app.repository.getVehicle(id))
        compose.onNodeWithText("确认整份恢复？").assertDoesNotExist()
    }

    /** 已保存的云端失败和即时配置失败都只显示在上传卡片，Activity 重建后也不污染其他区域。 */
    @Test
    fun cloudErrorsOnlyAppearInUploadAndAutomaticBackupCard() = runBlocking {
        val uploadError = "云端备份大小校验失败，请重试"
        app.backupSettingsRepository.failed(uploadError, BackupScope.CLOUD)
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("上传与自动备份"))
        waitForText(uploadError)
        compose.onAllNodes(hasText(uploadError)).assertCountEquals(1)
        compose.onNodeWithText(uploadError).assert(hasAnyAncestor(hasTestTag("backup_upload")))
        screenshot("25-backup-cloud-error-scoped")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("backup_local"))
        compose.onAllNodes(hasText(uploadError) and hasAnyAncestor(hasTestTag("backup_local"))).assertCountEquals(0)
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("backup_url"))
        compose.onAllNodes(hasText(uploadError) and hasAnyAncestor(hasTestTag("backup_connection"))).assertCountEquals(0)
        compose.onNodeWithTag("backup_url").performTextReplacement("http://fixture.invalid/dav/")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("保存配置"))
        compose.onNodeWithText("保存配置").performClick()
        val configurationError = "请输入不含账号或查询参数的 HTTPS WebDAV 地址"
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("上传与自动备份"))
        waitForText(configurationError)
        compose.onAllNodes(hasText(configurationError)).assertCountEquals(1)
        compose.onNodeWithText(configurationError).assert(hasAnyAncestor(hasTestTag("backup_upload")))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("上传与自动备份"))
        waitForText(configurationError)
        compose.onNodeWithText(configurationError).assert(hasAnyAncestor(hasTestTag("backup_upload")))
        Unit
    }

    /** 未保存的非敏感配置草稿保留，应用密码在 Activity 中重建由 ViewModel 持有，截图验证长表单可滚动。 */
    @Test
    fun webDavConfigurationFieldsAreUsableInScrollableSettings() {
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("backup_username"))
        compose.onNodeWithTag("backup_username").performTextInput("fixture@example.com")
        compose.onNodeWithTag("backup_password").performTextInput("fixture-only-password")
        compose.activityRule.scenario.recreate()
        waitForText("WebDAV 配置")
        screenshot("23-backup-webdav-configuration")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("上传与自动备份"))
        waitForText("上传与自动备份")
        screenshot("24-backup-automatic-settings")
    }

    /** 接收系统文档选择器请求，不打开电脑或手机上的外部应用。 */
    private fun startedDocument(): org.robolectric.shadows.ShadowActivity.IntentForResult {
        var request: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
        compose.waitUntil(15_000) { if (request == null) request = Shadows.shadowOf(compose.activity).nextStartedActivityForResult; request != null }
        return requireNotNull(request)
    }

    /** Activity Result 注册与生产保持一致，只用沙箱文件模拟文档提供方结果。 */
    private fun returnDocument(requestCode: Int, uri: Uri) {
        compose.runOnUiThread { check(compose.activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))) }
    }

    /** 本地恢复必须由真实恢复按钮发起并等待系统返回。 */
    private fun selectImport(file: File) {
        compose.onNodeWithTag("backup_import").performClick()
        val request = startedDocument()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        returnDocument(request.requestCode, Uri.fromFile(file))
    }

    /** IO 和 Compose 异步完成后才断言页面文本。 */
    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** Native graphics 截图覆盖恢复范围与配置表单，供本机视觉检查。 */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(System.getProperty("ui.screenshot.dir"), "").apply { mkdirs() }
        compose.runOnUiThread {
            val view = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
