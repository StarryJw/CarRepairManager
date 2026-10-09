package com.example.carrepairmanager.data

import android.util.Log
import androidx.room.withTransaction
import com.example.carrepairmanager.data.local.CarRepairDatabase
import com.example.carrepairmanager.data.local.ServiceOrderDetails
import com.example.carrepairmanager.data.local.VehicleWithCustomer
import com.example.carrepairmanager.data.local.entity.Customer
import com.example.carrepairmanager.data.local.entity.Photo
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.ServiceOrder
import com.example.carrepairmanager.data.local.entity.Vehicle
import com.example.carrepairmanager.data.backup.BackupData
import com.example.carrepairmanager.data.backup.PreparedRestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 重复车牌是可向表单反馈的业务错误，不应由页面崩溃处理。 */
class DuplicatePlateException : IllegalArgumentException("车牌已存在，请打开已有车辆档案")

/** 提供 ViewModel 可复用的数据库业务入口；时钟可注入，便于复现测试。 */
class CarRepairRepository(
    private val database: CarRepairDatabase,
    private val photoFiles: PhotoFileStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.recordsDao()
    private val photoMutex = Mutex()
    private var filesRecovered = false

    /** 新客户和车辆原子保存；也可用 customerId 关联已有客户，不按姓名猜测身份。 */
    suspend fun createVehicle(vehicle: Vehicle, newCustomer: Customer? = null): Long {
        require(vehicle.id == 0L) { "新增车辆不能指定已有 ID" }
        require(newCustomer == null || (newCustomer.id == 0L && vehicle.customerId == null)) {
            "新增客户与已有客户关联不能同时指定"
        }
        val normalizedPlate = normalizePlateNumber(vehicle.plateNumber)
        require(normalizedPlate.isNotEmpty()) { "请输入车牌号" }
        return photoMutex.withLock { database.withTransaction {
            // 在同一事务内检查并插入，避免重复车牌时留下孤立客户。
            if (dao.findVehicleByPlate(normalizedPlate) != null) throw DuplicatePlateException()
            val timestamp = nowMillis()
            val customerId = newCustomer?.let {
                dao.insertCustomer(it.copy(createdAt = timestamp, updatedAt = timestamp))
            } ?: vehicle.customerId
            dao.insertVehicle(vehicle.copy(
                customerId = customerId,
                plateNumber = vehicle.plateNumber.trim(),
                normalizedPlateNumber = normalizedPlate,
                isArchived = false,
                createdAt = timestamp,
                updatedAt = timestamp,
            ))
        } }
    }

    /** 订阅车辆与客户快照，默认不展示归档车辆。 */
    fun observeVehicles(includeArchived: Boolean = false): Flow<List<VehicleWithCustomer>> =
        dao.observeVehicles(includeArchived)

    /** 按内部 ID 查询完整车辆信息，归档车辆也能查看。 */
    suspend fun getVehicle(id: Long): VehicleWithCustomer? = dao.getVehicle(id)

    /** 查询时与保存时使用相同的标准化规则。 */
    suspend fun findVehicleByPlate(plateNumber: String): VehicleWithCustomer? =
        dao.findVehicleByPlate(normalizePlateNumber(plateNumber))

    /** 总金额由项目计算，任一金额留空时保存为待填；外键由新单 ID 设置。 */
    suspend fun createServiceOrder(
        vehicleId: Long,
        serviceDate: String,
        items: List<ServiceItem>,
        remark: String = "",
        photos: List<PhotoDraft> = emptyList(),
    ): Long {
        requireServiceDate(serviceDate)
        require(items.all { it.id == 0L && it.orderId == 0L }) { "新增项目不能属于已有服务单" }
        require(items.all { it.name.isNotBlank() }) { "请输入服务项目名称" }
        val totalAmountCent = sumAmountCent(items.map { it.amountCent })
        return withPhotoMutation { database.withTransaction {
            require(dao.getVehicle(vehicleId) != null) { "车辆不存在，无法保存服务" }
            val timestamp = nowMillis()
            val orderId = dao.insertServiceOrder(ServiceOrder(
                vehicleId = vehicleId,
                serviceDate = serviceDate,
                totalAmountCent = totalAmountCent,
                remark = remark,
                createdAt = timestamp,
                updatedAt = timestamp,
            ))
            dao.insertServiceItems(items.map { it.copy(orderId = orderId, name = it.name.trim()) })
            replacePhotos(orderId, emptyList(), photos)
            orderId
        } }
    }

    /** 按服务日期倒序订阅指定车辆的历史；项目明细在打开详情时再查询。 */
    fun observeServiceHistory(vehicleId: Long): Flow<List<ServiceOrder>> =
        dao.observeServiceHistory(vehicleId)

    /** 项目按录入顺序、照片按 sortOrder 展示，不依赖 SQLite 未指定的返回顺序。 */
    suspend fun getServiceOrder(id: Long): ServiceOrderDetails? {
        ensurePhotosReady()
        return dao.getServiceOrder(id)?.let { it.copy(
            items = it.items.sortedBy { item -> item.id },
            photos = it.photos.sortedWith(compareBy({ photo -> photo.sortOrder }, { photo -> photo.id })),
        ) }
    }

    /** 编辑时保留创建时间、归档状态和关联主键；同一客户的联系人修改同步到其其他车辆。 */
    suspend fun updateVehicle(vehicle: Vehicle, customer: Customer? = null) = photoMutex.withLock { database.withTransaction {
        val existing = requireNotNull(dao.getVehicle(vehicle.id)) { "车辆不存在，无法保存" }
        val normalizedPlate = normalizePlateNumber(vehicle.plateNumber)
        require(normalizedPlate.isNotEmpty()) { "请输入车牌号" }
        val duplicate = dao.findVehicleByPlate(normalizedPlate)
        if (duplicate != null && duplicate.vehicle.id != vehicle.id) throw DuplicatePlateException()
        val timestamp = nowMillis()
        val customerId = if (customer == null) existing.vehicle.customerId else {
            val oldCustomer = existing.customer
            if (oldCustomer == null) {
                dao.insertCustomer(customer.copy(id = 0, createdAt = timestamp, updatedAt = timestamp))
            } else {
                dao.updateCustomer(oldCustomer.copy(name = customer.name, phone = customer.phone, updatedAt = timestamp))
                oldCustomer.id
            }
        }
        dao.updateVehicle(vehicle.copy(
            customerId = customerId, plateNumber = vehicle.plateNumber.trim(), normalizedPlateNumber = normalizedPlate,
            isArchived = existing.vehicle.isArchived, createdAt = existing.vehicle.createdAt, updatedAt = timestamp,
        ))
        Unit
    } }

    /** 补填或清空金额时重新计算待填状态和合计；事务更新项目，原有照片保持不变。 */
    suspend fun updateServiceOrder(orderId: Long, serviceDate: String, items: List<ServiceItem>, remark: String,
        photos: List<PhotoDraft>? = null) {
        requireServiceDate(serviceDate)
        require(items.all { it.id == 0L && it.orderId == 0L }) { "请以新项目列表保存服务" }
        require(items.all { it.name.isNotBlank() }) { "请输入服务项目名称" }
        val total = sumAmountCent(items.map { it.amountCent })
        withPhotoMutation { database.withTransaction {
            val existing = requireNotNull(dao.getServiceOrder(orderId)) { "服务记录不存在，无法保存" }
            dao.updateServiceOrder(existing.order.copy(serviceDate = serviceDate, totalAmountCent = total,
                remark = remark, updatedAt = nowMillis()))
            dao.deleteServiceItems(orderId)
            dao.insertServiceItems(items.map { it.copy(orderId = orderId, name = it.name.trim()) })
            if (photos != null) replacePhotos(orderId, existing.photos, photos)
        } }
    }

    /** 日常归档或恢复完整保留关联服务和客户，永久删除另需输入车牌确认。 */
    suspend fun setVehicleArchived(id: Long, archived: Boolean) = photoMutex.withLock {
        require(dao.setVehicleArchived(id, archived, nowMillis()) == 1) { "车辆不存在" }
    }

    /** 与备份快照共用锁，事务内重新校验归档和车牌；暂移失败或回滚按原引用恢复照片。 */
    suspend fun deleteVehicle(id: Long, confirmedPlate: String) = withPhotoMutation { database.withTransaction {
        val existing = requireNotNull(dao.getVehicle(id)) { "车辆不存在，无法删除" }.vehicle
        require(existing.isArchived) { "请先归档车辆，再永久删除" }
        require(normalizePlateNumber(confirmedPlate) == existing.normalizedPlateNumber) { "输入的车牌与当前车辆不一致" }
        val paths = dao.getVehiclePhotoPaths(id)
        if (paths.isNotEmpty()) requireNotNull(photoFiles) { "图片存储未配置，无法安全删除" }.stageRemoval(paths)
        // Vehicle 到 ServiceOrder 是 RESTRICT；先删服务，再删车辆，保留共享客户和其他车辆。
        dao.deleteVehicleServiceOrders(id)
        check(dao.deleteArchivedVehicle(id) == 1) { "车辆删除失败，请重试" }
        Unit
    } }

    /** 确认界面订阅完整计数，服务和照片变化时同步更新删除范围。 */
    fun observeVehicleDeletionSummary(id: Long) = dao.observeVehicleDeletionSummary(id)

    /** 快照读取与所有业务写入共用锁；只在复制本地照片时持锁，网络上传不阻塞业务保存。 */
    suspend fun <T> withBackupSnapshot(block: suspend (BackupData) -> T): T = photoMutex.withLock {
        recoverPhotoFiles()
        val data = database.withTransaction {
            BackupData(dao.backupCustomers(), dao.backupVehicles(), dao.backupOrders(), dao.backupItems(), dao.backupPhotos())
        }
        block(data)
    }

    /** 新照片先用独立文件名落盘，事务提交前绝不覆盖旧图；中断后复用已有文件引用恢复机制。 */
    suspend fun restoreBackup(prepared: PreparedRestore) = withPhotoMutation {
        withContext(NonCancellable) {
            val store = requireNotNull(photoFiles) { "图片存储未配置" }
            val photos = prepared.data.photos.map { photo ->
                photo.copy(relativePath = store.copyRestoredPhoto(photo.orderId, prepared.directory, photo.relativePath))
            }
            database.withTransaction {
                dao.clearOrdersForRestore()
                dao.clearVehiclesForRestore()
                dao.clearCustomersForRestore()
                prepared.data.customers.forEach { dao.insertCustomer(it) }
                prepared.data.vehicles.forEach { dao.insertVehicle(it) }
                prepared.data.orders.forEach { dao.insertServiceOrder(it) }
                dao.insertServiceItems(prepared.data.items)
                photos.forEach { dao.insertPhoto(it) }
            }
        }
    }

    /** 二次确认后暂移图片并事务删除元数据；回滚与进程中断均按数据库引用恢复文件。 */
    suspend fun deleteServiceOrder(id: Long) = withPhotoMutation { database.withTransaction {
        val existing = requireNotNull(dao.getServiceOrder(id)) { "服务记录不存在" }
        if (existing.photos.isNotEmpty()) requireNotNull(photoFiles) { "图片存储未配置，无法安全删除" }
            .stageRemoval(existing.photos.map { it.relativePath })
        dao.deleteServiceOrder(id)
        Unit
    } }

    /** 单张删除仅影响图片，关联服务、金额与其他照片保持原样。 */
    suspend fun deletePhoto(id: Long) = withPhotoMutation { database.withTransaction {
        val photo = requireNotNull(dao.getPhoto(id)) { "照片不存在" }
        requireNotNull(photoFiles) { "图片存储未配置，无法安全删除" }.stageRemoval(listOf(photo.relativePath))
        dao.deletePhoto(id)
        Unit
    } }

    /** 持续读取详情，让编辑返回后的页面无需手动重载。 */
    fun observeVehicle(id: Long) = dao.observeVehicle(id)

    /** 排序规则与单次详情查询保持一致。 */
    fun observeServiceOrder(id: Long) = readyPhotos(dao.observeServiceOrder(id)).map { detail ->
        detail?.copy(items = detail.items.sortedBy { it.id },
            photos = detail.photos.sortedWith(compareBy({ it.sortOrder }, { it.id })))
    }

    /** 参数化并转义 LIKE 元字符，用户输入百分号等字符不应匹配全部档案。 */
    fun observeVehicleList(query: String = "", brand: String = "", includeArchived: Boolean = false,
        recentFirst: Boolean = true, limit: Int = 50) = readyPhotos(dao.observeVehicleList(
        query.trim(), likePattern(normalizePlateNumber(query)), likePattern(query.trim()), brand,
        includeArchived, recentFirst, limit.coerceAtLeast(1),
    ))

    /** 根据已有品牌产生筛选选项。 */
    fun observeBrands() = dao.observeBrands()

    /** 首页摘要按需扩展显示批次。 */
    fun observeRecentServices(limit: Int = 20) = readyPhotos(dao.observeRecentServices(limit.coerceAtLeast(1)))

    /** 历史仅加载卡片摘要，不读取高清图片或完整文件。 */
    fun observeServiceHistoryEntries(vehicleId: Long, limit: Int = 50) =
        readyPhotos(dao.observeServiceHistoryEntries(vehicleId, limit.coerceAtLeast(1)))

    /** 表单照片以完整列表提交，校验归属后才清理移除的原图；新图排序沿用录入顺序。 */
    private suspend fun replacePhotos(orderId: Long, existing: List<Photo>, drafts: List<PhotoDraft>) {
        require(drafts.all { it.id >= 0 } && drafts.map { it.relativePath }.distinct().size == drafts.size) { "照片列表无效" }
        val retained = drafts.filter { it.id != 0L }
        require(retained.map { it.id }.distinct().size == retained.size && retained.all { draft ->
            existing.any { it.id == draft.id && it.relativePath == draft.relativePath && it.type == draft.type }
        }) { "照片不属于当前服务记录" }
        val removed = existing.filter { old -> retained.none { it.id == old.id } }
        val added = drafts.filter { it.id == 0L }
        if (removed.isEmpty() && added.isEmpty()) return
        val store = requireNotNull(photoFiles) { "图片存储未配置" }
        store.stageRemoval(removed.map { it.relativePath })
        removed.forEach { dao.deletePhoto(it.id) }
        var nextSort = (existing.maxOfOrNull { it.sortOrder } ?: -1).toLong() + 1
        added.forEach { draft ->
            require(nextSort <= Int.MAX_VALUE) { "照片数量超出可保存范围" }
            val path = store.saveDraft(orderId, draft.relativePath)
            dao.insertPhoto(Photo(orderId = orderId, relativePath = path, type = draft.type,
                createdAt = nowMillis(), sortOrder = nextSort.toInt()))
            nextSort++
        }
    }

    /** 图片写操作串行，避免恢复过程把另一事务尚未提交的新文件当作孤立文件删除。 */
    private suspend fun <T> withPhotoMutation(block: suspend () -> T): T = photoMutex.withLock {
        recoverPhotoFiles()
        try { block() } finally {
            // 数据库提交结果不能因后续文件清理失败变成“保存失败”；保留暂移文件供下次重试。
            withContext(NonCancellable) {
                try { photoFiles?.reconcile(dao.getPhotoPaths().toSet()); filesRecovered = true }
                catch (error: Exception) { filesRecovered = false; Log.e("CarRepairRepository", "图片清理未完成，将在下次读取时重试", error) }
            }
        }
    }

    /** 首次读取前恢复中断的文件操作，错误继续交由界面反馈并重试。 */
    private suspend fun ensurePhotosReady() = photoMutex.withLock { recoverPhotoFiles() }

    /** 只在首次读取或上次清理失败后扫描，避免每次列表重组遍历整个图库。 */
    private suspend fun recoverPhotoFiles() {
        if (photoFiles != null && !filesRecovered) {
            photoFiles.reconcile(dao.getPhotoPaths().toSet())
            filesRecovered = true
        }
    }

    /** 保持 DAO Flow 的生命周期语义，后台文件恢复完成后才发出图片相关列表。 */
    private fun <T> readyPhotos(flow: Flow<T>): Flow<T> = flow.onStart { ensurePhotosReady() }

    /** 使用反斜杠转义匹配符，实际搜索仍保持包含匹配。 */
    private fun likePattern(value: String) = "%${value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
}
