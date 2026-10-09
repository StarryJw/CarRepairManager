package com.example.carrepairmanager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.carrepairmanager.data.local.entity.Customer
import com.example.carrepairmanager.data.local.entity.Photo
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.ServiceOrder
import com.example.carrepairmanager.data.local.entity.Vehicle

/** version 2 允许待填金额，通过保留数据的迁移升级，不使用清库兜底。 */
@Database(
    entities = [Customer::class, Vehicle::class, ServiceOrder::class, ServiceItem::class, Photo::class],
    version = 2,
    exportSchema = true,
)
abstract class CarRepairDatabase : RoomDatabase() {
    /** 数据访问统一经过 DAO，页面不直接操作 SQLite。 */
    abstract fun recordsDao(): CarRepairDao

    companion object {
        /** 取消金额 NOT NULL；先暂存子表，避免重建服务单时 CASCADE 删除项目或照片元数据。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            /** Room 升级事务内先复制数据，再重建约束并恢复自增上界，失败时整体回滚。 */
            override fun migrate(db: SupportSQLiteDatabase) {
                val sequences = mutableMapOf<String, Long>()
                db.query("SELECT name, seq FROM sqlite_sequence WHERE name IN ('service_orders', 'service_items', 'photos')").use { cursor ->
                    while (cursor.moveToNext()) sequences[cursor.getString(0)] = cursor.getLong(1)
                }
                db.execSQL("CREATE TABLE `_service_items_v1` AS SELECT * FROM `service_items`")
                db.execSQL("CREATE TABLE `_photos_v1` AS SELECT * FROM `photos`")
                db.execSQL("""CREATE TABLE `_service_orders_v2` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vehicleId` INTEGER NOT NULL,
                    `serviceDate` TEXT NOT NULL, `totalAmountCent` INTEGER, `remark` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                    FOREIGN KEY(`vehicleId`) REFERENCES `vehicles`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)""".trimIndent())
                db.execSQL("INSERT INTO `_service_orders_v2` SELECT * FROM `service_orders`")
                db.execSQL("DROP TABLE `photos`")
                db.execSQL("DROP TABLE `service_items`")
                db.execSQL("DROP TABLE `service_orders`")
                db.execSQL("ALTER TABLE `_service_orders_v2` RENAME TO `service_orders`")
                db.execSQL("CREATE INDEX `index_service_orders_vehicleId_serviceDate` ON `service_orders` (`vehicleId`, `serviceDate`)")
                db.execSQL("""CREATE TABLE `service_items` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL,
                    `category` TEXT NOT NULL, `name` TEXT NOT NULL, `amountCent` INTEGER, `remark` TEXT NOT NULL,
                    FOREIGN KEY(`orderId`) REFERENCES `service_orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
                db.execSQL("INSERT INTO `service_items` SELECT * FROM `_service_items_v1`")
                db.execSQL("CREATE INDEX `index_service_items_orderId` ON `service_items` (`orderId`)")
                db.execSQL("""CREATE TABLE `photos` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `orderId` INTEGER NOT NULL,
                    `relativePath` TEXT NOT NULL, `type` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL,
                    FOREIGN KEY(`orderId`) REFERENCES `service_orders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
                db.execSQL("INSERT INTO `photos` SELECT * FROM `_photos_v1`")
                db.execSQL("CREATE INDEX `index_photos_orderId_sortOrder` ON `photos` (`orderId`, `sortOrder`)")
                db.execSQL("DROP TABLE `_service_items_v1`")
                db.execSQL("DROP TABLE `_photos_v1`")
                // 保留已删除记录的自增上界，避免升级后复用历史 ID；图片文件路径始终不变。
                sequences.forEach { (table, sequence) ->
                    db.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", arrayOf(table))
                    db.execSQL("INSERT INTO sqlite_sequence (name, seq) VALUES (?, ?)", arrayOf<Any>(table, sequence))
                }
            }
        }

        /** 使用应用上下文构建私有数据库，实际打开由 Room 异步查询触发。 */
        fun build(context: Context): CarRepairDatabase = Room.databaseBuilder(
            context.applicationContext,
            CarRepairDatabase::class.java,
            "car_repair.db",
        ).addMigrations(MIGRATION_1_2).build()
    }
}
