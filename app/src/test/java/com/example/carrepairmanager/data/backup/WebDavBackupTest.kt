package com.example.carrepairmanager.data.backup

import androidx.room.Room
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.carrepairmanager.CarRepairApplication
import com.example.carrepairmanager.data.CarRepairRepository
import com.example.carrepairmanager.data.PhotoFileStore
import com.example.carrepairmanager.data.local.CarRepairDatabase
import com.example.carrepairmanager.data.local.entity.Vehicle
import com.example.carrepairmanager.ui.settings.BackupUiState
import com.example.carrepairmanager.ui.settings.BackupViewModel
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 本机自签 HTTPS WebDAV 测试服务器，验证协议与失败反馈，不连接用户坚果云。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CarRepairApplication::class)
class WebDavBackupTest {
    private val app get() = RuntimeEnvironment.getApplication() as CarRepairApplication
    private lateinit var server: MockWebServer
    private lateinit var database: CarRepairDatabase
    private lateinit var records: CarRepairRepository
    private lateinit var config: BackupConfig
    private lateinit var settings: BackupSettingsRepository
    private lateinit var client: WebDavClient
    private lateinit var service: BackupRepository
    private val files = ConcurrentHashMap<String, ByteArray>()
    private var forcedStatus: Int? = null
    private var disconnectUpload = false
    private var failMove = false
    private var omitHeadLength = false
    private var headLengthOverride: Long? = null
    private var truncateUpload = false
    private var filePropertyResponse: ((String, Long) -> String)? = null
    private var uploadEntered: CountDownLatch? = null
    private var uploadRelease: CountDownLatch? = null
    private val password = "fixture_password_no_real_account"
    private val user = "fixture@example.com"
    private lateinit var preferencesFile: File
    private lateinit var preferencesStore: DataStore<Preferences>
    private val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backupModels = ViewModelStore()

