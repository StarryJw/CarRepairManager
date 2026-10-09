package com.example.carrepairmanager.data.backup

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.utils.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.carrepairmanager.CarRepairApplication
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 检查真实 WorkManager 队列与约束，不触发手机任务或生产网络上传。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CarRepairApplication::class)
class BackupSchedulerTest {
    private val app get() = RuntimeEnvironment.getApplication() as CarRepairApplication
    private lateinit var config: BackupConfig
    private lateinit var scheduler: BackupScheduler

    /** 测试调度使用同步执行器，自动任务读取的真实偏好仍是默认关闭。 */
    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        config = BackupConfig.load(app)
        scheduler = BackupScheduler(app, config)
    }

    /** 反复开启只有一个唯一周期任务，关闭取消，不再保留可执行上传任务。 */
    @Test
    fun repeatedSchedulingKeepsOnePeriodicTaskAndDisableCancels() {
        val settings = BackupSettings(config.webDavUrl, config.remoteDirectory, "fixture@example.com", passwordSaved = true, automatic = true)
        scheduler.sync(settings); scheduler.sync(settings)
        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork(BackupScheduler.WORK_NAME).get()
        assertEquals(1, work.size)
        assertEquals(NetworkType.UNMETERED, work.first().constraints.requiredNetworkType)
        assertEquals(config.intervalHours * 60 * 60 * 1000, work.first().periodicityInfo!!.repeatIntervalMillis)
        scheduler.sync(settings.copy(automatic = false))
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(BackupScheduler.WORK_NAME).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    /** 切换允许计费网络时更新同一任务的约束；未配置账号不能安排自动上传。 */
    @Test
    fun networkPolicyUpdatesExistingTaskAndUnconfiguredStateHasNoWork() {
        val settings = BackupSettings(config.webDavUrl, config.remoteDirectory, "fixture@example.com", passwordSaved = true, automatic = true)
        scheduler.sync(settings)
        val id = WorkManager.getInstance(app).getWorkInfosForUniqueWork(BackupScheduler.WORK_NAME).get().single().id
        scheduler.sync(settings.copy(unmetered = false))
        val updated = WorkManager.getInstance(app).getWorkInfosForUniqueWork(BackupScheduler.WORK_NAME).get().single()
        assertEquals(id, updated.id); assertEquals(NetworkType.CONNECTED, updated.constraints.requiredNetworkType)
        scheduler.sync(settings.copy(username = "", passwordSaved = false))
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(BackupScheduler.WORK_NAME).get().all { it.state == WorkInfo.State.CANCELLED })
    }
}
