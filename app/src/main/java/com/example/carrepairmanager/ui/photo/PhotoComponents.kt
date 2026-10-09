package com.example.carrepairmanager.ui.photo

import androidx.activity.compose.BackHandler
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.example.carrepairmanager.R
import com.example.carrepairmanager.data.PhotoDraft
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.ui.components.DetailScaffold
import com.example.carrepairmanager.ui.components.ListEmptyHint
import com.example.carrepairmanager.ui.components.RecordError
import com.example.carrepairmanager.ui.displayName
import java.io.File
import kotlinx.coroutines.launch

/** 四个业务分类复用同一缩略图组件，横向列表只加载当前可见的小尺寸图片。 */
@Composable
fun PhotoSection(type: PhotoType, photos: List<PhotoDraft>, enabled: Boolean = true,
    onTakePhoto: (() -> Unit)? = null, onPhoto: (PhotoDraft) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${type.displayName()}（${photos.size} 张）", style = MaterialTheme.typography.titleMedium)
        if (photos.isEmpty() && onTakePhoto == null) Text("暂无照片", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onTakePhoto != null) item {
                OutlinedButton(onTakePhoto, Modifier.size(96.dp).testTag("take_${type.name}"), enabled = enabled) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(painterResource(R.drawable.ic_camera), null)
                        Text("拍照")
                    }
                }
            }
            itemsIndexed(photos, key = { _, photo -> photo.relativePath }) { index, photo ->
                PhotoImage(photo.relativePath, "查看${type.displayName()}照片${index + 1}",
                    Modifier.size(96.dp).clip(MaterialTheme.shapes.medium).clickable(enabled) { onPhoto(photo) })
            }
        }
    }
}

/** Coil 在后台解码；缩略图明确限制为 384px，界面创建 File 对象不读取磁盘。 */
@Composable
fun PhotoImage(path: String, description: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val context = LocalContext.current
    val request = remember(path, large, context) {
        ImageRequest.Builder(context).data(File(context.filesDir, path)).size(if (large) 1920 else 384).build()
    }
    var failed by remember(path) { mutableStateOf(false) }
    var loaded by remember(path) { mutableStateOf(false) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics(mergeDescendants = true) {
        stateDescription = if (failed) "图片加载失败" else if (loaded) "图片已加载" else "正在加载图片"
    }, contentAlignment = Alignment.Center) {
        AsyncImage(request, description, Modifier.fillMaxSize(), contentScale = if (large) ContentScale.Fit else ContentScale.Crop,
            placeholder = if (large) null else painterResource(R.drawable.ic_camera), error = if (large) null else painterResource(R.drawable.ic_camera),
            onSuccess = { failed = false; loaded = true }, onError = { failed = true; loaded = false; Log.w("PhotoImage", "本地照片加载失败", it.result.throwable) })
        if (failed && large) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_camera), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("图片不可用", color = MaterialTheme.colorScheme.error)
            Text("可返回服务详情重新拍照", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (failed) Text("图片失效", Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface).padding(4.dp),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 正式图片页与草稿预览共用滑动、缩放和二次确认；业务删除由对应 ViewModel 执行。 */
@Composable
fun PhotoGallery(photos: List<PhotoDraft>, initialPath: String, busy: Boolean, error: String?,
    onBack: () -> Unit, onDelete: (PhotoDraft) -> Unit, draft: Boolean = false, onRetry: (() -> Unit)? = null) {
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.relativePath == initialPath }.coerceAtLeast(0)) { photos.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var resetZoom by remember { mutableStateOf(0) }
    var deletingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val current = photos.getOrNull(pager.currentPage.coerceAtMost((photos.size - 1).coerceAtLeast(0)))
    LaunchedEffect(photos.size) {
        if (photos.isNotEmpty() && pager.currentPage >= photos.size) pager.scrollToPage(photos.lastIndex)
    }
    LaunchedEffect(pager.currentPage) { zoomed = false; resetZoom++ }
    BackHandler { if (!busy) onBack() }
    DetailScaffold("照片查看", onBack, !busy) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            if (error != null) Box(Modifier.padding(horizontal = 16.dp)) { RecordError(error, onRetry) }
            if (photos.isEmpty()) Box(Modifier.weight(1f)) { ListEmptyHint("暂无照片", "返回服务详情添加照片。") }
            else HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("photo_pager"),
                key = { photos[it].relativePath }, userScrollEnabled = !busy && !zoomed) { page ->
                ZoomablePhoto(photos[page].relativePath, "${photos[page].type.displayName()}照片${page + 1}",
                    pager.currentPage == page, resetZoom, !busy) { if (pager.currentPage == page) zoomed = it }
            }
            if (current != null) {
                Text("${current.type.displayName()}  ·  ${pager.currentPage.coerceAtMost(photos.lastIndex) + 1} / ${photos.size}",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                Text("双指缩放 / 双击放大，原尺寸下左右滑动", Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ scope.launch { pager.scrollToPage(pager.currentPage - 1) } }, enabled = !busy && pager.currentPage > 0) { Text("上一张") }
                    TextButton({ resetZoom++ }, enabled = !busy) { Text("重置缩放") }
                    TextButton({ scope.launch { pager.scrollToPage(pager.currentPage + 1) } }, enabled = !busy && pager.currentPage < photos.lastIndex) { Text("下一张") }
                }
                TextButton({ deletingPath = current.relativePath }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (draft) "移除此照片" else "删除此照片", color = MaterialTheme.colorScheme.error)
                }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
        }
    }
    val deleting = photos.firstOrNull { it.relativePath == deletingPath }
    if (deleting != null) AlertDialog(onDismissRequest = { deletingPath = null }, title = { Text(if (draft) "移除此照片？" else "删除此照片？") },
        text = { Text(if (draft && deleting.id != 0L) "保存服务后才会删除原照片；放弃修改会保留原照片。"
            else "对应照片文件也将删除，无法撤销。") },
        confirmButton = { TextButton({ deletingPath = null; onDelete(deleting) }, enabled = !busy) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ deletingPath = null }) { Text("取消") } })
}

/** 原尺寸允许翻页，放大后拖动图片；限制缩放与平移范围，避免图片被拖出屏幕。 */
@Composable
private fun ZoomablePhoto(path: String, description: String, active: Boolean, reset: Int, enabled: Boolean, onZoom: (Boolean) -> Unit) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(reset, active) { scale = 1f; offset = Offset.Zero; if (active) onZoom(false) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        val maxX = size.width * (scale - 1) / 2
        val maxY = size.height * (scale - 1) / 2
        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
        onZoom(scale > 1f)
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }
        .transformable(transform, canPan = { scale > 1f }, enabled = enabled)
        .pointerInput(path, enabled) {
            if (enabled) detectTapGestures(onDoubleTap = {
                scale = if (scale > 1f) 1f else 2.5f
                offset = Offset.Zero
                onZoom(scale > 1f)
            })
        }) {
        PhotoImage(path, description, Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
        }, large = true)
    }
}
