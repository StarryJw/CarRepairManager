package com.example.carrepairmanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 客户可关联多辆车；时间戳统一使用 Unix 毫秒，姓名与手机号不作为唯一身份。 */
@Entity(tableName = "customers")
@Serializable
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val remark: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
