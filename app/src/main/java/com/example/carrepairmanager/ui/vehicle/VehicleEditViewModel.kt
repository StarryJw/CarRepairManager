package com.example.carrepairmanager.ui.vehicle

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.normalizePlateNumber
import com.example.carrepairmanager.data.local.VehicleWithCustomer
import com.example.carrepairmanager.data.local.entity.Customer
import com.example.carrepairmanager.data.local.entity.Vehicle
import com.example.carrepairmanager.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 车辆草稿只包含表单简单文本，可由 Bundle 安全恢复。 */
data class VehicleDraft(val plateNumber: String = "", val name: String = "", val phone: String = "", val brand: String = "",
    val model: String = "", val color: String = "", val remark: String = "")

/** 加载和保存分开标记，失败保留用户输入以便修正。 */
data class VehicleEditUiState(val draft: VehicleDraft = VehicleDraft(), val loading: Boolean = false, val saving: Boolean = false,
    val dirty: Boolean = false, val error: String? = null, val savedId: Long? = null, val hasCustomer: Boolean = false)

/** 新增与编辑共用表单，已确认保存的数据直接进入 Repository 事务。 */
class VehicleEditViewModel(private val repository: CarRepairRepository, private val savedState: SavedStateHandle) : ViewModel() {
    val vehicleId: Long = savedState["vehicleId"] ?: 0L
    private var existing: VehicleWithCustomer? = null
    private val mutableState = MutableStateFlow(VehicleEditUiState(draft = readDraft(), loading = vehicleId != 0L,
        dirty = savedState["dirty"] ?: false, savedId = savedState["savedId"]))
    val state = mutableState.asStateFlow()
    init { if (vehicleId != 0L) load() }

    /** 编辑加载只填充尚未存在的草稿，不能覆盖锁屏或系统重建后恢复的输入。 */
    fun load() {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val record = requireNotNull(repository.getVehicle(vehicleId)) { "车辆不存在" }
                existing = record
                val draft = if (savedState.contains("draft")) readDraft() else record.run {
                    VehicleDraft(vehicle.plateNumber, customer?.name.orEmpty(), customer?.phone.orEmpty(), vehicle.brand,
                        vehicle.model, vehicle.color, vehicle.remark)
                }
                persist(draft)
                mutableState.value = mutableState.value.copy(draft = draft, loading = false, hasCustomer = record.customer != null)
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, error = error.userMessage("车辆读取失败，请重试"))
            }
        }
    }

    /** 字段修改立即保存草稿，不写入业务数据库。 */
    fun change(draft: VehicleDraft) {
        if (state.value.saving) return
        persist(draft); savedState["dirty"] = true
        mutableState.value = state.value.copy(draft = draft, dirty = true, error = null)
    }

    /** OCR 只更新新增表单的车牌字段，保留车主、手机号、车型等已经输入的草稿。 */
    fun applyRecognizedPlate(plateNumber: String) {
        if (vehicleId != 0L || state.value.saving || state.value.loading) return
        change(state.value.draft.copy(plateNumber = normalizePlateNumber(plateNumber)))
    }

    /** 阻止连续点击保存产生重复行，失败时保留草稿和中文反馈。 */
    fun save() {
        if (state.value.saving || state.value.loading || state.value.savedId != null) return
        val draft = state.value.draft
        mutableState.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val customer = if (draft.name.isBlank() && draft.phone.isBlank() && existing?.customer == null) null else
                    (existing?.customer ?: Customer()).copy(name = draft.name.trim(), phone = draft.phone.trim())
                val vehicle = (existing?.vehicle ?: Vehicle(plateNumber = draft.plateNumber)).copy(
                    plateNumber = draft.plateNumber, brand = draft.brand.trim(), model = draft.model.trim(), color = draft.color.trim(), remark = draft.remark.trim())
                val id = if (vehicleId == 0L) repository.createVehicle(vehicle, customer) else {
                    require(existing != null) { "请先成功读取车辆" }
                    repository.updateVehicle(vehicle, customer); vehicleId
                }
                savedState["savedId"] = id; savedState["dirty"] = false
                mutableState.value = state.value.copy(saving = false, dirty = false, savedId = id)
            } catch (error: Exception) {
                mutableState.value = state.value.copy(saving = false, error = error.userMessage("车辆保存失败，请重试"))
            }
        }
    }

    /** 保存结果作为可消费状态，避免重组重复导航。 */
    fun consumeSaved() { savedState.remove<Long>("savedId"); mutableState.value = state.value.copy(savedId = null) }

    /** 保存到系统支持的 Bundle，而不是不可序列化的任意 Kotlin 对象。 */
    private fun persist(draft: VehicleDraft) {
        savedState["draft"] = Bundle().apply {
            putString("plate", draft.plateNumber); putString("name", draft.name); putString("phone", draft.phone)
            putString("brand", draft.brand); putString("model", draft.model); putString("color", draft.color); putString("remark", draft.remark)
        }
    }
    /** 已恢复草稿优先，OCR 确认值仅用于新增表单首次预填，不自动保存。 */
    private fun readDraft(): VehicleDraft = savedState.get<Bundle>("draft")?.run {
        VehicleDraft(getString("plate").orEmpty(), getString("name").orEmpty(), getString("phone").orEmpty(),
            getString("brand").orEmpty(), getString("model").orEmpty(), getString("color").orEmpty(), getString("remark").orEmpty())
    } ?: VehicleDraft(plateNumber = if (vehicleId == 0L) savedState.get<String>("plateNumber").orEmpty() else "")
}
