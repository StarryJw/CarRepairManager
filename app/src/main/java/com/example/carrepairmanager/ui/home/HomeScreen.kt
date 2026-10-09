package com.example.carrepairmanager.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import com.example.carrepairmanager.ui.vehicle.VehicleListUiState
import com.example.carrepairmanager.ui.formatServiceAmount

/** OCR 回填搜索框沿用查车流程；最近到店明确区分待填金额与免费服务。 */
@Composable
fun HomeScreen(state: HomeUiState, search: VehicleListUiState, onQuery: (String) -> Unit, onAddVehicle: () -> Unit,
    onVehicle: (Long) -> Unit, onViewAll: () -> Unit, onRetry: () -> Unit, onSearchRetry: () -> Unit, onMoreSearch: () -> Unit,
    onOcr: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.recordContentWidth().testTag("home_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { RecordSearchField(search.filters.query, onQuery) }
            item { VehicleEntryActions(onAddVehicle, onOcr) }
            if (search.filters.query.isNotBlank()) {
                item { Text("搜索结果", style = MaterialTheme.typography.titleMedium) }
                if (search.loading) item { Text("正在查询…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { RecordError(search.error, onSearchRetry) }
                if (!search.loading && search.entries.isEmpty() && search.error == null) item {
                    ListEmptyHint("未找到车辆", "换个关键词，或新增车辆建立档案。")
                }
                items(search.entries, key = { "search_${it.vehicle.id}" }) { record ->
                    VehicleSummaryCard(record.vehicle.plateNumber, record.vehicle.brand, record.vehicle.model, record.vehicle.color,
                        record.customer?.name.orEmpty(), extra = record.lastServiceDate?.let { "最近到店：$it" } ?: "暂无服务记录",
                        photoPath = record.photoRelativePath, customerPhone = record.customer?.phone.orEmpty(),
                        onClick = { onVehicle(record.vehicle.id) })
                }
                if (search.hasMore) item { TextButton(onClick = onMoreSearch, Modifier.fillMaxWidth(), enabled = !search.loading) { Text("加载更多车辆") } }
            } else {
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近到店", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onViewAll) { Text("查看全部") }
                } }
                item { RecordError(state.error, onRetry) }
                if (!state.loading && state.recentServices.isEmpty() && state.error == null) item {
                    ListEmptyHint(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_description))
                }
                items(state.recentServices, key = { it.orderId }) { record ->
                    VehicleSummaryCard(record.plateNumber, record.brand, record.model, record.color,
                        record.customerName.orEmpty(), extra = record.serviceDate, amountLabel = formatServiceAmount(record.totalAmountCent),
                        photoPath = record.photoRelativePath, onClick = { onVehicle(record.vehicleId) })
                }
            }
        }
    }
}
