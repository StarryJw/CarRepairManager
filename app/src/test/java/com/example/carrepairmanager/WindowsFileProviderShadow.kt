package com.example.carrepairmanager

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/** Android FileProvider 的目录检查硬编码 /；本机 Windows 测试仅替换 URI 与文件打开的路径适配。 */
@Implements(FileProvider::class, isInAndroidSdk = false)
class WindowsFileProviderShadow {
    @RealObject private lateinit var provider: FileProvider

    /** 使用与 photo_paths.xml 相同的窄根目录，不扩大测试中可访问的文件范围。 */
    @Implementation
    fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = requireNotNull(provider.context)
        require(uri.authority == "${context.packageName}.photos" && uri.pathSegments.first() == "photo_capture")
        val root = File(context.filesDir, "photo_drafts").canonicalFile
        val file = File(root, uri.pathSegments.drop(1).joinToString("/")).canonicalFile
        require(file.path.startsWith(root.path + File.separator))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    companion object {
        /** 保留 Android content URI 格式，文件路径比较使用当前主机的分隔符。 */
        @JvmStatic
        @Implementation
        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            require(authority == "${context.packageName}.photos")
            val root = File(context.filesDir, "photo_drafts").canonicalFile
            val target = file.canonicalFile
            require(target.path.startsWith(root.path + File.separator))
            return Uri.Builder().scheme("content").authority(authority)
                .encodedPath("/photo_capture/" + Uri.encode(target.relativeTo(root).invariantSeparatorsPath, "/")).build()
        }
    }
}
