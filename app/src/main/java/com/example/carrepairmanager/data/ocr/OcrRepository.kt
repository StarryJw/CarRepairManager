package com.example.carrepairmanager.data.ocr

import android.content.Context
import android.net.Uri
import com.example.carrepairmanager.BuildConfig
import com.example.carrepairmanager.data.PhotoCapture
import com.example.carrepairmanager.data.PhotoFileStore
import com.example.carrepairmanager.data.local.entity.PhotoType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** OCR 只管理临时照片；凭据从构建生成的代码读取，识别结果不写业务数据库。 */
class OcrRepository(context: Context, private val config: OcrConfig,
    private val photos: PhotoFileStore = PhotoFileStore(context, config.imageMaxEdge),
    credentials: OcrCredentials = OcrCredentials(BuildConfig.BAIDU_OCR_API_KEY, BuildConfig.BAIDU_OCR_SECRET_KEY),
    recognizer: PlateRecognizer? = null, httpClient: okhttp3.OkHttpClient? = null) {
    private val context = context.applicationContext
    private val recognizer = recognizer ?: BaiduPlateRecognizer(config, { credentials }, httpClient)
    val configured: Boolean = credentials.apiKey.isNotBlank() && credentials.secretKey.isNotBlank()

    /** 复用已有 FileProvider 和会话目录，不新增相机或存储权限。 */
    suspend fun prepareCapture(session: String): PhotoCapture = photos.prepareCapture(session)

    /** 未发起的相机请求可重建 URI，已发起的请求不能在旋转后重复打开。 */
    suspend fun captureUri(path: String): Uri = photos.captureUri(path)

    /** 先矫正 EXIF、压缩并移除元数据，再由识别接口读取临时 JPEG。 */
    suspend fun recognizeCapture(path: String): PlateRecognitionResult = withContext(Dispatchers.IO) {
        require(configured) { "车牌识别暂不可用，请返回手动输入" }
        val draft = photos.compressCapture(path, PhotoType.OTHER, config.imageQuality)
        recognizer.recognize(File(context.filesDir, draft.relativePath).absolutePath)
    }

    /** 成功、取消或离开页面只清理当前 OCR 会话，不接触任何服务照片。 */
    suspend fun discardSession(session: String) = photos.discardSession(session)
}
