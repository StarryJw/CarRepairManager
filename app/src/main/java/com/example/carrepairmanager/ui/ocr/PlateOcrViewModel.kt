package com.example.carrepairmanager.ui.ocr

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.data.PhotoCapture
import com.example.carrepairmanager.data.normalizePlateNumber
import com.example.carrepairmanager.data.ocr.OcrRepository
import com.example.carrepairmanager.data.ocr.PlateCandidate
import com.example.carrepairmanager.ui.userMessage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 图片路径与识别草稿可恢复，凭据、Token 和图片字节永不进入页面状态。 */
data class PlateOcrUiState(val configured: Boolean = false, val loadingConfiguration: Boolean = false, val busy: Boolean = false,
    val pendingCapture: Boolean = false, val cameraRequest: PhotoCapture? = null,
    val candidates: List<PlateCandidate> = emptyList(), val selectedCandidate: Int = 0,
    val plateNumber: String = "", val error: String? = null, val confirmedPlate: String? = null)

/** 拍照和识别仅生成草稿，人工确认后只把车牌返回原页面，不查档案也不写数据库。 */
class PlateOcrViewModel(private val savedState: SavedStateHandle,
    ocr: OcrRepository?, configurationError: String? = null, loadOcr: (suspend () -> OcrRepository)? = null) : ViewModel() {
    // 与备份页保持一致，资产配置和 Repository 初始化不在 Compose / 主线程读取。
    private val ocrService = viewModelScope.async(Dispatchers.IO) { ocr ?: loadOcr?.invoke() }
    private val session = savedState.get<String>("ocrSession") ?: UUID.randomUUID().toString().also { savedState["ocrSession"] = it }
    private val mutableState = MutableStateFlow(readDraft().copy(pendingCapture = savedState.contains("ocrCapture"),
        loadingConfiguration = loadOcr != null, error = configurationError))
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var leaving = false

    init {
        viewModelScope.launch {
            try {
                val repository = ocrService.await()
                val capture = savedState.get<Bundle>("ocrCapture")
                if (repository != null && capture != null && !capture.getBoolean("launched")) runOperation {
                    val path = requireNotNull(capture.getString("path"))
                    mutableState.value = state.value.copy(cameraRequest = PhotoCapture(path, repository.captureUri(path)))
                }
                mutableState.value = state.value.copy(configured = repository?.configured == true, loadingConfiguration = false)
            } catch (error: Exception) {
                mutableState.value = state.value.copy(loadingConfiguration = false, error = error.userMessage("车牌识别暂不可用，请返回手动输入"))
            }
        }
        // 已发起或中断的识别由用户点击继续，系统重建不能自动重发可能计费的云请求。
    }

    /** 拍照按钮明确表示云识别；准备失败不会启动系统相机。 */
    fun takePhoto() {
        if (state.value.pendingCapture || !state.value.configured) return
        runOperation {
            val repository = service()
            repository.discardSession(session)
            val capture = repository.prepareCapture(session)
            savedState["ocrCapture"] = Bundle().apply { putString("path", capture.relativePath); putBoolean("launched", false) }
            mutableState.value = state.value.copy(pendingCapture = true, cameraRequest = capture, candidates = emptyList(),
                selectedCandidate = 0, plateNumber = "", confirmedPlate = null)
            persist()
        }
    }

    /** 发起前持久化标记，旋转或锁屏恢复后不重复弹出相机。 */
    fun consumeCameraRequest() {
        savedState.get<Bundle>("ocrCapture")?.let { savedState["ocrCapture"] = Bundle(it).apply { putBoolean("launched", true) } }
        mutableState.value = state.value.copy(cameraRequest = null)
    }

    /** 只有相机确认交付的照片才自动开始本次用户发起的识别，取消不会上传。 */
    fun cameraResult(success: Boolean) {
        if (!savedState.contains("ocrCapture")) return
        if (success) recognize() else cancelCapture()
    }

    /** 相机不可用保留明确反馈和待清理状态，用户仍能取消后手动查车。 */
    fun cameraLaunchFailed() { mutableState.value = state.value.copy(cameraRequest = null, error = "无法打开系统相机，请取消本次拍照后重试") }

    /** 识别失败保留照片供显式重试；成功先保存结果，再清理当前临时会话。 */
    fun recognize() = runOperation {
        val path = savedState.get<Bundle>("ocrCapture")?.getString("path") ?: return@runOperation
        val result = service().recognizeCapture(path)
        savedState.remove<Bundle>("ocrCapture")
        mutableState.value = state.value.copy(candidates = result.candidates, selectedCandidate = 0,
            plateNumber = result.candidates.first().plateNumber, pendingCapture = false, cameraRequest = null)
        persist()
        service().discardSession(session)
    }

    /** 取消拍照或识别等待只删除本次临时文件，不能删除车辆服务照片。 */
    fun cancelCapture() = runOperation {
        service().discardSession(session)
        savedState.remove<Bundle>("ocrCapture")
        mutableState.value = state.value.copy(pendingCapture = false, cameraRequest = null)
    }

    /** 原始候选与人工修订分开保留，修订后必须重新确认填入值。 */
    fun changePlate(value: String) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(plateNumber = value, confirmedPlate = null, error = null)
        persist()
    }

    /** 多车牌只选定草稿，选择操作不查询也不保存。 */
    fun selectCandidate(index: Int) {
        if (state.value.busy) return
        val candidate = state.value.candidates.getOrNull(index) ?: return
        mutableState.value = state.value.copy(selectedCandidate = index, plateNumber = candidate.plateNumber,
            confirmedPlate = null, error = null)
        persist()
    }

    /** 复用车牌标准化，只返回人工确认的文本；搜索和保存仍由原页面处理。 */
    fun confirmPlate() {
        if (state.value.pendingCapture) return
        runOperation {
            val plate = normalizePlateNumber(state.value.plateNumber)
            require(plate.isNotBlank()) { "请确认或输入车牌号" }
            optionalService()?.discardSession(session)
            mutableState.value = state.value.copy(plateNumber = plate, confirmedPlate = plate)
            persist()
        }
    }

    /** 确认结果只回填一次，重组与页面恢复不重复覆盖原页面输入。 */
    fun consumeConfirmation() { mutableState.value = state.value.copy(confirmedPlate = null); persist() }

    /** 返回先取消网络任务再清理照片；清理失败保留页面提示，不静默丢弃。 */
    fun leave(onDone: () -> Unit) {
        if (leaving) return
        leaving = true
        val running = operation
        running?.cancel()
        operation = viewModelScope.launch {
            running?.join()
            mutableState.value = state.value.copy(busy = true, cameraRequest = null)
            try {
                optionalService()?.discardSession(session)
                savedState.remove<Bundle>("ocrCapture")
                mutableState.value = state.value.copy(pendingCapture = false)
                onDone()
            } catch (error: Exception) { mutableState.value = state.value.copy(error = error.userMessage("OCR 临时图片清理失败，请重试")) }
            finally { leaving = false; mutableState.value = state.value.copy(busy = false) }
        }
    }

    /** 配置错误仅阻止云功能，原页面仍能手动输入。 */
    private suspend fun service(): OcrRepository = requireNotNull(optionalService()) { "车牌识别暂不可用，请返回手动输入" }

    /** 默认配置损坏时只禁用云功能，手动查询和返回不被失败的初始化任务阻塞。 */
    private suspend fun optionalService(): OcrRepository? = try { ocrService.await() }
    catch (error: Exception) { if (error is CancellationException) throw error; null }

    /** 单一操作锁覆盖图片和确认，协程取消由统一错误处理继续传播。 */
    private fun runOperation(action: suspend () -> Unit) {
        if (operation?.isActive == true || state.value.confirmedPlate != null) return
        mutableState.value = state.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { action() }
            catch (error: Exception) { mutableState.value = state.value.copy(error = error.userMessage("OCR 操作失败，请重试或手动输入车牌")) }
            finally { mutableState.value = state.value.copy(busy = false) }
        }
    }

    /** Bundle 仅保存少量字符串与概率，兼容进程重建，且不包含认证信息。 */
    private fun persist() {
        val current = state.value
        savedState["ocrDraft"] = Bundle().apply {
            putString("plate", current.plateNumber); putInt("selected", current.selectedCandidate)
            putStringArrayList("candidates", ArrayList(current.candidates.map { it.plateNumber }))
            putDoubleArray("confidence", current.candidates.map { it.confidence ?: Double.NaN }.toDoubleArray())
            putString("confirmedPlate", current.confirmedPlate)
        }
    }

    /** 只恢复识别草稿，不把系统重建当成用户再次同意上传照片。 */
    private fun readDraft(): PlateOcrUiState = savedState.get<Bundle>("ocrDraft")?.run {
        val confidence = getDoubleArray("confidence") ?: doubleArrayOf()
        val rows = getStringArrayList("candidates").orEmpty().mapIndexed { index, plate ->
            PlateCandidate(plate, confidence.getOrNull(index)?.takeIf { it.isFinite() && it in 0.0..1.0 })
        }
        PlateOcrUiState(candidates = rows, selectedCandidate = getInt("selected").coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
            plateNumber = getString("plate").orEmpty(), confirmedPlate = getString("confirmedPlate"))
    } ?: PlateOcrUiState()
}
