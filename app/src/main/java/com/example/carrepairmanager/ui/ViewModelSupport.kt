package com.example.carrepairmanager.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException

/** 复用 Navigation 提供的 ViewModelStore 与参数，保持草稿属于对应返回栈页面。 */
@Composable
inline fun <reified T : ViewModel> recordViewModel(noinline create: (SavedStateHandle) -> T): T =
    viewModel(factory = viewModelFactory { initializer { create(createSavedStateHandle()) } })

/** 协程取消必须继续传播；预期输入错误保留中文说明，底层错误反馈为业务失败。 */
internal fun Throwable.userMessage(fallback: String): String {
    if (this is CancellationException) throw this
    return if (this is IllegalArgumentException) message ?: fallback else fallback
}
