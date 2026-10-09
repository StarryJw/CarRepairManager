package com.example.carrepairmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 稳定枚举名存入 Room，统一表示维修、保养、洗车、配件和其他项目。 */
@Serializable
enum class ServiceCategory { REPAIR, MAINTENANCE, WASH, PART, OTHER }

/** 金额保存整数分，null 表示待填而非免费；项目与服务单同事务保存。 */
@Entity(
    tableName = "service_items",
    foreignKeys = [ForeignKey(
        entity = ServiceOrder::class,
        parentColumns = ["id"],
        childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("orderId")],
)
@Serializable
data class ServiceItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long = 0,
    val category: ServiceCategory,
    val name: String,
    val amountCent: Long? = null,
    val remark: String = "",
)
