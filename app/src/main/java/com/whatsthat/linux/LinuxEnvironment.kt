package com.whatsthat.linux

import android.content.Context
import android.os.Build
import java.io.File

/** How a started desktop can be reached, by backend. */
sealed interface DesktopLaunch {
    data class Vnc(val host: String, val port: Int) : DesktopLaunch
    data class Framebuffer(val fbPath: String, val xSocketPath: String) : DesktopLaunch
}

/**
 * The engine. Owns the on-device layout of the embedded Linux and exposes the
 * three operations the UI needs: prepare assets, bootstrap Ubuntu, install the
 * desktop, and launch it. Everything funnels through [exec] so process setup
 * (env, working dir, proot path) lives in one place.
 */
class LinuxEnvironment(context: Context) {

    private val appContext = context.applicationContext
    val home: File = appContext.filesDir
    private val scriptsDir = File(home, "scripts")
    private val distrosDir = File(home, "distros")
    private val prefs = appContext.getSharedPreferences("wt", Context.MODE_PRIVATE)

    init { migrateLegacyUbuntu() }

    /** Per-distro rootfs directory. */
    private fun distroDir(d: Distro) = File(distrosDir, d.id)
    private val rootfs: File get() = distroDir(activeDistro)

    val allDistros: List<Distro> get() = Distros.all
    var activeDistro: Distro
        get() = Distros.byId(prefs.getString("active_distro", "ubuntu") ?: "ubuntu")
        set(v) { prefs.edit().putString("active_distro", v.id).apply() }

    // Treat a distro as installed only if the rootfs is actually there — a
    // leftover .bootstrap-done marker with the files gone (a corrupted/half
    // removed install) must not be trusted, or proot fails with "/usr/bin/env
    // not found". Re-checking a real file makes the app reinstall cleanly.
    fun rootfsReady(d: Distro) =
        File(distroDir(d), ".bootstrap-done").exists() && File(distroDir(d), "usr/bin/env").exists()
    // Desktop is ready if XFCE was installed on-device (Ubuntu/Debian) OR the
    // distro is one of our pre-built images that bakes the marker (the Wine one).
    fun desktopReady(d: Distro) =
        File(distroDir(d), "usr/bin/startxfce4").exists() ||
        File(distroDir(d), "root/.wt-desktop-ready").exists()
    fun removeDistro(d: Distro) { distroDir(d).deleteRecursively() }

    /** Move a v1 install (filesDir/ubuntu) into the per-distro layout, once. */
    private fun migrateLegacyUbuntu() {
        val legacy = File(home, "ubuntu")
        val target = File(distrosDir, "ubuntu")
        if (legacy.exists() && !target.exists()) {
            distrosDir.mkdirs()
            legacy.renameTo(target)
        }
    }

