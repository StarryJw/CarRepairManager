package com.example.carrepairmanager.ui

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 元输入、整数分显示和日期选择器时区是核心 UI 的数据边界。 */
class RecordPresentationTest {
    /** 常见元输入精确转换，允许免费项目、小于一元及两位小数。 */
    @Test
    fun yuanInputIsConvertedExactlyToCents() {
        mapOf("29.90" to 2990L, "128" to 12800L, ".5" to 50L, "0" to 0L, "1." to 100L,
            " 0001.01 " to 101L, "92233720368547758.07" to Long.MAX_VALUE).forEach { (input, expected) ->
            assertEquals(expected, parseAmountCent(input))
        }
    }
    /** 多余小数、负值、科学记数法和溢出不允许静默四舍五入。 */
    @Test
    fun invalidAndOverflowAmountsAreRejected() {
        listOf("", ".", "-1", "1.001", "1e3", "NaN", "1,000", "92233720368547758.08", "92233720368547759")
            .forEach { assertThrows(IllegalArgumentException::class.java) { parseAmountCent(it) } }
    }
    /** 从 Long 最大金额显示再读回仍精确，不使用 Double。 */
    @Test
    fun centsFormattingRoundTripsLargeValues() {
        listOf(0L, 1L, 2990L, 3_000_000_001L, Long.MAX_VALUE).forEach {
            assertEquals(it, parseAmountCent(formatAmountCent(it)))
        }
    }

    /** 空白允许稍后填写，明确的 0 仍是免费；非空非法输入必须拒绝。 */
    @Test
    fun optionalAmountDistinguishesPendingFromZero() {
        assertNull(parseOptionalAmountCent(""))
        assertNull(parseOptionalAmountCent(" \t\n"))
        assertEquals(0L, parseOptionalAmountCent("0"))
        assertEquals(2990L, parseOptionalAmountCent("29.90"))
        assertEquals("金额待填", formatServiceAmount(null))
        assertEquals("¥0.00", formatServiceAmount(0))
        listOf("-1", "1.001", "abc").forEach {
            assertThrows(IllegalArgumentException::class.java) { parseOptionalAmountCent(it) }
        }
    }
    /** 日期选择器毫秒采用 UTC，手机时区变化不能改变选中的日期。 */
    @Test
    fun pickerDateIsIndependentOfDeviceTimeZone() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Shanghai", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("2026-10-02", pickerMillisToDate(requireNotNull(dateToPickerMillis("2026-10-02"))))
            }
            assertNull(dateToPickerMillis("2026-02-30"))
            assertNull(dateToPickerMillis("2026-10-02junk"))
        } finally { TimeZone.setDefault(previous) }
    }
}
