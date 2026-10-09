package com.example.carrepairmanager.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** 排序偏好只影响车辆列表，历史服务仍按服务日期倒序。 */
enum class VehicleSort { RECENT_SERVICE, CREATED_AT }

/** 配置与任务内导航状态分开存储，默认照片质量沿用已验收的标准档位。 */
data class AppSettings(val vehicleSort: VehicleSort = VehicleSort.RECENT_SERVICE, val imageQuality: Int = 70)

/** 单个应用 DataStore 保存设置；异常向 ViewModel 传播以便显示失败反馈。 */
class AppSettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val sortKey = stringPreferencesKey("vehicle_sort")
    private val qualityKey = intPreferencesKey("image_quality")
    val settings = store.data.map { preferences ->
        AppSettings(
            vehicleSort = VehicleSort.entries.firstOrNull { it.name == preferences[sortKey] } ?: VehicleSort.RECENT_SERVICE,
            imageQuality = preferences[qualityKey]?.takeIf { it in listOf(50, 70, 90) } ?: 70,
        )
    }

    /** 写入完成后 Flow 才更新界面，避免保存失败仍显示已生效的选项。 */
    suspend fun setVehicleSort(sort: VehicleSort) { store.edit { it[sortKey] = sort.name } }

    /** 压缩档位限定为界面提供的值，新拍照片直接复用此配置。 */
    suspend fun setImageQuality(quality: Int) {
        require(quality in listOf(50, 70, 90)) { "图片质量选项无效" }
        store.edit { it[qualityKey] = quality }
    }
}
