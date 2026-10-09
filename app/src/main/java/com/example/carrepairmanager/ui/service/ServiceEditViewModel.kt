package com.example.carrepairmanager.ui.service

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.AppSettingsRepository
import com.example.carrepairmanager.data.PhotoCapture
import com.example.carrepairmanager.data.PhotoDraft
import com.example.carrepairmanager.data.PhotoFileStore
import com.example.carrepairmanager.data.sumAmountCent
import com.example.carrepairmanager.data.local.VehicleWithCustomer
import com.example.carrepairmanager.data.local.entity.ServiceCategory
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.ui.formatAmountCent
import com.example.carrepairmanager.ui.parseOptionalAmountCent
import com.example.carrepairmanager.ui.todayServiceDate
import com.example.carrepairmanager.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** 草稿键仅供界面稳定定位项目，不复用数据库项目 ID。 */
data class ServiceItemDraft(val key: Long, val category: ServiceCategory = ServiceCategory.REPAIR, val name: String = "", val amount: String = "", val remark: String = "")
/** 图片新增和移除与日期、项目一起保存，放弃编辑不改变原服务记录。 */
data class ServiceDraft(val date: String = todayServiceDate(), val items: List<ServiceItemDraft> = emptyList(), val remark: String = "",
    val photos: List<PhotoDraft> = emptyList())
/** 合计为空时允许留空项目保存为待填；非法输入仍由保存校验明确拒绝。 */
data class ServiceEditUiState(val vehicle: VehicleWithCustomer? = null, val draft: ServiceDraft = ServiceDraft(),
    val loading: Boolean = true, val saving: Boolean = false, val dirty: Boolean = false, val totalAmountCent: Long? = 0,
    val error: String? = null, val savedId: Long? = null, val processingPhoto: Boolean = false,
    val waitingForCamera: Boolean = false, val cameraRequest: PhotoCapture? = null, val photoError: String? = null,
    val pendingCapture: Boolean = false)

