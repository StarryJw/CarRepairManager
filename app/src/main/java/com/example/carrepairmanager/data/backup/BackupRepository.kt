package com.example.carrepairmanager.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.PhotoFileStore
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** 手动、本地与自动操作共用互斥锁，状态携带来源以便界面正确归属反馈。 */
data class BackupOperation(val busy: Boolean = false, val phase: String = "", val message: String = "", val error: String? = null,
    val scope: BackupScope = BackupScope.LOCAL)

/** ViewModel 与 Worker 共用的备份入口，文件和网络细节留在数据层。 */
class BackupRepository(context: Context, private val records: CarRepairRepository, photoFiles: PhotoFileStore,
    val config: BackupConfig, val settings: BackupSettingsRepository,
    private val webDav: WebDavClient = WebDavClient(config), private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val context = context.applicationContext
    private val cache = File(context.cacheDir, "backup")
    private val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    private val archive = BackupArchive(cache, config, photoFiles, version, nowMillis)
    private val mutex = Mutex()
    private val mutableOperation = MutableStateFlow(BackupOperation())
    val operation = mutableOperation.asStateFlow()

    /** 先取得一致快照，再写入用户选择的 SAF 文档；文件复制始终在 IO 线程执行。 */
    suspend fun export(uri: Uri) = runOperation(BackupScope.LOCAL, "正在生成本地备份") {
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        try {
            progress("正在导出备份")
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("无法打开导出位置")
            output.use { stream -> snapshot.file.inputStream().use { input -> archive.copyLimited(input, stream, config.maxArchiveBytes) } }
            recordResult { settings.exportSucceeded(nowMillis()) }
            complete("本地备份已导出")
        } finally { snapshot.file.delete() }
    }

    /** 本地备份先复制到私有暂存区，确认前不改变任何业务记录。 */
    suspend fun prepareRestore(uri: Uri): PreparedRestore = runOperation(BackupScope.LOCAL, "正在校验本地备份") {
        incoming { file ->
            val input = context.contentResolver.openInputStream(uri) ?: throw BackupException("无法读取备份文件")
            input.use { stream -> file.outputStream().use { output -> archive.copyLimited(stream, output, config.maxArchiveBytes) } }
            archive.inspect(file)
        }.also { complete("备份验证通过，请核对恢复范围") }
    }

    /** 任务重建只恢复预览，不自动执行恢复，缓存被系统清理时重新选择文件。 */
    suspend fun reopenRestore(name: String): PreparedRestore = runOperation(BackupScope.LOCAL, "正在校验恢复预览") { archive.reopen(name) }

    /** 二次确认后再次校验，事务完成前保留所有原图，事务失败保留原档案。 */
    suspend fun restore(prepared: PreparedRestore) = runOperation(BackupScope.LOCAL, "正在恢复数据与照片") {
        val verified = archive.reopen(prepared.directory.name)
        // 开始替换后等待事务与结果记录一起完成，页面取消不能把已提交恢复误报为失败。
        withContext(NonCancellable) {
            records.restoreBackup(verified)
            recordResult { settings.restored() }
            discard(verified)
            complete("备份已恢复，车辆、服务和照片已更新")
        }
    }

    /** 用户取消或替换预览只清理本次私有暂存目录。 */
    suspend fun discard(prepared: PreparedRestore) = withContext(NonCancellable + Dispatchers.IO) {
        require(prepared.directory.parentFile?.canonicalFile == cache.canonicalFile && Regex("restore_[a-f0-9-]{36}").matches(prepared.directory.name)) { "恢复暂存路径无效" }
        if (!prepared.directory.deleteRecursively()) Log.w("BackupRepository", "恢复暂存清理未完成")
    }

    /** 配置变更与上传串行，成功时间和摘要不会误记到另一台服务器。 */
    suspend fun saveConnection(url: String, directory: String, username: String, password: String) = runOperation(BackupScope.CLOUD, "正在保存备份配置") {
        settings.saveConnection(url, directory, username, password)
        complete("备份配置已保存")
    }

    /** 连接测试只检查服务器和目录权限，不发送本地业务数据。 */
    suspend fun testConnection() = runOperation(BackupScope.CLOUD, "正在测试 WebDAV 连接") {
        webDav.testConnection(settings.credentials())
        complete("WebDAV 连接成功")
    }

    /** 自动任务跳过相同快照，手动任务总是创建一个独立版本；网络操作不持有业务写入锁。 */
    suspend fun backupNow(automatic: Boolean = false) = runOperation(BackupScope.CLOUD, "正在生成云端备份") {
        val preferences = settings.settings.first()
        if (automatic && !preferences.automatic) { complete("自动备份已关闭"); return@runOperation }
        val credentials = settings.credentials()
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        try {
            if (automatic && snapshot.fingerprint == preferences.lastFingerprint) { complete("数据无变更，无需上传"); return@runOperation }
            progress("正在上传云端备份")
            webDav.upload(credentials, snapshot.file)
            recordResult { settings.cloudSucceeded(nowMillis(), snapshot.fingerprint) }
            complete("云端备份成功：${snapshot.file.name}")
        } finally { snapshot.file.delete() }
    }

    /** 返回完整版本列表，上传未完成的临时文件不展示。 */
    suspend fun remoteBackups(): List<RemoteBackup> = runOperation(BackupScope.CLOUD, "正在读取云端备份") {
        webDav.list(settings.credentials()).also { complete(if (it.isEmpty()) "云端暂无完整备份" else "云端备份列表已更新") }
    }

    /** 云端版本下载后沿用同一本地验证与确认流程，下载不自动恢复。 */
    suspend fun prepareRemoteRestore(backup: RemoteBackup): PreparedRestore = runOperation(BackupScope.CLOUD, "正在下载并校验云端备份") {
        incoming { file ->
            webDav.download(settings.credentials(), backup, file, archive)
            archive.inspect(file)
        }.also { complete("云端备份验证通过，请核对恢复范围") }
    }

    /** 失败下载与本地输入的临时 ZIP 无论成功与否均清理，不留下伪完整备份。 */
    private suspend fun <T> incoming(block: suspend (File) -> T): T {
        require(cache.isDirectory || cache.mkdirs()) { "无法创建备份暂存目录" }
        val file = File(cache, "incoming_${UUID.randomUUID()}.zip")
        return try { block(file) } finally { file.delete() }
    }

    /** 互斥覆盖整个操作，失败与取消保留来源，避免后台云端反馈污染本地卡片。 */
    private suspend fun <T> runOperation(scope: BackupScope, phase: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) throw BackupException("已有备份或恢复正在执行，请稍后重试", true)
        mutableOperation.value = BackupOperation(busy = true, phase = phase, scope = scope)
        try { block() }
        catch (error: CancellationException) { mutableOperation.value = BackupOperation(message = "操作已中断，可重新执行", scope = scope); throw error }
        catch (error: Exception) {
            val message = error.backupMessage()
            mutableOperation.value = BackupOperation(error = message, scope = scope)
            recordResult { settings.failed(message, scope) }
            throw error
        } finally {
            mutableOperation.value = mutableOperation.value.copy(busy = false, phase = "")
            mutex.unlock()
        }
    }

    /** 打包、导出与上传状态统一反馈，但不暴露文件内容或凭据。 */
    private fun progress(phase: String) { mutableOperation.value = mutableOperation.value.copy(phase = phase) }
    private fun complete(message: String) { mutableOperation.value = mutableOperation.value.copy(message = message) }

    /** 已成功导出或恢复的数据不能因状态记录失败误报为未完成；保留明确的次级错误。 */
    private suspend fun recordResult(block: suspend () -> Unit) = withContext(NonCancellable) {
        try { block() } catch (_: Exception) {
            mutableOperation.value = mutableOperation.value.copy(error = "操作结果已保留，但备份状态记录未能保存")
        }
    }
}

/** JSON 库与底层 IO 的原始错误可能包含数据，统一转换成可安全展示的中文说明。 */
internal fun Throwable.backupMessage(): String = when (this) {
    is CancellationException -> throw this
    is BackupException -> message ?: "备份操作失败"
    is SerializationException -> "备份结构化数据损坏或缺少必要字段"
    is ZipException -> "ZIP 备份文件损坏或不完整"
    is IllegalArgumentException -> message ?: "备份内容或配置无效"
    is SecurityException -> "文件访问权限失效，请重新选择文件"
    is IOException -> "文件或网络操作失败，请检查存储空间、网络后重试"
    else -> "备份操作失败，请重试"
}
