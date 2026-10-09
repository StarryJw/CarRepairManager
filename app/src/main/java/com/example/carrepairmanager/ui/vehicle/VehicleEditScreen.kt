package com.example.carrepairmanager.ui.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.carrepairmanager.ui.components.DetailScaffold
import com.example.carrepairmanager.ui.components.FormExitGuard
import com.example.carrepairmanager.ui.components.RecordError
import com.example.carrepairmanager.ui.components.SuggestionField
import com.example.carrepairmanager.ui.components.recordContentWidth

/** 新增车辆可拍照回填车牌，其他字段继续保留草稿，保存仍由用户明确触发。 */
@Composable
fun VehicleEditScreen(state: VehicleEditUiState, editing: Boolean, onChange: (VehicleDraft) -> Unit,
    onSave: () -> Unit, onBack: () -> Unit, onRetry: () -> Unit, onOcr: () -> Unit) {
    FormExitGuard(state.dirty, state.saving, onBack) { requestBack ->
        DetailScaffold(if (editing) "编辑车辆" else "新增车辆", requestBack, !state.saving) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                val draft = state.draft
                val enabled = !state.loading && !state.saving
                LazyColumn(Modifier.recordContentWidth().testTag("vehicle_form"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("车牌号必填，其余信息可稍后补充。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { OutlinedTextField(draft.plateNumber, { onChange(draft.copy(plateNumber = it)) }, Modifier.fillMaxWidth(),
                        enabled = enabled, singleLine = true, label = { Text("车牌号 *") }, placeholder = { Text("例如：冀A12345") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)) }
                    if (!editing) item { OutlinedButton(onOcr, Modifier.fillMaxWidth().testTag("vehicle_ocr"), enabled = enabled) { Text("拍照填入车牌号") } }
                    item { OutlinedTextField(draft.name, { onChange(draft.copy(name = it)) }, Modifier.fillMaxWidth(),
                        enabled = enabled, singleLine = true, label = { Text("车主姓名") }) }
                    item { OutlinedTextField(draft.phone, { onChange(draft.copy(phone = it)) }, Modifier.fillMaxWidth(),
                        enabled = enabled, singleLine = true, label = { Text("手机号") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)) }
                    if (state.hasCustomer) item { Text("联系人修改会同步到该客户名下的其他车辆。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { SuggestionField("品牌", draft.brand, listOf("宝马", "丰田", "五菱", "本田", "大众", "奔驰", "比亚迪", "吉利", "长安", "奥迪", "其他"), enabled) {
                        onChange(draft.copy(brand = it))
                    } }
                    item { OutlinedTextField(draft.model, { onChange(draft.copy(model = it)) }, Modifier.fillMaxWidth(),
                        enabled = enabled, singleLine = true, label = { Text("车型") }) }
                    item { SuggestionField("颜色", draft.color, listOf("白色", "黑色", "银色", "灰色", "蓝色", "红色", "绿色", "黄色", "其他"), enabled) {
                        onChange(draft.copy(color = it))
                    } }
                    item { OutlinedTextField(draft.remark, { onChange(draft.copy(remark = it)) }, Modifier.fillMaxWidth(),
                        enabled = enabled, minLines = 3, label = { Text("车辆备注") }) }
                    item { RecordError(state.error, if (editing) onRetry else null) }
                    item { Button(onSave, Modifier.fillMaxWidth(), enabled = enabled) { Text(if (state.saving) "正在保存…" else "保存车辆") } }
                }
            }
        }
    }
}
