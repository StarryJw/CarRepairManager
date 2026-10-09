package com.example.carrepairmanager.ui.settings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carrepairmanager.CarRepairApplication
import com.example.carrepairmanager.data.backup.BackupOperation
import com.example.carrepairmanager.data.backup.BackupRepository
import com.example.carrepairmanager.data.backup.BackupSettings
import com.example.carrepairmanager.data.backup.BackupScope
import com.example.carrepairmanager.data.backup.PreparedRestore
import com.example.carrepairmanager.data.backup.RemoteBackup
import com.example.carrepairmanager.data.backup.backupMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 应用密码只保存在当前 ViewModel 内存，禁止进入 Bundle 或 SavedStateHandle。 */
data class BackupConnectionDraft(val url: String = "", val directory: String = "", val username: String = "", val password: String = "")

/** 本地恢复与云端版本由数据层验证，界面只展示已展开批次及对应来源的反馈。 */
data class BackupUiState(val settings: BackupSettings? = null, val connection: BackupConnectionDraft = BackupConnectionDraft(),
    val dirty: Boolean = false, val working: Boolean = false, val operation: BackupOperation = BackupOperation(),
    val error: String? = null, val preview: PreparedRestore? = null, val remote: List<RemoteBackup> = emptyList(),
    val remoteLoaded: Boolean = false, val restored: Boolean = false, val intervalHours: Long = 0,
    val actionScope: BackupScope = BackupScope.LOCAL, val remoteVisibleCount: Int = 0, val remotePageSize: Int = 0) {
    val busy: Boolean get() = working || operation.busy
    val visibleRemote: List<RemoteBackup> get() = remote.take(remoteVisibleCount)
    val hasMoreRemote: Boolean get() = remoteLoaded && remoteVisibleCount < remote.size
}

