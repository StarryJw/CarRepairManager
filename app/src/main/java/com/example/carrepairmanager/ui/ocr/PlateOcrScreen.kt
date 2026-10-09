package com.example.carrepairmanager.ui.ocr

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.carrepairmanager.data.normalizePlateNumber
import com.example.carrepairmanager.ui.components.DetailScaffold
import com.example.carrepairmanager.ui.components.RecordError
import com.example.carrepairmanager.ui.components.recordContentWidth
import java.util.Locale

/** 只展示拍照、候选和人工修订；确认后回填原输入框，配置不出现在 App 界面。 */
@Composable
fun PlateOcrScreen(state: PlateOcrUiState, onBack: () -> Unit,
    onCapture: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onPlate: (String) -> Unit,
    onCandidate: (Int) -> Unit, onConfirm: () -> Unit, fillingVehicle: Boolean = false) {
    BackHandler(onBack = onBack)
    DetailScaffold("识别车牌", onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.recordContentWidth().testTag("ocr_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("点击拍照识别后，本次车牌照片将上传至百度智能云。请先在系统相机中确认照片。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Text(if (fillingVehicle) "确认后填入新增车辆的车牌号，其余表单内容会保留。"
                    else "确认后填入原页面搜索框，可继续筛选和选择车辆。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.loadingConfiguration) item { Text("正在准备车牌识别…") }
                else if (!state.configured) item { Text("车牌识别暂不可用，可返回手动输入。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Button(onCapture, Modifier.fillMaxWidth().testTag("ocr_capture"), enabled = state.configured && !state.busy && !state.pendingCapture) {
                    Text("拍照并识别车牌")
                } }
                if (state.busy) item { Text("正在处理，请稍候…", color = MaterialTheme.colorScheme.primary) }
                if (state.pendingCapture && !state.busy) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("有一张待处理照片。确认相机已交付照片后可继续识别，或取消本次拍照。")
                    OutlinedButton(onResume, Modifier.fillMaxWidth()) { Text("继续识别照片") }
                    TextButton(onCancel, Modifier.fillMaxWidth()) { Text("取消本次拍照") }
                } }
                if (state.candidates.isNotEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("识别结果，请核对", style = MaterialTheme.typography.titleMedium)
                    if (state.candidates.size > 1) {
                        Text("识别到多张车牌，请选择本次车辆。")
                        state.candidates.forEachIndexed { index, candidate ->
                            FilterChip(state.selectedCandidate == index, { onCandidate(index) }, enabled = !state.busy,
                                label = { Text(candidate.plateNumber) }, modifier = Modifier.testTag("ocr_candidate_$index"))
                        }
                    }
                    val candidate = state.candidates.getOrNull(state.selectedCandidate)
                    Text(candidate?.confidence?.let { "原始识别置信度 ${String.format(Locale.ROOT, "%.0f%%", it * 100)}" }
                        ?: "识别置信度未提供，请仔细核对", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (candidate != null && normalizePlateNumber(state.plateNumber) != candidate.plateNumber) Text("车牌已手动修改，确认后填入修改值。")
                } }
                item { OutlinedTextField(state.plateNumber, onPlate, Modifier.fillMaxWidth().testTag("ocr_plate"),
                    enabled = !state.busy && !state.pendingCapture, singleLine = true, label = { Text("确认 / 修改车牌号") },
                    placeholder = { Text("也可手动输入，例如：冀A12345") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)) }
                item { RecordError(state.error) }
                item { Button(onConfirm, Modifier.fillMaxWidth().testTag("ocr_confirm"), enabled = !state.busy && !state.pendingCapture && state.plateNumber.isNotBlank()) {
                    Text(if (fillingVehicle) "确认并填入车牌号" else "确认并填入搜索框")
                } }
                item { Text("识别结果必须人工确认；也可返回原页面手动输入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
