package com.example.carrepairmanager.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.example.carrepairmanager.data.local.entity.PhotoType
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 新照片只持有草稿路径；已有照片保留数据库 ID，放弃表单时不会误删原文件。 */
data class PhotoDraft(val relativePath: String, val type: PhotoType, val id: Long = 0)

/** 相机只获得单个草稿文件的临时 content URI，状态恢复使用相对路径。 */
data class PhotoCapture(val relativePath: String, val uri: Uri)

/** 统一在 IO 线程处理私有图片；文件名与数据库主键分离，避免编辑覆盖旧照片。 */
class PhotoFileStore(context: Context, private val maxEdge: Int = 1920) {
    private val context = context.applicationContext
    private val root = context.filesDir

    init { require(maxEdge in 1440..1920) { "图片最长边应在 1440～1920 像素之间" } }

    /** 原图暂存私有 files 目录，避免外部相机运行期间系统清理 cache 导致照片丢失。 */
    suspend fun prepareCapture(session: String): PhotoCapture = withContext(Dispatchers.IO) {
        requireSession(session)
        val path = "photo_drafts/$session/capture_${UUID.randomUUID()}.jpg"
        val target = file(path)
        makeParent(target)
        check(target.createNewFile()) { "无法创建拍照文件" }
        PhotoCapture(path, FileProvider.getUriForFile(context, "${context.packageName}.photos", target))
    }

    /** 进程恢复后重建相同 URI，不新建或覆盖相机正在写入的文件。 */
    suspend fun captureUri(path: String): Uri = withContext(Dispatchers.IO) {
        require(path.startsWith("photo_drafts/")) { "拍照路径无效" }
        FileProvider.getUriForFile(context, "${context.packageName}.photos", file(path))
    }