/** 项目增删改、金额转换和保存均在 ViewModel 中处理，界面只发送草稿事件。 */
class ServiceEditViewModel(private val repository: CarRepairRepository, private val savedState: SavedStateHandle,
    private val photoFiles: PhotoFileStore? = null, private val settings: AppSettingsRepository? = null) : ViewModel() {
    val orderId: Long = savedState["orderId"] ?: 0L
    private var vehicleId: Long = savedState["vehicleId"] ?: 0L
    private val initialDraft = readDraft()
    private val photoSession = savedState.get<String>("photoSession") ?: UUID.randomUUID().toString().also { savedState["photoSession"] = it }
    private val mutableState = MutableStateFlow(ServiceEditUiState(draft = initialDraft, dirty = savedState["dirty"] ?: false,
        totalAmountCent = total(initialDraft), savedId = savedState["savedId"],
        waitingForCamera = savedState.contains("capture"), pendingCapture = savedState.contains("capture")))
    val state = mutableState.asStateFlow()
    init { load() }

    /** 编辑从数据库取车辆归属；恢复的草稿始终优先于旧服务内容。 */
    fun load() {
        if (state.value.saving || state.value.processingPhoto) return
        mutableState.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val order = if (orderId != 0L) requireNotNull(repository.getServiceOrder(orderId)) { "服务记录不存在" } else null
                if (order != null) vehicleId = order.order.vehicleId
                val vehicle = requireNotNull(repository.getVehicle(vehicleId)) { "车辆不存在" }
                val draft = if (savedState.contains("draft")) readDraft() else if (order == null) ServiceDraft() else
                    ServiceDraft(order.order.serviceDate, order.items.mapIndexed { index, item ->
                        ServiceItemDraft(index.toLong(), item.category, item.name, item.amountCent?.let(::formatAmountCent).orEmpty(), item.remark)
                    }, order.order.remark, order.photos.map { PhotoDraft(it.relativePath, it.type, it.id) })
                persist(draft)
                mutableState.value = state.value.copy(vehicle = vehicle, draft = draft, totalAmountCent = total(draft), loading = false)
                val capture = savedState.get<Bundle>("capture")
                if (capture != null) {
                    if (capture.containsKey("success")) processCapture()
                    else if (!capture.getBoolean("launched")) {
                        val path = requireNotNull(capture.getString("path"))
                        mutableState.value = state.value.copy(cameraRequest = PhotoCapture(path, requireNotNull(photoFiles).captureUri(path)))
                    }
                }
            } catch (error: Exception) {
                mutableState.value = state.value.copy(loading = false, error = error.userMessage("服务读取失败，请重试"))
            }
        }
    }

    /** 每次变化保存 Bundle 草稿并重新计算整数分合计。 */
    fun change(draft: ServiceDraft) {
        if (state.value.saving || state.value.processingPhoto || state.value.waitingForCamera) return
        updateDraft(draft)
    }
    /** 拍照完成等内部事件也写入同一个 Bundle 草稿，防止旋转后丢失照片列表。 */
    private fun updateDraft(draft: ServiceDraft) {
        persist(draft); savedState["dirty"] = true
        mutableState.value = state.value.copy(draft = draft, dirty = true, totalAmountCent = total(draft), error = null)
    }
    /** 本地键单调递增，删除中间项目后仍能稳定定位输入控件。 */
    fun addItem() {
        val draft = state.value.draft
        val nextKey = savedState.get<Long>("nextItemKey") ?: ((draft.items.maxOfOrNull { it.key } ?: -1L) + 1)
        savedState["nextItemKey"] = nextKey + 1
        change(draft.copy(items = draft.items + ServiceItemDraft(nextKey)))
    }
    /** 保存时再次校验，确保表单合计和数据库合计使用相同整数金额。 */
    fun save() {
        if (state.value.loading || state.value.saving || state.value.processingPhoto || state.value.pendingCapture || state.value.savedId != null) return
        val draft = state.value.draft
        mutableState.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                require(state.value.vehicle != null) { "请先成功读取车辆" }
                val items = draft.items.map { ServiceItem(category = it.category, name = it.name,
                    amountCent = parseOptionalAmountCent(it.amount), remark = it.remark.trim()) }
                val id = if (orderId == 0L) repository.createServiceOrder(vehicleId, draft.date, items, draft.remark.trim(), draft.photos) else {
                    repository.updateServiceOrder(orderId, draft.date, items, draft.remark.trim(), draft.photos); orderId
                }
                savedState["savedId"] = id; savedState["dirty"] = false
                // 已保存档案不能因草稿清理失败被重复提交；残留草稿不影响正式照片。
                withContext(NonCancellable) {
                    try { photoFiles?.discardSession(photoSession) }
                    catch (error: Exception) { Log.e("ServiceEditViewModel", "服务已保存，图片草稿清理失败", error) }
                }
                mutableState.value = state.value.copy(saving = false, dirty = false, savedId = id)
            } catch (error: Exception) {
                mutableState.value = state.value.copy(saving = false, error = error.userMessage("服务保存失败，请重试"))
            }
        }
    }
    /** 结果消费后不因重组再次打开详情。 */
    fun consumeSaved() { savedState.remove<Long>("savedId"); mutableState.value = state.value.copy(savedId = null) }

    /** 拍照前由后台创建文件和 URI，UI 只负责启动 Activity Result。 */
    fun takePhoto(type: PhotoType) {
        if (state.value.loading || state.value.saving || state.value.processingPhoto || state.value.pendingCapture) return
        mutableState.value = state.value.copy(processingPhoto = true, photoError = null)
        viewModelScope.launch {
            try {
                val capture = requireNotNull(photoFiles) { "图片存储未配置" }.prepareCapture(photoSession)
                savedState["capture"] = Bundle().apply { putString("path", capture.relativePath); putString("type", type.name) }
                mutableState.value = state.value.copy(processingPhoto = false, waitingForCamera = true, pendingCapture = true, cameraRequest = capture)
            } catch (error: Exception) {
                mutableState.value = state.value.copy(processingPhoto = false, photoError = error.userMessage("拍照准备失败，请重试"))
            }
        }
    }

    /** 启动前标记请求已消费，Activity 重建不会再次弹出相机。 */
    fun consumeCameraRequest() {
        savedState.get<Bundle>("capture")?.let { savedState["capture"] = Bundle(it).apply { putBoolean("launched", true) } }
        mutableState.value = state.value.copy(cameraRequest = null)
    }

    /** 结果先保存再处理，回调早于车辆加载时仍能在恢复后继续。 */
    fun cameraResult(success: Boolean) {
        val capture = savedState.get<Bundle>("capture") ?: return
        savedState["capture"] = Bundle(capture).apply { putBoolean("success", success) }
        if (!state.value.loading) processCapture()
    }

    /** 无可用相机或系统拒绝启动时清理空文件，并显示可重试的拍照错误。 */
    fun cameraLaunchFailed() {
        cameraResult(false)
        mutableState.value = state.value.copy(photoError = "无法打开系统相机，请检查相机是否可用后重试")
    }

    /** 相机结果丢失时可手动继续；压缩失败保留原件供重试或取消。 */
    fun processCapture() {
        val capture = savedState.get<Bundle>("capture") ?: return
        if (state.value.processingPhoto || state.value.loading || state.value.saving) return
        mutableState.value = state.value.copy(processingPhoto = true, waitingForCamera = false, cameraRequest = null, photoError = null)
        viewModelScope.launch {
            try {
                val path = requireNotNull(capture.getString("path"))
                val store = requireNotNull(photoFiles)
                if (capture.containsKey("success") && !capture.getBoolean("success")) {
                    store.discardPhoto(path)
                } else {
                    val type = PhotoType.valueOf(requireNotNull(capture.getString("type")))
                    val quality = settings?.settings?.first()?.imageQuality ?: 70
                    val photo = store.compressCapture(path, type, quality)
                    if (state.value.draft.photos.none { it.relativePath == photo.relativePath })
                        updateDraft(state.value.draft.copy(photos = state.value.draft.photos + photo))
                }
                savedState.remove<Bundle>("capture")
                mutableState.value = state.value.copy(processingPhoto = false, waitingForCamera = false, pendingCapture = false)
            } catch (error: Exception) {
                mutableState.value = state.value.copy(processingPhoto = false, waitingForCamera = false,
                    photoError = error.userMessage("照片处理失败，可重新处理或取消本次拍照"))
            }
        }
    }

    /** 取消未完成拍照同步移除原件和压缩件，不留下无法保存的等待状态。 */
    fun cancelCapture() {
        val capture = savedState.get<Bundle>("capture") ?: return
        if (state.value.processingPhoto || state.value.saving) return
        mutableState.value = state.value.copy(processingPhoto = true, cameraRequest = null)
        viewModelScope.launch {
            try {
                val path = requireNotNull(capture.getString("path"))
                val photoPath = path.substringBeforeLast('/') + "/" + path.substringAfterLast('/').replaceFirst("capture_", "photo_")
                requireNotNull(photoFiles).discardPhoto(photoPath)
                savedState.remove<Bundle>("capture")
                mutableState.value = state.value.copy(processingPhoto = false, waitingForCamera = false, pendingCapture = false, photoError = null)
            } catch (error: Exception) {
                mutableState.value = state.value.copy(processingPhoto = false, photoError = error.userMessage("拍照文件清理失败，请重试"))
            }
        }
    }

    /** 原照片只从表单中移除，保存时才删文件；新照片立即清理本次草稿。 */
    fun removePhoto(photo: PhotoDraft) {
        if (state.value.saving || state.value.processingPhoto || state.value.pendingCapture) return
        if (photo.id != 0L) { change(state.value.draft.copy(photos = state.value.draft.photos - photo)); return }
        mutableState.value = state.value.copy(processingPhoto = true, photoError = null)
        viewModelScope.launch {
            try {
                requireNotNull(photoFiles).discardPhoto(photo.relativePath)
                updateDraft(state.value.draft.copy(photos = state.value.draft.photos - photo))
                mutableState.value = state.value.copy(processingPhoto = false)
            } catch (error: Exception) {
                mutableState.value = state.value.copy(processingPhoto = false, photoError = error.userMessage("照片移除失败，请重试"))
            }
        }
    }

    /** 先清理当前会话再离开表单，放弃编辑不会触碰数据库原有照片。 */
    fun discard(onDone: () -> Unit) {
        if (state.value.saving || state.value.processingPhoto || state.value.pendingCapture) return
        mutableState.value = state.value.copy(saving = true)
        viewModelScope.launch {
            try {
                photoFiles?.discardSession(photoSession)
                mutableState.value = state.value.copy(saving = false)
                onDone()
            } catch (error: Exception) {
                mutableState.value = state.value.copy(saving = false, error = error.userMessage("图片草稿清理失败，请重试"))
            }
        }
    }
    /** 空金额参与待填合计；预览非法输入时不崩溃，保存时仍提供明确错误。 */
    private fun total(draft: ServiceDraft): Long? = try {
        sumAmountCent(draft.items.map { parseOptionalAmountCent(it.amount) })
    } catch (_: IllegalArgumentException) { null }
    /** Bundle 中仅放入系统支持的字符串与子 Bundle 列表，兼容进程重建。 */
    private fun persist(draft: ServiceDraft) {
        savedState["nextItemKey"] = maxOf(savedState.get<Long>("nextItemKey") ?: 0L, (draft.items.maxOfOrNull { it.key } ?: -1L) + 1)
        savedState["draft"] = Bundle().apply {
            putString("date", draft.date); putString("remark", draft.remark)
            putParcelableArrayList("items", ArrayList(draft.items.map { item -> Bundle().apply {
                putLong("key", item.key); putString("category", item.category.name); putString("name", item.name)
                putString("amount", item.amount); putString("remark", item.remark)
            } }))
            putParcelableArrayList("photos", ArrayList(draft.photos.map { photo -> Bundle().apply {
                putLong("id", photo.id); putString("path", photo.relativePath); putString("type", photo.type.name)
            } }))
        }
    }
    /** 显式读取平台 Bundle，枚举缺失时只回退展示分类，不改变已保存业务数据。 */
    @Suppress("DEPRECATION")
    private fun readDraft(): ServiceDraft = savedState.get<Bundle>("draft")?.run {
        ServiceDraft(getString("date") ?: todayServiceDate(), getParcelableArrayList<Bundle>("items").orEmpty().map {
            ServiceItemDraft(it.getLong("key"), ServiceCategory.entries.firstOrNull { category -> category.name == it.getString("category") }
                ?: ServiceCategory.OTHER, it.getString("name").orEmpty(), it.getString("amount").orEmpty(), it.getString("remark").orEmpty())
        }, getString("remark").orEmpty(), getParcelableArrayList<Bundle>("photos").orEmpty().map {
            PhotoDraft(it.getString("path").orEmpty(), PhotoType.entries.firstOrNull { type -> type.name == it.getString("type") }
                ?: PhotoType.OTHER, it.getLong("id"))
        })
    } ?: ServiceDraft()
}
