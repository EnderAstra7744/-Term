package com.dtfa.terminal

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Owns one running "proot -> bash inside the Debian rootfs" process, connected
 * through a real pty. Call [start] once, feed user keystrokes with [write],
 * and receive raw terminal bytes via the [onOutput] callback (call it on the
 * UI thread yourself if you need to touch views).
 */
class TerminalSession(
    private val context: Context,
    private val rootfsDir: File,
    private val onOutput: (ByteArray, Int) -> Unit,
    private val onFinished: (exitCode: Int) -> Unit
) {
    private var masterFd: Int = -1
    private var pid: Int = -1
    private var input: FileOutputStream? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false

    private fun prootPath(): String = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    /**
     * Prints a one-line sanity check before we even try to exec anything, so if
     * proot fails silently (common: it prints nothing and just exits) we still
     * know *why* from the terminal itself instead of just seeing a bare exit code.
     */
    private fun preflightCheck(prootCmd: String) {
        val prootFile = File(prootCmd)
        val diag = buildString {
            append("[dtfa] proot: ").append(prootCmd).append('\n')
            append("[dtfa]   exists=").append(prootFile.exists())
            append(" canExecute=").append(prootFile.canExecute())
            append(" size=").append(if (prootFile.exists()) prootFile.length() else -1).append('\n')
            append("[dtfa] rootfs: ").append(rootfsDir.absolutePath)
            append(" bashExists=").append(File(rootfsDir, "bin/bash").exists())
            append('\n')
        }
        onOutput(diag.toByteArray(Charsets.UTF_8), diag.toByteArray(Charsets.UTF_8).size)
    }

    fun start(rows: Int, cols: Int) {
        val tmpDir = File(context.filesDir, "proot-tmp").apply { mkdirs() }
        File(rootfsDir, "root").mkdirs()

        val cmd = prootPath()
        preflightCheck(cmd)

        val args = arrayOf(
            "-r", rootfsDir.absolutePath,
            "-0",
            "--link2symlink",
            "--kill-on-exit",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${rootfsDir.absolutePath}/tmp:/dev/shm",
            "-w", "/root",
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash",
            "--login"
        )
        val env = arrayOf(
            "PROOT_TMP_DIR=${tmpDir.absolutePath}",
            "HOME=/root",
            "TERM=xterm-256color"
        )

        val pidOut = IntArray(1)
        val fd = PtyNative.createSubprocess(cmd, rootfsDir.absolutePath, args, env, pidOut, rows, cols)
        if (fd < 0) {
            onFinished(-1)
            return
        }
        masterFd = fd
        pid = pidOut[0]

        val pfd = ParcelFileDescriptor.adoptFd(fd)
        input = FileOutputStream(pfd.fileDescriptor)
        val output = FileInputStream(pfd.fileDescriptor)

        running = true
        readerThread = Thread({
            val buf = ByteArray(8192)
            try {
                while (running) {
                    val n = output.read(buf)
                    if (n < 0) break
                    if (n > 0) onOutput(buf, n)
                }
            } catch (e: IOException) {
                // Session ended (pty closed) — normal on exit.
            } finally {
                running = false
                val code = if (pid > 0) PtyNative.waitFor(pid) else -1
                onFinished(code)
            }
        }, "dtfa-pty-reader").apply { isDaemon = true; start() }
    }

    fun write(data: ByteArray) {
        try {
            input?.write(data)
            input?.flush()
        } catch (e: IOException) {
            // Session likely already dead; ignore.
        }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun resize(rows: Int, cols: Int) {
        if (masterFd >= 0) PtyNative.setWindowSize(masterFd, rows, cols)
    }

    /** Sends SIGINT (Ctrl+C) to the foreground process group's leader. */
    fun sendInterrupt() {
        if (pid > 0) PtyNative.sendSignal(pid, 2 /* SIGINT */)
    }

    fun destroy() {
        running = false
        if (pid > 0) PtyNative.sendSignal(pid, 9 /* SIGKILL */)
        runCatching { input?.close() }
    }
}
