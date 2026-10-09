package com.example.carrepairmanager.ui.ocr

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.example.carrepairmanager.WindowsFileProviderShadow
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.local.CarRepairDatabase
import com.example.carrepairmanager.data.local.entity.Vehicle
import com.example.carrepairmanager.data.ocr.OcrConfig
import com.example.carrepairmanager.data.ocr.OcrException
import com.example.carrepairmanager.data.ocr.OcrRepository
import com.example.carrepairmanager.data.ocr.OcrCredentials
import com.example.carrepairmanager.data.ocr.PlateCandidate
import com.example.carrepairmanager.data.ocr.PlateRecognitionResult
import com.example.carrepairmanager.data.ocr.PlateRecognizer
import com.example.carrepairmanager.data.ocr.writeOcrTestImage
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 真实 Room 与 JPEG 验证人工确认、系统恢复和取消，不让识别草稿进入数据库。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [WindowsFileProviderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlateOcrViewModelTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var database: CarRepairDatabase
    private lateinit var records: CarRepairRepository
    private lateinit var ocr: OcrRepository
    private val models = ViewModelStore()
    private val calls = AtomicInteger()
    private var failing = false
    private var gate: CompletableDeferred<Unit>? = null
    private val result = PlateRecognitionResult(listOf(PlateCandidate("冀A12345", 0.97), PlateCandidate("粤BD12345", null)))

    /** 每个用例独立数据库，凭据由代码注入，识别接口返回虚构候选但真实处理图片。 */
    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(app, CarRepairDatabase::class.java).build()
        records = CarRepairRepository(database)
        ocr = OcrRepository(app, OcrConfig.load(app), credentials = OcrCredentials("fixture", "fixture"), recognizer = PlateRecognizer {
            calls.incrementAndGet()
            gate?.await()
            if (failing) throw OcrException("测试识别失败，请重试")
            result
        })
        Unit
    }

    /** 先清理 ViewModel 观察与网络任务，再关闭本机测试数据库。 */
    @After
    fun tearDown() { models.clear(); shadowOf(Looper.getMainLooper()).idle(); database.close() }

    /** OCR 完成、多候选选择与人工修订仅修改草稿，Parcel 恢复后也不能自动查询或保存。 */
    @Test
    fun recognitionAndRestorationDoNotSaveOrNavigateBeforeConfirmation() {
        val saved = SavedStateHandle()
        val model = model(saved)
        capture(model)
        await { !model.state.value.busy && model.state.value.candidates.size == 2 }
        assertNull(model.state.value.confirmedPlate)
        assertNull(runBlocking { records.findVehicleByPlate("冀A12345") })
        model.selectCandidate(1)
        assertEquals("粤BD12345", model.state.value.plateNumber)
        model.changePlate("京 b88888")
        val restored = model(restored(saved))
        assertEquals("京 b88888", restored.state.value.plateNumber)
        assertEquals(2, restored.state.value.candidates.size)
        assertEquals(0.97, requireNotNull(restored.state.value.candidates[0].confidence), 0.0)
        assertNull(restored.state.value.confirmedPlate)
        assertEquals(1, calls.get())
        assertTrue(runBlocking { records.withBackupSnapshot { it.vehicles.isEmpty() } })
        restored.confirmPlate()
        await { restored.state.value.confirmedPlate == "京B88888" }
        assertTrue(runBlocking { records.withBackupSnapshot { it.vehicles.isEmpty() } })
    }

    /** 修改后的车牌只作为回填文本返回，即使已有归档档案也不直接打开或修改。 */
    @Test
    fun confirmedCorrectionReturnsPlateEvenWhenArchivedVehicleExists() {
        val id = runBlocking { records.createVehicle(Vehicle(plateNumber = "京B88888")) }
        runBlocking { records.setVehicleArchived(id, true) }
        val model = model()
        capture(model)
        await { !model.state.value.busy && model.state.value.candidates.isNotEmpty() }
        model.changePlate("京 b 88 888")
        assertNull(model.state.value.confirmedPlate)
        model.confirmPlate()
        await { model.state.value.confirmedPlate != null }
        assertEquals("京B88888", model.state.value.confirmedPlate)
        assertTrue(runBlocking { records.getVehicle(id)?.vehicle?.isArchived == true })
        assertEquals(1, runBlocking { records.withBackupSnapshot { it.vehicles.size } })
    }

    /** 确认事件消费后可以继续确认新的修订文本，旧事件不会覆盖第二次结果。 */
    @Test
    fun confirmationCanBeConsumedAndRepeatedWithAnotherPlate() {
        val model = model()
        model.changePlate("冀A12345"); model.confirmPlate()
        await { model.state.value.confirmedPlate == "冀A12345" && !model.state.value.busy }
        model.consumeConfirmation()
        assertNull(model.state.value.confirmedPlate)
        model.changePlate("京 b88888"); model.confirmPlate()
        await { model.state.value.confirmedPlate == "京B88888" }
        assertTrue(runBlocking { records.withBackupSnapshot { it.vehicles.isEmpty() } })
    }

    /** 相机已发起的请求在系统恢复后不重复打开，也不隐式上传未确认照片。 */
    @Test
    fun pendingCameraRestoresWithoutLaunchingOrRecognizingAgain() {
        val saved = SavedStateHandle()
        val original = model(saved)
        original.takePhoto()
        await { !original.state.value.busy && original.state.value.cameraRequest != null }
        val path = requireNotNull(original.state.value.cameraRequest).relativePath
        original.consumeCameraRequest()
        writeOcrTestImage(File(app.filesDir, path))
        val restored = model(restored(saved))
        await { restored.state.value.configured }
        assertTrue(restored.state.value.pendingCapture); assertNull(restored.state.value.cameraRequest)
        assertEquals(0, calls.get())
        restored.recognize()
        await { !restored.state.value.busy && restored.state.value.candidates.isNotEmpty() }
        assertEquals(1, calls.get()); assertFalse(File(app.filesDir, path).exists())
    }

    /** 失败不丢弃照片，用户重试成功；取消相机结果不能调用云接口。 */
    @Test
    fun failureAllowsExplicitRetryAndCancelledCameraNeverUploads() {
        failing = true
        val model = model()
        capture(model)
        await { !model.state.value.busy && model.state.value.error != null }
        assertTrue(model.state.value.pendingCapture); assertEquals(1, calls.get())
        failing = false
        model.recognize()
        await { !model.state.value.busy && model.state.value.candidates.isNotEmpty() }
        model.takePhoto()
        await { !model.state.value.busy && model.state.value.cameraRequest != null }
        val path = requireNotNull(model.state.value.cameraRequest).relativePath
        model.consumeCameraRequest(); model.cameraResult(false)
        await { !model.state.value.busy && !model.state.value.pendingCapture }
        assertEquals(2, calls.get()); assertFalse(File(app.filesDir, path).exists())
    }

    /** 返回会取消正在等待识别的任务，完成当前会话清理后才离开。 */
    @Test
    fun leavingCancelsRecognitionAndCleansTemporaryImages() {
        gate = CompletableDeferred()
        val model = model()
        val path = capture(model)
        await { calls.get() == 1 }
        var left = false
        model.leave { left = true }
        await { left && !model.state.value.busy }
        assertFalse(File(app.filesDir, path).exists())
        assertFalse(model.state.value.pendingCapture)
        assertNull(model.state.value.confirmedPlate)
        assertTrue(runBlocking { records.withBackupSnapshot { it.vehicles.isEmpty() } })
    }

    /** 默认配置失效时仍可确认手动修订文本，不把云功能错误扩散到原页面。 */
    @Test
    fun manualConfirmationWorksWithoutOcrConfiguration() {
        val model = PlateOcrViewModel(SavedStateHandle(), null, loadOcr = {
            assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
            throw IllegalArgumentException("测试配置无效")
        })
        models.put("without_config", model)
        await { model.state.value.error == "测试配置无效" && !model.state.value.loadingConfiguration }
        assertFalse(model.state.value.configured)
        model.changePlate("冀 a12345"); model.confirmPlate()
        await { model.state.value.confirmedPlate != null }
        assertEquals("冀A12345", model.state.value.confirmedPlate)
        assertEquals(0, calls.get())
    }

    /** ViewModel 由 Store 持有，测试结束能真实触发生命周期取消。 */
    private fun model(saved: SavedStateHandle = SavedStateHandle()): PlateOcrViewModel = PlateOcrViewModel(saved, ocr).also {
        models.put(UUID.randomUUID().toString(), it)
        await { it.state.value.configured }
    }

    /** 模拟系统相机写入单张真实 JPEG，并发送已确认的结果。 */
    private fun capture(model: PlateOcrViewModel): String {
        model.takePhoto()
        await { !model.state.value.busy && model.state.value.cameraRequest != null }
        val path = requireNotNull(model.state.value.cameraRequest).relativePath
        writeOcrTestImage(File(app.filesDir, path))
        model.consumeCameraRequest(); model.cameraResult(true)
        return path
    }

    /** 使用平台 Parcel 重建 Bundle，避免仅复制 Kotlin 对象掩盖系统恢复问题。 */
    private fun restored(saved: SavedStateHandle): SavedStateHandle {
        val values = saved.keys().associateWith { name ->
            val value = saved.get<Any?>(name)
            if (value !is Bundle) value else {
                val parcel = Parcel.obtain()
                try { parcel.writeBundle(value); parcel.setDataPosition(0); requireNotNull(parcel.readBundle(javaClass.classLoader)) }
                finally { parcel.recycle() }
            }
        }
        return SavedStateHandle(values)
    }

    /** 持续推进本机主线程，等待 IO / Room 回调，所有等待均有超时上限。 */
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("异步 OCR 状态未在期限内到达", predicate())
    }
}
