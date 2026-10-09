package com.example.carrepairmanager.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 覆盖车牌等价输入、整数金额边界和日期校验，不依赖设备。 */
class RecordValidationTest {
    /** 普通与新能源车牌只进行标准化，不猜测具体地区的合法长度。 */
    @Test
    fun plateNormalizationRemovesWhitespaceAndUppercases() {
        assertEquals("冀A88888", normalizePlateNumber(" \t冀 a88 888\n"))
        assertEquals("京AD12345", normalizePlateNumber("京ad12345"))
        assertEquals("", normalizePlateNumber(" \t\n"))
    }

    /** 土耳其语手机环境不能让英文 i 标准化成不同的车牌字符。 */
    @Test
    fun plateNormalizationIsIndependentOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("冀I12345", normalizePlateNumber("冀i12345"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** 用整数分相加，包含超过 Int 范围的金额。 */
    @Test
    fun amountsAreSummedWithoutRoundingOrIntOverflow() {
        assertEquals(115840L, sumAmountCent(listOf(2990L, 12800L, 100050L)))
        assertEquals(3_000_000_001L, sumAmountCent(listOf(3_000_000_000L, 1L)))
        assertEquals(0L, sumAmountCent(emptyList()))
        assertEquals(Long.MAX_VALUE, sumAmountCent(listOf(Long.MAX_VALUE, 0L)))
    }

    /** 非法负金额和溢出必须明确失败，不能形成错误服务总额。 */
    @Test
    fun negativeAndOverflowAmountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { sumAmountCent(listOf(-1L)) }
        assertThrows(IllegalArgumentException::class.java) { sumAmountCent(listOf(Long.MAX_VALUE, 1L)) }
    }

    /** 待填项目不会被当作免费，且不能掩盖其他项目的非法金额。 */
    @Test
    fun pendingAmountsKeepTotalPendingButStillValidateKnownAmounts() {
        assertNull(sumAmountCent(listOf(null)))
        assertNull(sumAmountCent(listOf(2990L, null, 0L)))
        assertEquals(2990L, sumAmountCent(listOf(2990L, 0L)))
        assertThrows(IllegalArgumentException::class.java) { sumAmountCent(listOf(null, -1L)) }
        assertThrows(IllegalArgumentException::class.java) { sumAmountCent(listOf(null, Long.MAX_VALUE, 1L)) }
    }

    /** 固定宽度日期允许闰日，但拒绝自动纠正日期、缺少补零和尾部垃圾。 */
    @Test
    fun serviceDatesAreStrictCalendarDates() {
        requireServiceDate("2024-02-29")
        requireServiceDate("2026-10-02")
        listOf("2026-02-29", "2026-02-30", "2026-13-01", "2026-1-02", "2026-10-02x", "0000-01-01")
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) { requireServiceDate(invalid) }
            }
    }
}
