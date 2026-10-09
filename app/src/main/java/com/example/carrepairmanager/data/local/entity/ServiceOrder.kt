package com.example.carrepairmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 一次到店：金额为整数分；任一项目待填时合计为 null，不把部分金额当作完整总额。 */
@Entity(
    tableName = "service_orders",
    foreignKeys = [ForeignKey(
        entity = Vehicle::class,
        parentColumns = ["id"],
        childColumns = ["vehicleId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["vehicleId", "serviceDate"])],
)
@Serializable
data class ServiceOrder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val serviceDate: String,
    val totalAmountCent: Long? = 0,
    val remark: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