    /** TLS 信任仅注入测试客户端，生产客户端仍使用系统证书验证。 */
    @Before
    fun setUp() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
        server.start()
        config = BackupConfig.load(app).copy(webDavUrl = server.url("/dav/").toString(), remoteDirectory = "fixture")
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .followRedirects(false).followSslRedirects(false).readTimeout(3, TimeUnit.SECONDS).build()
        client = WebDavClient(config, http)
        database = Room.inMemoryDatabaseBuilder(app, CarRepairDatabase::class.java).build()
        val photos = PhotoFileStore(app)
        records = CarRepairRepository(database, photos)
        preferencesFile = File(app.cacheDir, "fixture_${java.util.UUID.randomUUID()}.preferences_pb")
        preferencesStore = PreferenceDataStoreFactory.create(scope = preferencesScope, produceFile = { preferencesFile })
        settings = BackupSettingsRepository(app, config, FixturePasswordCipher(), preferencesStore)
        service = BackupRepository(app, records, photos, config, settings, client)
        service.saveConnection(config.webDavUrl, config.remoteDirectory, user, password)
        records.createVehicle(Vehicle(plateNumber = "测试云端车"))
        Unit
    }

    /** 测试服务与数据库全部在本机临时沙箱中关闭。 */
    @After
    fun tearDown() {
        backupModels.clear()
        shadowOf(Looper.getMainLooper()).idle()
        uploadRelease?.countDown(); server.shutdown(); database.close(); preferencesScope.cancel()
    }

    /** 真实 WebDAV 列表经过 ViewModel 后按五个展开，刷新重置、失败保留、空目录和不足一批均可用。 */
    @Test
    fun remoteBackupListStartsWithFiveAndOnlyExpandsOnRequest() {
        assertEquals(5, config.cloudPageSize)
        val names = fixtureRemoteVersions(12)
        val model = backupModel(service)
        model.loadRemote()
        awaitBackupState(model) { it.remoteLoaded && !it.busy && it.remote.size == 12 }
        assertEquals(names.take(5), model.state.value.visibleRemote.map { it.name })
        assertTrue(model.state.value.hasMoreRemote)
        val requests = server.requestCount
        listOf(10, 12).forEach { count ->
            model.loadMoreRemote()
            assertEquals(names.take(count), model.state.value.visibleRemote.map { it.name })
            assertEquals(count < 12, model.state.value.hasMoreRemote)
        }
        model.loadMoreRemote()
        assertEquals(12, model.state.value.visibleRemote.size)
        assertEquals(requests, server.requestCount)
        model.loadRemote()
        awaitBackupState(model) { !it.busy && it.remoteVisibleCount == 5 }
        assertEquals(names.take(5), model.state.value.visibleRemote.map { it.name })
        forcedStatus = 503
        model.loadRemote()
        awaitBackupState(model) { !it.busy && it.error != null }
        assertEquals(names.take(5), model.state.value.visibleRemote.map { it.name })
        forcedStatus = null
        files.clear()
        model.loadRemote()
        awaitBackupState(model) { !it.busy && it.remoteLoaded && it.remote.isEmpty() }
        assertTrue(model.state.value.visibleRemote.isEmpty())
        assertFalse(model.state.value.hasMoreRemote)
        val fewer = fixtureRemoteVersions(2)
        model.loadRemote()
        awaitBackupState(model) { !it.busy && it.remote.size == 2 }
        assertEquals(fewer, model.state.value.visibleRemote.map { it.name })
        assertFalse(model.state.value.hasMoreRemote)
    }

    /** 分批大小读取统一配置，末批不足时不重复或越界。 */
    @Test
    fun remoteBackupBatchSizeUsesConfiguredValue() {
        val names = fixtureRemoteVersions(8)
        val customConfig = config.copy(cloudPageSize = 3)
        val customService = BackupRepository(app, records, PhotoFileStore(app), customConfig, settings, client)
        val model = backupModel(customService)
        model.loadRemote()
        awaitBackupState(model) { it.remoteLoaded && !it.busy && it.remote.size == 8 }
        assertEquals(names.take(3), model.state.value.visibleRemote.map { it.name })
        model.loadMoreRemote()
        assertEquals(names.take(6), model.state.value.visibleRemote.map { it.name })
        model.loadMoreRemote()
        assertEquals(names, model.state.value.visibleRemote.map { it.name })
        assertFalse(model.state.value.hasMoreRemote)
    }

    /** 仅创建服务端列表元数据夹具，文件名顺序与服务器枚举顺序独立。 */
    private fun fixtureRemoteVersions(count: Int): List<String> = (1..count).map { index ->
        val name = "CarShopBackup_20261006_1200${index.toString().padStart(2, '0')}_000_00000000-0000-0000-0000-000000000000.zip"
        files["/dav/fixture/$name"] = byteArrayOf(index.toByte())
        name
    }.sortedDescending()

    /** 使用真实 Repository 和本机 TLS 服务验证页面状态，结束测试时取消所有页面观察。 */
    private fun backupModel(repository: BackupRepository): BackupViewModel = BackupViewModel(app, SavedStateHandle(), repository).also {
        backupModels.put("backup", it)
        awaitBackupState(it) { state -> state.settings != null && !state.busy }
    }

    /** 等待主线程状态与后台网络完成，不依赖固定耗时或触发真实手机操作。 */
    private fun awaitBackupState(model: BackupViewModel, predicate: (BackupUiState) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate(model.state.value)) return
            Thread.sleep(10)
        }
        throw AssertionError("未得到预期备份列表状态：${model.state.value}")
    }

    /** 上传、枚举、下载、确认前预览及整份恢复可往返，数据不混入凭据。 */
    @Test
    fun uploadDownloadAndRestoreCompleteSnapshot() = runBlocking {
        service.testConnection()
        val expected = records.withBackupSnapshot { it }
        service.backupNow()
        val versions = service.remoteBackups()
        assertEquals(1, versions.size)
        assertTrue(settings.settings.first().lastSuccessAt > 0)
        val pending = files.keys.filter { it.endsWith(".uploading") }
        assertTrue(pending.isEmpty())
        val extra = records.createVehicle(Vehicle(plateNumber = "之后保存的新车"))
        val prepared = service.prepareRemoteRestore(versions.first())
        assertNotNull(records.getVehicle(extra))
        service.restore(prepared)
        assertEquals(expected, records.withBackupSnapshot { it })
        assertNull(records.getVehicle(extra))
        assertTrue(settings.settings.first().lastFingerprint.isEmpty())
        val zipBytes = files.values.first()
        assertFalse(zipBytes.toString(Charsets.ISO_8859_1).contains(password))
    }

    /** HEAD 缺少长度头或返回零 / 错误长度时，已正确保存且 DAV 属性完整的文件仍能通过校验。 */
    @Test
    fun uploadedFileWithCorrectDavSizeDoesNotRequireHeadContentLength() = runBlocking {
        listOf<Long?>(null, 0, 1).forEach { headSize ->
            omitHeadLength = headSize == null
            headLengthOverride = headSize
            service.backupNow()
            assertTrue(settings.settings.first().lastSuccessAt > 0)
        }
        assertEquals(3, files.size)
        service.remoteBackups().forEach { backup ->
            val stored = files.entries.single { it.key.endsWith("/${backup.name}") }.value
            assertEquals(stored.size.toLong(), backup.size)
        }
        val requests = List(server.requestCount) { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        assertTrue(requests.any { it.method == "PROPFIND" && it.getHeader("Depth") == "0" && it.requestUrl!!.encodedPath.endsWith(".zip") })
    }

    /** 真实服务端截断上传内容时仍报出两端字节数，不推进成功时间或已备份摘要。 */
    @Test
    fun truncatedServerFileStillFailsSizeVerification() = runBlocking {
        service.backupNow()
        val success = settings.settings.first()
        records.createVehicle(Vehicle(plateNumber = "截断后未备份的新车"))
        truncateUpload = true
        val error = failure { service.backupNow() }
        assertTrue(error is BackupException && error.retryable)
        assertTrue(error.message.orEmpty().contains("云端备份大小校验失败（本地"))
        assertTrue(error.message.orEmpty().contains("字节，云端"))
        assertEquals(success.lastSuccessAt, settings.settings.first().lastSuccessAt)
        assertEquals(success.lastFingerprint, settings.settings.first().lastFingerprint)
    }

    /** 失败属性中的长度不能借用其他属性的 200 状态；缺失、无效长度也不能冒充验证成功。 */
    @Test
    fun missingOrUnsuccessfulFileSizeIsNotReportedAsBackupSuccess() = runBlocking {
        listOf("missing", "invalid", "failed").forEach { scenario ->
            filePropertyResponse = { path, size ->
                val length = when (scenario) {
                    "missing" -> ""
                    "invalid" -> "<d:getcontentlength>invalid</d:getcontentlength>"
                    else -> "<d:getcontentlength>$size</d:getcontentlength>"
                }
                val status = if (scenario == "failed") "404 Not Found" else "200 OK"
                "<d:response><d:href>$path</d:href><d:propstat><d:prop>$length</d:prop><d:status>HTTP/1.1 $status</d:status></d:propstat>" +
                    "<d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
            }
            val error = failure { service.backupNow() }
            assertTrue(error is BackupException && error.retryable)
            assertTrue(error.message.orEmpty().contains("未提供有效的文件大小"))
            assertEquals(0L, settings.settings.first().lastSuccessAt)
            assertTrue(settings.settings.first().lastFingerprint.isEmpty())
        }
    }

    /** 成功长度和不支持属性混在同一 207 中时，无论分组顺序如何都以成功长度校验。 */
    @Test
    fun failedPropertyGroupDoesNotOverrideSuccessfulFileSize() = runBlocking {
        listOf(false, true).forEach { failedFirst ->
            filePropertyResponse = { path, size ->
                val failed = "<d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>"
                val valid = "<d:propstat><d:prop><d:getcontentlength>$size</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
                "<d:response><d:href>$path</d:href>" + (if (failedFirst) failed + valid else valid + failed) + "</d:response>"
            }
            service.backupNow()
            assertTrue(settings.settings.first().lastSuccessAt > 0)
        }
    }

    /** Depth 0 响应必须指向本次上传文件，跨主机、跨目录或其他文件的大小不能拿来验证。 */
    @Test
    fun otherResourceSizeCannotVerifyTheUploadedFile() = runBlocking {
        listOf("host", "directory", "file").forEach { scenario ->
            filePropertyResponse = { path, size ->
                val href = when (scenario) {
                    "host" -> "https://example.com$path"
                    "directory" -> "/dav/other/${path.substringAfterLast('/')}"
                    else -> "/dav/fixture/CarShopBackup_20261004_010000_000_00000000-0000-0000-0000-000000000000.zip"
                }
                responseXml(href, size)
            }
            val error = failure { service.backupNow() }
            assertTrue(error is BackupException && error.retryable)
            assertTrue(error.message.orEmpty().contains("未提供有效的文件大小"))
            assertEquals(0L, settings.settings.first().lastSuccessAt)
        }
    }

    /** 自动备份无变更不上传，新增和删除都触发新版本；关闭后不发起网络请求。 */
    @Test
    fun automaticBackupOnlyUploadsChangedSnapshotsAndRespectsDisabledState() = runBlocking {
        settings.setAutomatic(true)
        service.backupNow(automatic = true)
        assertEquals(1, files.size)
        service.backupNow(automatic = true)
        assertEquals(1, files.size)
        val vehicle = records.withBackupSnapshot { it.vehicles.first().id }
        val order = records.createServiceOrder(vehicle, "2026-10-04", emptyList())
        service.backupNow(automatic = true)
        assertEquals(2, files.size)
        records.deleteServiceOrder(order)
        service.backupNow(automatic = true)
        assertEquals(3, files.size)
        settings.setAutomatic(false)
        val requests = server.requestCount
        service.backupNow(automatic = true)
        assertEquals(requests, server.requestCount)
    }

    /** 本地删除不改写历史云版本；上传失败保留变化，下次自动备份成功才更新摘要。 */
    @Test
    fun vehicleDeletionRemainsPendingAfterFailedUploadAndPreservesCloudHistory() = runBlocking {
        settings.setAutomatic(true)
        val vehicle = records.withBackupSnapshot { it.vehicles.single().id }
        records.createServiceOrder(vehicle, "2026-10-06", emptyList())
        records.setVehicleArchived(vehicle, true)
        service.backupNow(automatic = true)
        val oldSettings = settings.settings.first()
        val oldFiles = files.toMap()
        records.deleteVehicle(vehicle, "测试云端车")
        assertNull(records.getVehicle(vehicle))
        assertEquals(oldFiles.keys, files.keys)
        forcedStatus = 503
        failure { service.backupNow(automatic = true) }
        assertNull(records.getVehicle(vehicle))
        assertEquals(oldSettings.lastSuccessAt, settings.settings.first().lastSuccessAt)
        assertEquals(oldSettings.lastFingerprint, settings.settings.first().lastFingerprint)
        forcedStatus = null
        service.backupNow(automatic = true)
        assertEquals(2, files.size)
        assertNotEquals(oldSettings.lastFingerprint, settings.settings.first().lastFingerprint)
        oldFiles.forEach { (path, bytes) -> assertArrayEquals(bytes, files[path]) }
        val latestName = files.keys.single { it !in oldFiles }.substringAfterLast('/')
        val preview = service.prepareRemoteRestore(service.remoteBackups().single { it.name == latestName })
        assertTrue(preview.data.vehicles.isEmpty() && preview.data.orders.isEmpty() && preview.data.photos.isEmpty())
        service.discard(preview)
        service.backupNow(automatic = true)
        assertEquals(2, files.size)
    }

    /** 上传中业务仍可保存，新修改留待下次；同时触发的另一备份被合并为可重试请求。 */
    @Test
    fun editsDuringUploadRemainPendingAndConcurrentBackupIsRejected() = runBlocking {
        settings.setAutomatic(true)
        uploadEntered = CountDownLatch(1); uploadRelease = CountDownLatch(1)
        val uploading = async(Dispatchers.IO) { service.backupNow(automatic = true) }
        assertTrue(uploadEntered!!.await(5, TimeUnit.SECONDS))
        records.createVehicle(Vehicle(plateNumber = "上传期间的新车"))
        val concurrent = failure { service.backupNow() }
        assertTrue(concurrent is BackupException && concurrent.retryable)
        uploadRelease!!.countDown()
        uploading.await()
        uploadEntered = null; uploadRelease = null
        service.backupNow(automatic = true)
        assertEquals(2, files.size)
    }

    /** 401、空间不足、服务故障和重定向分别反馈，失败不能更新成功时间。 */
    @Test
    fun authenticationQuotaServerAndRedirectFailuresAreReported() = runBlocking {
        listOf(401 to false, 507 to false, 503 to true, 429 to true, 302 to false).forEach { (code, retryable) ->
            forcedStatus = code
            val error = failure { service.backupNow() }
            assertTrue(error is BackupException)
            assertEquals(retryable, (error as BackupException).retryable)
            assertEquals(0L, settings.settings.first().lastSuccessAt)
            assertFalse(settings.settings.first().lastError.contains(password))
            assertEquals(BackupScope.CLOUD, service.operation.value.scope)
            assertEquals(BackupScope.CLOUD, settings.settings.first().lastErrorScope)
        }
        assertTrue(files.isEmpty())
    }

    /** 升级前无来源的历史错误归入云端，新本地失败的来源在重新读取配置后仍被保留。 */
    @Test
    fun legacyCloudErrorAndNewLocalErrorKeepTheirDisplayScope() = runBlocking {
        val message = "云端备份大小校验失败，请重试"
        preferencesStore.edit {
            it[stringPreferencesKey("last_error")] = message
            it.remove(stringPreferencesKey("last_error_scope"))
        }
        val reloaded = BackupSettingsRepository(app, config, FixturePasswordCipher(), preferencesStore)
        assertEquals(message, reloaded.settings.first().lastError)
        assertEquals(BackupScope.CLOUD, reloaded.settings.first().lastErrorScope)
        val broken = File(app.cacheDir, "broken_local_scope.zip").apply { writeText("invalid zip") }
        failure { service.prepareRestore(Uri.fromFile(broken)) }
        assertEquals(BackupScope.LOCAL, service.operation.value.scope)
        assertEquals(BackupScope.LOCAL, reloaded.settings.first().lastErrorScope)
        assertEquals("ZIP 备份文件损坏或不完整", reloaded.settings.first().lastError)
    }

    /** 临时上传中断或 MOVE 失败都不产生可选完整版本。 */
    @Test
    fun interruptedUploadAndFailedMoveNeverAppearAsCompleteBackups() = runBlocking {
        disconnectUpload = true
        val error = failure { service.backupNow() }
        assertTrue(error is BackupException && error.retryable)
        disconnectUpload = false
        assertTrue(service.remoteBackups().isEmpty())
        failMove = true
        failure { service.backupNow() }
        assertTrue(files.keys.any { it.endsWith(".uploading") })
        assertTrue(service.remoteBackups().isEmpty())
        assertEquals(0L, settings.settings.first().lastSuccessAt)
    }

    /** 上传前检查账号单文件限制，不截断 ZIP，也不发送数据。 */
    @Test
    fun cloudSizeLimitRejectsBeforeUpload() = runBlocking {
        val limited = config.copy(maxCloudBytes = 1)
        val photos = PhotoFileStore(app)
        val httpService = BackupRepository(app, records, photos, limited, settings, WebDavClient(limited))
        val error = failure { httpService.backupNow() }
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, server.requestCount)
        assertEquals(0L, settings.settings.first().lastSuccessAt)
    }

    /** 不接受跨主机链接、其他目录文件、临时上传和未知命名的用户文件。 */
    @Test
    fun listingFiltersUnsafeAndIncompleteFiles() {
        val name = "CarShopBackup_20261004_010000_000_00000000-0000-0000-0000-000000000000.zip"
        val directory = server.url("/dav/fixture/")
        val xml = "<d:multistatus xmlns:d=\"DAV:\">" + listOf(
            directory.toString() + name,
            directory.toString() + name + ".uploading",
            "https://example.com/dav/fixture/$name",
            "/dav/other/$name",
            "/dav/fixture/user.zip",
        ).joinToString("") { responseXml(it, 12) } + "</d:multistatus>"
        assertEquals(listOf(RemoteBackup(name, 12)), client.parseListing(xml, directory))
        try { WebDavClient.validateUrl("http://example.com/dav/"); fail("HTTP must be rejected") } catch (_: IllegalArgumentException) { }
        try { WebDavClient.validateDirectory("../outside"); fail("unsafe directory must be rejected") } catch (_: IllegalArgumentException) { }
    }

    /** 密码以认证密文写入独立配置文件，留空沿用凭据；更换服务器必须重新填写密码。 */
    @Test
    fun credentialsAreEncryptedAndNotSilentlyReusedForAnotherServer() = runBlocking {
        val file = preferencesFile
        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(password))
        assertEquals(password, settings.credentials().password)
        settings.saveConnection(config.webDavUrl, config.remoteDirectory, user, "")
        assertEquals(password, settings.credentials().password)
        assertTrue(failure { settings.saveConnection("https://example.com/dav/", "fixture", user, "") } is IllegalArgumentException)
    }

    /** 实际 CoroutineWorker 对临时网络失败重试、凭据失效失败、关闭状态跳过，并限制重试次数。 */
    @Test
    fun workerRetriesTransientErrorsButStopsForCredentialsOrAttemptLimit() = runBlocking {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                AutomaticBackupWorker(appContext, workerParameters, service)
        }
        fun worker(attempts: Int = 0) = TestListenableWorkerBuilder<AutomaticBackupWorker>(app)
            .setWorkerFactory(factory).setRunAttemptCount(attempts).build()
        val requests = server.requestCount
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(requests, server.requestCount)
        settings.setAutomatic(true)
        forcedStatus = 503
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(config.maxAttempts - 1).doWork())
        forcedStatus = 401
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        assertEquals(0L, settings.settings.first().lastSuccessAt)
    }

    /** 实现足够真实的 HTTP 协议和服务器文件状态，供上传、移动、枚举与下载验证。 */
    private fun respond(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != Credentials.basic(user, password)) return MockResponse().setResponseCode(401)
        forcedStatus?.let { return MockResponse().setResponseCode(it) }
        val path = request.requestUrl!!.encodedPath
        return when (request.method) {
            "MKCOL" -> MockResponse().setResponseCode(201)
            "PROPFIND" -> {
                val content = files[path]
                val body = if (content != null && request.getHeader("Depth") == "0")
                    filePropertyResponse?.invoke(path, content.size.toLong()) ?: responseXml(path, content.size.toLong())
                    else if (request.getHeader("Depth") == "1") files.entries.filter { it.key.startsWith(path) }
                        .joinToString("") { responseXml(it.key, it.value.size.toLong()) } else ""
                MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$body</d:multistatus>")
            }
            "PUT" -> {
                uploadEntered?.countDown(); uploadRelease?.await(5, TimeUnit.SECONDS)
                if (disconnectUpload) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else {
                    val bytes = request.body.readByteArray()
                    files[path] = if (truncateUpload) bytes.copyOf(bytes.size - 1) else bytes
                    MockResponse().setResponseCode(201)
                }
            }
            "MOVE" -> {
                if (failMove) MockResponse().setResponseCode(503) else {
                    val target = request.getHeader("Destination")!!.toHttpUrl().encodedPath
                    files[target] = files.remove(path)!!
                    MockResponse().setResponseCode(201)
                }
            }
            "HEAD" -> MockResponse().setResponseCode(200).apply {
                if (omitHeadLength) removeHeader("Content-Length") else setHeader("Content-Length", headLengthOverride ?: files[path]!!.size.toLong())
            }
            "GET" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(files[path]!!))
            else -> MockResponse().setResponseCode(405)
        }
    }

    /** 构建带成功属性状态的 DAV 响应，文件大小由服务端实际存储字节生成。 */
    private fun responseXml(path: String, size: Long) = "<d:response><d:href>$path</d:href><d:propstat><d:prop><d:getcontentlength>$size</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    /** 保留实际失败对象以检验重试策略，而非模拟成功。 */
    private suspend fun failure(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("expected backup failure")
    }
}

/** 仅 JVM 测试替代不可用的 Android Keystore，使用真实 AES-GCM；绝不进入生产 APK。 */
internal class FixturePasswordCipher : PasswordCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    override fun encrypt(password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray()))
    }
    override fun decrypt(encrypted: String): String {
        val parts = encrypted.split(':')
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        }.doFinal(Base64.getDecoder().decode(parts[1])).toString(Charsets.UTF_8)
    }
}
