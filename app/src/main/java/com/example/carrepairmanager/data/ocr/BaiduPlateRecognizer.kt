package com.example.carrepairmanager.data.ocr

import android.graphics.BitmapFactory
import android.util.Base64
import com.example.carrepairmanager.data.normalizePlateNumber
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 按百度 REST 协议调用；只缓存内存 Token，不记录图片、密钥或原始响应。 */
class BaiduPlateRecognizer(private val config: OcrConfig, private val credentials: suspend () -> OcrCredentials,
    client: OkHttpClient? = null, private val now: () -> Long = System::currentTimeMillis) : PlateRecognizer {
    private val client = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(config.callTimeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val mutex = Mutex()
    private var cachedToken: CachedToken? = null

    /** 串行请求避免重复兑换 Token；仅 Token 失效时刷新一次，其他失败由用户重试。 */
    override suspend fun recognize(imagePath: String): PlateRecognitionResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val image = File(imagePath)
            require(image.isFile && image.length() in 1..config.maxFormBytes) { "车牌照片为空或过大，请重新拍照" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            image.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            require(minOf(bounds.outWidth, bounds.outHeight) >= config.imageMinSide &&
                maxOf(bounds.outWidth, bounds.outHeight) <= 4096) { "车牌照片尺寸无效，请重新拍照" }
            val body = FormBody.Builder().add("image", Base64.encodeToString(image.readBytes(), Base64.NO_WRAP))
                .add("multi_detect", config.multiDetect.toString()).build()
            // 上限必须核对 Base64 和 URL 编码后的字节数，不能只检查 JPEG 文件大小。
            require(body.contentLength() <= config.maxFormBytes) { "车牌照片编码后过大，请重新拍照" }
            val keys = credentials()
            var token = token(keys)
            var response = recognizeRequest(token, body)
            if (errorCode(response) in listOf(110, 111)) {
                cachedToken = null
                token = token(keys)
                response = recognizeRequest(token, body)
            }
            parseResult(response)
        }
    }

    /** 缓存依据有效期与当前凭据复用，提前刷新窗口不会吞掉短有效期 Token。 */
    private suspend fun token(keys: OcrCredentials): String {
        cachedToken?.takeIf { it.keys.apiKey == keys.apiKey && it.keys.secretKey == keys.secretKey && now() < it.expiresAt }
            ?.let { return it.value }
        val body = FormBody.Builder().add("grant_type", "client_credentials")
            .add("client_id", keys.apiKey).add("client_secret", keys.secretKey).build()
        val response = requestJson(Request.Builder().url(config.tokenUrl).post(body).build())
        if (response["error"] != null || errorCode(response) != null) throw OcrException("百度 OCR 鉴权失败，请手动输入车牌")
        val value = (response["access_token"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw OcrException("百度 OCR 鉴权失败，请稍后重试或手动输入车牌")
        val expires = (response["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..31_536_000 }
            ?: throw OcrException("百度 OCR Token 有效期无效，请重试")
        val lifetime = expires - minOf(config.tokenRefreshMarginSeconds, expires / 2)
        cachedToken = CachedToken(keys, value, now() + lifetime * 1000)
        return value
    }

    /** access_token 仅加到请求 URL；不使用日志拦截器或携带凭据重定向。 */
    private suspend fun recognizeRequest(token: String, body: FormBody): JsonObject = requestJson(Request.Builder()
        .url(config.licensePlateUrl.toHttpUrl().newBuilder().addQueryParameter("access_token", token).build()).post(body).build())

    /** 取消页面任务时同时取消 HTTP Call，网络超时及服务失败提供固定中文反馈。 */
    private suspend fun requestJson(request: Request): JsonObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(OcrException(
                    if (e is SocketTimeoutException || e is java.io.InterruptedIOException) "OCR 请求超时，请检查网络后重试"
                    else "OCR 网络连接失败，请检查网络后重试"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw OcrException(when (response.code) {
                            400, 401, 403 -> "百度 OCR 请求或鉴权失败，请手动输入车牌"
                            429 -> "百度 OCR 调用过于频繁，请稍后重试"
                            in 300..399 -> "OCR 接口发生重定向，请返回手动输入车牌"
                            else -> "百度 OCR 服务暂不可用，请稍后重试"
                        })
                        val json = Json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                            ?: throw OcrException("百度 OCR 返回格式无效，请重试")
                        if (continuation.isActive) continuation.resume(json)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is OcrException) error else OcrException("百度 OCR 响应读取失败，请重试"))
                    }
                }
            }
        })
    }

    /** Token 与密钥使用普通类，防止调试输出自动包含敏感值。 */
    private class CachedToken(val keys: OcrCredentials, val value: String, val expiresAt: Long)

    companion object {
        /** 业务错误与 HTTP 成功独立，不能把错误 JSON 当成空识别结果。 */
        private fun errorCode(response: JsonObject): Int? = (response["error_code"] as? JsonPrimitive)?.intOrNull

        /** 新接口返回数组；兼容旧版单对象，置信度只使用完整有效的逐字符概率。 */
        internal fun parseResult(response: JsonObject): PlateRecognitionResult {
            errorCode(response)?.let { code -> throw OcrException(when (code) {
                6, 14 -> "百度 OCR 无车牌识别权限，请手动输入车牌"
                4, 17, 18, 19 -> "百度 OCR 调用额度或频率受限，请稍后重试"
                110, 111 -> "百度 OCR 鉴权失败，请稍后重试或手动输入车牌"
                216200, 216201, 216202, 216205 -> "车牌照片格式、尺寸或大小不符合要求，请重新拍照"
                else -> "百度 OCR 识别失败（错误码 $code），请重试或手动输入车牌"
            }, code) }
            val rows = when (val words = response["words_result"]) {
                is JsonArray -> words.toList()
                is JsonObject -> listOf(words)
                else -> throw OcrException("百度 OCR 未返回车牌结果，请重新拍照或手动输入")
            }
            val candidates = rows.mapNotNull { row ->
                val value = row as? JsonObject ?: return@mapNotNull null
                val raw = (value["number"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val plate = normalizePlateNumber(raw).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val probabilities = (value["probability"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
                val confidence = probabilities?.takeIf { it.size == plate.length && it.all { p -> p != null && p.isFinite() && p in 0.0..1.0 } }
                    ?.map { requireNotNull(it) }?.average()
                PlateCandidate(plate, confidence)
            }.distinctBy { it.plateNumber }
            require(candidates.isNotEmpty()) { "未识别到车牌，请靠近车牌重新拍照或手动输入" }
            return PlateRecognitionResult(candidates)
        }
    }
}
