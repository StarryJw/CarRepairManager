package com.example.carrepairmanager.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.example.carrepairmanager.data.local.CarRepairDatabase
import com.example.carrepairmanager.data.local.entity.Photo
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.data.local.entity.ServiceCategory
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.Vehicle
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 从已导出的真实 version 1 schema 建库，经生产入口升级，验证旧金额、关联与图片都保留。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AmountMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var database: CarRepairDatabase? = null

    /** 仅删除 Robolectric 沙箱内建出的数据库，不连接手机或使用用户档案。 */
    @After
    fun closeFixture() {
        database?.close()
        app.deleteDatabase("car_repair.db")
    }

    /** 完整旧库升级不能丢照片、重用已删除的 ID，明确的零金额也不能变成待填。 */
    @Test
    fun populatedVersionOnePreservesDataFilesAndSequences() = runBlocking {
        createVersionOne(populated = true)
        val oldPhoto = File(app.filesDir, "images/order_50/old.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val upgraded = CarRepairDatabase.build(app).also { database = it }
        val records = CarRepairRepository(upgraded)
        val detail = requireNotNull(records.getServiceOrder(50))
        assertEquals(2990L, detail.order.totalAmountCent)
        assertEquals(listOf(2990L, 0L), detail.items.map { it.amountCent })
        assertEquals(70L, detail.photos.single().id)
        assertEquals("images/order_50/old.jpg", detail.photos.single().relativePath)
        assertArrayEquals(byteArrayOf(1, 2, 3), oldPhoto.readBytes())
        assertEquals("旧客户", records.getVehicle(20)?.customer?.name)
        assertTrue(requireNotNull(records.getVehicle(20)).vehicle.isArchived)
        upgraded.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }

        val newOrder = records.createServiceOrder(20, "2026-10-06", listOf(
            ServiceItem(category = ServiceCategory.REPAIR, name = "待报价")))
        assertEquals(101L, newOrder)
        val pending = requireNotNull(records.getServiceOrder(newOrder))
        assertEquals(201L, pending.items.single().id)
        assertNull(pending.items.single().amountCent)
        assertNull(pending.order.totalAmountCent)
        val newPhoto = upgraded.recordsDao().insertPhoto(Photo(orderId = newOrder,
            relativePath = "images/order_$newOrder/new.jpg", type = PhotoType.OTHER))
        assertEquals(301L, newPhoto)
        upgraded.close()
        val reopened = CarRepairDatabase.build(app).also { database = it }
        assertNull(CarRepairRepository(reopened).getServiceOrder(newOrder)?.order?.totalAmountCent)
        assertEquals(1, CarRepairRepository(reopened).getServiceOrder(50)?.photos?.size)
    }

    /** 尚无业务数据的旧安装也能升级，并保存待填项目，不依赖已有 sqlite_sequence 行。 */
    @Test
    fun emptyVersionOneCanUpgradeAndSavePendingAmount() = runBlocking {
        createVersionOne(populated = false)
        val upgraded = CarRepairDatabase.build(app).also { database = it }
        val records = CarRepairRepository(upgraded)
        val vehicleId = records.createVehicle(Vehicle(plateNumber = "京B12345"))
        val orderId = records.createServiceOrder(vehicleId, "2026-10-06", listOf(
            ServiceItem(category = ServiceCategory.OTHER, name = "待填金额项目")))
        assertNull(records.getServiceOrder(orderId)?.order?.totalAmountCent)
        upgraded.openHelper.writableDatabase.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
        }
    }

    /** 直接使用版本库中的 schema 1 SQL，避免用新版实体造出并不真实的旧库。 */
    private fun createVersionOne(populated: Boolean) {
        app.deleteDatabase("car_repair.db")
        val path = app.getDatabasePath("car_repair.db")
        check(requireNotNull(path.parentFile).exists() || path.parentFile?.mkdirs() == true)
        val schema = JSONObject(File(checkNotNull(System.getProperty("room.schema.dir")),
            "com.example.carrepairmanager.data.local.CarRepairDatabase/1.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.setForeignKeyConstraintsEnabled(true)
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                val tableName = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                entity.optJSONArray("indices")?.let { indices ->
                    repeat(indices.length()) { old.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", tableName)) }
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            if (populated) {
                old.execSQL("INSERT INTO customers VALUES (10, '旧客户', '13000000000', '旧备注', 1, 2)")
                old.execSQL("INSERT INTO vehicles VALUES (20, 10, '京A12345', '京A12345', '品牌', '车型', '白色', '旧车备注', 1, 1, 2)")
                old.execSQL("INSERT INTO service_orders VALUES (50, 20, '2026-10-02', 2990, '旧服务', 1, 2)")
                old.execSQL("INSERT INTO service_items VALUES (60, 50, 'REPAIR', '旧项目', 2990, '旧项目备注')")
                old.execSQL("INSERT INTO service_items VALUES (61, 50, 'WASH', '免费项目', 0, '')")
                old.execSQL("INSERT INTO photos VALUES (70, 50, 'images/order_50/old.jpg', 'BEFORE', 1, 0)")
                mapOf("service_orders" to 100L, "service_items" to 200L, "photos" to 300L).forEach { (table, seq) ->
                    old.execSQL("UPDATE sqlite_sequence SET seq = ? WHERE name = ?", arrayOf<Any>(seq, table))
                }
            }
            old.version = 1
        }
    }
}
