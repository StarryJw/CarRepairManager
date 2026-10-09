package com.example.carrepairmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 车牌可修改，因此使用独立主键；归档车辆仍占用标准化车牌，保留历史关联。 */
@Entity(
    tableName = "vehicles",
    foreignKeys = [ForeignKey(
        entity = Customer::class,
        parentColumns = ["id"],
        childColumns = ["customerId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["normalizedPlateNumber"], unique = true), Index("customerId")],
)
@Serializable
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long? = null,
    val plateNumber: String,
    val normalizedPlateNumber: String = "",
    val brand: String = "",
    val model: String = "",
    val color: String = "",
    val remark: String = "",
    val isArchived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
