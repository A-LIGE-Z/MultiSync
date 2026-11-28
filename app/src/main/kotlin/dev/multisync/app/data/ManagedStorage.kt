package dev.multisync.app.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Central working folder under the shared Download directory, e.g.
 * /storage/emulated/0/Download/MultiSync.
 *
 * It is created as soon as all-files access is granted and is used to:
 *  - save APKs downloaded from the remote OpenList,
 *  - hold APKs extracted from installed apps locally (for publishing/upload),
 *  - keep downloaded files.
 * Writing here needs MANAGE_EXTERNAL_STORAGE ("所有文件访问"), so [ensure] returns null until granted.
 */
object ManagedStorage {
    const val FOLDER_NAME = "MultiSync"

    /** /storage/emulated/0/Download/MultiSync (or the equivalent public downloads path). */
    @Suppress("DEPRECATION")
    fun root(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)

    /** Creates the folder tree when access is granted; returns null when it is not. */
    fun ensure(context: Context): File? {
        if (!hasDeviceFileAccess(context)) return null
        val root = root()
        if (!root.isDirectory && !root.mkdirs()) return null
        return root
    }

    /** Folder for downloaded + extracted APKs. */
    fun apkDir(context: Context): File? = ensure(context)?.let { dir(it, "apk") }

    /** Folder for downloaded / archived files. */
    fun filesDir(context: Context): File? = ensure(context)?.let { dir(it, "files") }

    private fun dir(parent: File, name: String): File = File(parent, name).apply { if (!isDirectory) mkdirs() }

    /** Human-friendly path for messages/UI, e.g. "Download/MultiSync/apk". */
    fun label(file: File): String {
        val root = root()
        return if (file.path.startsWith(root.path)) "Download/$FOLDER_NAME" + file.path.removePrefix(root.path).replace(File.separatorChar, '/')
        else file.path
    }
}
