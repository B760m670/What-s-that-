package com.whatsthat.linux

import java.io.File

/**
 * Host half of GPU acceleration for the container.
 *
 * The container is glibc; the phone's GL driver is a bionic blob in
 * /vendor/lib*. A glibc process can never dlopen it, which is why the desktop
 * has always fallen back to Mesa's llvmpipe and rendered on the CPU.
 *
 * virgl splits the problem in two. Mesa inside the container uses its `virpipe`
 * gallium driver, which does no rendering itself — it serialises GL commands
 * onto a UNIX socket. This class runs the other end of that socket
 * (`virgl_test_server_android`, shipped as a native lib) as a plain bionic
 * process OUTSIDE proot, where the real driver *is* loadable. Commands come in,
 * actual GPU work goes out.
 *
 * The socket lives in the host dir that run-in-ubuntu.sh binds to the guest's
 * /tmp, so both halves see the same path with no extra plumbing.
 *
 * This is best-effort by design: if the server binary is missing for this ABI,
 * or fails to get a GL context on this device, [start] reports it and the
 * desktop carries on under llvmpipe exactly as before.
 */
class GpuBridge(
    private val nativeLibDir: File,
    private val home: File,
) {
    /** Guest-visible path is /tmp/.virgl_test — see run-in-ubuntu.sh's bind. */
    private val socket: File get() = File(File(home, "tmp"), ".virgl_test")

    private val serverBinary: File get() = File(nativeLibDir, "libvirglserver.so")

    @Volatile private var process: Process? = null

    /** True once [start] has a server process that has not exited. */
    val isRunning: Boolean get() = process?.let { it.isAliveCompat() } == true

    /**
     * Launch the server. Returns true if it came up and created its socket.
     * Never throws — a failure here only costs hardware rendering.
     */
    fun start(onLog: (String) -> Unit): Boolean {
        stop()
        if (!serverBinary.exists()) {
            onLog("[gpu] no virgl server for this ABI — using software rendering")
            return false
        }
        // A socket left over from a killed session stops the server binding.
        runCatching { socket.delete() }
        socket.parentFile?.mkdirs()

        return runCatching {
            val pb = ProcessBuilder(
                serverBinary.absolutePath,
                "--no-fork",        // stay in the foreground so we can supervise it
                "--multi-clients",  // the desktop plus every GL app it launches
                "--socket-path", socket.absolutePath,
            ).redirectErrorStream(true)

            pb.environment().apply {
                // Both bundled libs carry a RUNPATH into /data/data/com.termux,
                // which does not exist here; point the linker at our own dir.
                put("LD_LIBRARY_PATH", nativeLibDir.absolutePath)
                // Android has no desktop GL and no window for the server to draw
                // into — render through GLES on a surfaceless EGL context.
                put("VTEST_USE_GLES", "1")
                put("VTEST_USE_EGL_SURFACELESS", "1")
            }

            val p = pb.start()
            process = p
            // Drain the server's output into the app log; a full pipe would
            // otherwise wedge it once it has said enough.
            Thread { runCatching { p.inputStream.bufferedReader().forEachLine { onLog("[gpu] $it") } } }
                .apply { isDaemon = true; name = "virgl-log"; start() }

            if (awaitSocket()) {
                onLog("[gpu] virgl server ready — hardware rendering enabled")
                true
            } else {
                onLog("[gpu] virgl server did not start — using software rendering")
                stop()
                false
            }
        }.getOrElse {
            onLog("[gpu] virgl server failed: ${it.message} — using software rendering")
            stop()
            false
        }
    }

    fun stop() {
        process?.let { runCatching { it.destroy() } }
        process = null
        runCatching { socket.delete() }
    }

    /**
     * The server binds its socket a moment after exec. Poll briefly rather than
     * sleeping a fixed amount, and bail early if the process has already died
     * (a missing GL driver shows up that way).
     */
    private fun awaitSocket(): Boolean {
        val deadline = System.currentTimeMillis() + SOCKET_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists()) return true
            val p = process ?: return false
            if (!p.isAliveCompat()) return false
            try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
        }
        return socket.exists()
    }

    /** Process.isAlive() is API 26+; this app supports back to 21. */
    private fun Process.isAliveCompat(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 5000L
    }
}
