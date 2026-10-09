package com.example.carrepairmanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.carrepairmanager.R
import com.example.carrepairmanager.ui.vehicleDescription
import com.example.carrepairmanager.ui.photo.PhotoImage

/** 详情和表单共用返回栏；危险操作可放入更多菜单，主入口底栏只在根页面显示。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(title: String, onBack: () -> Unit, backEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack, enabled = backEnabled) { Icon(painterResource(R.drawable.ic_back), "返回") }
    }, actions = actions) }, content = content)
}

/** 车牌始终醒目；最近服务可显示金额或待填状态，纯车辆卡片不显示服务金额。 */
@Composable
fun VehicleSummaryCard(plate: String, brand: String, model: String, color: String, customerName: String = "",
    archived: Boolean = false, extra: String? = null, amountLabel: String? = null, photoPath: String? = null,
    customerPhone: String = "", onClick: (() -> Unit)? = null) {
    val cardContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (photoPath == null) Icon(painterResource(R.drawable.ic_vehicle), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                else PhotoImage(photoPath, "车辆照片", Modifier.size(56.dp).clip(MaterialTheme.shapes.small))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(plate, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(vehicleDescription(brand, model, color), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (customerName.isNotBlank()) Text(customerName, style = MaterialTheme.typography.bodyMedium)
            if (customerPhone.isNotBlank()) Text("手机号：$customerPhone", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (archived) Text("已归档", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (extra != null) Text(extra, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (amountLabel != null) Text(amountLabel, Modifier.align(Alignment.End),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
    val modifier = Modifier.fillMaxWidth()
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    if (onClick == null) Card(modifier, colors = colors) { cardContent() }
    else Card(onClick = onClick, modifier = modifier, colors = colors) { cardContent() }
}

/** 输入保存在 ViewModel；键盘搜索收起键盘，清空直接恢复当前列表或最近到店。 */
@Composable
fun RecordSearchField(query: String, onQueryChanged: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    OutlinedTextField(value = query, onValueChange = onQueryChanged, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("搜索车牌 / 车主 / 手机号") }, leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChanged("") }) {
            Icon(painterResource(R.drawable.ic_clear), "清空搜索", tint = MaterialTheme.colorScheme.primary)
        } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }))
}

/** 新增和拍照查车共享一行，避免横屏根页面的空状态被额外操作行挤出首屏。 */
@Composable
fun VehicleEntryActions(onAdd: () -> Unit, onOcr: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onAdd, Modifier.weight(1f)) { Text("＋ 新增车辆") }
        OutlinedButton(onOcr, Modifier.weight(1f)) { Text("拍车牌搜索") }
    }
}

/** 内联错误保留当前表单，重试操作由对应 ViewModel 完成。 */
@Composable
fun RecordError(message: String?, onRetry: (() -> Unit)? = null) {
    if (message == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
    }
}

/** 空提示用于 LazyColumn 的单个条目，避免嵌套无限高度滚动容器。 */
@Composable
fun ListEmptyHint(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 表单和详情控制宽屏阅读宽度，窄屏保留完整可用空间。 */
fun Modifier.recordContentWidth(): Modifier = this.widthIn(max = 640.dp).fillMaxWidth()
