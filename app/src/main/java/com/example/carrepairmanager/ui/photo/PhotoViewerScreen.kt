package com.example.carrepairmanager.ui.photo

import androidx.compose.runtime.Composable
import com.example.carrepairmanager.data.PhotoDraft
import com.example.carrepairmanager.ui.components.DetailScaffold

/** 元数据加载完成再创建图库，确保点击任意缩略图都从正确页开始。 */
@Composable
fun PhotoViewerScreen(state: PhotoViewerUiState, onBack: () -> Unit, onDelete: (PhotoDraft) -> Unit, onRetry: () -> Unit) {
    if (state.loading) DetailScaffold("照片查看", onBack) { }
    else PhotoGallery(state.photos, state.initialPath, state.busy, state.error, onBack, onDelete, onRetry = onRetry)
}
