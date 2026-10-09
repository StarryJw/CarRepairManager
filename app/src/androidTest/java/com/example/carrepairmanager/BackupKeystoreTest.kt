package com.example.carrepairmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.carrepairmanager.data.backup.KeystorePasswordCipher
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 设备上验证真实 Keystore；独立随机别名不会读取或改动用户备份账号密钥。 */
@RunWith(AndroidJUnit4::class)
class BackupKeystoreTest {
    private val alias = "fixture_backup_key_${UUID.randomUUID()}"

    /** 只清理此测试创建的随机密钥。 */
    @After
    fun cleanup() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }

    /** 密文随机化、重建后解密与 GCM 篡改拒绝均由真实设备密钥实现完成。 */
    @Test
    fun ciphertextIsAuthenticatedAndSurvivesCipherRecreation() {
        val cipher = KeystorePasswordCipher(alias)
        val first = cipher.encrypt("fixture_password")
        val second = cipher.encrypt("fixture_password")
        assertNotEquals(first, second)
        assertFalse(first.contains("fixture_password"))
        assertEquals("fixture_password", KeystorePasswordCipher(alias).decrypt(first))
        val parts = first.split(':')
        val changed = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP).apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val tampered = parts[0] + ":" + android.util.Base64.encodeToString(changed, android.util.Base64.NO_WRAP)
        try { cipher.decrypt(tampered); fail("tampered ciphertext must be rejected") } catch (_: Exception) { }
    }
}
