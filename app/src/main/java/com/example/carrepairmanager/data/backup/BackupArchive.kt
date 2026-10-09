package com.example.carrepairmanager.data.backup

import android.graphics.BitmapFactory
import com.example.carrepairmanager.data.PhotoFileStore
import com.example.carrepairmanager.data.normalizePlateNumber
import com.example.carrepairmanager.data.requireServiceDate
import com.example.carrepairmanager.data.sumAmountCent
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 在私有缓存中构建或校验完整 ZIP，未验证完成的备份不会进入数据库。 */
class BackupArchive(private val cache: File, private val config: BackupConfig, private val photoFiles: PhotoFileStore,
    private val appVersion: String, private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val json = Json { encodeDefaults = true }

    /** 调用方持有业务快照锁，逐个复制已保存图片并记录摘要，不读取草稿或数据库文件。 */
    suspend fun create(data: BackupData): BackupSnapshot = withContext(Dispatchers.IO) {
        validateData(data)
        require(data.photos.size.toLong() + 2 <= config.maxEntries) { "备份文件数量超过配置上限" }
        val dataBytes = json.encodeToString(data).toByteArray(Charsets.UTF_8)
        require(dataBytes.size <= config.maxJsonBytes) { "备份数据超过 JSON 大小上限" }
        makeDirectory(cache)
        val timestamp = nowMillis()
        val file = File(cache, backupFileName(timestamp))
        val dataHash = sha256(dataBytes)
        val images = mutableListOf<BackupImage>()
        var totalBytes = dataBytes.size.toLong()
        try {
            var manifest: BackupManifest? = null
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("data.json")); zip.write(dataBytes); zip.closeEntry()
                data.photos.forEach { photo ->
                    currentCoroutineContext().ensureActive()
                    val source = photoFiles.savedPhotoFile(photo.relativePath)
                    require(source.isFile && source.length() > 0) { "已保存照片缺失，无法生成完整备份" }
                    requireCompleteJpeg(source)
                    zip.putNextEntry(ZipEntry(photo.relativePath))
                    val digest = MessageDigest.getInstance("SHA-256")
                    val size = source.inputStream().use { copyLimited(it, zip, config.maxExpandedBytes - totalBytes, digest) }
                    zip.closeEntry()
                    totalBytes += size
                    images += BackupImage(photo.relativePath, size, hex(digest.digest()))
                }
                manifest = BackupManifest(2, 2, appVersion, date(timestamp, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"),
                    data.customers.size, data.vehicles.size, data.orders.size, data.items.size, data.photos.size, dataHash, images)
                val manifestBytes = json.encodeToString(requireNotNull(manifest)).toByteArray(Charsets.UTF_8)
                require(manifestBytes.size <= config.maxJsonBytes && totalBytes <= config.maxExpandedBytes - manifestBytes.size) { "备份超过解压大小上限" }
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifestBytes); zip.closeEntry()
            }
            require(file.length() <= config.maxArchiveBytes) { "备份超过本地文件大小上限" }
            val complete = requireNotNull(manifest)
            // 只标记上传的这个快照；上传过程中后来保存的修改仍会生成不同摘要。
            val fingerprint = sha256((dataHash + json.encodeToString(images)).toByteArray(Charsets.UTF_8))
            BackupSnapshot(file, complete, fingerprint)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /** 本地和云端下载共用此验证入口；ZIP 中央目录、CRC、摘要、外键、金额与路径逐项检查。 */
    suspend fun inspect(source: File): PreparedRestore = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() in 1..config.maxArchiveBytes) { "备份文件为空或超过大小上限" }
        makeDirectory(cache)
        val stage = File(cache, "restore_${UUID.randomUUID()}")
        makeDirectory(stage)
        try {
            val files = mutableSetOf<String>()
            var totalBytes = 0L
            ZipFile(source).use { zip ->
                val entries = zip.entries()
                val names = mutableSetOf<String>()
                while (entries.hasMoreElements()) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries.nextElement()
                    require(names.add(entry.name) && names.size <= config.maxEntries) { "备份含重复文件或文件数量超限" }
                    val name = entry.name.removeSuffix("/")
                    requireSafePath(name)
                    require(name == "manifest.json" || name == "data.json" || name == "images" || name.startsWith("images/")) { "备份包含未知文件" }
                    if (entry.isDirectory) {
                        require(name == "images" || name.startsWith("images/")) { "备份目录无效" }
                        continue
                    }
                    require(name != "images") { "图片目录无效" }
                    val destination = File(stage, name)
                    makeDirectory(requireNotNull(destination.parentFile))
                    val maxBytes = minOf(config.maxExpandedBytes - totalBytes,
                        if (name.endsWith(".json")) config.maxJsonBytes else config.maxExpandedBytes)
                    val crc = CRC32()
                    val size = zip.getInputStream(entry).use { input -> destination.outputStream().use { output ->
                        copyLimited(input, output, maxBytes, crc = crc)
                    } }
                    require(size == entry.size && crc.value == entry.crc) { "备份文件校验失败" }
                    totalBytes += size
                    files += name
                }
            }
            require("manifest.json" in files && "data.json" in files) { "备份缺少 manifest.json 或 data.json" }
            val prepared = validateStage(stage)
            require(files == setOf("manifest.json", "data.json") + prepared.data.photos.map { it.relativePath }) { "备份图片数量或路径不一致" }
            prepared
        } catch (error: Exception) {
            stage.deleteRecursively()
            throw error
        }
    }

    /** 重建恢复预览时再次验证私有暂存文件，避免进程重建后跳过校验或自动覆盖档案。 */
    suspend fun reopen(name: String): PreparedRestore = withContext(Dispatchers.IO) {
        require(Regex("restore_[a-f0-9-]{36}").matches(name)) { "恢复暂存路径无效" }
        validateStage(File(cache, name))
    }

    /** 校验所有表、图片摘要与 JPEG 完整性后再返回，失败时保持原档案不变。 */
    private fun validateStage(stage: File): PreparedRestore {
        val manifestFile = File(stage, "manifest.json")
        val dataFile = File(stage, "data.json")
        require(manifestFile.isFile && dataFile.isFile && manifestFile.length() <= config.maxJsonBytes && dataFile.length() <= config.maxJsonBytes) { "恢复暂存文件缺失，请重新选择备份" }
        val manifest = json.decodeFromString<BackupManifest>(manifestFile.readText(Charsets.UTF_8))
        require((manifest.backupVersion == 1 && manifest.databaseVersion == 1) ||
            (manifest.backupVersion == 2 && manifest.databaseVersion == 2)) { "不支持此备份版本或数据库版本" }
        val bytes = dataFile.readBytes()
        require(sha256(bytes) == manifest.dataSha256) { "备份数据校验失败" }
        val data = json.decodeFromString<BackupData>(bytes.toString(Charsets.UTF_8))
        // 旧备份只能包含已填写金额；新格式才允许 null，避免伪造旧格式掩盖缺失字段。
        if (manifest.backupVersion == 1) require(data.items.all { it.amountCent != null } &&
            data.orders.all { it.totalAmountCent != null }) { "旧版备份金额缺失" }
        validateData(data)
        require(listOf(manifest.customerCount, manifest.vehicleCount, manifest.orderCount, manifest.itemCount, manifest.photoCount) ==
            listOf(data.customers.size, data.vehicles.size, data.orders.size, data.items.size, data.photos.size)) { "备份记录数量不一致" }
        require(manifest.images.size == data.photos.size && manifest.images.map { it.relativePath }.toSet() == data.photos.map { it.relativePath }.toSet()) { "备份图片清单不一致" }
        manifest.images.forEach { image ->
            val file = File(stage, image.relativePath)
            require(file.isFile && image.size > 0 && file.length() == image.size && hashFile(file) == image.sha256) { "备份图片缺失或损坏" }
            requireCompleteJpeg(file)
        }
        return PreparedRestore(stage, data, manifest)
    }

    /** 摘要只能验证打包后的字节；BitmapFactory 可能容忍截断，另核对头尾标记并实际解码像素。 */
    private fun requireCompleteJpeg(file: File) {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 4 && input.readUnsignedShort() == 0xFFD8) { "备份照片格式无效" }
            input.seek(input.length() - 2)
            require(input.readUnsignedShort() == 0xFFD9) { "照片文件不完整，请检查原照片后重新备份" }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == "image/jpeg") { "备份照片格式无效" }
        // 逐张采样解码到既有 1920px 图片量级，不按不可信的原始尺寸分配整张位图。
        val longest = maxOf(bounds.outWidth, bounds.outHeight).toLong()
        var sample = 1
        while ((longest + sample - 1) / sample > 1920) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        requireNotNull(bitmap) { "备份照片无法解码，请检查原照片后重新备份" }.recycle()
    }

    /** 保留主键及待填金额；项目与服务合计必须一致，不能把 null 转成免费金额。 */
    internal fun validateData(data: BackupData) {
        fun ids(values: List<Long>): Set<Long> {
            require(values.all { it > 0 } && values.distinct().size == values.size) { "备份主键无效或重复" }
            return values.toSet()
        }
        val customers = ids(data.customers.map { it.id })
        val vehicles = ids(data.vehicles.map { it.id })
        val orders = ids(data.orders.map { it.id })
        ids(data.items.map { it.id }); ids(data.photos.map { it.id })
        require(data.vehicles.map { it.normalizedPlateNumber }.distinct().size == data.vehicles.size) { "备份车牌重复" }
        data.customers.forEach { require(it.createdAt >= 0 && it.updatedAt >= 0) { "备份客户时间无效" } }
        data.vehicles.forEach {
            require(it.customerId == null || it.customerId in customers) { "备份客户关联无效" }
            require(it.normalizedPlateNumber.isNotBlank() && it.normalizedPlateNumber == normalizePlateNumber(it.plateNumber)) { "备份车牌无效" }
            require(it.createdAt >= 0 && it.updatedAt >= 0) { "备份车辆时间无效" }
        }
        data.items.forEach { require(it.orderId in orders && it.name.isNotBlank() &&
            (it.amountCent == null || it.amountCent >= 0)) { "备份项目或金额无效" } }
        val totals = data.items.groupBy { it.orderId }.mapValues { (_, items) -> sumAmountCent(items.map { it.amountCent }) }
        data.orders.forEach {
            require(it.vehicleId in vehicles) { "备份车辆关联无效" }
            requireServiceDate(it.serviceDate)
            require(it.totalAmountCent == totals.getOrDefault(it.id, 0L) && it.createdAt >= 0 && it.updatedAt >= 0) { "备份服务金额或时间无效" }
        }
        require(data.photos.map { it.relativePath }.distinct().size == data.photos.size) { "备份照片路径重复" }
        data.photos.forEach {
            requireSafePath(it.relativePath)
            require(it.orderId in orders && it.relativePath.startsWith("images/order_${it.orderId}/") &&
                Regex("[a-zA-Z0-9_.-]+\\.jpg").matches(it.relativePath.substringAfterLast('/')) && it.relativePath.count { char -> char == '/' } == 2 &&
                it.sortOrder >= 0 && it.createdAt >= 0) { "备份照片关联或路径无效" }
        }
    }

    /** ZIP 和数据库图片路径都拒绝绝对路径、上级跳转及 Windows 路径分隔符。 */
    private fun requireSafePath(path: String) {
        require(!File(path).isAbsolute && '\\' !in path && ':' !in path && '\u0000' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "备份包含不安全路径" }
    }

    /** 对解压流按实际读取字节限制，不能相信 ZIP 自报的大小。 */
    internal suspend fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long,
        digest: MessageDigest? = null, crc: CRC32? = null): Long {
        var total = 0L
        val buffer = ByteArray(32 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            require(total <= maxBytes - count) { "备份超过配置的大小上限" }
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count); crc?.update(buffer, 0, count)
            total += count
        }
        return total
    }

    /** 文件摘要逐块计算，高清照片不一次性读取到内存。 */
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }

    /** 摘要统一小写十六进制，格式独立于手机语言。 */
    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    /** manifest 时间保持 UTC 以兼容原备份格式，文件名另用北京时间。 */
    private fun date(timestamp: Long, pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestamp))

    /** 空间不足或目录创建失败向调用方传播。 */
    private fun makeDirectory(directory: File) { require(directory.isDirectory || directory.mkdirs()) { "无法创建备份暂存目录" } }
}
