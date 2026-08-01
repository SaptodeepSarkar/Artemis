package com.example.artemis.feature

import android.content.Context
import android.os.Environment
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val size: Long,
    val mtime: Long,
    val isDir: Boolean
)

/**
 * Files/assets helper (v2.3.0).
 *
 * Whitelisted-root file access for the dashboard: list directories,
 * download files and upload bytes — but ONLY under the app's own storage
 * (filesDir / cacheDir / external app dirs) plus, when the user grants
 * "All files access" (MANAGE_EXTERNAL_STORAGE — a Settings-granted special
 * permission, `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`
 * for sideloaded builds), the whole external storage tree. Anything outside
 * the roots resolves to null and the endpoint answers 403 — full-root is
 * opt-in, not the default.
 *
 * FGS-only: no Activity reference, plain blocking file I/O.
 */
class FileSystemHelper(private val context: Context) {

    /** App-owned roots — always accessible without any permission. */
    private fun appRoots(): List<File> {
        return buildList {
            context.filesDir?.let { add(it) }
            context.cacheDir?.let { add(it) }
            context.getExternalFilesDirs(null).filterNotNull().forEach { add(it) }
        }
    }

    /**
     * Public/external roots — only when the user granted "All files access"
     * (MANAGE_EXTERNAL_STORAGE) or the app runs on API 29 with the legacy
     * READ_EXTERNAL_STORAGE grant.
     */
    private fun externalRoots(): List<File> {
        if (Environment.isExternalStorageManager()) {
            Environment.getExternalStorageDirectory().let { return listOf(it) }
        }
        // API 29 legacy path: READ_EXTERNAL_STORAGE grants raw tree access.
        return if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q &&
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Environment.getExternalStorageDirectory().let { listOf(it) }
        } else {
            emptyList()
        }
    }

    private fun allRoots(): List<File> = appRoots() + externalRoots()

    /** Canonical paths of every allowed root (for the dashboard root picker). */
    fun allowedRoots(): List<String> {
        return allRoots().mapNotNull { root ->
            runCatching { root.canonicalPath }.getOrNull()
        }.distinct()
    }

    /**
     * Resolve a requested path against the whitelist. Returns the canonical
     * file if it lies under an allowed root, null otherwise. Works for
     * paths that do not exist yet (upload targets) as long as the parent
     * chain resolves under a root.
     */
    fun resolve(requested: String): File? {
        if (requested.isBlank()) return null
        val file = File(requested)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val canonicalPath = canonical.path

        for (root in allRoots()) {
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: continue
            if (canonicalPath == rootPath ||
                canonicalPath.startsWith(rootPath + File.separator)
            ) {
                return canonical
            }
        }
        return null
    }

    /** Directory listing (name, size, mtime, isDir), sorted dirs-first. Null
     *  if the path is outside the whitelist or not a readable directory. */
    fun listDirectory(path: String): List<FileEntry>? {
        val dir = resolve(path) ?: return null
        if (!dir.isDirectory) return null
        val entries = dir.listFiles() ?: return emptyList()
        return entries
            .map { f ->
                FileEntry(
                    name = f.name,
                    path = f.absolutePath,
                    size = if (f.isFile) f.length() else 0L,
                    mtime = f.lastModified(),
                    isDir = f.isDirectory
                )
            }
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Resolve a downloadable file (must exist, be a file and be readable). */
    fun resolveFile(path: String): File? {
        val f = resolve(path) ?: return null
        return if (f.isFile && f.canRead()) f else null
    }

    /** Write bytes to an allowed path (creates parent dirs). */
    fun writeFile(path: String, data: ByteArray): Result<File> {
        val target = resolve(path) ?: return Result.failure(
            SecurityException("Path is outside the allowed roots")
        )
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(data)
            target
        }
    }
}
