package com.example.carrepairmanager.ui.service

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.carrepairmanager.data.local.entity.ServiceCategory
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.data.PhotoDraft
import com.example.carrepairmanager.ui.photo.PhotoGallery
import com.example.carrepairmanager.ui.photo.PhotoSection
import com.example.carrepairmanager.ui.components.ChoiceField
import com.example.carrepairmanager.ui.components.DetailScaffold
import com.example.carrepairmanager.ui.components.FormExitGuard
import com.example.carrepairmanager.ui.components.ListEmptyHint
import com.example.carrepairmanager.ui.components.RecordError
import com.example.carrepairmanager.ui.components.VehicleSummaryCard
import com.example.carrepairmanager.ui.components.recordContentWidth
import com.example.carrepairmanager.ui.dateToPickerMillis
import com.example.carrepairmanager.ui.displayName
import com.example.carrepairmanager.ui.formatAmountCent
import com.example.carrepairmanager.ui.pickerMillisToDate
import com.example.carrepairmanager.ui.todayServiceDate

/** 服务表单展示草稿照片，相机启动和文件处理分别交给导航绑定与 ViewModel。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceEditScreen(state: ServiceEditUiState, editing: Boolean, onChange: (ServiceDraft) -> Unit, onAddItem: () -> Unit,
    onSave: () -> Unit, onBack: () -> Unit, onRetry: () -> Unit, onTakePhoto: (PhotoType) -> Unit,
    onRemovePhoto: (PhotoDraft) -> Unit, onProcessCapture: () -> Unit, onCancelCapture: () -> Unit) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    var viewerPath by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = state.draft
    val busy = state.saving || state.processingPhoto || state.pendingCapture
    val enabled = !state.loading && !busy && state.vehicle != null
    FormExitGuard(state.dirty, busy, onBack) { requestBack ->
        DetailScaffold(if (editing) "编辑服务" else "新增服务", requestBack, !busy) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.recordContentWidth().testTag("service_form"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.vehicle?.let { record -> item {
                        VehicleSummaryCard(record.vehicle.plateNumber, record.vehicle.brand, record.vehicle.model, record.vehicle.color,
                            photoPath = draft.photos.firstOrNull { it.type != PhotoType.RECEIPT }?.relativePath)
                    } }
                    item { OutlinedTextField(draft.date, { onChange(draft.copy(date = it)) }, Modifier.fillMaxWidth(), singleLine = true,
                        enabled = enabled, label = { Text("服务日期") }, supportingText = { Text("格式：yyyy-MM-dd") }) }
                    item { OutlinedButton({ calendar = true }, Modifier.fillMaxWidth(), enabled = enabled) { Text("选择日期") } }
                    item { Text("服务项目", style = MaterialTheme.typography.titleMedium) }
                    if (draft.items.isEmpty()) item { ListEmptyHint("尚未添加项目", "金额可稍后补填；免费项目请明确填写 0。") }
                    items(draft.items, key = { it.key }) { item ->
                        ServiceItemEditor(item, enabled, onChange = { updated ->
                            onChange(draft.copy(items = draft.items.map { if (it.key == item.key) updated else it }))
                        }, onRemove = { onChange(draft.copy(items = draft.items.filterNot { it.key == item.key })) })
                    }
                    item { OutlinedButton(onAddItem, Modifier.fillMaxWidth(), enabled = enabled) { Text("＋ 添加服务项目") } }
                    item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("合计", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(state.totalAmountCent?.let { "¥${formatAmountCent(it)}" }
                                ?: if (draft.items.any { it.amount.isBlank() }) "金额待填" else "待完善金额", style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    } }
                    item { OutlinedTextField(draft.remark, { onChange(draft.copy(remark = it)) }, Modifier.fillMaxWidth(),
                        enabled = enabled, minLines = 3, label = { Text("服务备注") }) }
                    item {
                        Text("服务照片", style = MaterialTheme.typography.titleMedium)
                        Text("照片随服务记录一起保存；放弃编辑会保留原有照片。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        if (state.processingPhoto) Text("正在处理照片…", color = MaterialTheme.colorScheme.primary)
                        RecordError(state.photoError)
                        if (state.pendingCapture && !state.processingPhoto) {
                            Text("本次拍照尚未处理，可继续处理已拍照片或取消。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onProcessCapture) { Text("继续处理照片") }
                            TextButton(onCancelCapture) { Text("取消本次拍照") }
                        }
                    }
                    PhotoType.entries.forEach { type -> item(key = "photos_${type.name}") {
                        PhotoSection(type, draft.photos.filter { it.type == type }, enabled, { onTakePhoto(type) }) { viewerPath = it.relativePath }
                    } }
                    item { RecordError(state.error, onRetry) }
                    item { Button(onSave, Modifier.fillMaxWidth(), enabled = enabled) { Text(if (state.saving) "正在保存…" else "保存服务记录") } }
                }
            }
        }
    }
    LaunchedEffect(draft.photos.size) { if (draft.photos.isEmpty()) viewerPath = null }
    viewerPath?.let { path ->
        if (draft.photos.isNotEmpty()) Dialog(onDismissRequest = { if (!busy) viewerPath = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            PhotoGallery(draft.photos, path, busy, state.photoError, { viewerPath = null }, onRemovePhoto, draft = true)
        }
    }
    if (calendar) {
        val density = LocalDensity.current
        val windowSize = LocalWindowInfo.current.containerSize
        val windowHeight = with(density) { windowSize.height.toDp() }
        val compactPicker = windowHeight < 600.dp || density.fontScale > 1.3f
        val picker = rememberDatePickerState(initialSelectedDateMillis = dateToPickerMillis(draft.date) ?: dateToPickerMillis(todayServiceDate()),
            initialDisplayMode = if (compactPicker) DisplayMode.Input else DisplayMode.Picker)
        // 横屏或大字体下日历网格会裁切，改用日期输入模式；旋转后恢复的弹窗也执行此规则。
        LaunchedEffect(compactPicker) { if (compactPicker) picker.displayMode = DisplayMode.Input }
        DatePickerDialog(onDismissRequest = { calendar = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { onChange(draft.copy(date = pickerMillisToDate(it))) }; calendar = false },
                enabled = picker.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { calendar = false }) { Text("取消") } }) {
            DatePicker(picker, showModeToggle = !compactPicker)
        }
    }
}

/** 项目卡片使用稳定草稿键定位；金额保留输入文本直到 ViewModel 精确转为整数分。 */
@Composable
private fun ServiceItemEditor(item: ServiceItemDraft, enabled: Boolean, onChange: (ServiceItemDraft) -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(item.name, { onChange(item.copy(name = it)) }, Modifier.fillMaxWidth().testTag("item_name_${item.key}"),
                enabled = enabled, singleLine = true, label = { Text("项目名称 *") })
            ChoiceField("服务分类", item.category.displayName(), ServiceCategory.entries.map { it.displayName() }, enabled) { selected ->
                onChange(item.copy(category = ServiceCategory.entries.first { it.displayName() == selected }))
            }
            OutlinedTextField(item.amount, { onChange(item.copy(amount = it)) }, Modifier.fillMaxWidth().testTag("item_amount_${item.key}"),
                enabled = enabled, singleLine = true, label = { Text("项目金额（元，选填）") },
                supportingText = { Text("留空为金额待填，免费项目请填写 0") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(item.remark, { onChange(item.copy(remark = it)) }, Modifier.fillMaxWidth(),
                enabled = enabled, label = { Text("项目备注（选填）") })
            TextButton(onRemove, Modifier.align(Alignment.End), enabled = enabled) { Text("移除项目", color = MaterialTheme.colorScheme.error) }
        }
    }
}
