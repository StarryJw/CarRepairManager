package com.example.carrepairmanager.data.local

import androidx.room.Embedded
import androidx.room.Relation
import com.example.carrepairmanager.data.local.entity.Customer
import com.example.carrepairmanager.data.local.entity.Photo
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.ServiceOrder
import com.example.carrepairmanager.data.local.entity.Vehicle

/** 车辆允许没有客户；关联查询在 Room 事务内读取一致的快照。 */
data class VehicleWithCustomer(
    @Embedded val vehicle: Vehicle,
    @Relation(parentColumn = "customerId", entityColumn = "id") val customer: Customer?,
)

/** 永久删除确认使用完整档案计数，不受历史列表分页限制。 */
data class VehicleDeletionSummary(val serviceCount: Int, val photoCount: Int)

/** 服务详情携带项目和图片元数据；展示顺序由 Repository 统一整理。 */
data class ServiceOrderDetails(
    @Embedded val order: ServiceOrder,
    @Relation(parentColumn = "id", entityColumn = "orderId") val items: List<ServiceItem>,
    @Relation(parentColumn = "id", entityColumn = "orderId") val photos: List<Photo>,
)

/** 列表只查询最近服务日期，不加载每辆车的全部历史与照片。 */
data class VehicleListEntry(
    @Embedded val vehicle: Vehicle,
    @Relation(parentColumn = "customerId", entityColumn = "id") val customer: Customer?,
    val lastServiceDate: String?,
    val photoRelativePath: String? = null,
)

/** 历史卡片使用轻量摘要，项目明细及图片记录在打开详情时读取。 */
data class ServiceHistoryEntry(
    @Embedded val order: ServiceOrder,
    val itemSummary: String,
    val photoCount: Int,
    val photoRelativePath: String? = null,
)

/** 首页使用轻量投影；合计为空代表金额待填，不把未报价项目显示为免费服务。 */
data class RecentServiceEntry(
    val orderId: Long,
    val vehicleId: Long,
    val plateNumber: String,
    val brand: String,
    val model: String,
    val color: String,
    val customerName: String?,
    val serviceDate: String,
    val totalAmountCent: Long?,
    val photoRelativePath: String? = null,
)
