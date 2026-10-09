package com.example.carrepairmanager.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 只有存在未保存修改时才确认退出；保存中阻止返回避免重复创建。 */
@Composable
fun FormExitGuard(dirty: Boolean, saving: Boolean, onBack: () -> Unit, content: @Composable (() -> Unit) -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val requestBack = { if (!saving) { if (dirty) confirm = true else onBack() } }
    BackHandler { requestBack() }
    content(requestBack)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("放弃未保存的修改？") },
        text = { Text("返回后，本次未保存的内容将被丢弃。") }, confirmButton = {
            TextButton(onClick = { confirm = false; onBack() }) { Text("放弃修改") }
        }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("继续编辑") } })
}

/** 品牌和颜色提供常用建议，同时允许直接输入自定义值。 */
@Composable
fun SuggestionField(label: String, value: String, options: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text(label) }, trailingIcon = {
                TextButton(onClick = { expanded = true }, enabled = enabled) { Text("选择") }
            })
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            DropdownMenuItem(text = { Text("未填写") }, onClick = { onChange(""); expanded = false })
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false }) }
        }
    }
}

/** 稳定枚举选项用按钮打开菜单，避免只读文本框吞掉点击。 */
@Composable
fun ChoiceField(label: String, value: String, options: List<String>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$value  ⌄") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false }) }
            }
        }
    }
}
