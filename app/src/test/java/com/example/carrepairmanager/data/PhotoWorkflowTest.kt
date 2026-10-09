package com.example.carrepairmanager.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import com.example.carrepairmanager.CarRepairApplication
import com.example.carrepairmanager.WindowsFileProviderShadow
import com.example.carrepairmanager.data.local.CarRepairDatabase
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.data.local.entity.ServiceCategory
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.Vehicle
import com.example.carrepairmanager.data.local.entity.Customer
import java.io.File
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import org.robolectric.annotation.GraphicsMode

/** 使用真实 JPEG、EXIF 和 Room 验证文件与元数据的一致性，不操作设备或真实档案。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CarRepairApplication::class, shadows = [WindowsFileProviderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoWorkflowTest {
    private val context get() = RuntimeEnvironment.getApplication() as CarRepairApplication
    private val root get() = context.filesDir
    private val diskName = "milestone3_photos_test.db"
    private lateinit var database: CarRepairDatabase
    private lateinit var store: PhotoFileStore
    private lateinit var repository: CarRepairRepository
    private val timestamp = 1_791_000_000_000L

    /** 每项测试独立构建数据库和文件存储，时钟固定以验证照片时间与排序。 */
    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, CarRepairDatabase::class.java).build()
        store = PhotoFileStore(context)
        repository = CarRepairRepository(database, store) { timestamp }
    }

    /** 只清理 Robolectric 私有沙箱中的夹具文件与专用测试数据库。 */
    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(diskName)
        listOf("images", "photo_drafts", "photo_trash").forEach { File(root, it).deleteRecursively() }
    }

    /** 大尺寸相机 JPEG 实际缩放至最长边 1920，旋转后像素正向且不保留定位信息。 */
    @Test
    fun compressionResizesRotatesAndDropsGpsMetadata() = runBlocking {
        val session = UUID.randomUUID().toString()
        val capture = store.prepareCapture(session)
        assertEquals("content", capture.uri.scheme)
        assertEquals("${context.packageName}.photos", capture.uri.authority)
        writeJpeg(File(root, capture.relativePath), 4000, 3000)
        ExifInterface(File(root, capture.relativePath)).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(39.9, 116.4)
            saveAttributes()
        }
        val photo = store.compressCapture(capture.relativePath, PhotoType.BEFORE, 70)
        val file = File(root, photo.relativePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        assertEquals(1440, bounds.outWidth)
        assertEquals(1920, bounds.outHeight)
        assertTrue(file.length() > 0)
        assertNull(ExifInterface(file).latLong)
        assertTrue(ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            in listOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(photo, store.compressCapture(capture.relativePath, PhotoType.BEFORE, 70))
        store.discardSession(session)
        assertFalse(File(root, "photo_drafts/$session").exists())
    }

    /** 四角色块验证 EXIF 八种方向的像素布局，覆盖镜像与转置而不只检查宽高。 */
    @Test
    fun allExifOrientationsProduceUprightPixels() = runBlocking {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expected = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2))
        expected.forEachIndexed { index, layout ->
            val capture = store.prepareCapture(UUID.randomUUID().toString())
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until 40) for (x in 0 until 80) bitmap.setPixel(x, y, colors[(if (y < 20) 0 else 2) + if (x < 40) 0 else 1])
                File(root, capture.relativePath).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            } finally { bitmap.recycle() }
            ExifInterface(File(root, capture.relativePath)).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, (index + 1).toString()); saveAttributes()
            }
            val photo = store.compressCapture(capture.relativePath, PhotoType.OTHER, 90)
            val result = requireNotNull(BitmapFactory.decodeFile(File(root, photo.relativePath).path))
            try {
                val corners = listOf(result.getPixel(result.width / 4, result.height / 4), result.getPixel(result.width * 3 / 4, result.height / 4),
                    result.getPixel(result.width / 4, result.height * 3 / 4), result.getPixel(result.width * 3 / 4, result.height * 3 / 4))
                corners.forEachIndexed { corner, actual ->
                    val target = colors[layout[corner]]
                    assertTrue("EXIF ${index + 1} 的第 $corner 个色块不正确", kotlin.math.abs(Color.red(actual) - Color.red(target)) < 20 &&
                        kotlin.math.abs(Color.green(actual) - Color.green(target)) < 20 && kotlin.math.abs(Color.blue(actual) - Color.blue(target)) < 20)
                }
            } finally { result.recycle() }
        }
    }

    /** 同一随机像素夹具使用两档 JPEG 质量，验证设置确实传给编码器。 */
    @Test
    fun qualitySettingChangesJpegEncoding() = runBlocking {
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val random = Random(7)
        val pixels = IntArray(400 * 300) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        bitmap.setPixels(pixels, 0, 400, 0, 0, 400, 300)
        try {
            val lengths = listOf(50, 90).map { quality ->
                val capture = store.prepareCapture(UUID.randomUUID().toString())
                File(root, capture.relativePath).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                val photo = store.compressCapture(capture.relativePath, PhotoType.OTHER, quality)
                File(root, photo.relativePath).length()
            }
            assertTrue("高清档应保留更多 JPEG 信息", lengths[1] > lengths[0])
        } finally { bitmap.recycle() }
    }

    /** 取消和相机空结果可清理，损坏文件不能变成可保存照片。 */
    @Test
    fun cancelledEmptyAndInvalidCapturesDoNotProducePhotos() = runBlocking {
        val capture = store.prepareCapture(UUID.randomUUID().toString())
        expectFailure<IllegalArgumentException> { store.compressCapture(capture.relativePath, PhotoType.BEFORE, 70) }
        File(root, capture.relativePath).writeText("not an image")
        expectFailure<IllegalArgumentException> { store.compressCapture(capture.relativePath, PhotoType.BEFORE, 70) }
        store.discardPhoto(capture.relativePath)
        assertFalse(File(root, capture.relativePath).exists())
        assertTrue(database.recordsDao().getPhotoPaths().isEmpty())
    }

    /** 四类照片保存相对路径、稳定顺序和时间；单张及整单删除同步清理文件并保留车辆。 */
    @Test
    fun photoSaveSingleDeleteAndOrderDeleteStayConsistent() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val drafts = PhotoType.entries.map { draft(it) }
        val id = repository.createServiceOrder(vehicleId, "2026-10-03", listOf(item(2990)), photos = drafts)
        val detail = requireNotNull(repository.getServiceOrder(id))
        assertEquals(PhotoType.entries, detail.photos.map { it.type })
        assertEquals(listOf(0, 1, 2, 3), detail.photos.map { it.sortOrder })
        assertTrue(detail.photos.all { it.createdAt == timestamp && it.orderId == id && !File(it.relativePath).isAbsolute })
        assertTrue(detail.photos.all { File(root, it.relativePath).isFile })
        assertEquals(detail.photos.first().relativePath, repository.observeVehicleList().first().single().photoRelativePath)
        assertEquals(detail.photos.first().relativePath, repository.observeRecentServices().first().single().photoRelativePath)
        assertEquals(4, repository.observeServiceHistoryEntries(vehicleId).first().single().photoCount)
        repository.deletePhoto(detail.photos.first().id)
        assertFalse(File(root, detail.photos.first().relativePath).exists())
        assertEquals(3, requireNotNull(repository.observeServiceOrder(id).first()).photos.size)
        assertEquals(2990L, repository.getServiceOrder(id)?.order?.totalAmountCent)
        assertEquals(detail.photos[1].relativePath, repository.observeRecentServices().first().single().photoRelativePath)
        repository.deleteServiceOrder(id)
        assertNull(repository.getServiceOrder(id))
        assertNotNull(repository.getVehicle(vehicleId))
        assertTrue(detail.photos.none { File(root, it.relativePath).exists() })
        assertTrue(database.recordsDao().getPhotoPaths().isEmpty())
        assertFalse(File(root, "photo_trash").exists())
    }

    /** 服务编辑先移除旧图，再新增图片和项目，保留未改照片的主键、路径与时间。 */
    @Test
    fun editingReplacesOnlyRemovedPhotosAndRecalculatesTotal() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val id = repository.createServiceOrder(vehicleId, "2026-10-03", listOf(item(100)), photos = listOf(draft(PhotoType.BEFORE), draft(PhotoType.AFTER)))
        val old = requireNotNull(repository.getServiceOrder(id)).photos
        repository.updateServiceOrder(id, "2026-10-02", listOf(item(33000)), "编辑", listOf(
            PhotoDraft(old[1].relativePath, old[1].type, old[1].id), draft(PhotoType.OTHER)))
        val updated = requireNotNull(repository.getServiceOrder(id))
        assertEquals(33000L, updated.order.totalAmountCent)
        assertEquals(listOf(PhotoType.AFTER, PhotoType.OTHER), updated.photos.map { it.type })
        assertEquals(old[1], updated.photos.first())
        assertFalse(File(root, old[0].relativePath).exists())
        assertTrue(updated.photos.all { File(root, it.relativePath).isFile })
    }

    /** 插入新图失败时恢复被暂移的旧图、原项目和金额，并清掉未提交的新文件。 */
    @Test
    fun failedPhotoInsertRollsBackFilesItemsAndTotal() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val id = repository.createServiceOrder(vehicleId, "2026-10-03", listOf(item(100)), photos = listOf(draft(PhotoType.BEFORE)))
        val original = requireNotNull(repository.getServiceOrder(id))
        val newDraft = draft(PhotoType.AFTER)
        executeSql("CREATE TRIGGER reject_photo BEFORE INSERT ON photos BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        expectFailure<android.database.sqlite.SQLiteException> {
            repository.updateServiceOrder(id, "2026-10-02", listOf(item(900)), "失败", listOf(newDraft))
        }
        assertEquals(original, repository.getServiceOrder(id))
        assertTrue(File(root, original.photos.single().relativePath).isFile)
        assertTrue(File(root, newDraft.relativePath).isFile)
        assertEquals(1, File(root, "images").walkTopDown().count { it.isFile })
        assertFalse(File(root, "photo_trash").exists())
    }

    /** 服务删除在 SQLite 层失败，所有照片元数据和文件仍需可读。 */
    @Test
    fun failedOrderDeleteRestoresEveryPhoto() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val id = repository.createServiceOrder(vehicleId, "2026-10-03", listOf(item(100)), photos = listOf(draft(PhotoType.BEFORE), draft(PhotoType.AFTER)))
        val original = requireNotNull(repository.getServiceOrder(id))
        executeSql("CREATE TRIGGER reject_delete BEFORE DELETE ON service_orders BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        expectFailure<android.database.sqlite.SQLiteException> { repository.deleteServiceOrder(id) }
        assertEquals(original, repository.getServiceOrder(id))
        assertTrue(original.photos.all { File(root, it.relativePath).isFile })
        assertFalse(File(root, "photo_trash").exists())
    }

    /** 多次服务及四类图片全部删除，共享客户、另一辆车和它的服务照片逐项保持不变。 */
    @Test
    fun permanentVehicleDeleteRemovesAllOrdersButKeepsSharedCustomerAndOtherCar() = runBlocking {
        val vehicle = repository.createVehicle(Vehicle(plateNumber = "冀A12345"), Customer(name = "共享测试客户"))
        val customerId = repository.getVehicle(vehicle)?.vehicle?.customerId
        val other = repository.createVehicle(Vehicle(plateNumber = "京B54321", customerId = customerId))
        val otherOrder = repository.createServiceOrder(other, "2026-10-06", listOf(item(900)), photos = listOf(draft(PhotoType.BEFORE)))
        val targetOrders = listOf(
            repository.createServiceOrder(vehicle, "2026-10-05", listOf(item(100)), photos = PhotoType.entries.map { draft(it) }),
            repository.createServiceOrder(vehicle, "2026-10-06", listOf(item(200)), photos = listOf(draft(PhotoType.OTHER))))
        repository.setVehicleArchived(vehicle, true)
        val before = repository.withBackupSnapshot { it }
        val keptDetail = requireNotNull(repository.getServiceOrder(otherOrder))
        val keptBytes = File(root, keptDetail.photos.single().relativePath).readBytes().toList()
        val summary = requireNotNull(repository.observeVehicleDeletionSummary(vehicle).first())
        assertEquals(2, summary.serviceCount); assertEquals(5, summary.photoCount)
        val targetPhotos = before.photos.filter { it.orderId in targetOrders }
        // 缺失原图允许清理失效档案，不能因一张丢失的图片遗留整辆测试车。
        assertTrue(File(root, targetPhotos.first().relativePath).delete())
        repository.deleteVehicle(vehicle, "冀 a12 345")
        val after = repository.withBackupSnapshot { it }
        assertEquals(before.customers, after.customers)
        assertEquals(before.vehicles.filter { it.id != vehicle }, after.vehicles)
        assertEquals(before.orders.filter { it.id !in targetOrders }, after.orders)
        assertEquals(before.items.filter { it.orderId !in targetOrders }, after.items)
        assertEquals(before.photos.filter { it.orderId !in targetOrders }, after.photos)
        assertEquals(keptDetail, repository.getServiceOrder(otherOrder))
        assertEquals(keptBytes, File(root, keptDetail.photos.single().relativePath).readBytes().toList())
        assertTrue(targetPhotos.none { File(root, it.relativePath).exists() })
        assertFalse(File(root, "photo_trash").exists())
        assertTrue(repository.observeServiceHistory(vehicle).first().isEmpty())
        assertEquals(listOf(other), repository.observeVehicleList(includeArchived = true).first().map { it.vehicle.id })
    }

    /** 最后删除车辆失败时，先删除的全部服务及暂移的照片也必须事务回滚。 */
    @Test
    fun failedVehicleDeleteRollsBackEarlierOrderDeletionsAndAllFiles() = runBlocking {
        val vehicle = repository.createVehicle(Vehicle(plateNumber = "冀A12345"))
        repeat(2) { repository.createServiceOrder(vehicle, "2026-10-06", listOf(item(100)), photos = listOf(draft(PhotoType.BEFORE))) }
        repository.setVehicleArchived(vehicle, true)
        val before = repository.withBackupSnapshot { it }
        executeSql("CREATE TRIGGER reject_vehicle_delete BEFORE DELETE ON vehicles BEGIN SELECT RAISE(ABORT, 'fixture_delete_failure'); END")
        expectFailure<android.database.sqlite.SQLiteException> { repository.deleteVehicle(vehicle, "冀A12345") }
        assertEquals(before, repository.withBackupSnapshot { it })
        assertTrue(before.photos.all { File(root, it.relativePath).isFile })
        assertFalse(File(root, "photo_trash").exists())
    }

    /** 后一服务的图片暂移失败时恢复前一服务的原图，不产生部分删除的车辆档案。 */
    @Test
    fun partialVehiclePhotoRemovalFailureRestoresEarlierOrders() = runBlocking {
        val vehicle = repository.createVehicle(Vehicle(plateNumber = "冀A12345"))
        repeat(2) { repository.createServiceOrder(vehicle, "2026-10-06", listOf(item(100)), photos = listOf(draft(PhotoType.BEFORE))) }
        repository.setVehicleArchived(vehicle, true)
        val before = repository.withBackupSnapshot { it }
        val invalid = File(root, before.photos.last().relativePath)
        assertTrue(invalid.delete()); assertTrue(invalid.mkdir())
        expectFailure<IllegalArgumentException> { repository.deleteVehicle(vehicle, "冀A12345") }
        assertEquals(before, repository.withBackupSnapshot { it })
        assertTrue(File(root, before.photos.first().relativePath).isFile)
        assertFalse(File(root, "photo_trash").exists())
    }

    /** 模拟暂移后与事务提交后的进程中断，磁盘重开分别恢复原图或完成垃圾清理。 */
    @Test
    fun interruptedVehicleDeleteRecoversBeforeAndAfterCommitOnDisk() = runBlocking {
        database.close()
        database = Room.databaseBuilder(context, CarRepairDatabase::class.java, diskName).build()
        repository = CarRepairRepository(database, store)
        val vehicle = repository.createVehicle(Vehicle(plateNumber = "冀A12345"), Customer(name = "保留客户"))
        repeat(2) { repository.createServiceOrder(vehicle, "2026-10-06", listOf(item(100)), photos = listOf(draft(PhotoType.BEFORE))) }
        repository.setVehicleArchived(vehicle, true)
        val before = repository.withBackupSnapshot { it }
        val paths = before.photos.map { it.relativePath }
        store.stageRemoval(paths)
        database.close()
        database = Room.databaseBuilder(context, CarRepairDatabase::class.java, diskName).build()
        repository = CarRepairRepository(database, PhotoFileStore(context))
        assertEquals(before, repository.withBackupSnapshot { it })
        assertTrue(paths.all { File(root, it).isFile })
        store.stageRemoval(paths)
        // 只模拟持久化的提交结果，刻意跳过 Repository 的 finally，重开后再验证恢复机制。
        withContext(Dispatchers.IO) {
            database.runInTransaction {
                database.openHelper.writableDatabase.execSQL("DELETE FROM service_orders WHERE vehicleId = ?", arrayOf(vehicle))
                database.openHelper.writableDatabase.execSQL("DELETE FROM vehicles WHERE id = ?", arrayOf(vehicle))
            }
        }
        database.close()
        database = Room.databaseBuilder(context, CarRepairDatabase::class.java, diskName).build()
        repository = CarRepairRepository(database, PhotoFileStore(context))
        val after = repository.withBackupSnapshot { it }
        assertEquals(before.customers, after.customers)
        assertTrue(after.vehicles.isEmpty() && after.orders.isEmpty() && after.items.isEmpty() && after.photos.isEmpty())
        assertTrue(paths.none { File(root, it).exists() })
        assertFalse(File(root, "photo_trash").exists())
    }

    /** 后一文件无法暂移时，先暂移的文件必须恢复，服务与照片记录均不能被部分删除。 */
    @Test
    fun partialFileRemovalFailureKeepsOrderAndRestoresEarlierFiles() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val id = repository.createServiceOrder(vehicleId, "2026-10-03", emptyList(), photos = listOf(draft(PhotoType.BEFORE), draft(PhotoType.AFTER)))
        val original = requireNotNull(repository.getServiceOrder(id))
        val invalid = File(root, original.photos[1].relativePath)
        assertTrue(invalid.delete()); assertTrue(invalid.mkdir())
        expectFailure<IllegalArgumentException> { repository.deleteServiceOrder(id) }
        assertEquals(original, repository.getServiceOrder(id))
        assertTrue(File(root, original.photos.first().relativePath).isFile)
    }

    /** 中断发生在暂移前后时，新 Repository 根据持久化引用分别恢复或清理文件。 */
    @Test
    fun recoveryRepairsInterruptedRemovalAndCleansUncommittedCopies() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val id = repository.createServiceOrder(vehicleId, "2026-10-03", emptyList(), photos = listOf(draft(PhotoType.BEFORE)))
        val photo = requireNotNull(repository.getServiceOrder(id)).photos.single()
        store.stageRemoval(listOf(photo.relativePath))
        assertFalse(File(root, photo.relativePath).exists())
        val orphan = File(root, "images/order_$id/uncommitted.jpg")
        orphan.writeText("uncommitted copy")
        repository = CarRepairRepository(database, store)
        assertEquals(photo, repository.getServiceOrder(id)?.photos?.single())
        assertTrue(File(root, photo.relativePath).isFile)
        assertFalse(orphan.exists())
        store.stageRemoval(listOf(photo.relativePath))
        database.recordsDao().deletePhoto(photo.id)
        repository = CarRepairRepository(database, store)
        assertTrue(requireNotNull(repository.getServiceOrder(id)).photos.isEmpty())
        assertFalse(File(root, photo.relativePath).exists())
        assertFalse(File(root, "photo_trash").exists())
    }

    /** 图片源文件已缺失也能清理元数据；票据不会被误用为车辆缩略图。 */
    @Test
    fun missingFileCanBeDeletedAndReceiptIsNotVehicleCover() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val id = repository.createServiceOrder(vehicleId, "2026-10-03", emptyList(), photos = listOf(draft(PhotoType.RECEIPT)))
        val photo = requireNotNull(repository.getServiceOrder(id)).photos.single()
        assertNull(repository.observeVehicleList().first().single().photoRelativePath)
        assertTrue(File(root, photo.relativePath).delete())
        repository.deletePhoto(photo.id)
        assertTrue(requireNotNull(repository.getServiceOrder(id)).photos.isEmpty())
    }

    /** 越界路径及跨服务图片引用必须拒绝，不能触碰另一服务的照片。 */
    @Test
    fun invalidPathsAndForeignPhotosAreRejected() = runBlocking {
        expectFailure<IllegalArgumentException> { store.discardSession("../images") }
        expectFailure<IllegalArgumentException> { store.discardPhoto("photo_drafts/../../car_repair.db") }
        expectFailure<IllegalArgumentException> { store.discardPhoto("images/test.jpg") }
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val first = repository.createServiceOrder(vehicleId, "2026-10-03", emptyList(), photos = listOf(draft(PhotoType.BEFORE)))
        val second = repository.createServiceOrder(vehicleId, "2026-10-03", emptyList())
        val photo = requireNotNull(repository.getServiceOrder(first)).photos.single()
        expectFailure<IllegalArgumentException> {
            repository.updateServiceOrder(second, "2026-10-03", emptyList(), "", listOf(PhotoDraft(photo.relativePath, photo.type, photo.id)))
        }
        assertTrue(File(root, photo.relativePath).isFile)
        assertEquals(photo, repository.getServiceOrder(first)?.photos?.single())
    }

    /** 并行保存不同服务不会被文件恢复扫描误清理对方尚未提交的照片。 */
    @Test
    fun concurrentPhotoSavesKeepBothOrders() = runBlocking {
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val drafts = listOf(draft(PhotoType.BEFORE), draft(PhotoType.AFTER))
        val ids = drafts.map { photo -> async(Dispatchers.IO) {
            repository.createServiceOrder(vehicleId, "2026-10-03", emptyList(), photos = listOf(photo))
        } }.awaitAll()
        assertEquals(2, ids.distinct().size)
        ids.forEach { assertTrue(File(root, requireNotNull(repository.getServiceOrder(it)).photos.single().relativePath).isFile) }
    }

    /** 关闭并重新打开磁盘数据库和文件存储，验证重启后正式照片仍可读取。 */
    @Test
    fun photosSurviveDatabaseAndRepositoryRecreation() = runBlocking {
        database.close()
        database = Room.databaseBuilder(context, CarRepairDatabase::class.java, diskName).build()
        repository = CarRepairRepository(database, store)
        val vehicleId = repository.createVehicle(Vehicle(plateNumber = "冀A88888"))
        val id = repository.createServiceOrder(vehicleId, "2026-10-03", emptyList(), photos = listOf(draft(PhotoType.BEFORE)))
        val original = requireNotNull(repository.getServiceOrder(id))
        database.close()
        database = Room.databaseBuilder(context, CarRepairDatabase::class.java, diskName).build()
        repository = CarRepairRepository(database, PhotoFileStore(context))
        assertEquals(original, repository.getServiceOrder(id))
        assertTrue(File(root, original.photos.single().relativePath).isFile)
    }

    /** 生成真实小图草稿，所有像素均为测试夹具。 */
    private suspend fun draft(type: PhotoType): PhotoDraft {
        val capture = store.prepareCapture(UUID.randomUUID().toString())
        writeJpeg(File(root, capture.relativePath), 80, 60)
        return store.compressCapture(capture.relativePath, type, 70)
    }

    /** 编码夹具不复制真实照片，色块便于判断 EXIF 与压缩结果。 */
    private fun writeJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.BLUE); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    /** 金额使用整数分，与现有服务业务保持同一接口。 */
    private fun item(amountCent: Long) = ServiceItem(category = ServiceCategory.REPAIR, name = "测试项目", amountCent = amountCent)

    /** 失败注入只作用于本项测试的 SQLite，不改变生产代码。 */
    private suspend fun executeSql(sql: String) = withContext(Dispatchers.IO) { database.openHelper.writableDatabase.execSQL(sql) }

    /** 不吞掉非预期异常，避免把运行错误误判为成功拒绝输入。 */
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try { block() } catch (error: Throwable) { if (error is T) return else throw error }
        fail("Expected ${T::class.java.simpleName}")
    }
}
