package com.example.carrepairmanager

import android.app.Application
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.AppSettingsRepository
import com.example.carrepairmanager.data.local.CarRepairDatabase
import com.example.carrepairmanager.data.PhotoFileStore
import com.example.carrepairmanager.data.backup.BackupConfig
import com.example.carrepairmanager.data.backup.BackupRepository
import com.example.carrepairmanager.data.backup.BackupSettingsRepository
import com.example.carrepairmanager.data.backup.BackupScheduler
import com.example.carrepairmanager.data.ocr.OcrConfig
import com.example.carrepairmanager.data.ocr.OcrRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 应用范围共享 Room、图片存储和 Repository，页面不直接执行文件 IO。 */
class CarRepairApplication : Application() {
    val database: CarRepairDatabase by lazy { CarRepairDatabase.build(this) }
    val photoFileStore: PhotoFileStore by lazy { PhotoFileStore(this) }
    val repository: CarRepairRepository by lazy { CarRepairRepository(database, photoFileStore) }
    val settingsRepository: AppSettingsRepository by lazy { AppSettingsRepository(this) }
    val backupConfig: BackupConfig by lazy { BackupConfig.load(this) }
    val backupSettingsRepository: BackupSettingsRepository by lazy { BackupSettingsRepository(this, backupConfig) }
    val backupRepository: BackupRepository by lazy { BackupRepository(this, repository, photoFileStore, backupConfig, backupSettingsRepository) }
    val backupScheduler: BackupScheduler by lazy { BackupScheduler(this, backupConfig) }
    val ocrRepository: OcrRepository by lazy { OcrRepository(this, OcrConfig.load(this)) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 配置文件或应用升级后同步已有调度；默认关闭时不创建任何自动上传任务。 */
    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            try {
                val settings = backupSettingsRepository.settings.first()
                if (settings.automatic) backupScheduler.sync(settings)
            } catch (_: Exception) { android.util.Log.w("CarRepairApplication", "自动备份调度未完成，请在设置页检查配置") }
        }
    }
}
