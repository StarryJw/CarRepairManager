package com.example.carrepairmanager.data.ocr

/** 页面只依赖这个接口，后续更换识别服务不改变人工确认流程。 */
fun interface PlateRecognizer {
    /** 只识别临时图片，不查询或写入业务数据库。 */
    suspend fun recognize(imagePath: String): PlateRecognitionResult
}

/** 置信度为空表示服务未返回完整有效的逐字符概率，界面不能虚构百分比。 */
data class PlateCandidate(val plateNumber: String, val confidence: Double?)

/** 多车牌保持独立候选，最终车牌仍由用户确认。 */
data class PlateRecognitionResult(val candidates: List<PlateCandidate>)

/** 普通类避免自动生成包含密钥的 toString，凭据不进入日志或 SavedStateHandle。 */
class OcrCredentials(val apiKey: String, val secretKey: String)

/** 只使用本地固定错误说明，避免把服务端响应中的凭据回显到界面。 */
class OcrException(message: String, val code: Int? = null) : IllegalArgumentException(message)
