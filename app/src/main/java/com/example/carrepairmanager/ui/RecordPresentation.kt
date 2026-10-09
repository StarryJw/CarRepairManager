package com.example.carrepairmanager.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.example.carrepairmanager.data.local.entity.ServiceCategory
import com.example.carrepairmanager.data.local.entity.PhotoType
import com.example.carrepairmanager.data.requireServiceDate

/** 元输入按十进制文本转换为整数分，不经过浮点数，不默默截断多余小数。 */
fun parseAmountCent(text: String): Long {
    val value = text.trim()
    require(Regex("(?:[0-9]+(?:\\.[0-9]{0,2})?|\\.[0-9]{1,2})").matches(value)) { "金额应为非负数字，最多两位小数" }
    val parts = value.split('.')
    val yuan = (parts[0].ifEmpty { "0" }).toLongOrNull()
    require(yuan != null && yuan <= Long.MAX_VALUE / 100) { "项目金额超出可保存范围" }
    val fraction = parts.getOrElse(1) { "" }.padEnd(2, '0').toLong()
    require(yuan < Long.MAX_VALUE / 100 || fraction <= Long.MAX_VALUE % 100) { "项目金额超出可保存范围" }
    return yuan * 100 + fraction
}

/** 留空独立表示待填，输入了内容就沿用严格金额校验，不能把错误或空白当作 0。 */
fun parseOptionalAmountCent(text: String): Long? = if (text.isBlank()) null else parseAmountCent(text)

/** 展示层才转为元；用整数商和余数保证大金额也能精确显示。 */
fun formatAmountCent(amountCent: Long): String =
    String.format(Locale.ROOT, "%d.%02d", amountCent / 100, amountCent % 100)

/** 服务的未定金额显示状态文字，与明确填写的免费项目区分。 */
fun formatServiceAmount(amountCent: Long?): String = amountCent?.let { "¥${formatAmountCent(it)}" } ?: "金额待填"

/** 今日使用设备日历日期；日期选择器的毫秒则必须按 UTC 转换。 */
fun todayServiceDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

/** Material 日期选择器采用 UTC 午夜，避免设备时区导致日期向前或向后偏一天。 */
fun pickerMillisToDate(millis: Long): String = dateFormatterUtc().format(Date(millis))

/** 将已校验的日历日期转换为选择器时间戳；草稿无效时交由界面使用今日。 */
fun dateToPickerMillis(date: String): Long? = try {
    requireServiceDate(date)
    dateFormatterUtc().parse(date)?.time
} catch (_: IllegalArgumentException) { null }

/** 每次创建独立格式器，避免 SimpleDateFormat 在线程间共享可变状态。 */
private fun dateFormatterUtc() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** 分类枚举保持稳定存储名，中文标签只用于展示。 */
fun ServiceCategory.displayName(): String = when (this) {
    ServiceCategory.REPAIR -> "维修"
    ServiceCategory.MAINTENANCE -> "保养"
    ServiceCategory.WASH -> "洗车"
    ServiceCategory.PART -> "配件"
    ServiceCategory.OTHER -> "其他"
}

/** 图片中文分类不改变数据库中保存的稳定枚举名。 */
fun PhotoType.displayName(): String = when (this) {
    PhotoType.BEFORE -> "维修前"
    PhotoType.AFTER -> "维修后"
    PhotoType.RECEIPT -> "票据"
    PhotoType.OTHER -> "其他"
}

/** 未填写的可选字段不制造无意义分隔符。 */
fun vehicleDescription(brand: String, model: String, color: String): String =
    listOf(listOf(brand, model).filter { it.isNotBlank() }.joinToString(" "), color)
        .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "品牌、车型与颜色未填写" }
