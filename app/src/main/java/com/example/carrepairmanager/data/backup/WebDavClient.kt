package com.example.carrepairmanager.data.backup

import android.util.Xml
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser

/** HTTPS WebDAV 的唯一入口；禁用重定向，避免将账号密码发送给另一个主机。 */
class WebDavClient(private val config: BackupConfig, private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {

    /** 测试账号读取和专用目录权限，不上传业务档案。 */
    suspend fun testConnection(credentials: WebDavCredentials) = withContext(Dispatchers.IO) {
        ensureDirectory(credentials)
        propfind(credentials, directoryUrl(credentials), "0")
        Unit
    }

    /** 上传临时名称后 MOVE 为完整 ZIP，再用 DAV 文件属性核对大小；临时文件不作为恢复候选。 */
    suspend fun upload(credentials: WebDavCredentials, file: File) = withContext(Dispatchers.IO) {
        require(isBackupName(file.name)) { "云端备份文件名无效" }
        require(file.length() in 1..config.maxCloudBytes) { "备份超过云端单文件大小上限，请先导出本地备份" }
        ensureDirectory(credentials)
        val directory = directoryUrl(credentials)
        val pending = directory.newBuilder().addPathSegment(file.name + ".uploading").build()
        val complete = directory.newBuilder().addPathSegment(file.name).build()
        execute(request(credentials, pending).header("If-None-Match", "*")
            .put(file.asRequestBody("application/zip".toMediaType())).build()).use { requireSuccess(it, setOf(201, 204)) }
        execute(request(credentials, pending).header("Destination", complete.toString()).header("Overwrite", "F")
            .method("MOVE", null).build()).use { requireSuccess(it, setOf(201, 204)) }
        // HEAD 可以省略 Content-Length；Depth 0 查询的是目标文件大小，不依赖响应体长度或目录枚举。
        val remoteSize = parseListing(propfind(credentials, complete, "0"), directory, requireSize = true)
            .singleOrNull { it.name == file.name }?.size
            ?: throw BackupException("备份已上传，但 WebDAV 未提供有效的文件大小，无法确认完整性，请重试", true)
        if (remoteSize != file.length()) throw BackupException(
            "云端备份大小校验失败（本地 ${file.length()} 字节，云端 $remoteSize 字节），请重试", true)
    }

    /** 只显示本应用目录内的完整快照，拒绝服务器返回的跨目录或跨主机链接。 */
    suspend fun list(credentials: WebDavCredentials): List<RemoteBackup> = withContext(Dispatchers.IO) {
        ensureDirectory(credentials)
        val directory = directoryUrl(credentials)
        val xml = propfind(credentials, directory, "1")
        parseListing(xml, directory).distinctBy { it.name }.sortedByDescending { it.name }
    }

    /** 下载也限制实际字节数，后续必须再次经过完整 ZIP 校验才能恢复。 */
    suspend fun download(credentials: WebDavCredentials, backup: RemoteBackup, target: File,
        archive: BackupArchive) = withContext(Dispatchers.IO) {
        require(isBackupName(backup.name) && backup.size <= config.maxArchiveBytes) { "云端备份名称或大小无效" }
        val url = directoryUrl(credentials).newBuilder().addPathSegment(backup.name).build()
        execute(request(credentials, url).get().build()).use { response ->
            requireSuccess(response, setOf(200))
            val body = response.body ?: throw BackupException("云端备份内容为空")
            require(body.contentLength() <= config.maxArchiveBytes) { "云端备份超过本地导入大小上限" }
            val count = body.byteStream().use { input -> target.outputStream().use { output ->
                archive.copyLimited(input, output, config.maxArchiveBytes)
            } }
            require(count > 0 && (backup.size <= 0 || count == backup.size)) { "云端备份下载不完整" }
        }
    }

    /** 多级目录逐层创建，已存在时通过 PROPFIND 验证，不能把任意 405 当作成功。 */
    private suspend fun ensureDirectory(credentials: WebDavCredentials) {
        var url = requireNotNull(validateUrl(credentials.serverUrl).toHttpUrlOrNull())
        propfind(credentials, url, "0")
        validateDirectory(credentials.directory).split('/').forEach { segment ->
            url = url.newBuilder().addPathSegment(segment).addPathSegment("").build()
            execute(request(credentials, url).method("MKCOL", ByteArray(0).toRequestBody()).build()).use {
                if (it.code != 405) requireSuccess(it, setOf(201))
            }
            propfind(credentials, url, "0")
        }
    }

    /** 请求有限的 WebDAV 属性，避免下载不需要的服务器内容或递归枚举整盘。 */
    private suspend fun propfind(credentials: WebDavCredentials, url: HttpUrl, depth: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"
        return execute(request(credentials, url).header("Depth", depth)
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()).use {
            requireSuccess(it, setOf(207))
            val response = it.body ?: throw BackupException("WebDAV 返回空目录响应")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            response.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size().toLong() + count > config.maxJsonBytes) throw BackupException("WebDAV 目录响应过大")
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    /** 只采纳各 propstat 中成功的 DAV 属性；上传校验要求明确大小，列表可保留未知大小供下载时核对。 */
    internal fun parseListing(xml: String, directory: HttpUrl, requireSize: Boolean = false): List<RemoteBackup> {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val results = mutableListOf<RemoteBackup>()
        var href = ""
        var size: Long? = null
        var collection = false
        var successful = false
        var inPropstat = false
        var propertySize: Long? = null
        var propertyCollection = false
        var propertySuccessful = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            require(event != XmlPullParser.DOCDECL) { "WebDAV 响应包含不支持的文档声明" }
            if (event == XmlPullParser.START_TAG && parser.namespace == "DAV:") when (parser.name) {
                "response" -> { href = ""; size = null; collection = false; successful = false; inPropstat = false }
                "propstat" -> { inPropstat = true; propertySize = null; propertyCollection = false; propertySuccessful = false }
                "href" -> href = parser.nextText()
                "getcontentlength" -> {
                    val length = parser.nextText().trim().toLongOrNull()?.takeIf { it >= 0 }
                    if (inPropstat) propertySize = length
                }
                "collection" -> if (inPropstat) propertyCollection = true
                "status" -> {
                    val status = parser.nextText().trim()
                    if (inPropstat) propertySuccessful = Regex("HTTP/\\S+\\s+200(?:\\s.*)?").matches(status)
                }
            }
            if (event == XmlPullParser.END_TAG && parser.namespace == "DAV:" && parser.name == "propstat") {
                // 207 中可混有 200 和 404 属性，失败分组的长度不能被另一个成功状态误认为可信。
                if (propertySuccessful) {
                    if (propertySize != null) size = propertySize
                    collection = collection || propertyCollection
                    successful = true
                }
                inPropstat = false
            }
            if (event == XmlPullParser.END_TAG && parser.namespace == "DAV:" && parser.name == "response" &&
                !collection && successful && (!requireSize || size != null)) {
                val resolved = directory.resolve(href)
                if (resolved != null && resolved.scheme == directory.scheme && resolved.host == directory.host && resolved.port == directory.port &&
                    resolved.encodedPath.startsWith(directory.encodedPath) && resolved.pathSegments.size == directory.pathSegments.size &&
                    resolved.query == null && resolved.fragment == null) {
                    val name = resolved.pathSegments.last()
                    if (isBackupName(name)) results += RemoteBackup(name, size ?: 0)
                }
            }
            event = parser.next()
        }
        return results
    }

    /** 所有请求共享明确的认证方式，服务端响应正文不写入日志或用户错误。 */
    private fun request(credentials: WebDavCredentials, url: HttpUrl) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8))

    /** URL 路径由段构建，中文或空格目录正确编码，避免拼接导致越界。 */
    private fun directoryUrl(credentials: WebDavCredentials): HttpUrl {
        val builder = requireNotNull(validateUrl(credentials.serverUrl).toHttpUrlOrNull()).newBuilder()
        validateDirectory(credentials.directory).split('/').forEach { builder.addPathSegment(it) }
        return builder.addPathSegment("").build()
    }

    /** 可取消的请求支持 WorkManager 停止任务；不在主线程阻塞 execute。 */
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val error = if (e is SSLException) BackupException("HTTPS 证书验证失败，请检查服务器证书和地址", false, e)
                    else BackupException("网络连接失败，请检查网络后重试", true, e)
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, discarded, _ -> discarded.close() }
            }
        })
    }

    /** 将认证、配额与临时网络错误分开，只有可重试错误才进入后台退避。 */
    private fun requireSuccess(response: Response, accepted: Set<Int>) {
        if (response.code in accepted) return
        throw when (response.code) {
            401, 403 -> BackupException("WebDAV 认证或权限失败，请检查账号、专用应用密码和目录权限")
            404, 409 -> BackupException("WebDAV 地址或备份目录不存在，请检查配置")
            413 -> BackupException("备份超过服务器单文件大小限制，请导出本地备份")
            507 -> BackupException("云端存储空间不足，请清理空间后重试")
            408, 429, in 500..599 -> BackupException("WebDAV 服务暂时不可用（HTTP ${response.code}），稍后重试", true)
            in 300..399 -> BackupException("WebDAV 地址发生重定向，请直接配置最终 HTTPS 地址")
            else -> BackupException("WebDAV 操作失败（HTTP ${response.code}），请检查服务支持的功能")
        }
    }

    companion object {
        /** 地址必须直接指向 HTTPS WebDAV，认证信息只能从加密凭据读取。 */
        fun validateUrl(value: String): String {
            val url = value.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "请输入不含账号或查询参数的 HTTPS WebDAV 地址" }
            return if (url.encodedPath.endsWith('/')) url.toString() else url.newBuilder().addPathSegment("").build().toString()
        }

        /** 专用目录只允许相对路径，不接受父目录、绝对路径或 URL 查询字符。 */
        fun validateDirectory(value: String): String {
            val result = value.trim()
            require(result.isNotEmpty() && result.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
                result.none { it == '\\' || it == ':' || it == '?' || it == '#' || it == '%' || it.isISOControl() }) { "请输入有效的相对备份目录" }
            return result
        }

        /** 仅管理本应用版本名，其他用户文件和未完成上传始终排除。 */
        fun isBackupName(name: String) = Regex("CarShopBackup_[0-9]{8}_[0-9]{6}_[0-9]{3}_[a-f0-9-]{36}\\.zip").matches(name)
    }
}
