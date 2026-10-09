package com.example.carrepairmanager.data.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.carrepairmanager.CarRepairApplication
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** 唯一周期任务合并重复调度，时间与网络默认值来自统一配置文件。 */
class BackupScheduler(private val context: Context, private val config: BackupConfig) {
    /** UPDATE 保留已有周期的执行进度，关闭时取消排队或正在运行的自动任务。 */
    fun sync(settings: BackupSettings) {
        val work = WorkManager.getInstance(context)
        if (!settings.automatic || !settings.configured) { work.cancelUniqueWork(WORK_NAME); return }
        val request = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(config.intervalHours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.backoffSeconds, TimeUnit.SECONDS)
            .addTag(WORK_NAME).build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object { const val WORK_NAME = "car_repair_automatic_backup" }
}

/** 共享 Repository 互斥、变更检测与错误反馈，系统取消继续传播，临时失败有限退避重试。 */
class AutomaticBackupWorker @JvmOverloads constructor(context: Context, parameters: WorkerParameters,
    private val repositoryOverride: BackupRepository? = null) : CoroutineWorker(context, parameters) {
    /** 测试可注入本机 HTTPS 仓库，生产由 WorkManager 的两参数构造使用应用共享实例。 */
    override suspend fun doWork(): Result {
        val app = applicationContext as CarRepairApplication
        val repository = repositoryOverride ?: app.backupRepository
        if (!repository.settings.settings.first().automatic) return Result.success()
        return try { repository.backupNow(automatic = true); Result.success() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val retryable = (error is BackupException && error.retryable) || (error is IOException && error !is BackupException)
            if (retryable && runAttemptCount + 1 < repository.config.maxAttempts) Result.retry() else Result.failure()
        }
    }
}
