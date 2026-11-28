package dev.multisync.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import dev.multisync.core.checkCancellation
import java.io.File
import java.io.InputStream

data class DeviceStorageRoot(val name: String, val path: String)
data class LocalDirectory(val name: String, val path: String)
data class BackupFileStat(val name: String, val size: Long, val modified: Long, val directory: Boolean)
data class BackupDirectoryInfo(val name: String, val files: Int, val directories: Int)

interface BackupNode {
    val identity: String
    fun stat(): BackupFileStat
    fun children(): List<BackupNode>
    fun open(): InputStream
    fun delete(): Boolean
}

class BackupStorage(private val context: Context,
    private val rootsProvider: () -> List<DeviceStorageRoot> = { deviceStorageRoots(context) },
    private val access: () -> Boolean = { hasDeviceFileAccess(context) }) {
    fun roots() = rootsProvider()
    fun browse(path: String): List<LocalDirectory> {
        require(access()) { "请先允许所有文件访问，再读取设备存储目录" }
        if (path.isBlank()) return roots().map { LocalDirectory(it.name, it.path) }
        val directory = checkedDeviceFile(path)
        require(directory.isDirectory && directory.canRead()) { "系统不允许读取此目录，请重新选择共享存储中的备份目录" }
        return checkNotNull(directory.listFiles()) { "目录读取失败，请检查文件访问权限" }.asSequence()
            .filter { it.isDirectory && it.absoluteFile == it.canonicalFile }
            .map { LocalDirectory(it.name, it.canonicalPath) }.sortedBy { it.name.lowercase() }.toList()
    }

    fun source(uriString: String, forCleanup: Boolean = false): BackupNode {
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            "file" -> {
                require(access()) { "设备文件访问权限未开启，请在设置中允许所有文件访问" }
                val root = checkedDeviceFile(checkNotNull(uri.path) { "设备目录路径无效" })
                require(root.isDirectory && root.canRead()) { "备份目录不可读取，请重新选择目录或检查权限" }
                if (forCleanup) require(root.canWrite()) { "备份目录不可写，请重新授权或关闭校验后清理" }
                FileNode(root, root)
            }
            "content" -> {
                require(DocumentsContract.isTreeUri(uri)) { "请选择整个备份目录，不要选择单个文件" }
                val permission = requireNotNull(context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }) {
                    "目录长期读取授权已失效，请重新通过系统选择器授权"
                }
                require(permission.isReadPermission) { "目录长期读取授权已失效，请重新通过系统选择器授权" }
                if (forCleanup) require(permission.isWritePermission) { "目录仅有读取权限，请重新授权读写或关闭校验后清理" }
                val id = DocumentsContract.getTreeDocumentId(uri)
                DocumentNode(uri, DocumentsContract.buildDocumentUriUsingTree(uri, id))
            }
            else -> error("请选择设备目录或通过系统选择器授权目录")
        }
    }

    fun inspect(uri: String, forCleanup: Boolean): BackupDirectoryInfo {
        val root = source(uri, forCleanup)
        val stat = root.stat()
        require(stat.directory) { "所选位置不是文件夹" }
        val entries = root.children().map { it to it.stat() }
        entries.firstOrNull { !it.second.directory && it.second.size > 0 }?.first?.open()?.use { it.read() }
        return BackupDirectoryInfo(stat.name, entries.count { !it.second.directory }, entries.count { it.second.directory })
    }

    private fun checkedDeviceFile(path: String): File {
        val file = File(path).canonicalFile
        require(roots().any { root -> val base = File(root.path).canonicalFile; file == base || file.path.startsWith(base.path + File.separator) }) {
            "目录不在已挂载的设备存储中，请重新选择"
        }
        return file
    }

    private class FileNode(private val file: File, private val root: File) : BackupNode {
        private fun checked(): File {
            check(file.canonicalFile == file && (file == root || file.path.startsWith(root.path + File.separator))) { "文件位置已变化，本地文件已保留" }
            check(file.exists() && file.canRead()) { "文件不存在或没有读取权限" }
            return file
        }
        override val identity: String = Uri.fromFile(file).toString()
        override fun stat() = checked().let { BackupFileStat(it.name, it.length(), it.lastModified(), it.isDirectory) }
        override fun children(): List<BackupNode> = checkNotNull(checked().listFiles()) { "目录无法列出，请检查读取权限" }
            .filter { it.absoluteFile == it.canonicalFile }.map { FileNode(it.canonicalFile, root) }
        override fun open(): InputStream = checked().inputStream()
        override fun delete(): Boolean = checked().delete()
    }

    private inner class DocumentNode(private val treeUri: Uri, private val documentUri: Uri) : BackupNode {
        override val identity = documentUri.toString()
        override fun stat(): BackupFileStat = context.contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "文件已不存在或目录授权失效" }
            BackupFileStat(cursor.getString(1).orEmpty(), if (cursor.isNull(3)) 0 else cursor.getLong(3), if (cursor.isNull(4)) 0 else cursor.getLong(4),
                cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
        } ?: error("文件信息读取失败，请重新授权目录")
        override fun children(): List<BackupNode> {
            val id = DocumentsContract.getDocumentId(documentUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
            return context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        checkCancellation()
                        add(DocumentNode(treeUri, DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))))
                        check(size <= 5000) { "目录文件过多，请拆分备份规则" }
                    }
                }
            } ?: error("目录读取失败，请重新授权目录")
        }
        override fun open(): InputStream = checkNotNull(context.contentResolver.openInputStream(documentUri)) { "文件无法打开，请检查读取权限" }
        override fun delete() = DocumentsContract.deleteDocument(context.contentResolver, documentUri)
    }

    private val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
}

fun hasDeviceFileAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
fun deviceStorageRoots(context: Context): List<DeviceStorageRoot> {
    val roots = mutableListOf(DeviceStorageRoot("内部存储", Environment.getExternalStorageDirectory().canonicalPath))
    if (Build.VERSION.SDK_INT >= 30) {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        manager.storageVolumes.forEach { volume -> volume.directory?.let { roots.add(DeviceStorageRoot(volume.getDescription(context), it.canonicalPath)) } }
    } else context.getExternalFilesDirs(null).filterNotNull().forEach { file ->
        roots.add(DeviceStorageRoot("存储设备", file.canonicalPath.substringBefore("/Android/")))
    }
    return roots.distinctBy { it.path }
}