/** 保存配置、确认恢复与自动调度集中处理，所有文件和网络操作交给 Repository。 */
class BackupViewModel(private val app: CarRepairApplication, private val savedState: SavedStateHandle,
    repositoryOverride: BackupRepository? = null) : ViewModel() {
    private val repository = viewModelScope.async(Dispatchers.IO) { repositoryOverride ?: app.backupRepository }
    private val mutableState = MutableStateFlow(BackupUiState(connection = BackupConnectionDraft(
        savedState["backup_url"] ?: "", savedState["backup_directory"] ?: "", savedState["backup_username"] ?: ""),
        dirty = savedState["backup_dirty"] ?: false))
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val repo = repository.await()
                mutableState.update { it.copy(intervalHours = repo.config.intervalHours, remotePageSize = repo.config.cloudPageSize) }
                launch { repo.settings.settings.catch { error -> mutableState.update { it.copy(error = error.backupMessage(), actionScope = BackupScope.CLOUD) } }.collect { preferences ->
                    mutableState.update { old -> old.copy(settings = preferences,
                        connection = if (old.dirty) old.connection else BackupConnectionDraft(preferences.serverUrl, preferences.directory, preferences.username)) }
                } }
                launch { repo.operation.collect { operation -> mutableState.update { it.copy(operation = operation) } } }
                savedState.get<String>("backup_restore_stage")?.let { name ->
                    perform { val preview = repo.reopenRestore(name); mutableState.update { it.copy(preview = preview) } }
                }
            } catch (error: Exception) { mutableState.update { it.copy(error = error.backupMessage(), actionScope = BackupScope.CLOUD) } }
        }
    }

    /** 地址、目录和账号草稿可恢复，密码修改只留在内存中。 */
    fun change(connection: BackupConnectionDraft) {
        if (state.value.busy) return
        savedState["backup_url"] = connection.url; savedState["backup_directory"] = connection.directory
        savedState["backup_username"] = connection.username; savedState["backup_dirty"] = true
        mutableState.update { it.copy(connection = connection, dirty = true, error = null) }
    }

    /** 保存成功清空密码输入框，留空表示继续使用该账号已有的加密凭据。 */
    fun saveConnection() = perform(BackupScope.CLOUD) {
        val draft = state.value.connection
        repository.await().saveConnection(draft.url, draft.directory, draft.username, draft.password)
        savedState["backup_dirty"] = false
        mutableState.update { it.copy(dirty = false, connection = it.connection.copy(password = ""), remoteLoaded = false,
            remote = emptyList(), remoteVisibleCount = 0) }
        app.backupScheduler.sync(repository.await().settings.settings.first())
    }

    /** 测试使用已经保存的配置，避免测试和实际上传连接到不同服务器。 */
    fun testConnection() = perform(BackupScope.CLOUD) { repository.await().testConnection() }
    /** 系统文件选择完成后才发起本地导出。 */
    fun export(uri: Uri) = perform { repository.await().export(uri) }
    /** 每次手动请求生成一个独立完整云端版本。 */
    fun backupNow() = perform(BackupScope.CLOUD) { repository.await().backupNow() }

    /** 新文件校验成功后才替换预览，旧预览的私有暂存随之清理。 */
    fun prepareRestore(uri: Uri) = perform { setPreview(repository.await().prepareRestore(uri)) }
    /** 云端下载完成后进入相同的确认预览。 */
    fun prepareRemote(backup: RemoteBackup) = perform(BackupScope.CLOUD) { setPreview(repository.await().prepareRemoteRestore(backup)) }

    /** 只在用户点击确认按钮后替换数据，完成后通知导航清理旧表单返回首页。 */
    fun confirmRestore() = perform {
        val preview = state.value.preview ?: return@perform
        repository.await().restore(preview)
        savedState.remove<String>("backup_restore_stage")
        mutableState.update { it.copy(preview = null, restored = true) }
    }

    /** 取消确认不会修改数据库，只删除暂存文件。 */
    fun cancelRestore() = perform {
        state.value.preview?.let { repository.await().discard(it) }
        savedState.remove<String>("backup_restore_stage")
        mutableState.update { it.copy(preview = null) }
    }

    /** 主动开启自动上传后同步唯一周期任务，关闭时取消任务。 */
    fun setAutomatic(enabled: Boolean) = perform(BackupScope.CLOUD) {
        val repo = repository.await()
        repo.settings.setAutomatic(enabled)
        app.backupScheduler.sync(repo.settings.settings.first())
    }

    /** 网络偏好改动及时更新调度约束。 */
    fun setUnmetered(required: Boolean) = perform(BackupScope.CLOUD) {
        val repo = repository.await()
        repo.settings.setUnmetered(required)
        app.backupScheduler.sync(repo.settings.settings.first())
    }

    /** 沿用服务器版本倒序，刷新成功后回到首批，失败时保留上次已获取的版本。 */
    fun loadRemote() = perform(BackupScope.CLOUD) {
        val repo = repository.await()
        val versions = repo.remoteBackups()
        mutableState.update { it.copy(remote = versions, remoteLoaded = true, remotePageSize = repo.config.cloudPageSize,
            remoteVisibleCount = minOf(repo.config.cloudPageSize, versions.size)) }
    }

    /** 只有用户点击才追加一批已获取的版本，展开列表不会重新请求或下载备份。 */
    fun loadMoreRemote() {
        mutableState.update { old ->
            if (old.busy || old.dirty || old.preview != null || !old.hasMoreRemote) old
            else old.copy(remoteVisibleCount = old.remoteVisibleCount + minOf(old.remotePageSize, old.remote.size - old.remoteVisibleCount))
        }
    }

    /** 系统文件选择器缺失或拒绝访问时显示可操作说明。 */
    fun filePickerFailed() { mutableState.update { it.copy(error = "无法打开系统文件选择器，请检查系统文档应用", actionScope = BackupScope.LOCAL) } }
    /** 恢复后的导航事件只消费一次。 */
    fun consumeRestored() { mutableState.update { it.copy(restored = false) } }

    /** 已校验的缓存目录名写入任务状态，任务重建后仍需再次确认。 */
    private suspend fun setPreview(prepared: PreparedRestore) {
        state.value.preview?.let { repository.await().discard(it) }
        savedState["backup_restore_stage"] = prepared.directory.name
        mutableState.update { it.copy(preview = prepared) }
    }

    /** 立即设置忙碌状态抑制重复提交，连同来源保留中文错误；取消继续传播。 */
    private fun perform(scope: BackupScope = BackupScope.LOCAL, block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(working = true, error = null, actionScope = scope) }
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(error = error.backupMessage()) } }
            finally { mutableState.update { it.copy(working = false) } }
        }
    }
}
