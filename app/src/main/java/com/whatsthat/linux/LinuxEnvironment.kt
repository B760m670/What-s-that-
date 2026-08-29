package com.whatsthat.linux

import android.content.Context
import android.os.Build
import java.io.File

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
    // --- desktops --------------------------------------------------------------
    //
    // Which desktop and session type to use is remembered PER DISTRO, not once
    // for the app: Ubuntu may be running GNOME while Debian stays on XFCE, and
    // one global setting would silently change the other system every time the
    // user switched distro.

    fun desktopFor(d: Distro): DesktopEnv =
        DesktopEnv.byId(prefs.getString("de:${d.id}", DesktopEnv.XFCE.id))

    fun setDesktop(d: Distro, de: DesktopEnv) {
        prefs.edit().putString("de:${d.id}", de.id).apply()
    }

    fun sessionTypeFor(d: Distro): SessionType =
        SessionType.byId(prefs.getString("session:${d.id}", SessionType.X11.id))

    fun setSessionType(d: Distro, t: SessionType) {
        prefs.edit().putString("session:${d.id}", t.id).apply()
    }

    /**
     * Whether [de] is present in [d]'s rootfs. Answered from the filesystem, not
     * from a preference: a preference records what the user picked, which may be
     * a desktop this particular system has never had installed.
     */
    fun desktopInstalled(d: Distro, de: DesktopEnv) =
        File(distroDir(d), de.probeBinary).exists()

    fun installedDesktops(d: Distro): List<DesktopEnv> =
        DesktopEnv.all.filter { desktopInstalled(d, it) }

    // Ready if ANY desktop was installed on-device, or the distro is one of our
    // pre-built images that bakes the marker (the Wine one).
    fun desktopReady(d: Distro) =
        installedDesktops(d).isNotEmpty() ||
        File(distroDir(d), "root/.wt-desktop-ready").exists()

    /**
     * PRETTY_NAME out of the installed rootfs, e.g. "Debian GNU/Linux 13
     * (trixie)". Shown instead of a version baked into [Distros]: the constant
     * says what a *fresh* install would download, while an existing rootfs is
     * whatever it was when it was installed, and only the file knows which.
     */
    fun installedRelease(d: Distro): String? {
        val f = File(distroDir(d), "etc/os-release")
        if (!f.exists()) return null
        return runCatching {
            f.useLines { lines ->
                lines.firstOrNull { it.startsWith("PRETTY_NAME=") }
                    ?.substringAfter('=')?.trim('"', ' ')
            }
        }.getOrNull()
    }

    /**
     * What the desktop session itself printed. start-desktop.sh redirects the
     * session's output to a fixed path in the /tmp we bind, so this is readable
     * straight off the app's own filesystem — no container needed, and no
     * guessing at TigerVNC's log filename, which is how the last attempt at
     * GNOME ended up with no diagnosis at all.
     */
    fun sessionLog(maxLines: Int = 200): List<String> {
        val f = File(home, "tmp/wt-session.log")
        if (!f.exists()) return emptyList()
        return runCatching { f.readLines().takeLast(maxLines) }.getOrDefault(emptyList())
    }
    fun removeDistro(d: Distro) {
        distroDir(d).deleteRecursively()
        invalidateDiskUsage(d)   // or the list would keep showing the size it no longer occupies
    }

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

    /** CPU arch in the naming the bootstrap script expects. */
    val arch: String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        else -> Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    }

    /** State of the currently active distro (drives the main button). */
    val isBootstrapped: Boolean get() = rootfsReady(activeDistro)

    /**
     * Whether the desktop the user has CHOSEN for the active distro is present.
     * Deliberately not "any desktop": picking GNOME on a system that only has
     * XFCE has to offer to install GNOME, not silently launch XFCE.
     */
    val isDesktopInstalled: Boolean
        get() = activeDistro.let { d ->
            desktopInstalled(d, desktopFor(d)) || File(distroDir(d), "root/.wt-desktop-ready").exists()
        }

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
    fun bootstrap(onLog: (String) -> Unit, onProgress: (Progress) -> Unit = {}): Int =
        bootstrapDistro(activeDistro, onLog, onProgress)

    fun bootstrapDistro(d: Distro, onLog: (String) -> Unit, onProgress: (Progress) -> Unit = {}): Int =
        RootfsInstaller(
            home = home, rootfs = distroDir(d), arch = arch, distro = d,
            onLog = onLog, onProgress = onProgress,
        ).install()

    /**
     * Install the desktop inside the active distro's container.
     *
     * The script announces each of its phases as `[step] n/total <label>`; those
     * lines are turned into [Progress] so the wait has a bar rather than a
     * spinner, and are still logged so the console keeps the full record.
     */
    fun installDesktop(onLog: (String) -> Unit, onProgress: (Progress) -> Unit = {}): Int =
        execScript(
            "install-desktop.sh", insideContainer = true,
            extraEnv = desktopEnvVars(),
            onLog = { line ->
                parseStep(line)?.let(onProgress)
                onLog(line)
            },
        )

    /**
     * The variables both container scripts need to know what to install and
     * what to start. They must also be listed in run-in-ubuntu.sh's `env -i`
     * whitelist, which is the one place a setting can vanish without a trace.
     */
    private fun desktopEnvVars(): Map<String, String> = activeDistro.let { d ->
        mapOf(
            "WT_PROFILE" to BuildConfig.DESKTOP_PROFILE,
            "WT_PKG" to d.pkg.name.lowercase(),
            "WT_DISTRO" to d.id,
            "WT_DE" to desktopFor(d).id,
            "WT_SESSION_TYPE" to sessionTypeFor(d).id,
        )
    }

    private fun parseStep(line: String): Progress? {
        val m = STEP_MARKER.find(line.trim()) ?: return null
        val (done, total, label) = m.destructured
        // Reported as "step n finished" — the bar should show the work done, and
        // a step that has only just started has not contributed any yet.
        return Progress(
            phase = Progress.Phase.DESKTOP,
            done = done.toLong() - 1,
            total = total.toLong(),
            label = label.trim(),
        )
    }

    /**
     * Start the desktop session and return the loopback "host:port" to connect
     * to, or null on failure. The VNC server is a long-lived process, so we must
     * NOT wait for it to exit — we read its output only until it announces
     * VNC_READY, then leave it running in the background (a daemon thread keeps
     * draining its output so it never blocks on a full pipe).
     */
    fun startDesktop(geometry: String, onLog: (String) -> Unit): String? {
        stopDesktop()
        val pb = ProcessBuilder(containerCommand("start-desktop.sh")).redirectErrorStream(true)
        configureEnv(pb, desktopEnvVars() + mapOf("WT_GEOMETRY" to geometry))
        val process = pb.start()
        desktopProcess = process
        val reader = process.inputStream.bufferedReader()

        var endpoint: String? = null
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break          // EOF = process exited early
            onLog(line)
            val ep = line.substringAfter("VNC_READY ", "").trim()
            if (ep.isNotEmpty()) { endpoint = ep; break }
        }

        if (endpoint != null) {
            // Keep the session alive; drain its remaining output off-thread.
            Thread { runCatching { reader.forEachLine(onLog) } }
                .apply { isDaemon = true; start() }
        } else {
            stopDesktop()
        }
        return endpoint
    }

    /** Stop a running desktop/VNC session, if any. */
    fun stopDesktop() {
        desktopProcess?.let { runCatching { it.destroy() } }
        desktopProcess = null
    }

    // --- state the UI needs --------------------------------------------------

    /**
     * Whether a desktop session is live right now.
     *
     * The launch process is not the session: `vncserver` daemonises, so our
     * handle can be long gone while the desktop is still up. Ask the port
     * instead — if something accepts on the VNC socket, there is a session.
     * Cheap enough to poll on resume, but it does touch the network stack, so
     * callers keep it off the main thread.
     */
    fun isSessionRunning(): Boolean = runCatching {
        java.net.Socket().use {
            it.connect(java.net.InetSocketAddress("127.0.0.1", VNC_PORT), 250)
            true
        }
    }.getOrDefault(false)

    /** Loopback endpoint of a running session, for reconnecting to it. */
    val sessionEndpoint: String get() = "127.0.0.1:$VNC_PORT"

    /**
     * Bytes on disk for a distro's rootfs.
     *
     * Walks the tree, which for a populated rootfs is tens of thousands of
     * files and takes real time — never call this from the main thread. Results
     * are cached per distro and invalidated when that distro changes, because
     * the figure is stable between installs and re-walking on every screen
     * redraw would make the list stutter.
     */
    fun diskUsage(d: Distro): Long {
        diskCache[d.id]?.let { return it }
        val bytes = runCatching { distroDir(d).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            .getOrDefault(0L)
        diskCache[d.id] = bytes
        return bytes
    }

    fun invalidateDiskUsage(d: Distro? = null) {
        if (d == null) diskCache.clear() else diskCache.remove(d.id)
    }

    private val diskCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Total occupied by every installed distro — the app's real footprint. */
    fun totalDiskUsage(): Long = Distros.all.filter { rootfsReady(it) }.sumOf { diskUsage(it) }

    /** Free space on the volume the rootfs lives on, so the UI can warn before a download. */
    fun freeSpace(): Long = runCatching { android.os.StatFs(home.path).availableBytes }.getOrDefault(0L)

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

    companion object {
        /** start-desktop.sh puts the session on display :1, i.e. 5900 + 1. */
        const val VNC_PORT = 5901

        /** Human-readable byte size — the UI shows several of these. */
        /** `[step] 3/7 Installing the XFCE desktop` — emitted by install-desktop.sh. */
        private val STEP_MARKER = Regex("""^\[step] (\d+)/(\d+) (.*)$""")

        fun formatBytes(bytes: Long): String = when {
            bytes <= 0 -> "—"
            bytes >= 1L shl 30 -> String.format("%.1f GB", bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> String.format("%.0f MB", bytes.toDouble() / (1L shl 20))
            else -> String.format("%.0f KB", bytes.toDouble() / 1024)
        }
    }
}
