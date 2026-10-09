package com.example.carrepairmanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.carrepairmanager.data.backup.BackupScope
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 本地按钮保持在设置页顶部，与 WebDAV 配置无关，未配置账号也能导出或恢复。 */
@Composable
internal fun LocalBackupCard(state: BackupUiState, onExport: () -> Unit, onRestore: () -> Unit) {
    BackupCard(Modifier.testTag("backup_local")) {
        Text("本地备份", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onExport, enabled = !state.busy && state.preview == null, modifier = Modifier.weight(1f).testTag("backup_export")) { Text("导出备份") }
            OutlinedButton(onClick = onRestore, enabled = !state.busy && state.preview == null, modifier = Modifier.weight(1f).testTag("backup_import")) { Text("恢复备份") }
        }
        Text("完整保存档案与照片。卸载前请导出到应用外部。", style = MaterialTheme.typography.bodySmall)
        Text("最近导出：${backupTime(state.settings?.lastExportAt ?: 0)}", style = MaterialTheme.typography.bodySmall)
        BackupFeedback(state, BackupScope.LOCAL)
    }
}

/** 云端版本只渲染已展开的批次；独立 LazyColumn 行保持长文件名与大字体可滚动。 */
internal fun LazyListScope.cloudBackupItems(state: BackupUiState, model: BackupViewModel) {
    item(key = "webdav_title") { Text("云端备份", style = MaterialTheme.typography.titleMedium) }
    item(key = "webdav_connection") {
        BackupCard(Modifier.testTag("backup_connection")) {
            Text("WebDAV 配置", style = MaterialTheme.typography.titleMedium)
            Text("坚果云使用账号邮箱和专用应用密码；也支持其他 HTTPS WebDAV 服务。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(state.connection.url, { model.change(state.connection.copy(url = it)) }, label = { Text("WebDAV 地址") },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup_url"))
            OutlinedTextField(state.connection.directory, { model.change(state.connection.copy(directory = it)) }, label = { Text("专用备份目录") },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup_directory"))
            OutlinedTextField(state.connection.username, { model.change(state.connection.copy(username = it)) }, label = { Text("WebDAV 账号") },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup_username"))
            OutlinedTextField(state.connection.password, { model.change(state.connection.copy(password = it)) }, label = { Text("专用应用密码") },
                placeholder = { if (state.settings?.passwordSaved == true) Text("已保存；留空沿用当前账号凭据") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("backup_password"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = model::saveConnection, enabled = !state.busy && state.settings != null) { Text("保存配置") }
                OutlinedButton(onClick = model::testConnection, enabled = cloudReady(state)) { Text("测试连接") }
            }
            if (state.dirty) Text("配置修改后请先保存，再测试或上传。", style = MaterialTheme.typography.bodySmall)
            BackupFeedback(state, BackupScope.CLOUD, showErrors = false)
        }
    }
    item(key = "webdav_actions") {
        BackupCard(Modifier.testTag("backup_upload")) {
            Text("上传与自动备份", style = MaterialTheme.typography.titleMedium)
            Button(onClick = model::backupNow, enabled = cloudReady(state), modifier = Modifier.fillMaxWidth()) { Text("立即备份到云端") }
            Text("最近云端成功：${backupTime(state.settings?.lastSuccessAt ?: 0)}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动备份")
                    Text("每 ${state.intervalHours} 小时检查，有变更才上传。系统可能延迟执行。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.settings?.automatic == true, model::setAutomatic, enabled = cloudReady(state), modifier = Modifier.testTag("backup_automatic"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动备份仅用非计费网络", Modifier.weight(1f))
                Switch(state.settings?.unmetered != false, model::setUnmetered, enabled = !state.busy && state.settings != null)
            }
            Text("主动上传或开启自动备份后，客户联系方式、车牌、服务记录和照片将上传至你的 WebDAV。所有历史版本保留，不自动清理。", style = MaterialTheme.typography.bodySmall)
            BackupFeedback(state, BackupScope.CLOUD)
        }
    }
    item(key = "webdav_versions") {
        BackupCard {
            Text("云端恢复", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = model::loadRemote, enabled = cloudReady(state)) { Text("刷新云端备份") }
            if (state.remoteLoaded && state.remote.isEmpty()) Text("云端暂无完整备份")
            Text("选择一个版本下载并校验，再确认整份替换本机档案。", style = MaterialTheme.typography.bodySmall)
            Text("历史版本很多时，可用网盘客户端下载指定 ZIP，再从本地恢复。", style = MaterialTheme.typography.bodySmall)
        }
    }
    items(state.visibleRemote, key = { "cloud_${it.name}" }) { backup ->
        BackupCard {
            Text(backup.name, style = MaterialTheme.typography.bodySmall)
            Text(if (backup.size > 0) "${(backup.size + 1023) / 1024} KB" else "大小由下载时核对", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { model.prepareRemote(backup) }, enabled = cloudReady(state)) { Text("恢复此版本") }
        }
    }
    if (state.hasMoreRemote) item(key = "webdav_more") {
        TextButton(onClick = model::loadMoreRemote, enabled = cloudReady(state),
            modifier = Modifier.fillMaxWidth().testTag("backup_remote_more")) { Text("加载更多") }
    }
}

/** 恢复确认始终展示替换范围；关闭弹窗或取消不会写入数据库。 */
@Composable
internal fun BackupRestoreDialog(state: BackupUiState, model: BackupViewModel) {
    val preview = state.preview ?: return
    val manifest = preview.manifest
    AlertDialog(onDismissRequest = { if (!state.busy) model.cancelRestore() },
        title = { Text("确认整份恢复？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("备份时间：${manifest.createdAt}")
            Text("客户 ${manifest.customerCount} · 车辆 ${manifest.vehicleCount}\n服务 ${manifest.orderCount} · 项目 ${manifest.itemCount} · 照片 ${manifest.photoCount}")
            Text("将替换本机全部已保存档案，不合并数据。建议先导出当前档案。恢复后返回首页，未保存表单不保留。", color = MaterialTheme.colorScheme.error)
            BackupFeedback(state, BackupScope.LOCAL)
        } },
        confirmButton = { TextButton(onClick = model::confirmRestore, enabled = !state.busy) { Text("确认替换并恢复") } },
        dismissButton = { TextButton(onClick = model::cancelRestore, enabled = !state.busy) { Text("取消") } })
}

/** 按来源展示执行结果；云端错误只在上传卡片显示，配置卡片仅展示操作进度与成功信息。 */
@Composable
private fun BackupFeedback(state: BackupUiState, scope: BackupScope, showErrors: Boolean = true) {
    if (state.busy) {
        if ((state.working && state.actionScope == scope) || (state.operation.busy && state.operation.scope == scope)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(state.operation.phase.takeIf { state.operation.busy && state.operation.scope == scope } ?: "正在处理")
            }
        }
    } else {
        if (state.operation.scope == scope && state.operation.message.isNotEmpty()) Text(state.operation.message, style = MaterialTheme.typography.bodySmall)
        if (showErrors) {
            val error = state.error?.takeIf { state.actionScope == scope }
                ?: state.operation.error?.takeIf { state.operation.scope == scope }
                ?: state.settings?.takeIf { it.lastErrorScope == scope }?.lastError?.takeIf { it.isNotEmpty() }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** 待确认的恢复和未保存的配置不能同时触发上传或重复恢复。 */
private fun cloudReady(state: BackupUiState) = !state.busy && !state.dirty && state.preview == null && state.settings?.configured == true

/** 与现有设置卡片使用相同的视觉与间距。 */
@Composable
private fun BackupCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** 成功时间采用设备当地时区，未成功时不展示虚构的最近时间。 */
private fun backupTime(timestamp: Long) = if (timestamp <= 0) "暂无" else
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA).format(Date(timestamp))
