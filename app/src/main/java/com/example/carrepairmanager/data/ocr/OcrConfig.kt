package com.example.carrepairmanager.data.ocr

import android.content.Context
import java.util.Properties
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 非敏感调用参数集中在资产文件，凭据由项目本机配置生成到代码。 */
data class OcrConfig(
    val tokenUrl: String,
    val licensePlateUrl: String,
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val writeTimeoutSeconds: Long,
    val callTimeoutSeconds: Long,
    val tokenRefreshMarginSeconds: Long,
    val imageMaxEdge: Int,
    val imageQuality: Int,
    val imageMinSide: Int,
    val maxFormBytes: Long,
    val multiDetect: Boolean,
) {
    init {
        validateEndpoint(tokenUrl); validateEndpoint(licensePlateUrl)
        require(listOf(connectTimeoutSeconds, readTimeoutSeconds, writeTimeoutSeconds, callTimeoutSeconds).all { it in 1..300 }) { "OCR 网络超时配置须为 1～300 秒" }
        require(tokenRefreshMarginSeconds in 0..3600) { "OCR Token 提前刷新时间无效" }
        require(imageMaxEdge in 1440..1920 && imageQuality in listOf(50, 70, 90)) { "OCR 图片配置不符合现有压缩规则" }
        require(imageMinSide in 15..imageMaxEdge && maxFormBytes in 1..8_000_000) { "OCR 图片限制超出百度接口范围" }
    }

    companion object {
        /** UTF-8 读取并严格检查必需参数，配置错误只反馈到 OCR 页面。 */
        fun load(context: Context): OcrConfig = fromProperties(Properties().apply {
            context.assets.open("ocr.properties").reader(Charsets.UTF_8).use { load(it) }
        })

        /** 与测试共用真实配置解析，避免缺失参数时静默改变调用行为。 */
        internal fun fromProperties(properties: Properties): OcrConfig {
            fun value(key: String) = requireNotNull(properties.getProperty(key)) { "OCR 配置缺少 $key" }.trim()
            fun number(key: String) = requireNotNull(value(key).toLongOrNull()) { "OCR 配置 $key 无效" }
            fun integer(key: String) = requireNotNull(value(key).toIntOrNull()) { "OCR 配置 $key 无效" }
            return OcrConfig(value("baidu.token.url"), value("baidu.license.plate.url"),
                number("network.connect.timeout.seconds"), number("network.read.timeout.seconds"),
                number("network.write.timeout.seconds"), number("network.call.timeout.seconds"),
                number("token.refresh.margin.seconds"), integer("image.max.edge"), integer("image.jpeg.quality"),
                integer("image.min.side"), number("request.max.form.bytes"),
                requireNotNull(value("recognition.multi.detect").toBooleanStrictOrNull()) { "OCR 多车牌配置无效" })
        }

        /** 凭据只通过 HTTPS 发出，禁止重定向、URL 内嵌账号或查询参数。 */
        private fun validateEndpoint(value: String) {
            val url = value.toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null) { "OCR 接口须为不含账号、查询参数或片段的 HTTPS 地址" }
        }
    }
}
