package com.example.carrepairmanager

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carrepairmanager.data.PhotoFileStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 在 Android 上验证真实 FileProvider；只建立随机测试草稿，不修改用户数据库和照片。 */
@RunWith(AndroidJUnit4::class)
class PhotoFileProviderTest {
    /** 草稿 URI 可以读写，但已保存图库和数据库都不属于共享根目录。 */
    @Test
    fun providerSharesOnlyCaptureDrafts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PhotoFileStore(context)
        val session = UUID.randomUUID().toString()
        try {
            val capture = store.prepareCapture(session)
            assertEquals("content", capture.uri.scheme)
            val bytes = byteArrayOf(1, 2, 3, 4)
            requireNotNull(context.contentResolver.openOutputStream(capture.uri)).use { it.write(bytes) }
            val read = requireNotNull(context.contentResolver.openInputStream(capture.uri)).use { it.readBytes() }
            assertArrayEquals(bytes, read)
            listOf(File(context.filesDir, "images/private.jpg"), context.getDatabasePath("car_repair.db")).forEach { file ->
                try { FileProvider.getUriForFile(context, "${context.packageName}.photos", file); fail("私有档案不应产生共享 URI") }
                catch (expected: IllegalArgumentException) { assertEquals(true, expected.message?.contains("root")) }
            }
        } finally { store.discardSession(session) }
    }
}
