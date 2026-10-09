package com.example.carrepairmanager.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.backupDataStore by preferencesDataStore(name = "backup_settings")

/** 界面只读取配置和执行结果，不暴露密文或解密后的应用密码。 */
data class BackupSettings(val serverUrl: String, val directory: String, val username: String = "",
    val passwordSaved: Boolean = false, val automatic: Boolean = false, val unmetered: Boolean = true,
    val lastSuccessAt: Long = 0, val lastExportAt: Long = 0, val lastFingerprint: String = "", val lastError: String = "",
    val lastErrorScope: BackupScope = BackupScope.CLOUD) {
    val configured: Boolean get() = username.isNotBlank() && passwordSaved
}

/** 凭据只供网络层使用，禁止打印或序列化到日志与备份中。 */
class WebDavCredentials(val serverUrl: String, val directory: String, val username: String, val password: String)

/** 隔离平台密钥接口方便测试；生产实现始终使用 Android Keystore，不提供明文回退。 */
interface PasswordCipher {
    fun encrypt(password: String): String
    fun decrypt(encrypted: String): String
}

/** AES-GCM 的密钥不离开 Keystore，每次保存随机 IV，认证失败要求重新输入密码。 */
class KeystorePasswordCipher(private val alias: String = "car_repair_webdav_password") : PasswordCipher {

    /** 仅首次保存生成不可导出的密钥，换机或卸载后重新配置凭据。 */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    /** IV 与密文存入私有 DataStore，密钥永不写入配置文件。 */
    override fun encrypt(password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /** GCM 标签不匹配或密钥丢失时让调用方显示凭据失效，不静默降级。 */
    override fun decrypt(encrypted: String): String {
        val parts = encrypted.split(':')
        require(parts.size == 2) { "备份凭据无效，请重新配置" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}

/** 备份运行时设置单独存储，应用的一般排序与图片质量不参与整份恢复。 */
class BackupSettingsRepository(context: Context, private val config: BackupConfig,
    private val cipher: PasswordCipher = KeystorePasswordCipher(),
    private val store: DataStore<Preferences> = context.applicationContext.backupDataStore) {
    private val url = stringPreferencesKey("webdav_url")
    private val directory = stringPreferencesKey("webdav_directory")
    private val username = stringPreferencesKey("webdav_username")
    private val secret = stringPreferencesKey("password_ciphertext")
    private val automatic = booleanPreferencesKey("automatic")
    private val unmetered = booleanPreferencesKey("unmetered")
    private val success = longPreferencesKey("last_success_at")
    private val exported = longPreferencesKey("last_export_at")
    private val fingerprint = stringPreferencesKey("last_fingerprint")
    private val error = stringPreferencesKey("last_error")
    private val errorScope = stringPreferencesKey("last_error_scope")
    val settings = store.data.map {
        BackupSettings(it[url] ?: config.webDavUrl, it[directory] ?: config.remoteDirectory, it[username].orEmpty(),
            !it[secret].isNullOrEmpty(), it[automatic] ?: false, it[unmetered] ?: config.unmetered,
            it[success] ?: 0, it[exported] ?: 0, it[fingerprint].orEmpty(), it[error].orEmpty(),
            // 旧版未记录来源的错误归入云端，升级后不再把历史上传失败重复显示到本地。
            BackupScope.entries.firstOrNull { scope -> scope.name == it[errorScope] } ?: BackupScope.CLOUD)
    }

    /** 留空密码只允许沿用同一服务器、目录及账号的既有凭据。 */
    suspend fun saveConnection(serverUrl: String, remoteDirectory: String, account: String, password: String) = withContext(Dispatchers.IO) {
        val normalizedUrl = WebDavClient.validateUrl(serverUrl)
        val normalizedDirectory = WebDavClient.validateDirectory(remoteDirectory)
        require(account.trim().isNotEmpty() && ':' !in account && '\n' !in account && '\r' !in account) { "请输入有效的 WebDAV 账号" }
        val old = settings.first()
        val changed = old.serverUrl != normalizedUrl || old.directory != normalizedDirectory || old.username != account.trim()
        require(password.isNotBlank() || (!changed && old.passwordSaved)) { "请输入专用应用密码" }
        val encrypted = if (password.isNotBlank()) cipher.encrypt(password) else null
        store.edit {
            it[url] = normalizedUrl; it[directory] = normalizedDirectory; it[username] = account.trim()
            if (encrypted != null) it[secret] = encrypted
            if (changed) { it.remove(fingerprint); it.remove(success) }
            it.remove(error); it.remove(errorScope)
        }
    }

    /** 密钥丢失等细节不向日志或界面暴露，用户重新填写应用密码即可。 */
    suspend fun credentials(): WebDavCredentials = withContext(Dispatchers.IO) {
        val saved = settings.first()
        require(saved.configured) { "请先保存 WebDAV 账号与应用密码" }
        val encrypted = store.data.first()[secret].orEmpty()
        val password = try { cipher.decrypt(encrypted) } catch (_: Exception) { throw BackupException("备份凭据失效，请重新输入应用密码") }
        WebDavCredentials(saved.serverUrl, saved.directory, saved.username, password)
    }

    /** 自动上传必须由用户主动开启；未配置账号时不能开启。 */
    suspend fun setAutomatic(enabled: Boolean) {
        require(!enabled || settings.first().configured) { "请先配置 WebDAV 账号" }
        store.edit { it[automatic] = enabled }
    }

    /** 网络条件只影响自动任务，手动上传仍由用户明确发起。 */
    suspend fun setUnmetered(required: Boolean) { store.edit { it[unmetered] = required } }

    /** 成功摘要只对应已上传的快照；不读取此刻的新数据冒充已经备份。 */
    suspend fun cloudSucceeded(at: Long, snapshotFingerprint: String) {
        store.edit { it[success] = at; it[fingerprint] = snapshotFingerprint; it.remove(error); it.remove(errorScope) }
    }

    /** 本地成功与云端成功分别记录，导出不能使自动云备份跳过未上传变更。 */
    suspend fun exportSucceeded(at: Long) { store.edit { it[exported] = at; it.remove(error); it.remove(errorScope) } }

    /** 恢复会改变本地快照，下一次自动任务必须重新比较并上传。 */
    suspend fun restored() { store.edit { it.remove(fingerprint); it.remove(error); it.remove(errorScope) } }

    /** 同时保存错误来源，App 重开后仍在对应的本地或云端区域展示。 */
    suspend fun failed(message: String, scope: BackupScope) { store.edit { it[error] = message; it[errorScope] = scope.name } }
}
