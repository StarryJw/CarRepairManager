package com.example.carrepairmanager.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.carrepairmanager.data.local.entity.Customer
import com.example.carrepairmanager.data.local.entity.Photo
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.ServiceOrder
import com.example.carrepairmanager.data.local.entity.Vehicle
import kotlinx.coroutines.flow.Flow

/** 集中保存 SQL；底层写入不覆盖已有行，业务组合操作由 Repository 提供事务。 */
@Dao
interface CarRepairDao {
    /** 完整快照按主键稳定排序，跨表一致性由 Repository 的外层事务保证。 */
    @Query("SELECT * FROM customers ORDER BY id")
    suspend fun backupCustomers(): List<Customer>

    /** 归档档案也必须包含在备份中。 */
    @Query("SELECT * FROM vehicles ORDER BY id")
    suspend fun backupVehicles(): List<Vehicle>

    /** 导出全部服务单，保留原主键与金额。 */
    @Query("SELECT * FROM service_orders ORDER BY id")
    suspend fun backupOrders(): List<ServiceOrder>

    /** 项目独立导出，恢复前验证服务外键及合计。 */
    @Query("SELECT * FROM service_items ORDER BY id")
    suspend fun backupItems(): List<ServiceItem>

    /** 图片仅导出元数据，文件由图片存储层读取。 */
    @Query("SELECT * FROM photos ORDER BY id")
    suspend fun backupPhotos(): List<Photo>

    /** 只有经过验证并二次确认的整份恢复事务才调用以下删除入口。 */
    @Query("DELETE FROM service_orders")
    suspend fun clearOrdersForRestore()

    /** 先删除服务以满足 RESTRICT 外键，禁止以 destructive migration 实现恢复。 */
    @Query("DELETE FROM vehicles")
    suspend fun clearVehiclesForRestore()

    /** 客户最后删除，事务失败时所有旧行自动恢复。 */
    @Query("DELETE FROM customers")
    suspend fun clearCustomersForRestore()
    /** 创建客户并返回内部主键。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomer(customer: Customer): Long

    /** 唯一索引作为重复车牌的最终约束，禁止用 REPLACE 破坏历史外键。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVehicle(vehicle: Vehicle): Long

    /** 写入到店记录；必须与项目插入处于同一个事务。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertServiceOrder(order: ServiceOrder): Long

    /** 批量保存服务项目，任何一项失败都由外层事务回滚。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertServiceItems(items: List<ServiceItem>)

    /** 图片文件准备完成后写入元数据，与服务保存处于同一事务。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPhoto(photo: Photo): Long

    /** 单张删除前先取得其所属服务与文件路径。 */
    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getPhoto(id: Long): Photo?

    /** 单张图片删除不修改项目或服务金额。 */
    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deletePhoto(id: Long): Int

    /** 只读取路径用于文件恢复，不装载图片二进制。 */
    @Query("SELECT relativePath FROM photos")
    suspend fun getPhotoPaths(): List<String>

    /** 默认仅显示未归档车辆，按建立时间倒序；归档数据仍可单独读取。 */
    @Transaction
    @Query("SELECT * FROM vehicles WHERE :includeArchived OR isArchived = 0 ORDER BY createdAt DESC, id DESC")
    fun observeVehicles(includeArchived: Boolean): Flow<List<VehicleWithCustomer>>

    /** 使用内部主键读取车辆及可选客户，包括已归档车辆。 */
    @Transaction
    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun getVehicle(id: Long): VehicleWithCustomer?

    /** 精确匹配标准化车牌，包括归档记录，以防创建重复档案。 */
    @Transaction
    @Query("SELECT * FROM vehicles WHERE normalizedPlateNumber = :normalizedPlateNumber")
    suspend fun findVehicleByPlate(normalizedPlateNumber: String): VehicleWithCustomer?

    /** 日期格式固定，文本倒序即日期倒序；同一天以内部主键保持稳定顺序。 */
    @Query("SELECT * FROM service_orders WHERE vehicleId = :vehicleId ORDER BY serviceDate DESC, id DESC")
    fun observeServiceHistory(vehicleId: Long): Flow<List<ServiceOrder>>

    /** 事务内取得服务单、项目和图片，避免读到一半更新的关联数据。 */
    @Transaction
    @Query("SELECT * FROM service_orders WHERE id = :id")
    suspend fun getServiceOrder(id: Long): ServiceOrderDetails?

    /** 编辑采用 UPDATE，保留主键和关联车辆，禁止替换已有客户行。 */
    @Update
    suspend fun updateCustomer(customer: Customer): Int

    /** 修改车牌仍受唯一索引约束。 */
    @Update
    suspend fun updateVehicle(vehicle: Vehicle): Int

    /** 修改服务只更新其自身字段，关联照片不受影响。 */
    @Update
    suspend fun updateServiceOrder(order: ServiceOrder): Int

    /** 服务项目整体替换必须与服务总额更新处于同一事务。 */
    @Query("DELETE FROM service_items WHERE orderId = :orderId")
    suspend fun deleteServiceItems(orderId: Long)

