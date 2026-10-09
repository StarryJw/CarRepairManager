package com.example.carrepairmanager.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.carrepairmanager.R
import com.example.carrepairmanager.data.VehicleSort
import com.example.carrepairmanager.data.backup.backupFileName
import com.example.carrepairmanager.ui.components.ChoiceField
import com.example.carrepairmanager.ui.components.RecordError
import com.example.carrepairmanager.ui.components.recordContentWidth

/** 沿用设置页分组，文件选择器只传递 URI，实际 IO 全部由 ViewModel 调用数据层执行。 */
@Composable
fun SettingsScreen(state: SettingsUiState, onSort: (VehicleSort) -> Unit, onQuality: (Int) -> Unit, onRetry: () -> Unit,
    backupModel: BackupViewModel) {
    val backup by backupModel.state.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(backupModel::export) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(backupModel::prepareRestore) }
    BackupRestoreDialog(backup, backupModel)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.recordContentWidth().testTag("settings_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("数据管理", style = MaterialTheme.typography.titleMedium) }
            item { LocalBackupCard(backup, {
                try { export.launch(backupFileName()) }
                catch (_: ActivityNotFoundException) { backupModel.filePickerFailed() }
                catch (_: SecurityException) { backupModel.filePickerFailed() }
            }, {
                try { restore.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
                catch (_: ActivityNotFoundException) { backupModel.filePickerFailed() }
                catch (_: SecurityException) { backupModel.filePickerFailed() }
            }) }
            item { Text("应用设置", style = MaterialTheme.typography.titleMedium) }
            item { SettingsCard {
                ChoiceField("默认排序", if (state.settings.vehicleSort == VehicleSort.RECENT_SERVICE) "最近到店优先" else "新建车辆优先",
                    listOf("最近到店优先", "新建车辆优先"), !state.busy) {
                    onSort(if (it == "最近到店优先") VehicleSort.RECENT_SERVICE else VehicleSort.CREATED_AT)
                }
                val qualityLabels = mapOf("省空间 50%" to 50, "标准 70%" to 70, "高清 90%" to 90)
                ChoiceField("图片压缩质量", qualityLabels.entries.first { it.value == state.settings.imageQuality }.key,
                    qualityLabels.keys.toList(), !state.busy) { onQuality(qualityLabels.getValue(it)) }
                Text("用于之后拍摄的照片；最长边 1920 像素，已有照片保持原质量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            cloudBackupItems(backup, backupModel)
            item { RecordError(state.error, onRetry) }
            item { SettingsCard {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Text("修车记录 1.0\n车辆档案与服务记录保存在本机。")
            } }
            item { SettingsCard {
                Text(stringResource(R.string.settings_local_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.settings_local_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

/** 设置分组复用相同的白色卡片与可滚动内容间距。 */
@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
