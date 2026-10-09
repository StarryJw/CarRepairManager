package com.example.carrepairmanager.data

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.Room
import com.example.carrepairmanager.data.local.CarRepairDatabase
import com.example.carrepairmanager.data.local.entity.Customer
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.data.local.entity.ServiceCategory
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.ServiceOrder
import com.example.carrepairmanager.data.local.entity.Vehicle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 在本机运行真实 Room/SQLite，验证索引、外键、事务、关系查询和磁盘重开。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarRepairRepositoryTest {
    private lateinit var database: CarRepairDatabase
    private lateinit var repository: CarRepairRepository
    private val timestamp = 1_791_000_000_000L
    private val diskName = "milestone1_persistence_test.db"

    /** 每项测试使用独立内存数据库，避免接触应用的真实档案。 */
    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), CarRepairDatabase::class.java,
        ).build()
        repository = CarRepairRepository(database) { timestamp }
    }

    /** 关闭连接并清理测试专用磁盘库，不留下测试数据。 */
    @After
    fun tearDown() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(diskName)
    }

    /** 最小车辆只有车牌；存取保持原显示车牌并使用独立主键。 */
    @Test
    fun createAndFindVehicleWithOptionalCustomer() = runBlocking {
        val id = repository.createVehicle(Vehicle(plateNumber = " 冀 a88 888 "))
        val record = requireNotNull(repository.findVehicleByPlate("冀A88888"))
        assertEquals(id, record.vehicle.id)
        assertEquals("冀 a88 888", record.vehicle.plateNumber)
        assertEquals("冀A88888", record.vehicle.normalizedPlateNumber)
        assertEquals(timestamp, record.vehicle.createdAt)
        assertEquals(timestamp, record.vehicle.updatedAt)
        assertNull(record.customer)
        assertNull(repository.getVehicle(id + 1))
        assertNull(repository.findVehicleByPlate("京B54321"))
        assertEquals(1, repository.observeVehicles().first().size)
    }

    /** 同一客户可关联多车，关系查询不需要重复创建客户。 */
    @Test
    fun customerCanOwnMultipleVehicles() = runBlocking {
        val first = repository.createVehicle(
            Vehicle(plateNumber = "冀A88888"), Customer(name = "测试客户", phone = "测试手机号"),
        )
        val customer = requireNotNull(repository.getVehicle(first)?.customer)
        val second = repository.createVehicle(Vehicle(plateNumber = "冀B12345", customerId = customer.id))
        assertEquals(customer, repository.getVehicle(second)?.customer)
        assertEquals(timestamp, customer.createdAt)
        assertEquals(1L, rowCount("customers"))
    }

    /** 防重复不只依赖页面检查；数据库唯一索引也拒绝直接插入。 */
    @Test
    fun duplicatePlateDoesNotCreateAnExtraCustomer() = runBlocking {
        repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        expectFailure<DuplicatePlateException> {
            repository.createVehicle(Vehicle(plateNumber = "冀 a88 888"), Customer(name = "不应保存"))
        }
        expectFailure<SQLiteException> {
            database.recordsDao().insertVehicle(Vehicle(plateNumber = "另一种显示", normalizedPlateNumber = "冀A88888"))
        }
        assertEquals(1L, rowCount("vehicles"))
        assertEquals(0L, rowCount("customers"))
    }

    /** 客户已插入后强制让车辆插入失败，也不能留下孤立客户档案。 */
    @Test
    fun failingVehicleRollsBackItsNewCustomer() = runBlocking {
        executeSql("CREATE TRIGGER reject_test_vehicle BEFORE INSERT ON vehicles BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        expectFailure<SQLiteException> {
            repository.createVehicle(Vehicle(plateNumber = "冀A88888"), Customer(name = "不应保存"))
        }
        assertEquals(0L, rowCount("customers"))
        assertEquals(0L, rowCount("vehicles"))
    }

    /** 归档只隐藏默认列表，服务历史和唯一车牌仍保留。 */
    @Test
    fun archivedVehicleKeepsHistoryAndPlateReservation() = runBlocking {
        val id = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val orderId = repository.createServiceOrder(id, "2026-10-02", emptyList())
        executeSql("UPDATE vehicles SET isArchived = 1 WHERE id = ?", arrayOf(id))
        assertTrue(repository.observeVehicles().first().isEmpty())
        assertEquals(id, repository.observeVehicles(includeArchived = true).first().single().vehicle.id)
        assertEquals(orderId, repository.observeServiceHistory(id).first().single().id)
        assertTrue(requireNotNull(repository.getVehicle(id)).vehicle.isArchived)
        expectFailure<DuplicatePlateException> { repository.createVehicle(Vehicle(plateNumber = "冀a88888")) }
    }

    /** 多项目合计由 Repository 生成；日期相同也有稳定倒序，且历史不会跨车辆混入。 */
    @Test
    fun serviceTotalsAndHistoryAreConsistent() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val otherId = repository.createVehicle(Vehicle(plateNumber = "京B54321"))
        val oldId = repository.createServiceOrder(vehicleId, "2026-09-01", emptyList())
        val currentId = repository.createServiceOrder(vehicleId, "2026-10-02", listOf(
            ServiceItem(category = ServiceCategory.MAINTENANCE, name = " 更换机油 ", amountCent = 30_000),
            ServiceItem(category = ServiceCategory.WASH, name = "洗车", amountCent = 2_990),
        ), remark = "测试服务备注")
        val laterId = repository.createServiceOrder(vehicleId, "2026-10-02", emptyList())
        repository.createServiceOrder(otherId, "2026-12-01", emptyList())
        assertEquals(listOf(laterId, currentId, oldId), repository.observeServiceHistory(vehicleId).first().map { it.id })
        val detail = requireNotNull(repository.getServiceOrder(currentId))
        assertEquals(32_990L, detail.order.totalAmountCent)
        assertEquals(timestamp, detail.order.createdAt)
        assertEquals("测试服务备注", detail.order.remark)
        assertEquals(listOf("更换机油", "洗车"), detail.items.map { it.name })
        assertEquals(listOf(ServiceCategory.MAINTENANCE, ServiceCategory.WASH), detail.items.map { it.category })
        assertTrue(detail.items.all { it.orderId == currentId })
        assertTrue(detail.photos.isEmpty())
        assertNull(repository.getServiceOrder(Long.MAX_VALUE))
    }

    /** 文件操作尚未接入，仅用元数据夹具验证图片所属服务单、枚举和排序。 */
    @Test
    fun photoMetadataIsRelatedAndOrdered() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val orderId = repository.createServiceOrder(vehicleId, "2026-10-02", emptyList())
        executeSql(
            "INSERT INTO photos(orderId, relativePath, type, createdAt, sortOrder) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(orderId, "images/order_test/after.jpg", "AFTER", timestamp, 2),
        )
        executeSql(
            "INSERT INTO photos(orderId, relativePath, type, createdAt, sortOrder) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(orderId, "images/order_test/before.jpg", "BEFORE", timestamp, 1),
        )
        val photos = requireNotNull(repository.getServiceOrder(orderId)).photos
        assertEquals(listOf(PhotoType.BEFORE, PhotoType.AFTER), photos.map { it.type })
        assertEquals(listOf(1, 2), photos.map { it.sortOrder })
        assertEquals("images/order_test/before.jpg", photos.first().relativePath)
        assertTrue(photos.all { it.orderId == orderId })
    }

    /** 直接 DAO 写入也不能产生悬空车辆、服务单、项目或照片。 */
    @Test
    fun foreignKeysRejectOrphansAndParentDeletion() = runBlocking {
        val dao = database.recordsDao()
        expectFailure<SQLiteException> {
            dao.insertVehicle(Vehicle(customerId = 999, plateNumber = "冀A88888", normalizedPlateNumber = "冀A88888"))
        }
        expectFailure<SQLiteException> { dao.insertServiceOrder(ServiceOrder(vehicleId = 999, serviceDate = "2026-10-02")) }
        expectFailure<SQLiteException> {
            dao.insertServiceItems(listOf(ServiceItem(orderId = 999, category = ServiceCategory.REPAIR, name = "孤立项目", amountCent = 0)))
        }
        expectFailure<SQLiteException> {
            executeSql("INSERT INTO photos(orderId, relativePath, type, createdAt, sortOrder) VALUES (999, 'images/test.jpg', 'OTHER', 0, 0)")
        }
        val id = repository.createVehicle(Vehicle(plateNumber = "京B54321"), Customer(name = "测试客户"))
        val customerId = requireNotNull(repository.getVehicle(id)?.customer).id
        repository.createServiceOrder(id, "2026-10-02", emptyList())
        expectFailure<SQLiteException> { executeSql("DELETE FROM customers WHERE id = ?", arrayOf(customerId)) }
        expectFailure<SQLiteException> { executeSql("DELETE FROM vehicles WHERE id = ?", arrayOf(id)) }
        assertNotNull(repository.getVehicle(id))
    }

    /** 在项目批量插入中途触发 SQLite 失败，整张服务单及此前项目都必须回滚。 */
    @Test
    fun failingItemRollsBackTheEntireServiceOrder() = runBlocking {
        val id = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        executeSql("CREATE TRIGGER reject_test_item BEFORE INSERT ON service_items WHEN NEW.name = '失败项目' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        expectFailure<SQLiteException> {
            repository.createServiceOrder(id, "2026-10-02", listOf(
                ServiceItem(category = ServiceCategory.REPAIR, name = "先插入项目", amountCent = 100),
                ServiceItem(category = ServiceCategory.REPAIR, name = "失败项目", amountCent = 100),
            ))
        }
        assertEquals(0L, rowCount("service_orders"))
        assertEquals(0L, rowCount("service_items"))
        assertTrue(repository.observeServiceHistory(id).first().isEmpty())
    }

    /** 非法输入在保存前明确失败，数据库不产生半成品。 */
    @Test
    fun invalidInputsDoNotPersistRecords() = runBlocking {
        expectFailure<IllegalArgumentException> { repository.createVehicle(Vehicle(plateNumber = " \t")) }
        val id = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        expectFailure<IllegalArgumentException> { repository.createServiceOrder(id, "2026-02-30", emptyList()) }
        expectFailure<IllegalArgumentException> { repository.createServiceOrder(999, "2026-10-02", emptyList()) }
        expectFailure<IllegalArgumentException> {
            repository.createServiceOrder(id, "2026-10-02", listOf(ServiceItem(category = ServiceCategory.OTHER, name = " ", amountCent = 0)))
        }
        expectFailure<IllegalArgumentException> {
            repository.createServiceOrder(id, "2026-10-02", listOf(ServiceItem(category = ServiceCategory.OTHER, name = "项目", amountCent = -1)))
        }
        assertEquals(0L, rowCount("service_orders"))
        assertEquals(1L, rowCount("vehicles"))
    }

    /** 持续订阅会在 Room 写入后收到新列表，无需页面手动重新查询。 */
    @Test
    fun vehicleFlowReceivesDatabaseInvalidations() = runBlocking {
        withTimeout(15_000) {
            val initialReceived = CompletableDeferred<Unit>()
            val updates = async {
                repository.observeVehicles().onEach { initialReceived.complete(Unit) }.take(2).toList()
            }
            initialReceived.await()
            val id = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
            val snapshots = updates.await()
            assertTrue(snapshots.first().isEmpty())
            assertEquals(id, snapshots.last().single().vehicle.id)
        }
    }

    /** 关闭并重新构建同一路径的 Room 实例，实际验证磁盘数据与 Long 金额未丢失。 */
    @Test
    fun recordsSurviveClosingAndReopeningDiskDatabase() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(diskName)
        database.close()
        database = Room.databaseBuilder(context, CarRepairDatabase::class.java, diskName).build()
        repository = CarRepairRepository(database) { timestamp }
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"), Customer(name = "持久化测试"))
        val orderId = repository.createServiceOrder(vehicleId, "2026-10-02", listOf(
            ServiceItem(category = ServiceCategory.PART, name = "测试配件", amountCent = 3_000_000_001L),
        ))
        database.close()
        database = Room.databaseBuilder(context, CarRepairDatabase::class.java, diskName).build()
        repository = CarRepairRepository(database)
        assertEquals("持久化测试", repository.getVehicle(vehicleId)?.customer?.name)
        assertEquals(3_000_000_001L, repository.getServiceOrder(orderId)?.order?.totalAmountCent)
        assertEquals(ServiceCategory.PART, repository.getServiceOrder(orderId)?.items?.single()?.category)
        assertEquals(orderId, repository.observeServiceHistory(vehicleId).first().single().id)
        assertFalse(repository.observeVehicles().first().single().vehicle.isArchived)
    }

    /** 留空、补填、再次清空和免费项目均经真实 Room 保存，历史摘要同步更新。 */
    @Test
    fun optionalAmountsPersistAndUpdateAcrossServiceHistory() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "京A12345"))
        val known = ServiceItem(category = ServiceCategory.WASH, name = "洗车", amountCent = 2990)
        val pending = ServiceItem(category = ServiceCategory.REPAIR, name = "待报价维修")
        val orderId = repository.createServiceOrder(vehicleId, "2026-10-06", listOf(known, pending))
        assertNull(repository.getServiceOrder(orderId)?.order?.totalAmountCent)
        assertNull(repository.getServiceOrder(orderId)?.items?.last()?.amountCent)
        assertNull(repository.observeRecentServices().first().single().totalAmountCent)
        assertNull(repository.observeServiceHistoryEntries(vehicleId).first().single().order.totalAmountCent)
        repository.updateServiceOrder(orderId, "2026-10-06", listOf(known, pending.copy(amountCent = 0)), "已补填")
        assertEquals(2990L, repository.getServiceOrder(orderId)?.order?.totalAmountCent)
        repository.updateServiceOrder(orderId, "2026-10-06", listOf(known, pending), "再次待填")
        assertNull(repository.getServiceOrder(orderId)?.order?.totalAmountCent)
    }

    /** 待填状态写入磁盘后重开仍为空，不变成 0 或丢失项目。 */
    @Test
    fun pendingAmountsSurviveDiskReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(diskName)
        database.close()
        database = Room.databaseBuilder(context, CarRepairDatabase::class.java, diskName).build()
        repository = CarRepairRepository(database)
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "京B12345"))
        val orderId = repository.createServiceOrder(vehicleId, "2026-10-06", listOf(
            ServiceItem(category = ServiceCategory.REPAIR, name = "待填项目")))
        database.close()
        database = Room.databaseBuilder(context, CarRepairDatabase::class.java, diskName).build()
        repository = CarRepairRepository(database)
        val detail = requireNotNull(repository.getServiceOrder(orderId))
        assertNull(detail.order.totalAmountCent)
        assertNull(detail.items.single().amountCent)
        assertEquals("待填项目", detail.items.single().name)
    }

    /** 修改车辆保留主键和历史；已有客户联系人更新同步到多车关系。 */
    @Test
    fun editingVehiclePreservesHistoryAndSharedCustomer() = runBlocking {
        val id = repository.createVehicle(Vehicle(plateNumber = "冀A88888"), Customer(name = "旧联系人"))
        val record = requireNotNull(repository.getVehicle(id))
        val second = repository.createVehicle(Vehicle(plateNumber = "冀B12345", customerId = record.customer?.id))
        val orderId = repository.createServiceOrder(id, "2026-10-02", emptyList())
        val editor = CarRepairRepository(database) { timestamp + 100 }
        editor.updateVehicle(record.vehicle.copy(plateNumber = "冀 c88 888", brand = "宝马"), record.customer?.copy(name = "新联系人"))
        val updated = requireNotNull(repository.getVehicle(id))
        assertEquals("冀C88888", updated.vehicle.normalizedPlateNumber)
        assertEquals(timestamp, updated.vehicle.createdAt)
        assertEquals(timestamp + 100, updated.vehicle.updatedAt)
        assertEquals("新联系人", repository.getVehicle(second)?.customer?.name)
        assertEquals(orderId, repository.observeServiceHistory(id).first().single().id)
        expectFailure<DuplicatePlateException> { editor.updateVehicle(updated.vehicle.copy(plateNumber = "冀B12345")) }
        assertEquals("冀C88888", repository.getVehicle(id)?.vehicle?.normalizedPlateNumber)
    }

    /** 编辑替换项目并重新求和，同时保留服务主键、创建时间和关联图片。 */
    @Test
    fun editingServiceRecalculatesTotalAndPreservesPhotos() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val orderId = repository.createServiceOrder(vehicleId, "2026-10-02", listOf(
            ServiceItem(category = ServiceCategory.REPAIR, name = "旧项目", amountCent = 100)))
        executeSql("INSERT INTO photos(orderId, relativePath, type, createdAt, sortOrder) VALUES (?, 'images/test.jpg', 'BEFORE', 0, 0)", arrayOf(orderId))
        repository.updateServiceOrder(orderId, "2026-09-30", listOf(
            ServiceItem(category = ServiceCategory.WASH, name = "洗车", amountCent = 2990),
            ServiceItem(category = ServiceCategory.PART, name = "配件", amountCent = 12800)), "编辑备注")
        val detail = requireNotNull(repository.getServiceOrder(orderId))
        assertEquals(15_790L, detail.order.totalAmountCent)
        assertEquals(listOf("洗车", "配件"), detail.items.map { it.name })
        assertEquals("2026-09-30", detail.order.serviceDate)
        assertEquals(timestamp, detail.order.createdAt)
        assertEquals(1, detail.photos.size)
        val history = repository.observeServiceHistoryEntries(vehicleId).first().single()
        assertEquals("洗车 · 配件", history.itemSummary)
        assertEquals(1, history.photoCount)
    }

    /** 替换项目中途失败也回滚删除操作与服务合计，原来的项目不得消失。 */
    @Test
    fun failedServiceEditRestoresOriginalItemsAndTotal() = runBlocking {
        val id = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val orderId = repository.createServiceOrder(id, "2026-10-02", listOf(
            ServiceItem(category = ServiceCategory.REPAIR, name = "原项目", amountCent = 900)))
        executeSql("CREATE TRIGGER reject_edit BEFORE INSERT ON service_items WHEN NEW.name = '失败' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        expectFailure<SQLiteException> { repository.updateServiceOrder(orderId, "2026-09-30", listOf(
            ServiceItem(category = ServiceCategory.REPAIR, name = "失败", amountCent = 1)), "不应保存") }
        val detail = requireNotNull(repository.getServiceOrder(orderId))
        assertEquals(900L, detail.order.totalAmountCent)
        assertEquals("原项目", detail.items.single().name)
        assertEquals("2026-10-02", detail.order.serviceDate)
        assertEquals("", detail.order.remark)
    }

    /** 归档可恢复；删除无照片服务级联清理项目，有照片服务必须保留。 */
    @Test
    fun archiveRestoreAndSafeServiceDeletion() = runBlocking {
        val id = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val orderId = repository.createServiceOrder(id, "2026-10-02", listOf(
            ServiceItem(category = ServiceCategory.REPAIR, name = "补胎", amountCent = 5000)))
        repository.setVehicleArchived(id, true)
        assertTrue(repository.observeVehicleList().first().isEmpty())
        repository.setVehicleArchived(id, false)
        assertEquals(id, repository.observeVehicleList().first().single().vehicle.id)
        executeSql("INSERT INTO photos(orderId, relativePath, type, createdAt, sortOrder) VALUES (?, 'images/test.jpg', 'OTHER', 0, 0)", arrayOf(orderId))
        expectFailure<IllegalArgumentException> { repository.deleteServiceOrder(orderId) }
        assertNotNull(repository.getServiceOrder(orderId))
        executeSql("DELETE FROM photos WHERE orderId = ?", arrayOf(orderId))
        repository.deleteServiceOrder(orderId)
        assertNull(repository.getServiceOrder(orderId))
        assertEquals(0L, rowCount("service_items"))
        assertNotNull(repository.getVehicle(id))
    }

    /** 后端强制校验归档和当前车牌；空档案可删除，孤立客户保留且车牌可重新建档。 */
    @Test
    fun permanentVehicleDeleteRequiresArchiveAndMatchingCurrentPlate() = runBlocking {
        val id = repository.createVehicle(Vehicle(plateNumber = "冀A12345"), Customer(name = "测试客户"))
        val customer = repository.getVehicle(id)?.customer
        expectFailure<IllegalArgumentException> { repository.deleteVehicle(id, "冀A12345") }
        repository.setVehicleArchived(id, true)
        listOf("", "京B54321").forEach { plate ->
            expectFailure<IllegalArgumentException> { repository.deleteVehicle(id, plate) }
        }
        // 编辑后旧确认文本不能删除新车牌的档案，归档状态仍由原记录保留。
        repository.updateVehicle(requireNotNull(repository.getVehicle(id)).vehicle.copy(plateNumber = "冀A88888"))
        expectFailure<IllegalArgumentException> { repository.deleteVehicle(id, "冀A12345") }
        repository.setVehicleArchived(id, false)
        expectFailure<IllegalArgumentException> { repository.deleteVehicle(id, "冀A88888") }
        repository.setVehicleArchived(id, true)
        val summary = requireNotNull(repository.observeVehicleDeletionSummary(id).first())
        assertEquals(0, summary.serviceCount); assertEquals(0, summary.photoCount)
        repository.deleteVehicle(id, " 冀 a88 888 ")
        assertNull(repository.getVehicle(id))
        assertNull(repository.observeVehicleDeletionSummary(id).first())
        assertEquals(listOf(customer), database.recordsDao().backupCustomers())
        assertTrue(repository.createVehicle(Vehicle(plateNumber = "冀A88888")) > id)
        expectFailure<IllegalArgumentException> { repository.deleteVehicle(id, "冀A88888") }
    }

    /** 未配置文件存储时不能只删除有照片的元数据，服务、项目和客户必须原样保留。 */
    @Test
    fun permanentVehicleDeleteWithoutPhotoStoreKeepsEveryRow() = runBlocking {
        val id = repository.createVehicle(Vehicle(plateNumber = "冀A12345"))
        val orderId = repository.createServiceOrder(id, "2026-10-06", listOf(
            ServiceItem(category = ServiceCategory.REPAIR, name = "测试项目", amountCent = 100)))
        executeSql("INSERT INTO photos(orderId, relativePath, type, createdAt, sortOrder) VALUES (?, 'images/test.jpg', 'OTHER', 0, 0)", arrayOf(orderId))
        repository.setVehicleArchived(id, true)
        val before = repository.withBackupSnapshot { it }
        expectFailure<IllegalArgumentException> { repository.deleteVehicle(id, "冀A12345") }
        assertEquals(before, repository.withBackupSnapshot { it })
    }

    /** 查询使用数据库全量筛选后限制可见批次，转义匹配符并优先车牌结果。 */
    @Test
    fun searchBrandSortingAndRecentSummaries() = runBlocking {
        val plateMatch = repository.createVehicle(Vehicle(plateNumber = "冀A12345", brand = "宝马"), Customer(name = "张师傅", phone = "13800000000"))
        val nameMatch = repository.createVehicle(Vehicle(plateNumber = "京B88888", brand = "丰田"), Customer(name = "A123车主"))
        repository.createServiceOrder(plateMatch, "2026-10-02", emptyList())
        assertEquals(listOf(plateMatch, nameMatch), repository.observeVehicleList(query = "a123").first().map { it.vehicle.id })
        assertEquals(plateMatch, repository.observeVehicleList(query = "138000").first().single().vehicle.id)
        assertEquals(plateMatch, repository.observeVehicleList(query = "张师傅").first().single().vehicle.id)
        assertTrue(repository.observeVehicleList(query = "%").first().isEmpty())
        assertTrue(repository.observeVehicleList(query = "_").first().isEmpty())
        assertEquals(nameMatch, repository.observeVehicleList(brand = "丰田").first().single().vehicle.id)
        assertEquals(plateMatch, repository.observeVehicleList(limit = 1).first().single().vehicle.id)
        assertEquals(nameMatch, repository.observeVehicleList(recentFirst = false, limit = 1).first().single().vehicle.id)
        assertEquals(setOf("宝马", "丰田"), repository.observeBrands().first().toSet())
        assertEquals("2026-10-02", repository.observeVehicleList().first().first().lastServiceDate)
        val recent = repository.observeRecentServices().first().single()
        assertEquals(plateMatch, recent.vehicleId)
        assertEquals("张师傅", recent.customerName)
    }

    /** 匹配字段的优先级高于到店日期；车牌大小写和内部空白沿用标准化规则。 */
    @Test
    fun searchPriorityIncludesPhoneAfterPlateAndName() = runBlocking {
        val plate = repository.createVehicle(Vehicle(plateNumber = " 冀 a12 345 ", brand = "宝马"),
            Customer(name = "123车主", phone = "13800000123"))
        val name = repository.createVehicle(Vehicle(plateNumber = "京B88888"), Customer(name = "第123位车主"))
        val phone = repository.createVehicle(Vehicle(plateNumber = "津C66666"), Customer(phone = "13900000123"))
        repository.createVehicle(Vehicle(plateNumber = "鲁D55555"))
        repository.createServiceOrder(phone, "2026-10-03", emptyList())
        repository.createServiceOrder(name, "2026-10-02", emptyList())
        assertEquals(listOf(plate, name, phone), repository.observeVehicleList(query = "123").first().map { it.vehicle.id })
        assertEquals(plate, repository.observeVehicleList(query = " \t冀 a 12 345\n").first().single().vehicle.id)
        assertEquals(name, repository.observeVehicleList(query = "第123").first().single().vehicle.id)
        assertEquals(phone, repository.observeVehicleList(query = "139000").first().single().vehicle.id)
        assertEquals(4, repository.observeVehicleList(query = " \t\n").first().size)
    }

    /** 百分号、下划线和反斜杠按输入的字面值查找，不能意外扩大到全部档案。 */
    @Test
    fun searchMatchesLiteralLikeCharacters() = runBlocking {
        val special = repository.createVehicle(Vehicle(plateNumber = "冀A12345"),
            Customer(name = "师傅%_\\记录"))
        repository.createVehicle(Vehicle(plateNumber = "京B88888"), Customer(name = "普通车主"))
        listOf("%", "_", "\\", "%_\\").forEach { query ->
            assertEquals(special, repository.observeVehicleList(query = query).first().single().vehicle.id)
        }
        assertTrue(repository.observeVehicleList(query = "%' OR 1=1 --").first().isEmpty())
    }

    /** 先在全库组合搜索、品牌与归档条件，再限制批次，不能只筛选前五十辆车。 */
    @Test
    fun searchAndBrandFiltersApplyBeforePagination() = runBlocking {
        val older = repository.createVehicle(Vehicle(plateNumber = "冀A12345", brand = "宝马"), Customer(name = "目标车主"))
        repeat(55) { index ->
            repository.createVehicle(Vehicle(plateNumber = "京T" + index.toString().padStart(5, '0'), brand = "丰田"))
        }
        val archived = repository.createVehicle(Vehicle(plateNumber = "津B66666", brand = "宝马"), Customer(name = "目标车主"))
        repository.setVehicleArchived(archived, true)
        assertEquals(older, repository.observeVehicleList(query = "目标", brand = "宝马", limit = 1).first().single().vehicle.id)
        assertTrue(repository.observeVehicleList(query = "目标", brand = "丰田").first().isEmpty())
        assertEquals(listOf(archived, older), repository.observeVehicleList(query = "目标", brand = "宝马",
            includeArchived = true).first().map { it.vehicle.id })
        assertEquals(55, repository.observeVehicleList(brand = "丰田", limit = 100).first().size)
    }

    /** 最近到店按服务日期与同日 ID 排序，编辑、删除和归档会刷新摘要而不改变车辆档案。 */
    @Test
    fun recentVisitsFollowDateAndReactToOrderChanges() = runBlocking {
        val first = repository.createVehicle(Vehicle(plateNumber = "冀A11111"))
        val second = repository.createVehicle(Vehicle(plateNumber = "京B22222"))
        val noVisit = repository.createVehicle(Vehicle(plateNumber = "津C33333"))
        val firstOrder = repository.createServiceOrder(first, "2026-10-01", emptyList())
        val secondOrder = repository.createServiceOrder(second, "2026-10-03", emptyList())
        val backdated = repository.createServiceOrder(first, "2026-09-27", emptyList())
        val sameDay = repository.createServiceOrder(second, "2026-10-03", emptyList())
        assertEquals(listOf(sameDay, secondOrder, firstOrder, backdated),
            repository.observeRecentServices().first().map { it.orderId })
        assertEquals(listOf(second, first, noVisit), repository.observeVehicleList().first().map { it.vehicle.id })
        assertEquals(noVisit, repository.observeVehicleList(recentFirst = false).first().first().vehicle.id)

        val initialReceived = CompletableDeferred<Unit>()
        val promoted = async(Dispatchers.Default) {
            repository.observeRecentServices().onEach { initialReceived.complete(Unit) }
                .first { it.firstOrNull()?.orderId == firstOrder }
        }
        withTimeout(5_000) { initialReceived.await() }
        repository.updateServiceOrder(firstOrder, "2026-10-04", emptyList(), "")
        assertEquals(firstOrder, withTimeout(5_000) { promoted.await() }.first().orderId)
        assertEquals(first, repository.observeVehicleList().first().first().vehicle.id)
        repository.deleteServiceOrder(firstOrder)
        assertEquals("2026-09-27", repository.observeVehicleList().first().single { it.vehicle.id == first }.lastServiceDate)
        repository.setVehicleArchived(second, true)
        assertEquals(backdated, repository.observeRecentServices().first().single().orderId)
        repository.setVehicleArchived(second, false)
        assertEquals(listOf(sameDay, secondOrder), repository.observeRecentServices(limit = 2).first().map { it.orderId })
    }

    /** 首页只读取最近二十次到店；同一车辆多次到店仍保留独立服务记录。 */
    @Test
    fun recentVisitsKeepOnlyTheLatestTwentyEntries() = runBlocking {
        val vehicle = repository.createVehicle(Vehicle(plateNumber = "冀A12345"))
        val orders = (1..22).map { day ->
            repository.createServiceOrder(vehicle, "2026-09-" + day.toString().padStart(2, '0'), emptyList())
        }
        assertEquals(orders.reversed().take(20), repository.observeRecentServices().first().map { it.orderId })
    }

    /** 测试夹具 SQL 在 IO 线程执行，保留生产 Room 的主线程访问禁令。 */
    private suspend fun executeSql(sql: String, arguments: Array<out Any?> = emptyArray()) = withContext(Dispatchers.IO) {
        database.openHelper.writableDatabase.execSQL(sql, arguments)
    }

    /** 只用于固定测试表的记录数断言，游标及时关闭。 */
    private suspend fun rowCount(table: String): Long = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            check(it.moveToFirst())
            it.getLong(0)
        }
    }

    /** 异常类型必须符合预期；其他错误直接抛出，避免测试掩盖实现缺陷。 */
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw error
        }
        fail("Expected ${T::class.java.simpleName}")
    }
}
