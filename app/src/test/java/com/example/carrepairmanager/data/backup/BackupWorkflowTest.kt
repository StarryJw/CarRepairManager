package com.example.carrepairmanager.data.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import com.example.carrepairmanager.CarRepairApplication
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.PhotoDraft
import com.example.carrepairmanager.data.PhotoFileStore
import com.example.carrepairmanager.data.local.CarRepairDatabase
import com.example.carrepairmanager.data.local.entity.Customer
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.data.local.entity.ServiceCategory
import com.example.carrepairmanager.data.local.entity.ServiceItem
import com.example.carrepairmanager.data.local.entity.Vehicle
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 使用真实 Room、JPEG 和 ZIP 验证备份完整性与恢复回滚，不访问手机或真实档案。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CarRepairApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupWorkflowTest {
    private val app get() = RuntimeEnvironment.getApplication() as CarRepairApplication
    private lateinit var database: CarRepairDatabase
    private lateinit var photos: PhotoFileStore
    private lateinit var records: CarRepairRepository
    private lateinit var archive: BackupArchive
    private lateinit var config: BackupConfig
    private val json = Json { encodeDefaults = true }
    private val cache get() = File(app.cacheDir, "backup_fixture")
    private val diskName = "milestone5_restore_test.db"

    /** 全部夹具处于 Robolectric 私有沙箱，每个用例独立创建数据和存储。 */
    @Before
    fun setUp() {
        config = BackupConfig.load(app)
        database = Room.inMemoryDatabaseBuilder(app, CarRepairDatabase::class.java).build()
        photos = PhotoFileStore(app)
        records = CarRepairRepository(database, photos) { 1_791_000_000_000L }
        archive = BackupArchive(cache, config, photos, "test-only", { 1_791_000_000_000L })
    }

    /** 清理测试数据库和本次沙箱文件，不操作用户真实数据。 */
    @After
    fun tearDown() {
        database.close(); app.deleteDatabase(diskName); cache.deleteRecursively()
        listOf("images", "photo_drafts", "photo_trash", "backup").forEach { File(app.filesDir, it).deleteRecursively() }
    }

    /** 导出后删除旧服务，再整份恢复；客户多车、归档、项目金额、四类照片与字节完全保留。 */
    @Test
    fun fullSnapshotRoundTripPreservesRelationshipsMoneyAndImages() = runBlocking {
        val order = fixture()
        val expected = records.withBackupSnapshot { it }
        val expectedBytes = expected.photos.associate { it.id to photos.savedPhotoFile(it.relativePath).readBytes().toList() }
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        val prepared = archive.inspect(snapshot.file)
        assertEquals(expected, prepared.data)
        val stage = archive.reopen(prepared.directory.name)
        records.deleteServiceOrder(order)
        val temporary = records.createVehicle(Vehicle(plateNumber = "测试临时车"))
        records.restoreBackup(stage)
        assertNull(records.getVehicle(temporary))
        val actual = records.withBackupSnapshot { it }
        assertEquals(expected.customers, actual.customers); assertEquals(expected.vehicles, actual.vehicles)
        assertEquals(expected.orders, actual.orders); assertEquals(expected.items, actual.items)
        assertEquals(expected.photos.map { it.copy(relativePath = "") }, actual.photos.map { it.copy(relativePath = "") })
        actual.photos.forEach { assertEquals(expectedBytes[it.id], photos.savedPhotoFile(it.relativePath).readBytes().toList()) }
        expected.photos.forEach { assertFalse(photos.savedPhotoFile(it.relativePath).exists()) }
    }

    /** 新 ZIP 排除已删除车辆及其服务图片，旧 ZIP 保持完整且主动恢复会重新带回档案。 */
    @Test
    fun vehicleDeletionChangesNewBackupAndOldBackupCanRestoreTheArchive() = runBlocking {
        val order = fixture()
        val vehicle = requireNotNull(records.getServiceOrder(order)).order.vehicleId
        records.setVehicleArchived(vehicle, true)
        val old = records.withBackupSnapshot { archive.create(it) }
        val oldBytes = old.file.readBytes().toList()
        val oldData = archive.inspect(old.file).data
        records.deleteVehicle(vehicle, "冀A12345")
        val latest = records.withBackupSnapshot { archive.create(it) }
        val newData = archive.inspect(latest.file).data
        assertNotEquals(old.fingerprint, latest.fingerprint)
        assertEquals(oldData.customers, newData.customers)
        assertEquals(oldData.vehicles.filter { it.id != vehicle }, newData.vehicles)
        assertTrue(newData.orders.isEmpty() && newData.items.isEmpty() && newData.photos.isEmpty())
        ZipFile(latest.file).use { zip -> assertEquals(setOf("manifest.json", "data.json"), zip.entries().asSequence().map { it.name }.toSet()) }
        assertEquals(oldBytes, old.file.readBytes().toList())
        records.restoreBackup(archive.inspect(old.file))
        assertEquals(oldData.vehicles, records.withBackupSnapshot { it.vehicles })
        val restored = requireNotNull(records.getServiceOrder(order))
        assertEquals(4, restored.photos.size)
        assertTrue(restored.photos.all { photos.savedPhotoFile(it.relativePath).isFile })
    }

    /** 快照复制期间永久删除必须等待，旧快照仍完整，释放锁后才提交删除。 */
    @Test
    fun permanentVehicleDeletionWaitsForBackupSnapshot() = runBlocking {
        val order = fixture()
        val vehicle = requireNotNull(records.getServiceOrder(order)).order.vehicleId
        records.setVehicleArchived(vehicle, true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val deleting = CompletableDeferred<Unit>()
        val capture = async(Dispatchers.IO) { records.withBackupSnapshot { data ->
            entered.complete(Unit); release.await(); archive.create(data)
        } }
        entered.await()
        val deletion = async(Dispatchers.IO) { deleting.complete(Unit); records.deleteVehicle(vehicle, "冀A12345") }
        deleting.await()
        try {
            delay(100)
            assertFalse(deletion.isCompleted)
            assertNotNull(records.getVehicle(vehicle))
        } finally { release.complete(Unit) }
        val snapshot = capture.await()
        deletion.await()
        val prepared = archive.inspect(snapshot.file)
        assertTrue(prepared.data.vehicles.any { it.id == vehicle })
        assertEquals(4, prepared.data.photos.size)
        assertNull(records.getVehicle(vehicle))
        assertTrue(records.withBackupSnapshot { it.photos.isEmpty() })
    }

    /** 新格式往返同时保留待填、免费与已有金额，并拒绝将部分金额伪装为完整合计。 */
    @Test
    fun pendingAmountsRoundTripAndRequireConsistentTotals() = runBlocking {
        val orderId = fixture()
        records.updateServiceOrder(orderId, "2026-10-06", listOf(
            ServiceItem(category = ServiceCategory.REPAIR, name = "待报价"),
            ServiceItem(category = ServiceCategory.WASH, name = "免费", amountCent = 0),
            ServiceItem(category = ServiceCategory.PART, name = "已报价", amountCent = 2990)), "金额待填")
        val data = records.withBackupSnapshot { it }
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        assertEquals(2, snapshot.manifest.backupVersion)
        assertEquals(2, snapshot.manifest.databaseVersion)
        val prepared = archive.inspect(snapshot.file)
        assertEquals(data, prepared.data)
        expectFailure { archive.validateData(data.copy(orders = data.orders.map { it.copy(totalAmountCent = 2990) })) }
        records.deleteServiceOrder(orderId)
        records.restoreBackup(prepared)
        val detail = requireNotNull(records.getServiceOrder(orderId))
        assertNull(detail.order.totalAmountCent)
        assertEquals(listOf(null, 0L, 2990L), detail.items.map { it.amountCent })
        assertEquals(4, detail.photos.size)
    }

    /** 旧版本 ZIP 在新数据库仍可恢复，不能把旧的零金额或已有图片误标为待填。 */
    @Test
    fun legacyVersionOneBackupCanStillBeRestored() = runBlocking {
        val orderId = fixture()
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        val entries = ZipFile(snapshot.file).use { zip -> zip.entries().asSequence().associate {
            it.name to zip.getInputStream(it).use { stream -> stream.readBytes() }
        } }
        val legacyManifest = entries.getValue("manifest.json").toString(Charsets.UTF_8)
            .replace("\"backupVersion\":2", "\"backupVersion\":1")
            .replace("\"databaseVersion\":2", "\"databaseVersion\":1")
        val legacy = File(cache, "legacy_v1.zip")
        writeZip(legacy, entries + ("manifest.json" to legacyManifest.toByteArray()))
        val prepared = archive.inspect(legacy)
        records.deleteServiceOrder(orderId)
        records.restoreBackup(prepared)
        assertEquals(33000L, records.getServiceOrder(orderId)?.order?.totalAmountCent)
        assertEquals(4, records.getServiceOrder(orderId)?.photos?.size)
    }

    /** 跨 UTC 日期且设备使用其他时区时，文件名仍为北京时间，原 UTC manifest 可正常校验。 */
    @Test
    fun archiveFilenameUsesBeijingTimeRegardlessOfDeviceTimezone() = runBlocking {
        val originalTimezone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val timestamp = Instant.parse("2026-10-03T20:05:06.789Z").toEpochMilli()
            val timedArchive = BackupArchive(cache, config, photos, "test-only", { timestamp })
            val snapshot = records.withBackupSnapshot { timedArchive.create(it) }
            assertTrue(snapshot.file.name.startsWith("CarShopBackup_20261004_040506_789_"))
            assertTrue(WebDavClient.isBackupName(snapshot.file.name))
            assertEquals("2026-10-03T20:05:06.789Z", snapshot.manifest.createdAt)
            assertEquals(snapshot.manifest, timedArchive.inspect(snapshot.file).manifest)
        } finally { TimeZone.setDefault(originalTimezone) }
    }

    /** 事务插入故障必须恢复原数据库和照片，并清理未提交的新图。 */
    @Test
    fun databaseFailureKeepsAllOldRowsAndPhotos() = runBlocking {
        fixture()
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        val prepared = archive.inspect(snapshot.file)
        records.createVehicle(Vehicle(plateNumber = "原数据保护"))
        val old = records.withBackupSnapshot { it }
        val oldPaths = old.photos.map { it.relativePath }.toSet()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_restore BEFORE INSERT ON vehicles BEGIN SELECT RAISE(ABORT, 'fixture_restore_failure'); END")
        expectFailure { records.restoreBackup(prepared) }
        assertEquals(old, records.withBackupSnapshot { it })
        oldPaths.forEach { assertTrue(photos.savedPhotoFile(it).isFile) }
        val actualPaths = File(app.filesDir, "images").walkTopDown().filter { it.isFile }.map { it.relativeTo(app.filesDir).invariantSeparatorsPath }.toSet()
        assertEquals(oldPaths, actualPaths)
    }

    /** 备份图片丢失时导出报错，不生成漏图的成功快照。 */
    @Test
    fun missingSavedImagePreventsExport() = runBlocking {
        fixture()
        val data = records.withBackupSnapshot { it }
        photos.savedPhotoFile(data.photos.first().relativePath).delete()
        expectFailure { records.withBackupSnapshot { archive.create(it) } }
        assertFalse(cache.listFiles().orEmpty().any { it.extension == "zip" })
    }

    /** JPEG 头部仍可读的截断原图不能生成成功快照，失败不修改档案或原照片。 */
    @Test
    fun truncatedSavedJpegPreventsCompleteBackup() = runBlocking {
        fixture()
        val old = records.withBackupSnapshot { it }
        val file = photos.savedPhotoFile(old.photos.first().relativePath)
        val original = file.readBytes()
        val damaged = truncatedJpeg(original)
        file.writeBytes(damaged)
        expectFailure { records.withBackupSnapshot { archive.create(it) } }
        assertEquals(old, records.withBackupSnapshot { it })
        assertArrayEquals(damaged, file.readBytes())
        assertFalse(cache.listFiles().orEmpty().any { it.extension == "zip" })
    }

    /** ZIP CRC 与清单摘要均匹配损坏图片时，仍须拒绝恢复，保留全部原档案与原图。 */
    @Test
    fun truncatedJpegWithMatchingChecksumsIsRejectedBeforeRestore() = runBlocking {
        fixture()
        val old = records.withBackupSnapshot { it }
        val originalImages = old.photos.associate { it.relativePath to photos.savedPhotoFile(it.relativePath).readBytes() }
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        val entries = ZipFile(snapshot.file).use { zip -> zip.entries().asSequence().associate {
            it.name to zip.getInputStream(it).use { input -> input.readBytes() }
        } }
        val path = old.photos.first().relativePath
        val damaged = truncatedJpeg(originalImages.getValue(path))
        val manifest = imageManifest(snapshot.manifest, path, damaged)
        val invalid = File(cache, "truncated_photo.zip")
        writeZip(invalid, entries + (path to damaged) + ("manifest.json" to manifest))
        expectFailure { archive.inspect(invalid) }
        assertEquals(old, records.withBackupSnapshot { it })
        originalImages.forEach { (name, bytes) -> assertArrayEquals(bytes, photos.savedPhotoFile(name).readBytes()) }
        assertFalse(cache.listFiles().orEmpty().any { it.name.startsWith("restore_") })
    }

    /** 预览后暂存照片损坏且摘要同步变化时，确认步骤再次校验，不能提交数据库替换。 */
    @Test
    fun damagedStagedJpegIsRejectedAgainOnConfirmation() = runBlocking {
        fixture()
        val old = records.withBackupSnapshot { it }
        val originalImages = old.photos.associate { it.relativePath to photos.savedPhotoFile(it.relativePath).readBytes() }
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        val service = BackupRepository(app, records, photos, config, BackupSettingsRepository(app, config))
        val prepared = service.prepareRestore(Uri.fromFile(snapshot.file))
        val path = old.photos.first().relativePath
        val damaged = truncatedJpeg(originalImages.getValue(path))
        File(prepared.directory, path).writeBytes(damaged)
        File(prepared.directory, "manifest.json").writeBytes(imageManifest(prepared.manifest, path, damaged))
        expectFailure { service.restore(prepared) }
        assertEquals(old, records.withBackupSnapshot { it })
        originalImages.forEach { (name, bytes) -> assertArrayEquals(bytes, photos.savedPhotoFile(name).readBytes()) }
        service.discard(prepared)
    }

    /** 缺图、篡改数据或图片、不支持版本和 ZIP 截断都在确认前拒绝，不改变现有档案。 */
    @Test
    fun invalidArchivesNeverChangeExistingData() = runBlocking {
        fixture()
        val old = records.withBackupSnapshot { it }
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        val entries = ZipFile(snapshot.file).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use { stream -> stream.readBytes() } } }
        val cases = listOf(
            entries - old.photos.first().relativePath,
            entries + ("data.json" to "{}".toByteArray()),
            entries + (old.photos.first().relativePath to "invalid jpeg".toByteArray()),
            entries + ("manifest.json" to entries.getValue("manifest.json").toString(Charsets.UTF_8).replace("\"backupVersion\":2", "\"backupVersion\":99").toByteArray()),
            entries - "manifest.json",
            entries + ("../outside.txt" to byteArrayOf(1)),
            entries + ("images/../../outside.txt" to byteArrayOf(1)),
            entries + ("C:/outside.txt" to byteArrayOf(1)),
            entries + ("images\\outside.jpg" to byteArrayOf(1)),
        )
        cases.forEachIndexed { index, content ->
            val file = File(cache, "invalid_$index.zip")
            writeZip(file, content)
            expectFailure { archive.inspect(file) }
            assertEquals(old, records.withBackupSnapshot { it })
        }
        val truncated = File(cache, "truncated.zip").apply { writeBytes(snapshot.file.readBytes().take(80).toByteArray()) }
        expectFailure { archive.inspect(truncated) }
        assertFalse(File(app.cacheDir, "outside.txt").exists())
    }

    /** 实际解压大小超过配置即停止，不能相信 ZIP 的压缩后大小。 */
    @Test
    fun oversizedExpandedContentIsRejected() = runBlocking {
        fixture()
        val snapshot = records.withBackupSnapshot { archive.create(it) }
        val limited = BackupArchive(cache, config.copy(maxExpandedBytes = 100), photos, "test-only")
        expectFailure { limited.inspect(snapshot.file) }
    }

    /** 字段验证复用业务规则，拒绝非法外键、重复 ID、车牌、负金额、合计和照片归属。 */
    @Test
    fun invalidRecordsAreRejectedBeforeDatabaseMutation() = runBlocking {
        fixture()
        val valid = records.withBackupSnapshot { it }
        val firstVehicle = valid.vehicles.first()
        val firstOrder = valid.orders.first()
        val cases = listOf(
            valid.copy(vehicles = listOf(firstVehicle.copy(customerId = Long.MAX_VALUE))),
            valid.copy(vehicles = valid.vehicles + firstVehicle),
            valid.copy(vehicles = valid.vehicles.map { it.copy(normalizedPlateNumber = "BAD") }),
            valid.copy(orders = listOf(firstOrder.copy(totalAmountCent = 1))),
            valid.copy(items = valid.items.map { it.copy(amountCent = -1) }),
            valid.copy(photos = valid.photos.map { it.copy(orderId = Long.MAX_VALUE) }),
            valid.copy(orders = listOf(firstOrder.copy(serviceDate = "2026-02-30"))),
        )
        cases.forEach { expectFailure { archive.validateData(it) } }
    }

    /** 快照生成期间编辑必须等到图片复制完成，避免打包前后混入两种业务状态。 */
    @Test
    fun snapshotLockPreventsConcurrentWriteUntilFilesAreCaptured() = runBlocking {
        fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val capture = async(Dispatchers.IO) { records.withBackupSnapshot { data -> entered.complete(Unit); release.await(); data } }
        entered.await()
        val write = async(Dispatchers.IO) { records.createVehicle(Vehicle(plateNumber = "并发新车")) }
        delay(100)
        assertFalse(write.isCompleted)
        release.complete(Unit)
        val snapshotData = capture.await()
        val id = write.await()
        assertFalse(snapshotData.vehicles.any { it.id == id })
        assertNotNull(records.getVehicle(id))
    }

    /** 磁盘数据库重开后仍能读取恢复的服务和照片，不依赖内存里的 DAO 状态。 */
    @Test
    fun restoredDataSurvivesDatabaseReopen() = runBlocking {
        fixture()
        val prepared = records.withBackupSnapshot { archive.inspect(archive.create(it).file) }
        database.close()
        database = Room.databaseBuilder(app, CarRepairDatabase::class.java, diskName).build()
        records = CarRepairRepository(database, photos)
        records.restoreBackup(prepared)
        database.close()
        database = Room.databaseBuilder(app, CarRepairDatabase::class.java, diskName).build()
        records = CarRepairRepository(database, photos)
        val detail = requireNotNull(records.getServiceOrder(prepared.data.orders.first().id))
        assertEquals(4, detail.photos.size); assertEquals(33000L, detail.order.totalAmountCent)
        detail.photos.forEach { assertTrue(photos.savedPhotoFile(it.relativePath).isFile) }
    }

    /** 本地导出不需要 WebDAV 配置，也不把凭据写入 ZIP。 */
    @Test
    fun localExportAndPreviewDoNotNeedCloudCredentials() = runBlocking {
        fixture()
        val settings = BackupSettingsRepository(app, config)
        val service = BackupRepository(app, records, photos, config, settings)
        val file = File(app.cacheDir, "local_export.zip")
        service.export(Uri.fromFile(file))
        assertTrue(file.isFile)
        assertTrue(settings.settings.first().lastExportAt > 0)
        assertEquals(0L, settings.settings.first().lastSuccessAt)
        val old = records.withBackupSnapshot { it }
        val preview = service.prepareRestore(Uri.fromFile(file))
        assertEquals(old, records.withBackupSnapshot { it })
        service.discard(preview)
        assertEquals(old, records.withBackupSnapshot { it })
        ZipFile(file).use { zip -> assertEquals(setOf("manifest.json", "data.json") + old.photos.map { it.relativePath }, zip.entries().asSequence().map { it.name }.toSet()) }
    }

    /** 创建多车关系、归档与真实四类 JPEG 夹具，金额保持精确整数分。 */
    private suspend fun fixture(): Long {
        val vehicle = records.createVehicle(Vehicle(plateNumber = "冀 a 12345"), Customer(name = "测试客户", phone = "13000000000", remark = "测试备注"))
        val customerId = records.getVehicle(vehicle)?.vehicle?.customerId
        val archived = records.createVehicle(Vehicle(plateNumber = "测试归档车", customerId = customerId))
        records.setVehicleArchived(archived, true)
        val drafts = PhotoType.entries.map { type ->
            val file = File(app.filesDir, "photo_drafts/${UUID.randomUUID()}/photo_${UUID.randomUUID()}.jpg")
            file.parentFile?.mkdirs()
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }; bitmap.recycle()
            PhotoDraft(file.relativeTo(app.filesDir).invariantSeparatorsPath, type)
        }
        return records.createServiceOrder(vehicle, "2026-10-04", listOf(
            ServiceItem(category = ServiceCategory.MAINTENANCE, name = "测试机油", amountCent = 30010),
            ServiceItem(category = ServiceCategory.WASH, name = "测试洗车", amountCent = 2990)), "测试服务备注", drafts)
    }

    /** 构建故意损坏的 ZIP，便于检查真实导入行为。 */
    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }

    /** 保留所有 JPEG 头部并移除结束标记，复现尺寸检查仍通过的原审查问题。 */
    private fun truncatedJpeg(original: ByteArray): ByteArray {
        assertEquals(listOf(0xFF.toByte(), 0xD9.toByte()), original.takeLast(2))
        val damaged = original.copyOf(original.size - 2)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(damaged, 0, damaged.size, bounds)
        assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == "image/jpeg")
        return damaged
    }

    /** 使用损坏图片的真实长度与摘要，确保测试命中 JPEG 校验而非已有的摘要检查。 */
    private fun imageManifest(manifest: BackupManifest, path: String, bytes: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val changed = manifest.copy(images = manifest.images.map {
            if (it.relativePath == path) it.copy(size = bytes.size.toLong(), sha256 = hash) else it
        })
        return json.encodeToString(changed).toByteArray(Charsets.UTF_8)
    }

    /** 失败用例只能捕获实际异常，不能把测试断言失败当作预期业务失败。 */
    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("expected backup validation or restore failure")
    }
}
