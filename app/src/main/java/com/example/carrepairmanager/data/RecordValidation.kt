package com.example.carrepairmanager.data

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 用固定语言环境去空白并转大写，避免手机语言改变车牌防重复结果。 */
fun normalizePlateNumber(plateNumber: String): String =
    plateNumber.filterNot { it.isWhitespace() }.uppercase(Locale.ROOT)

/** 任一项目待填则合计待填；已填金额仍检查负数和溢出，明确的 0 代表免费。 */
fun sumAmountCent(amounts: Iterable<Long?>): Long? {
    var total = 0L
    var pending = false
    amounts.forEach { amount ->
        if (amount == null) pending = true else {
            require(amount >= 0) { "项目金额不能为负数" }
            require(amount <= Long.MAX_VALUE - total) { "服务总金额超出可保存范围" }
            total += amount
        }
    }
    return if (pending) null else total
}

/** 固定日期格式才能安全排序；严格解析防止将 2 月 30 日自动调整为 3 月。 */
internal fun requireServiceDate(serviceDate: String) {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(serviceDate)) { "服务日期格式应为 yyyy-MM-dd" }
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val position = ParsePosition(0)
    require(formatter.parse(serviceDate, position) != null && position.index == serviceDate.length) {
        "服务日期无效"
    }
}
