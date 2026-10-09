package com.example.carrepairmanager.ui.vehicle

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.carrepairmanager.R
import com.example.carrepairmanager.ui.components.ListEmptyHint
import com.example.carrepairmanager.ui.components.RecordError
import com.example.carrepairmanager.ui.components.RecordSearchField
import com.example.carrepairmanager.ui.components.VehicleSummaryCard
import com.example.carrepairmanager.ui.components.VehicleEntryActions
import com.example.carrepairmanager.ui.components.recordContentWidth

/** OCR 回填搜索框，沿用当前品牌、归档和排序筛选，不直接跳转车辆详情。 */
@Composable
fun VehicleListScreen(state: VehicleListUiState, onQuery: (String) -> Unit, onBrand: (String) -> Unit,
    onArchived: (Boolean) -> Unit, onAdd: () -> Unit, onVehicle: (Long) -> Unit, onMore: () -> Unit, onRetry: () -> Unit,
    onOcr: () -> Unit) {
    val brands = (state.brands + state.filters.brand).filter { it.isNotEmpty() }.distinct()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.recordContentWidth().testTag("vehicle_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { RecordSearchField(state.filters.query, onQuery) }
            item { VehicleEntryActions(onAdd, onOcr) }
            item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.filters.brand.isEmpty(), onClick = { onBrand("") }, label = { Text("全部") })
                brands.forEach { brand -> FilterChip(selected = state.filters.brand == brand, onClick = { onBrand(brand) }, label = { Text(brand) }) }
            } }
            item { FilterChip(selected = state.filters.includeArchived, onClick = { onArchived(!state.filters.includeArchived) },
                label = { Text("显示已归档车辆") }) }
            item { RecordError(state.error, onRetry) }
            if (state.loading && state.filters.query.isNotBlank()) item { Text("正在查询…") }
            if (!state.loading && state.entries.isEmpty() && state.error == null) item {
                ListEmptyHint(if (state.filters.query.isBlank() && state.filters.brand.isEmpty()) stringResource(R.string.vehicles_empty_title) else "未找到车辆",
                    stringResource(R.string.vehicles_empty_description))
            }
            items(state.entries, key = { it.vehicle.id }) { record ->
                VehicleSummaryCard(record.vehicle.plateNumber, record.vehicle.brand, record.vehicle.model, record.vehicle.color,
                    record.customer?.name.orEmpty(), record.vehicle.isArchived,
                    extra = record.lastServiceDate?.let { "最近到店：$it" } ?: "暂无服务记录", photoPath = record.photoRelativePath,
                    customerPhone = record.customer?.phone.orEmpty(),
                    onClick = { onVehicle(record.vehicle.id) })
            }
            if (state.hasMore) item { TextButton(onClick = onMore, Modifier.fillMaxWidth(), enabled = !state.loading) { Text("加载更多车辆") } }
        }
    }
}
