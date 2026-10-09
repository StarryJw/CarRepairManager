package com.example.carrepairmanager.data.ocr

import android.app.Application
import android.graphics.Bitmap
import com.example.carrepairmanager.WindowsFileProviderShadow
import java.io.File
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 本机真实 JPEG、TLS 验证代码凭据、OCR 协议和文件流程，不连接真实百度账号。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [WindowsFileProviderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OcrWorkflowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var config: OcrConfig
    private lateinit var image: File
    private val keys = OcrCredentials("fixture_api_key", "fixture_secret_key")

    /** 只向测试客户端注入本机证书，生产仍保持系统 TLS 信任。 */
    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        server = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            start()
        }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        config = OcrConfig.load(app).copy(tokenUrl = server.url("/oauth/2.0/token").toString(),
            licensePlateUrl = server.url("/rest/2.0/ocr/v1/license_plate").toString())
        image = File(app.cacheDir, "plate_${UUID.randomUUID()}.jpg")
        writeOcrTestImage(image)
    }

    /** 释放本机服务器，不触及真实设备数据。 */
    @After
    fun tearDown() { server.shutdown() }

    /** 核对 OAuth、编码后的表单、候选标准化与逐字符置信度平均值，后续请求复用 Token。 */
    @Test
    fun httpsProtocolEncodesImageAndCachesToken() = runBlocking {
        server.enqueue(token("first_token")); server.enqueue(plate("冀 a12345")); server.enqueue(plate("京B12345"))
        val recognizer = recognizer()
        val result = recognizer.recognize(image.absolutePath).candidates.single()
        assertEquals("冀A12345", result.plateNumber); assertEquals(0.9, requireNotNull(result.confidence), 0.000001)
        recognizer.recognize(image.absolutePath)
        val auth = request()
        assertEquals("POST", auth.method); assertEquals("/oauth/2.0/token", auth.path)
        assertEquals(mapOf("grant_type" to "client_credentials", "client_id" to keys.apiKey, "client_secret" to keys.secretKey), form(auth))
        val first = request(); val fields = form(first)
        assertEquals("first_token", first.requestUrl?.queryParameter("access_token"))
        assertTrue(first.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertEquals("true", fields["multi_detect"])
        assertArrayEquals(image.readBytes(), Base64.getDecoder().decode(fields.getValue("image")))
        assertEquals("first_token", request().requestUrl?.queryParameter("access_token"))
        assertEquals(3, server.requestCount)
    }

    /** 服务声明 Token 失效只刷新一次；更换凭据后不能复用旧 Token。 */
    @Test
    fun invalidTokenRefreshesOnceAndCredentialChangesInvalidateCache() = runBlocking {
        server.enqueue(token("old")); server.enqueue(json("""{"error_code":110,"error_msg":"fixture_secret_key"}"""))
        server.enqueue(token("new")); server.enqueue(plate())
        var active = keys
        val recognizer = BaiduPlateRecognizer(config, { active }, client)
        assertEquals("冀A12345", recognizer.recognize(image.absolutePath).candidates.single().plateNumber)
        request(); assertEquals("old", request().requestUrl?.queryParameter("access_token"))
        request(); assertEquals("new", request().requestUrl?.queryParameter("access_token"))
        active = OcrCredentials("replacement_key", "replacement_secret")
        server.enqueue(token("replacement")); server.enqueue(plate())
        recognizer.recognize(image.absolutePath)
        assertEquals("replacement_key", form(request())["client_id"])
        assertEquals("replacement", request().requestUrl?.queryParameter("access_token"))
    }

    /** 缓存到提前刷新时间即过期，不能硬编码固定 30 天。 */
    @Test
    fun tokenExpiresAccordingToReturnedLifetime() = runBlocking {
        var clock = 1_000L
        val recognizer = BaiduPlateRecognizer(config, { keys }, client) { clock }
        server.enqueue(token("one", 120)); server.enqueue(plate())
        recognizer.recognize(image.absolutePath)
        clock += 60_001
        server.enqueue(token("two", 120)); server.enqueue(plate())
        recognizer.recognize(image.absolutePath)
        assertEquals(4, server.requestCount)
    }

    /** 第二个失效响应结束重试，不陷入无限请求或隐式重试其他计费错误。 */
    @Test
    fun repeatedTokenFailureStopsAfterOneRefresh() = runBlocking {
        repeat(2) { server.enqueue(token("invalid")); server.enqueue(json("""{"error_code":111}""")) }
        val error = failure { recognizer().recognize(image.absolutePath) }
        assertEquals(111, (error as OcrException).code)
        assertEquals(4, server.requestCount)
    }

    /** 成功 HTTP 中的鉴权和权限、额度错误必须反馈，且不回显原始响应或密钥。 */
    @Test
    fun authenticationAndBusinessErrorsRemainSafeAndExplicit() = runBlocking {
        server.enqueue(json("""{"error":"invalid_client","error_description":"fixture_secret_key"}"""))
        val error = failure { recognizer().recognize(image.absolutePath) }
        assertTrue(error.message.orEmpty().contains("鉴权失败")); assertFalse(error.message.orEmpty().contains(keys.secretKey))
        for (code in listOf(6, 17, 18, 216201, 999)) {
            val response = Json.parseToJsonElement("""{"error_code":$code,"error_msg":"fixture_secret_key"}""").jsonObject
            try { BaiduPlateRecognizer.parseResult(response); fail("错误响应不能成为正常结果") }
            catch (error: OcrException) { assertEquals(code, error.code); assertFalse(error.message.orEmpty().contains(keys.secretKey)) }
        }
    }

    /** 新数组、旧单对象、新能源车牌与缺失置信度均按真实响应处理。 */
    @Test
    fun parserSupportsMultipleAndLegacyResultsWithoutInventingConfidence() {
        val result = BaiduPlateRecognizer.parseResult(Json.parseToJsonElement("""{"words_result":[
            {"number":"冀A12345","probability":[0.9,0.9,0.9,0.9,0.9,0.9,0.9]},
            {"number":"粤BD12345","probability":[1,1,1,1,1,1,1,1]},
            {"number":"冀 a12345","probability":[0.5]},
            {"number":"京C88888","probability":[1,1,1,1,1,1,2]}]}""").jsonObject)
        assertEquals(listOf("冀A12345", "粤BD12345", "京C88888"), result.candidates.map { it.plateNumber })
        assertEquals(1.0, requireNotNull(result.candidates[1].confidence), 0.0)
        assertNull(result.candidates[2].confidence)
        val old = BaiduPlateRecognizer.parseResult(Json.parseToJsonElement("""{"words_result":{"number":"沪A12345"}}""").jsonObject)
        assertNull(old.candidates.single().confidence)
        try { BaiduPlateRecognizer.parseResult(Json.parseToJsonElement("""{"words_result":[]}""").jsonObject); fail("空结果应报错") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("未识别到车牌")) }
    }

    /** 上限检查编码后的 form，原 JPEG 未超限仍可能因编码膨胀被拒绝。 */
    @Test
    fun encodedFormAndInvalidDimensionsFailBeforeNetwork() = runBlocking {
        val smallLimit = config.copy(maxFormBytes = image.length() + 1)
        assertTrue(failure { recognizer(smallLimit).recognize(image.absolutePath) }.message.orEmpty().contains("编码后过大"))
        image.writeText("not a jpeg")
        assertTrue(failure { recognizer().recognize(image.absolutePath) }.message.orEmpty().contains("尺寸无效"))
        assertEquals(0, server.requestCount)
    }

    /** 非 HTTPS 与超出图片规则的配置在创建 OCR 服务前失败。 */
    @Test
    fun invalidConfigurationIsRejected() {
        for (change in listOf<() -> OcrConfig>({ config.copy(tokenUrl = "http://localhost/token") },
            { config.copy(tokenUrl = "https://fixture.invalid/token?key=secret") }, { config.copy(imageQuality = 85) },
            { config.copy(imageMinSide = 1) }, { config.copy(maxFormBytes = 8_000_001) })) {
            try { change(); fail("无效配置应被拒绝") } catch (error: IllegalArgumentException) { assertFalse(error.message.isNullOrBlank()) }
        }
    }

    /** 未受信任的服务器证书在生产默认客户端被拒绝，不能以关闭证书验证换取成功。 */
    @Test
    fun productionClientRejectsUntrustedTlsCertificate() = runBlocking {
        server.enqueue(token())
        val error = failure { BaiduPlateRecognizer(config, { keys }).recognize(image.absolutePath) }
        assertTrue(error.message.orEmpty().contains("网络连接失败"))
    }

    /** HTTP 限流、故障和重定向均不携带凭据继续请求另一地址。 */
    @Test
    fun httpErrorsAndRedirectsDoNotRetry() = runBlocking {
        for (status in listOf(429, 503, 302)) {
            server.enqueue(MockResponse().setResponseCode(status).addHeader("Location", "https://fixture.invalid/redirect"))
            val error = failure { recognizer().recognize(image.absolutePath) }
            assertTrue(error.message.orEmpty().contains(when (status) { 429 -> "频繁"; 302 -> "重定向"; else -> "不可用" }))
        }
        assertEquals(3, server.requestCount)
    }

    /** 超时可明确反馈，用户取消直接终止等待网络的挂起调用。 */
    @Test
    fun timeoutAndCancellationStopPendingRequests() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val short = config.copy(readTimeoutSeconds = 1, callTimeoutSeconds = 2)
        assertTrue(failure { recognizer(short).recognize(image.absolutePath) }.message.orEmpty().contains("超时"))
        request()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = async(Dispatchers.IO) { recognizer().recognize(image.absolutePath) }
        request()
        val before = System.nanoTime()
        job.cancelAndJoin()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2_000)
    }

    /** 代码提供的凭据用于真实 OAuth 表单；缺少凭据在发请求前失败，无 App 内配置依赖。 */
    @Test
    fun codeCredentialsDriveOauthAndMissingCredentialsPreventUpload() = runBlocking {
        val missing = OcrRepository(app, config, credentials = OcrCredentials("", ""), httpClient = client)
        assertFalse(missing.configured)
        assertTrue(failure { missing.recognizeCapture("not_used") }.message.orEmpty().contains("暂不可用"))
        assertEquals(0, server.requestCount)
        val service = OcrRepository(app, config, credentials = keys, httpClient = client)
        assertTrue(service.configured)
        val capture = service.prepareCapture(UUID.randomUUID().toString())
        writeOcrTestImage(File(app.filesDir, capture.relativePath))
        server.enqueue(token()); server.enqueue(plate())
        assertEquals("冀A12345", service.recognizeCapture(capture.relativePath).candidates.single().plateNumber)
        val auth = form(request())
        assertEquals(keys.apiKey, auth["client_id"]); assertEquals(keys.secretKey, auth["client_secret"])
        request()
        assertEquals(2, server.requestCount)
    }

    /** 临时拍照复用真实压缩与 EXIF 流程，清理只影响当前会话而不删除服务图片。 */
    @Test
    fun photoPreparationCompressionAndCleanupStayInCurrentSession() = runBlocking {
        var decodedWidth = 0
        val service = OcrRepository(app, config, credentials = keys, recognizer = PlateRecognizer { path ->
            val bitmap = File(path).inputStream().use { requireNotNull(android.graphics.BitmapFactory.decodeStream(it)) }
            decodedWidth = bitmap.width; bitmap.recycle()
            PlateRecognitionResult(listOf(PlateCandidate("冀A12345", 0.9)))
        })
        val session = UUID.randomUUID().toString()
        val capture = service.prepareCapture(session)
        writeOcrTestImage(File(app.filesDir, capture.relativePath), 2400, 800)
        val existing = File(app.filesDir, "images/order_1/photo_fixture.jpg").apply { parentFile?.mkdirs(); writeBytes(image.readBytes()) }
        service.recognizeCapture(capture.relativePath)
        assertEquals(1920, decodedWidth)
        service.discardSession(session)
        assertFalse(File(app.filesDir, "photo_drafts/$session").exists()); assertTrue(existing.isFile)
    }

    /** 请求体中的表单经 URL 解码后再核对，验证保留 Base64 的加号和斜线。 */
    private fun form(request: RecordedRequest) = request.body.readUtf8().split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }

    /** 有界等待真实 TLS 请求，失败时不能无限挂住测试。 */
    private fun request(): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    /** 每个协议测试使用同一生产识别实现，只替换测试证书信任。 */
    private fun recognizer(value: OcrConfig = config) = BaiduPlateRecognizer(value, { keys }, client)

    /** 固定 JSON 测试响应不包含任何真实账号。 */
    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)

    /** Token 有效期由测试分别控制，验证真实刷新边界。 */
    private fun token(value: String = "fixture_token", expires: Int = 3600) = json("""{"access_token":"$value","expires_in":$expires}""")

    /** 概率个数对应七位车牌，平均值便于确定性断言。 */
    private fun plate(number: String = "冀A12345") = json("""{"words_result":[{"number":"$number","probability":[0.9,0.9,0.9,0.9,0.9,0.9,0.9]}]}""")

    /** 捕获预期业务异常，不能把未失败的请求算成通过。 */
    private suspend fun failure(action: suspend () -> Unit): IllegalArgumentException {
        try { action(); fail("应当失败") } catch (error: IllegalArgumentException) { return error }
        throw AssertionError("应当失败")
    }
}

/** 仅生成颜色块 JPEG 夹具，真实编解码但不使用真实车辆照片。 */
internal fun writeOcrTestImage(file: File, width: Int = 720, height: Int = 200) {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
    file.parentFile?.mkdirs()
    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
    bitmap.recycle()
}