    /** Where Android extracted our native libs (proot, loader, libtalloc). */
    private val nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir)

    /** A writable dir holding the soname symlink the dynamic linker expects. */
    private val libDir = File(home, "lib")

    /** proot is shipped as a native lib so Android marks it executable on install. */
    private val prootBinary: File
        get() = File(nativeLibDir, "libproot.so")

    /** Host-side GL server the container's Mesa forwards to. See [GpuBridge]. */
    private val gpuBridge = GpuBridge(nativeLibDir = nativeLibDir, home = home)

    /** CPU arch in the naming the bootstrap script expects. */
    val arch: String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        else -> Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    }

    /** State of the currently active distro (drives the main button). */
    val isBootstrapped: Boolean get() = rootfsReady(activeDistro)
    val isDesktopInstalled: Boolean get() = desktopReady(activeDistro)

    /** Copy the bundled shell scripts out of the APK into a writable, exec dir. */
    fun prepareScripts() {
        scriptsDir.mkdirs()
        File(home, "tmp").mkdirs()
        val names = appContext.assets.list("scripts").orEmpty()
        for (name in names) {
            val target = File(scriptsDir, name)
            appContext.assets.open("scripts/$name").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            target.setExecutable(true, false)
        }
        linkProotDeps()
    }

    /**
     * proot's ELF asks for `libtalloc.so.2` by soname, but Android only extracts
     * native libs named `lib*.so` (we ship it as `libtalloc.so`). Create a
     * `libtalloc.so.2` symlink in a writable dir that we put on LD_LIBRARY_PATH.
     */
    private fun linkProotDeps() {
        libDir.mkdirs()
        val link = File(libDir, "libtalloc.so.2")
        val realLib = File(nativeLibDir, "libtalloc.so")
        runCatching {
            link.delete()   // removes a stale symlink (the link, not its target)
            android.system.Os.symlink(realLib.absolutePath, link.absolutePath)
        }
    }

    /**
     * Download + verify + extract the active distro's rootfs, in-process (the
     * Android sandbox has no curl/tar/xz), see [RootfsInstaller].
     */
    fun bootstrap(onLog: (String) -> Unit): Int = bootstrapDistro(activeDistro, onLog)

    fun bootstrapDistro(d: Distro, onLog: (String) -> Unit): Int =
        RootfsInstaller(home = home, rootfs = distroDir(d), arch = arch, distro = d, onLog = onLog).install()

    /** Install the desktop inside the active distro's container. */
    fun installDesktop(onLog: (String) -> Unit): Int =
        execScript(
            "install-desktop.sh", insideContainer = true,
            extraEnv = mapOf(
                "WT_PROFILE" to BuildConfig.DESKTOP_PROFILE,
                "WT_PKG" to activeDistro.pkg.name.lowercase(),
                "WT_DISTRO" to activeDistro.id,
            ),
            onLog = onLog,
        )

    /** Which display backend the next launch uses. Default is the proven VNC. */
    var displayBackend: String
        get() = prefs.getString("display_backend", "vnc") ?: "vnc"
        set(v) { prefs.edit().putString("display_backend", v).apply() }

    /**
     * Start the XFCE session and return how to connect to it, or null on failure.
     * The display server is long-lived, so we read the launcher's output only
     * until it announces readiness, then leave it running (a daemon thread drains
     * the rest so it never blocks on a full pipe).
     *
     * Two backends, chosen by [displayBackend]:
     *   VNC — an RFB endpoint (host:port).
     *   Framebuffer — the mmap'd Xvfb file plus the X socket for XTEST input.
     * The script prints guest paths; we map the guest's /tmp to the host dir it
     * is bound from so the app can reach them.
     */
    fun startDesktop(geometry: String, onLog: (String) -> Unit): DesktopLaunch? {
        stopDesktop()
        // Bring the GL server up first: Mesa in the container picks its driver
        // when the X session starts, so arriving late means a software session
        // for the rest of its life. A failure here is not fatal — WT_GPU=off
        // just leaves the guest on llvmpipe.
        val gpuReady = gpuBridge.start(onLog)
        val pb = ProcessBuilder(containerCommand("start-desktop.sh")).redirectErrorStream(true)
        configureEnv(pb, mapOf(
            "WT_GEOMETRY" to geometry,
            "WT_GPU" to if (gpuReady) "virpipe" else "off",
            "WT_DISPLAY_BACKEND" to displayBackend,
        ))
        val process = pb.start()
        desktopProcess = process
        val reader = process.inputStream.bufferedReader()

        var launch: DesktopLaunch? = null
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break          // EOF = process exited early
            onLog(line)
            line.substringAfter("VNC_READY ", "").trim().takeIf { it.isNotEmpty() }?.let {
                val parts = it.split(":")
                launch = DesktopLaunch.Vnc(parts.getOrElse(0) { "127.0.0.1" }, parts.getOrNull(1)?.toIntOrNull() ?: 5901)
            }
            line.substringAfter("FB_READY ", "").trim().takeIf { it.isNotEmpty() }?.let {
                val parts = it.split(" ")
                if (parts.size >= 2) {
                    launch = DesktopLaunch.Framebuffer(guestToHost(parts[0]), guestToHost(parts[1]))
                }
            }
            if (launch != null) break
        }

        if (launch != null) {
            // Keep the session alive; drain its remaining output off-thread.
            Thread { runCatching { reader.forEachLine(onLog) } }
                .apply { isDaemon = true; start() }
        } else {
            stopDesktop()
        }
        return launch
    }

    /** Map a guest path under /tmp to the host dir bound there by run-in-ubuntu.sh. */
    private fun guestToHost(guestPath: String): String =
        if (guestPath.startsWith("/tmp/")) File(File(home, "tmp"), guestPath.removePrefix("/tmp/")).absolutePath
        else guestPath

    /** Stop a running desktop/VNC session, if any. */
    fun stopDesktop() {
        desktopProcess?.let { runCatching { it.destroy() } }
        desktopProcess = null
        // The GL server is only useful to a live session, and holding its socket
        // open would block the next one from binding.
        gpuBridge.stop()
    }

    /** End the running session: kill the VNC server inside the container (it
     *  daemonizes, so destroying our launch process isn't enough), then drop
     *  our handle. Safe to call when nothing is running. */
    fun killSession(onLog: (String) -> Unit) {
        runCatching {
            if (rootfsReady(activeDistro)) {
                execScript("kill-session.sh", insideContainer = true, onLog = onLog)
            }
        }
        stopDesktop()
    }

    /**
     * Run an arbitrary command inside the Ubuntu container and stream its
     * combined stdout+stderr to [onLog]. This backs the in-app console: error
     * output from the command shows up in the log just like normal output.
     * Returns the command's exit code (non-zero is surfaced to the user).
     */
    fun runCommand(command: String, onLog: (String) -> Unit): Int {
        if (!isBootstrapped) {
            onLog("error: Ubuntu is not installed yet — install it first.")
            return 1
        }
        val cmd = listOf(
            "/system/bin/sh", File(scriptsDir, "run-in-ubuntu.sh").absolutePath,
            "/bin/bash", "-lc", command,
        )
        return exec(cmd, emptyMap(), onLog)
    }

    // --- internals -----------------------------------------------------------

    /** The running desktop/VNC session process (long-lived), if started. */
    private var desktopProcess: Process? = null

    /** Build the command that pipes a bundled script through proot into bash. */
    private fun containerCommand(scriptName: String): List<String> {
        val script = File(scriptsDir, scriptName)
        return listOf(
            "/system/bin/sh", File(scriptsDir, "run-in-ubuntu.sh").absolutePath, "/bin/bash", "-c",
            "cat << 'WT_EOF' | bash\n${script.readText()}\nWT_EOF",
        )
    }

    private fun configureEnv(pb: ProcessBuilder, extraEnv: Map<String, String>) {
        pb.environment().apply {
            put("WT_HOME", home.absolutePath)
            put("WT_ARCH", arch)
            put("WT_ROOTFS", rootfs.absolutePath)
            put("PROOT", prootBinary.absolutePath)
            put("PROOT_TMP_DIR", File(home, "tmp").absolutePath)
            put("WT_NATIVE_LIB", nativeLibDir.absolutePath)
            put("WT_LIBDIR", libDir.absolutePath)
            put("HOME", home.absolutePath)
            put("PATH", "${scriptsDir.absolutePath}:/system/bin:/system/xbin")
            putAll(extraEnv)
        }
    }

    private fun execScript(
        name: String,
        insideContainer: Boolean,
        extraEnv: Map<String, String> = emptyMap(),
        onLog: (String) -> Unit,
    ): Int {
        val command: List<String> = if (insideContainer) {
            containerCommand(name)
        } else {
            listOf("/system/bin/sh", File(scriptsDir, name).absolutePath)
        }
        return exec(command, extraEnv, onLog)
    }

    private fun exec(command: List<String>, extraEnv: Map<String, String>, onLog: (String) -> Unit): Int {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        configureEnv(pb, extraEnv)
        val process = pb.start()
        process.inputStream.bufferedReader().forEachLine(onLog)
        return process.waitFor()
    }
}
