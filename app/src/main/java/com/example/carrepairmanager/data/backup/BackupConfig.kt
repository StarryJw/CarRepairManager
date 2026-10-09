package com.example.carrepairmanager.data.backup

import android.content.Context
import java.util.Properties

/** 所有可调备份参数从一个文件读取；账号凭据只进入加密的运行时配置。 */
data class BackupConfig(
    val webDavUrl: String,
    val remoteDirectory: String,
    val intervalHours: Long,
    val unmetered: Boolean,
    val backoffSeconds: Long,
    val maxAttempts: Int,
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val writeTimeoutSeconds: Long,
    val maxCloudBytes: Long,
    val maxArchiveBytes: Long,
    val maxExpandedBytes: Long,
    val maxJsonBytes: Long,
    val maxEntries: Int,
    val cloudPageSize: Int,
) {
    companion object {
        /** 使用 UTF-8 读取资产配置，缺失或错误参数明确报错，避免静默换用另一台服务器。 */
        fun load(context: Context): BackupConfig {
            val properties = Properties().apply {
                context.assets.open("backup.properties").reader(Charsets.UTF_8).use { load(it) }
            }
            fun value(key: String) = requireNotNull(properties.getProperty(key)) { "备份配置缺少 $key" }.trim()
            fun number(key: String) = requireNotNull(value(key).toLongOrNull()?.takeIf { it > 0 }) { "备份配置 $key 无效" }
            fun boolean(key: String) = requireNotNull(value(key).toBooleanStrictOrNull()) { "备份配置 $key 无效" }
            require(value("retention.policy") == "keep_all") { "当前版本只支持保留全部历史备份" }
            require(number("auto.interval.hours") >= 1 && number("retry.backoff.seconds") >= 10) { "备份调度间隔无效" }
            require(number("restore.max.entries") <= Int.MAX_VALUE && number("retry.max.attempts") <= Int.MAX_VALUE) { "备份配置数量超限" }
            require(number("cloud.list.page.size") <= Int.MAX_VALUE) { "云端列表每批数量超限" }
            return BackupConfig(value("webdav.url"), value("webdav.directory"), number("auto.interval.hours"),
                boolean("auto.unmetered"), number("retry.backoff.seconds"), number("retry.max.attempts").toInt(),
                number("network.connect.timeout.seconds"), number("network.read.timeout.seconds"), number("network.write.timeout.seconds"),
                number("cloud.max.archive.bytes"), number("local.max.archive.bytes"), number("restore.max.expanded.bytes"),
                number("restore.max.json.bytes"), number("restore.max.entries").toInt(), number("cloud.list.page.size").toInt())
        }
    }
}