    /** 先采样解码再缩放、矫正 EXIF；重编码 JPEG 不复制原图的定位等元数据。 */
    suspend fun compressCapture(path: String, type: PhotoType, quality: Int): PhotoDraft = withContext(Dispatchers.IO) {
        require(quality in listOf(50, 70, 90)) { "图片质量选项无效" }
        require(path.startsWith("photo_drafts/") && File(path).name.startsWith("capture_")) { "拍照路径无效" }
        val source = file(path)
        require(source.isFile && source.length() > 0) { "未获得拍照图片，请重新拍照" }
        val outputPath = path.substringBeforeLast('/') + "/" + source.name.replaceFirst("capture_", "photo_")
        val output = file(outputPath)
        // 已完整压缩的文件可复用，恢复中重复收到结果不会产生第二张照片。
        if (output.isFile && output.length() > 0) return@withContext PhotoDraft(outputPath, type)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "拍摄图片无法读取，请重新拍照" }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmaps = mutableListOf<Bitmap>()
        val pending = File(output.parentFile, "${output.name}.pending")
        try {
            val decoded = source.inputStream().use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("图片解码失败")
            bitmaps += decoded
            val edge = maxOf(decoded.width, decoded.height)
            val scaled = if (edge <= maxEdge) decoded else Bitmap.createScaledBitmap(decoded,
                (decoded.width.toLong() * maxEdge / edge).toInt().coerceAtLeast(1),
                (decoded.height.toLong() * maxEdge / edge).toInt().coerceAtLeast(1), true).also { bitmaps += it }
            val matrix = orientationMatrix(ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            val oriented = if (matrix.isIdentity) scaled else Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true).also { bitmaps += it }
            pending.outputStream().use { stream ->
                if (!oriented.compress(Bitmap.CompressFormat.JPEG, quality, stream)) throw IOException("图片压缩失败")
                stream.fd.sync()
            }
            if (!pending.renameTo(output)) throw IOException("图片保存失败")
            PhotoDraft(outputPath, type)
        } finally {
            bitmaps.distinct().forEach { it.recycle() }
            deleteFile(pending)
        }
    }

    /** 保存服务时复制已压缩草稿，失败仍可继续编辑，绝不消耗唯一原件。 */
    suspend fun saveDraft(orderId: Long, path: String): String = withContext(Dispatchers.IO) {
        require(orderId > 0 && path.startsWith("photo_drafts/") && File(path).name.startsWith("photo_")) { "图片草稿无效" }
        val source = file(path)
        require(source.isFile && source.length() > 0) { "照片草稿已丢失，请移除后重新拍照" }
        val destinationPath = "images/order_$orderId/${source.name}"
        val destination = file(destinationPath)
        makeParent(destination)
        require(!destination.exists()) { "照片已保存，请重新打开服务记录" }
        val pending = File(destination.parentFile, "${destination.name}.pending")
        try {
            source.inputStream().use { input -> pending.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            if (!pending.renameTo(destination)) throw IOException("图片保存失败")
        } finally { deleteFile(pending) }
        destinationPath
    }

    /** 备份只读取已保存图片，沿用已有路径边界检查。 */
    fun savedPhotoFile(path: String): File {
        require(path.startsWith("images/")) { "备份图片路径无效" }
        return file(path)
    }

    /** 恢复使用新 UUID 避免与旧图库冲突，数据库回滚后 reconcile 会清理未引用的新图。 */
    suspend fun copyRestoredPhoto(orderId: Long, staging: File, path: String): String = withContext(Dispatchers.IO) {
        require(orderId > 0 && path.startsWith("images/order_$orderId/")) { "恢复照片归属无效" }
        val source = File(staging, path).canonicalFile
        require(source.path.startsWith(staging.canonicalPath + File.separator) && source.isFile && source.length() > 0) { "备份图片缺失" }
        val newPath = "images/order_$orderId/photo_${UUID.randomUUID()}.jpg"
        val target = file(newPath)
        makeParent(target)
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
        newPath
    }

    /** 先暂移到可恢复目录再提交数据库删除；缺失文件不阻止用户清理失效记录。 */
    suspend fun stageRemoval(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.distinct().forEach { path ->
            require(path.startsWith("images/")) { "已保存照片路径无效" }
            val source = file(path)
            val staged = trashFile(path)
            if (source.exists()) {
                require(source.isFile && !staged.exists()) { "图片文件状态异常，请重试" }
                makeParent(staged)
                if (!source.renameTo(staged)) throw IOException("图片无法删除，请重试")
            }
        }
    }

    /** 回滚或进程中断后按 Room 引用恢复暂移文件，提交成功后清理废弃文件和未提交的新图。 */
    suspend fun reconcile(referenced: Set<String>) = withContext(Dispatchers.IO) {
        referenced.forEach { require(it.startsWith("images/")) { "已保存照片路径无效" }; file(it) }
        val trash = File(root, "photo_trash")
        if (trash.exists()) trash.walkTopDown().filter { it.isFile }.forEach { staged ->
            val path = staged.relativeTo(trash).invariantSeparatorsPath
            val original = file(path)
            if (path in referenced && !original.exists()) {
                makeParent(original)
                if (!staged.renameTo(original)) throw IOException("图片恢复失败，请重试")
            } else deleteFile(staged)
        }
        val images = File(root, "images")
        if (images.exists()) images.walkTopDown().filter { it.isFile }.forEach { image ->
            if (image.relativeTo(root).invariantSeparatorsPath !in referenced) deleteFile(image)
        }
        removeEmptyDirectories(trash)
        removeEmptyDirectories(images)
    }

    /** 放弃表单只清理当前草稿目录，永远不递归操作已保存的 images 目录。 */
    suspend fun discardSession(session: String) = withContext(Dispatchers.IO) {
        requireSession(session)
        val directory = file("photo_drafts/$session")
        if (directory.exists()) directory.walkBottomUp().forEach { deleteFile(it) }
    }

    /** 移除新拍草稿时同步清理原图和压缩图，已有图片由 Repository 在保存时删除。 */
    suspend fun discardPhoto(path: String) = withContext(Dispatchers.IO) {
        require(path.startsWith("photo_drafts/")) { "不能直接删除已保存照片" }
        val photo = file(path)
        deleteFile(photo)
        if (photo.name.startsWith("photo_")) deleteFile(File(photo.parentFile, photo.name.replaceFirst("photo_", "capture_")))
    }

    /** 路径只能定位私有图片目录；拒绝绝对路径、上级跳转与符号链接越界。 */
    private fun file(path: String): File {
        require(!File(path).isAbsolute && '\\' !in path && path.split('/').none { it.isEmpty() || it == ".." || it == "." }) { "图片路径无效" }
        require(path.startsWith("images/") || path.startsWith("photo_drafts/")) { "图片路径无效" }
        val result = File(root, path).canonicalFile
        require(result.path.startsWith(root.canonicalPath + File.separator)) { "图片路径超出应用目录" }
        return result
    }

    /** 暂移目录镜像原相对路径，无需另外保存易失配的删除清单。 */
    private fun trashFile(path: String): File {
        file(path)
        val trash = File(root, "photo_trash").canonicalFile
        val result = File(trash, path).canonicalFile
        require(result.path.startsWith(trash.path + File.separator)) { "图片删除路径无效" }
        return result
    }

    /** 会话目录使用随机 UUID，避免外部输入参与递归清理路径。 */
    private fun requireSession(session: String) { require(Regex("[a-f0-9-]{36}").matches(session)) { "图片草稿会话无效" } }

    /** 不忽略目录创建失败，存储空间不足等问题必须回传页面。 */
    private fun makeParent(file: File) {
        val parent = requireNotNull(file.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建图片目录")
    }

    /** 文件不存在视为已清理；其他删除失败保留待重试文件。 */
    private fun deleteFile(file: File) { if (file.exists() && !file.delete()) throw IOException("图片文件清理失败") }

    /** 仅删除空目录，避免递归删除仍有引用的照片。 */
    private fun removeEmptyDirectories(root: File) {
        if (root.exists()) root.walkBottomUp().filter { it.isDirectory }.forEach { directory ->
            if (directory.list()?.isEmpty() == true) deleteFile(directory)
        }
    }

    /** 镜像和旋转覆盖 EXIF 八种方向，直接写正向像素便于跨设备查看。 */
    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
}
