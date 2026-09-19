package com.dtfa.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Extracts the bundled Debian rootfs (app/src/main/assets/rootfs/debian-rootfs-arm64.tar.xz)
 * into the app's private storage on first run. Everything after that just reuses it.
 */
class RootfsInstaller(private val context: Context) {

    companion object {
        private const val TAG = "DTFA-rootfs"
        private const val ASSET_PATH = "rootfs/debian-rootfs-arm64.tar.xz"
        private const val MARKER_NAME = ".dtfa_installed"

        // Bump this whenever extraction logic changes in a way that could fix/break
        // an existing install — it invalidates any rootfs extracted by an older
        // version of this class, forcing a clean re-extraction after an app update.
        private const val INSTALL_VERSION = 2
    }

    val rootfsDir: File get() = File(context.filesDir, "debian")
    private val markerFile: File get() = File(rootfsDir, MARKER_NAME)

    var lastSymlinkFailures: Int = 0
        private set

    fun isInstalled(): Boolean =
        runCatching { markerFile.readText().trim() }.getOrNull() == INSTALL_VERSION.toString()

    fun interface ProgressListener {
        fun onProgress(entriesExtracted: Int, currentPath: String)
    }

    /**
     * Blocking call — run this off the main thread.
     */
    fun install(listener: ProgressListener? = null) {
        if (isInstalled()) return

        if (rootfsDir.exists()) deleteRecursivelySafe(rootfsDir)
        rootfsDir.mkdirs()

        var count = 0
        var symlinkFailures = 0
        context.assets.open(ASSET_PATH).use { rawIn ->
            XZCompressorInputStream(BufferedInputStream(rawIn)).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextTarEntry
                    while (entry != null) {
                        if (!extractEntry(tarIn, entry)) symlinkFailures++
                        count++
                        if (count % 25 == 0) listener?.onProgress(count, entry.name)
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
        lastSymlinkFailures = symlinkFailures
        if (symlinkFailures > 0) Log.w(TAG, "$symlinkFailures symlink(s) failed to extract")

        // Make sure standard bind-mount targets exist even if the tar didn't include
        // empty directories for them.
        for (name in listOf("proc", "sys", "dev", "dev/pts", "tmp", "data")) {
            File(rootfsDir, name).mkdirs()
        }

        markerFile.writeText(INSTALL_VERSION.toString())
        listener?.onProgress(count, "done")
    }

    /**
     * A rootfs-safe recursive delete: never follows symlinks (Debian's merged-/usr layout is
     * full of them, e.g. /lib -> usr/lib), so it can't loop forever or wander outside the
     * directory. Kotlin's own `File.deleteRecursively()` walks through symlink targets in a
     * way that occasionally throws "rootDir must be verified to be directory beforehand" on
     * exactly this kind of layout — this avoids that entirely.
     */
    private fun deleteRecursivelySafe(file: File) {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            file.listFiles()?.forEach { deleteRecursivelySafe(it) }
        }
        runCatching { Files.delete(path) }
    }

    private fun extractEntry(tarIn: TarArchiveInputStream, entry: TarArchiveEntry): Boolean {
        val outFile = File(rootfsDir, entry.name)
        // Guard against path traversal in a hostile tar.
        if (!outFile.canonicalPath.startsWith(rootfsDir.canonicalPath)) return true

        when {
            entry.isDirectory -> outFile.mkdirs()

            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                outFile.delete()
                val result = runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                if (result.isFailure) {
                    Log.w(TAG, "symlink ${entry.name} -> ${entry.linkName} failed: ${result.exceptionOrNull()}")
                    return false
                }
            }

            entry.isLink -> {
                // Hard link inside the archive: fall back to a copy of the target if present.
                outFile.parentFile?.mkdirs()
                val target = File(rootfsDir, entry.linkName)
                if (target.exists()) target.copyTo(outFile, overwrite = true)
            }

            else -> {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out -> tarIn.copyTo(out) }
                applyMode(outFile, entry.mode)
            }
        }
        return true
    }

    private fun applyMode(file: File, mode: Int) {
        runCatching {
            val ownerExec = (mode and 0b001000000) != 0
            val groupExec = (mode and 0b000001000) != 0
            val otherExec = (mode and 0b000000001) != 0
            if (ownerExec || groupExec || otherExec) {
                file.setExecutable(true, false)
            }
            file.setReadable(true, false)
            file.setWritable(true, (mode and 0b000000010) == 0)
        }
    }
}
