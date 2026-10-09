package com.example.carrepairmanager.ui.vehicle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.carrepairmanager.data.normalizePlateNumber
import com.example.carrepairmanager.data.local.VehicleDeletionSummary
import com.example.carrepairmanager.ui.components.DetailScaffold
import com.example.carrepairmanager.ui.components.ListEmptyHint
import com.example.carrepairmanager.ui.components.RecordError
import com.example.carrepairmanager.ui.components.VehicleSummaryCard
import com.example.carrepairmanager.ui.components.recordContentWidth
import com.example.carrepairmanager.ui.formatServiceAmount

/** 归档保留历史；永久删除仅在已归档档案的更多菜单中提供，并要求输入车牌确认。 */
@Composable
fun VehicleDetailScreen(state: VehicleDetailUiState, onBack: () -> Unit, onEdit: () -> Unit, onAddService: () -> Unit,
    onService: (Long) -> Unit, onArchive: (Boolean) -> Unit, onMore: () -> Unit, onRetry: () -> Unit, onDelete: (String) -> Unit) {
    var archiveDialog by rememberSaveable { mutableStateOf(false) }
    var moreMenu by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val historyScroll = rememberLazyListState()
    val vehicle = state.record?.vehicle
    BackHandler(enabled = state.busy) { /* 删除完成前保留详情，避免结果落在已离开的导航栈。 */ }
    if (confirmDelete && vehicle?.isArchived == true && !state.deleted) {
        VehicleDeleteConfirmation(vehicle.plateNumber, vehicle.normalizedPlateNumber, state.deletionSummary, state.busy, state.error,
            onDelete, { confirmDelete = false })
        return
    }
    DetailScaffold("车辆详情", onBack, !state.busy, actions = {
        if (vehicle?.isArchived == true && !state.deleted) Box {
            TextButton({ moreMenu = true }, enabled = !state.busy) { Text("更多操作") }
            // 危险操作进入确认页面前关闭菜单，不让弹出窗口保留旧页面的焦点。
            if (moreMenu) DropdownMenu(expanded = true, onDismissRequest = { moreMenu = false }) {
                DropdownMenuItem(text = { Text("永久删除车辆", color = MaterialTheme.colorScheme.error) },
                    onClick = { moreMenu = false; confirmDelete = true }, enabled = !state.busy)
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.recordContentWidth().testTag("vehicle_detail"), state = historyScroll,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { RecordError(state.error, onRetry) }
                val record = state.record
                if (record == null) {
                    if (!state.loading && !state.busy && !state.deleted && state.error == null)
                        item { ListEmptyHint("车辆不存在", "请返回车辆列表重新选择。") }
                } else {
                    item { VehicleSummaryCard(record.vehicle.plateNumber, record.vehicle.brand, record.vehicle.model, record.vehicle.color,
                        archived = record.vehicle.isArchived, photoPath = state.history.firstNotNullOfOrNull { it.photoRelativePath }) }
                    item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(record.customer?.name?.takeIf { it.isNotBlank() } ?: "车主未填写", style = MaterialTheme.typography.titleMedium)
                            Text(record.customer?.phone?.takeIf { it.isNotBlank() } ?: "手机号未填写", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (record.vehicle.remark.isNotBlank()) Text(record.vehicle.remark)
                            OutlinedButton(onEdit, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("编辑车辆") }
                        }
                    } }
                    item { Button(onAddService, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("＋ 新增服务") } }
                    item { Text("历史服务", style = MaterialTheme.typography.titleMedium) }
                    if (state.history.isEmpty()) item { ListEmptyHint("暂无服务记录", "点击“新增服务”记录本次到店。") }
                    items(state.history, key = { it.order.id }) { entry ->
                        Card(onClick = { onService(entry.order.id) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(entry.order.serviceDate, style = MaterialTheme.typography.titleMedium)
                                Text(entry.itemSummary.ifBlank { "无服务项目" })
                                Text(if (entry.photoCount == 0) "无照片" else "${entry.photoCount} 张照片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatServiceAmount(entry.order.totalAmountCent), Modifier.align(Alignment.End),
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    if (state.hasMore) item { TextButton(onMore, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("加载更多历史") } }
                    item { OutlinedButton({ archiveDialog = true }, Modifier.fillMaxWidth(), enabled = !state.busy) {
                        Text(if (record.vehicle.isArchived) "恢复车辆" else "归档车辆")
                    } }
                }
            }
        }
    }
    if (archiveDialog && state.record != null) {
        val archived = state.record.vehicle.isArchived
        AlertDialog(onDismissRequest = { archiveDialog = false }, title = { Text(if (archived) "恢复这辆车？" else "归档这辆车？") },
            text = { Text(if (archived) "恢复后将重新显示在默认车辆列表。" else "归档后从默认列表隐藏，历史服务完整保留；可在归档筛选中恢复。") },
            confirmButton = { TextButton(onClick = { archiveDialog = false; onArchive(!archived) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { archiveDialog = false }) { Text("取消") } })
    }
}

/** 确认使用现有页面框架；说明独立滚动，输入与按钮固定，重建后保留确认文本。 */
@Composable
private fun VehicleDeleteConfirmation(plate: String, normalizedPlate: String, summary: VehicleDeletionSummary?, busy: Boolean,
    error: String?, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var confirmationPlate by rememberSaveable { mutableStateOf("") }
    val matches = normalizePlateNumber(confirmationPlate) == normalizedPlate
    BackHandler { if (!busy) onDismiss() }
    DetailScaffold("删除确认", onDismiss, !busy) { padding ->
        // 沿用车辆表单的边距处理，键盘出现时抬高确认控件，避免底部按钮被遮挡。
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.recordContentWidth().fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("永久删除车辆？", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (summary == null) "正在读取删除范围…" else
                        "将永久删除「$plate」、${summary.serviceCount} 次服务记录和 ${summary.photoCount} 张照片，以及这些服务的全部项目和本地照片文件。此操作无法直接撤销。")
                    Text("客户信息及其名下其他车辆保留。云端旧备份保留，主动恢复旧备份会恢复当时的档案。")
                }
                OutlinedTextField(confirmationPlate, { confirmationPlate = it }, Modifier.fillMaxWidth().testTag("vehicle_delete_plate"),
                    label = { Text("输入车牌确认") }, singleLine = true, enabled = !busy,
                    isError = confirmationPlate.isNotBlank() && !matches,
                    supportingText = { Text(if (confirmationPlate.isNotBlank() && !matches) "车牌不匹配" else "请输入 $plate") })
                RecordError(error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss, Modifier.weight(1f), enabled = !busy) { Text("取消") }
                    TextButton({ onDelete(confirmationPlate) }, Modifier.weight(2f), enabled = !busy && matches && summary != null,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(if (busy) "正在删除…" else "确认永久删除")
                    }
                }
            }
        }
    }
}
