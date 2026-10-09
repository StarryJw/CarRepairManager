package com.example.carrepairmanager.ui.service

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import com.example.carrepairmanager.ui.components.DetailScaffold
import com.example.carrepairmanager.ui.components.ListEmptyHint
import com.example.carrepairmanager.ui.components.RecordError
import com.example.carrepairmanager.ui.components.VehicleSummaryCard
import com.example.carrepairmanager.ui.components.recordContentWidth
import com.example.carrepairmanager.ui.displayName
import com.example.carrepairmanager.ui.formatServiceAmount
import com.example.carrepairmanager.data.PhotoDraft
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.ui.photo.PhotoSection

/** 项目和合计分别展示金额或待填状态；删除必须二次确认且保护已有图片。 */
@Composable
fun ServiceDetailScreen(state: ServiceDetailUiState, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onRetry: () -> Unit, onPhoto: (Long) -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.busy) { /* 删除事务结束前保留页面，避免结果落在已离开的返回栈。 */ }
    DetailScaffold("服务详情", onBack, !state.busy) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.recordContentWidth().testTag("service_detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { RecordError(state.error, onRetry) }
                val detail = state.detail
                if (detail == null) {
                    if (!state.loading && state.error == null && !state.deleted) item { ListEmptyHint("服务记录不存在", "请返回车辆详情重新选择。") }
                } else {
                    item { Text(detail.order.serviceDate, Modifier.testTag("service_detail_ready"), style = MaterialTheme.typography.headlineSmall) }
                    state.vehicle?.let { record -> item {
                        VehicleSummaryCard(record.vehicle.plateNumber, record.vehicle.brand, record.vehicle.model, record.vehicle.color,
                            photoPath = detail.photos.firstOrNull { it.type != PhotoType.RECEIPT }?.relativePath)
                    } }
                    item { Text("服务项目", style = MaterialTheme.typography.titleMedium) }
                    if (detail.items.isEmpty()) item { ListEmptyHint("无服务项目", "本次服务金额为零，可通过编辑补充项目。") }
                    items(detail.items, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                Text(entry.category.displayName(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (entry.remark.isNotBlank()) Text(entry.remark)
                                Text(formatServiceAmount(entry.amountCent), Modifier.align(Alignment.End), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    item { Text("合计  ${formatServiceAmount(detail.order.totalAmountCent)}", Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall) }
                    if (detail.order.remark.isNotBlank()) item { Text("服务备注\n${detail.order.remark}") }
                    PhotoType.entries.forEach { type -> item(key = "photos_${type.name}") {
                        PhotoSection(type, detail.photos.filter { it.type == type }.map { PhotoDraft(it.relativePath, it.type, it.id) }, !state.busy) {
                            onPhoto(it.id)
                        }
                    } }
                    item { Button(onEdit, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("编辑服务") } }
                    item { TextButton({ confirmDelete = true }, Modifier.fillMaxWidth(), enabled = !state.busy) {
                        Text("删除本次服务", color = MaterialTheme.colorScheme.error)
                    } }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("确定删除本次服务记录？") },
        text = { Text("本次服务、所有项目及相关照片文件将被永久删除，无法撤销。") },
        confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } })
}