    /** 归档保留所有历史记录及唯一车牌，可恢复为未归档状态。 */
    @Query("UPDATE vehicles SET isArchived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setVehicleArchived(id: Long, archived: Boolean, updatedAt: Long): Int

    /** 删除范围持续观察全部服务及照片，避免仅用当前可见的五十条历史计数。 */
    @Query("""
        SELECT (SELECT COUNT(*) FROM service_orders WHERE vehicleId = :id) AS serviceCount,
               (SELECT COUNT(*) FROM photos JOIN service_orders ON service_orders.id = photos.orderId
                WHERE service_orders.vehicleId = :id) AS photoCount
        FROM vehicles WHERE id = :id
    """)
    fun observeVehicleDeletionSummary(id: Long): Flow<VehicleDeletionSummary?>

    /** 只读目标车辆的照片路径，文件暂移不需要加载全部项目或图片二进制。 */
    @Query("""
        SELECT photos.relativePath FROM photos JOIN service_orders ON service_orders.id = photos.orderId
        WHERE service_orders.vehicleId = :id ORDER BY photos.id
    """)
    suspend fun getVehiclePhotoPaths(id: Long): List<String>

    /** 先清理目标车辆的服务，项目与照片元数据沿用现有 CASCADE 外键。 */
    @Query("DELETE FROM service_orders WHERE vehicleId = :id")
    suspend fun deleteVehicleServiceOrders(id: Long): Int

    /** 保留客户且限制已归档档案；须在 Repository 暂移照片并删除服务的事务中调用。 */
    @Query("DELETE FROM vehicles WHERE id = :id AND isArchived = 1")
    suspend fun deleteArchivedVehicle(id: Long): Int

    /** Repository 协调文件清理后删除；外键级联负责项目和照片元数据。 */
    @Query("DELETE FROM service_orders WHERE id = :id")
    suspend fun deleteServiceOrder(id: Long): Int

    /** 详情持续观察车辆和客户，编辑后自动刷新。 */
    @Transaction
    @Query("SELECT * FROM vehicles WHERE id = :id")
    fun observeVehicle(id: Long): Flow<VehicleWithCustomer?>

    /** 服务详情持续观察相关表，项目编辑后自动刷新金额与明细。 */
    @Transaction
    @Query("SELECT * FROM service_orders WHERE id = :id")
    fun observeServiceOrder(id: Long): Flow<ServiceOrderDetails?>

    /** 只取摘要并按批扩大上限，防止列表一次装载全部档案；LIKE 参数已由 Repository 转义。 */
    @Transaction
    @Query("""
        SELECT vehicles.*, (SELECT MAX(serviceDate) FROM service_orders WHERE vehicleId = vehicles.id) AS lastServiceDate,
               (SELECT photos.relativePath FROM photos JOIN service_orders ON service_orders.id = photos.orderId
                WHERE service_orders.vehicleId = vehicles.id AND photos.type != 'RECEIPT'
                ORDER BY service_orders.serviceDate DESC, service_orders.id DESC, photos.sortOrder, photos.id LIMIT 1) AS photoRelativePath
        FROM vehicles LEFT JOIN customers ON customers.id = vehicles.customerId
        WHERE (:includeArchived OR vehicles.isArchived = 0)
          AND (:brand = '' OR vehicles.brand = :brand)
          AND (:query = '' OR vehicles.normalizedPlateNumber LIKE :platePattern ESCAPE '\'
               OR customers.name LIKE :textPattern ESCAPE '\' OR customers.phone LIKE :textPattern ESCAPE '\')
        ORDER BY CASE WHEN :query != '' AND vehicles.normalizedPlateNumber LIKE :platePattern ESCAPE '\' THEN 0
                      WHEN :query != '' AND customers.name LIKE :textPattern ESCAPE '\' THEN 1 ELSE 2 END,
                 CASE WHEN :recentFirst THEN (SELECT MAX(serviceDate) FROM service_orders WHERE vehicleId = vehicles.id) END DESC,
                 vehicles.createdAt DESC, vehicles.id DESC
        LIMIT :limit
    """)
    fun observeVehicleList(query: String, platePattern: String, textPattern: String, brand: String,
        includeArchived: Boolean, recentFirst: Boolean, limit: Int): Flow<List<VehicleListEntry>>

    /** 筛选选项从已有车辆读取，避免新增自定义品牌后无法筛选。 */
    @Query("SELECT DISTINCT brand FROM vehicles WHERE brand != '' ORDER BY brand")
    fun observeBrands(): Flow<List<String>>

    /** 首页最近到店按日期倒序，仅查询一批可见卡片。 */
    @Query("""
        SELECT service_orders.id AS orderId, vehicles.id AS vehicleId, vehicles.plateNumber,
               vehicles.brand, vehicles.model, vehicles.color, customers.name AS customerName,
               service_orders.serviceDate, service_orders.totalAmountCent,
               (SELECT relativePath FROM photos WHERE orderId = service_orders.id AND type != 'RECEIPT'
                ORDER BY sortOrder, id LIMIT 1) AS photoRelativePath
        FROM service_orders JOIN vehicles ON vehicles.id = service_orders.vehicleId
        LEFT JOIN customers ON customers.id = vehicles.customerId
        WHERE vehicles.isArchived = 0
        ORDER BY service_orders.serviceDate DESC, service_orders.id DESC LIMIT :limit
    """)
    fun observeRecentServices(limit: Int): Flow<List<RecentServiceEntry>>

    /** 相关表在 SELECT 中显式引用，确保项目、照片变化也触发历史卡片刷新。 */
    @Query("""
        SELECT service_orders.*,
               COALESCE((SELECT GROUP_CONCAT(name, ' · ') FROM
                   (SELECT name FROM service_items WHERE orderId = service_orders.id ORDER BY id)), '') AS itemSummary,
               (SELECT COUNT(*) FROM photos WHERE orderId = service_orders.id) AS photoCount,
               (SELECT relativePath FROM photos WHERE orderId = service_orders.id AND type != 'RECEIPT'
                ORDER BY sortOrder, id LIMIT 1) AS photoRelativePath
        FROM service_orders WHERE vehicleId = :vehicleId
        ORDER BY serviceDate DESC, id DESC LIMIT :limit
    """)
    fun observeServiceHistoryEntries(vehicleId: Long, limit: Int): Flow<List<ServiceHistoryEntry>>
}
