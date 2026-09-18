package com.dtfa.terminal

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts the bundled Debian rootfs (app/src/main/assets/rootfs/debian-rootfs-arm64.tar.xz)
 * into the app's private storage on first run. Everything after that just reuses it.
 */
class RootfsInstaller(private val context: Context) {

    companion object {
        private const val ASSET_PATH = "rootfs/debian-rootfs-arm64.tar.xz"
        private const val MARKER_NAME = ".dtfa_installed"
    }

    val rootfsDir: File get() = File(context.filesDir, "debian")
    private val markerFile: File get() = File(rootfsDir, MARKER_NAME)

    fun isInstalled(): Boolean = markerFile.exists()

    fun interface ProgressListener {
        fun onProgress(entriesExtracted: Int, currentPath: String)
    }

    /**
     * Blocking call — run this off the main thread.
     */
    fun install(listener: ProgressListener? = null) {
        if (isInstalled()) return

        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()

        var count = 0
        context.assets.open(ASSET_PATH).use { rawIn ->
            XZCompressorInputStream(BufferedInputStream(rawIn)).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextTarEntry
                    while (entry != null) {
                        extractEntry(tarIn, entry)
                        count++
                        if (count % 25 == 0) listener?.onProgress(count, entry.name)
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }

        // Make sure standard bind-mount targets exist even if the tar didn't include
        // empty directories for them.
        for (name in listOf("proc", "sys", "dev", "dev/pts", "tmp", "data")) {
            File(rootfsDir, name).mkdirs()
        }

        markerFile.writeText(System.currentTimeMillis().toString())
        listener?.onProgress(count, "done")
    }

    private fun extractEntry(tarIn: TarArchiveInputStream, entry: TarArchiveEntry) {
        val outFile = File(rootfsDir, entry.name)
        // Guard against path traversal in a hostile tar.
        if (!outFile.canonicalPath.startsWith(rootfsDir.canonicalPath)) return

        when {
            entry.isDirectory -> outFile.mkdirs()

            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                outFile.delete()
                runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
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
