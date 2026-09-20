package com.dtfa.terminal

/**
 * Thin wrapper around the native `dtfapty` library (see src/main/cpp/pty.c).
 * Creates a real Linux pty and fork/execs a command inside it.
 */
object PtyNative {
    init {
        System.loadLibrary("dtfapty")
    }

    /**
     * @return the pty master file descriptor (>= 0) or -1 on failure.
     * The spawned process's pid is written into pidOut[0].
     */
    external fun createSubprocess(
        cmd: String,
        cwd: String?,
        args: Array<String>,
        env: Array<String>,
        pidOut: IntArray,
        rows: Int,
        cols: Int
    ): Int

    external fun setWindowSize(fd: Int, rows: Int, cols: Int)
    external fun waitFor(pid: Int): Int
    external fun closeFd(fd: Int)
    external fun sendSignal(pid: Int, signal: Int)

    /** Returns 0 if this process can ptrace its own children, else the errno (EPERM=1 = blocked). */
    external fun testPtrace(): Int
}
