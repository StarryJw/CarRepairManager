package com.example.carrepairmanager.data.backup

import com.example.carrepairmanager.data.local.entity.Customer
import com.example.carrepairmanager.data.local.entity.Vehicle
import com.example.carrepairmanager.data.local.entity.ServiceOrder
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.Photo
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** 本地与云端反馈分别展示，后台云端失败不能出现在本地备份区域。 */
enum class BackupScope { LOCAL, CLOUD }

/** 文件名统一采用北京时间，不受设备时区影响；UUID 避免同一时刻生成的版本重名。 */
internal fun backupFileName(timestamp: Long = System.currentTimeMillis()): String {
    val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(timestamp))
    return "CarShopBackup_${time}_${UUID.randomUUID()}.zip"
}

/** 直接复用现有实体，备份不包含设置、凭据或未保存草稿。 */
@Serializable
data class BackupData(val customers: List<Customer>, val vehicles: List<Vehicle>, val orders: List<ServiceOrder>,
    val items: List<ServiceItem>, val photos: List<Photo>)

/** 每个图片的字节数和摘要用于校验完整性，而非身份或业务主键。 */
@Serializable
data class BackupImage(val relativePath: String, val size: Long, val sha256: String)

/** 格式版本与 Room 版本分别校验，避免跨版本导入改变业务含义。 */
@Serializable
data class BackupManifest(val backupVersion: Int, val databaseVersion: Int, val appVersion: String, val createdAt: String,
    val customerCount: Int, val vehicleCount: Int, val orderCount: Int, val itemCount: Int, val photoCount: Int,
    val dataSha256: String, val images: List<BackupImage>)

/** 临时快照只在私有缓存中存在，导出或上传完成后清理。 */
data class BackupSnapshot(val file: File, val manifest: BackupManifest, val fingerprint: String)

/** 已验证备份先暂存，只有用户二次确认才传入数据库事务。 */
data class PreparedRestore(val directory: File, val data: BackupData, val manifest: BackupManifest)

/** 云端只列出完成 MOVE 的 ZIP；未完成的 uploading 文件不作为恢复候选。 */
data class RemoteBackup(val name: String, val size: Long)

/** 可向界面安全展示的错误；底层响应和凭据不得拼入错误信息。 */
class BackupException(message: String, val retryable: Boolean = false, cause: Throwable? = null) : IOException(message, cause)
