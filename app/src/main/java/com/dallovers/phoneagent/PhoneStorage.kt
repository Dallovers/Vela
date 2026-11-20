package com.dallovers.phoneagent

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class PhoneFolder(val volume: String, val path: String)
internal data class PhoneVolume(val id: String, val name: String, val root: File)

internal class PhoneStorage(private val context: Context) {
    fun volumes(): List<PhoneVolume> = context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { volume ->
        val root = volume.directory ?: return@mapNotNull null
        val id = if (volume.isPrimary) "primary" else volume.uuid ?: return@mapNotNull null
        PhoneVolume(id, volume.getDescription(context), root.canonicalFile)
    }

    fun root(folder: PhoneFolder): File {
        check(Environment.isExternalStorageManager()) { "请先允许 Vela 访问手机文件" }
        val volume = volumes().firstOrNull { it.id == folder.volume } ?: error("存储设备当前不可用")
        require(phonePathAllowed(folder.path)) { "这个目录受系统保护" }
        val root = checkedPath(volume.root, folder.path)
        check(root.isDirectory && root.canRead()) { "文件夹已移动、已删除或不可读取" }
        return root
    }
}

internal fun checkedPath(root: File, relative: String): File {
    val base = root.canonicalFile
    if (relative.isEmpty()) return base
    validateRelativePath(relative)
    val candidate = File(base, relative)
    require(candidate.canonicalFile == candidate.absoluteFile) { "不支持指向其他位置的链接" }
    return candidate
}

internal fun validateRelativePath(path: String) {
    require(path.isNotEmpty() && path.length <= 4096 && !path.contains('\\') &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) }) { "无效的相对路径" }
}

internal fun childPath(parent: String, name: String): String {
    require('/' !in name) { "文件名不能包含路径" }
    return (if (parent.isEmpty()) name else "$parent/$name").also(::validateRelativePath)
}

internal fun phonePathAllowed(relative: String): Boolean {
    val parts = relative.split('/')
    return !(parts.size >= 2 && parts[0].equals("Android", true) &&
        (parts[1].equals("data", true) || parts[1].equals("obb", true)))
}
