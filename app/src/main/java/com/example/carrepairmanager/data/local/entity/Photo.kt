package com.example.carrepairmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 图片类型对应服务前、服务后、票据和其他，枚举名是持久化格式的一部分。 */
@Serializable
enum class PhotoType { BEFORE, AFTER, RECEIPT, OTHER }

/** 只存应用私有目录的相对路径；Repository 协调文件保存与清理。 */
@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(
        entity = ServiceOrder::class,
        parentColumns = ["id"],
        childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["orderId", "sortOrder"])],
)
@Serializable
data class Photo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val relativePath: String,
    val type: PhotoType,
    val createdAt: Long = 0,
    val sortOrder: Int = 0,
)
