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
    private val rootfs = File(home, "ubuntu")

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

    val isBootstrapped: Boolean get() = File(rootfs, ".bootstrap-done").exists()
    val isDesktopInstalled: Boolean get() = File(rootfs, "usr/bin/startxfce4").exists()

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
     * Download + verify + extract the Ubuntu rootfs. Done entirely in-process
     * (the Android sandbox has no curl/tar/xz), see [RootfsInstaller].
     */
    fun bootstrap(onLog: (String) -> Unit): Int =
        RootfsInstaller(
            home = home,
            rootfs = rootfs,
            arch = arch,
            variant = BuildConfig.UBUNTU_VARIANT,
            onLog = onLog,
        ).install()

    /** Install the desktop inside the container, per the build's profile. */
    fun installDesktop(onLog: (String) -> Unit): Int =
        execScript(
            "install-desktop.sh", insideContainer = true,
            extraEnv = mapOf("WT_PROFILE" to BuildConfig.DESKTOP_PROFILE),
            onLog = onLog,
        )

    /**
     * Start the XFCE/VNC session. Returns the loopback "host:port" the viewer
     * should connect to, or null on failure.
     */
    fun startDesktop(geometry: String, onLog: (String) -> Unit): String? {
        var endpoint: String? = null
        execScript("start-desktop.sh", insideContainer = true, extraEnv = mapOf("WT_GEOMETRY" to geometry)) { line ->
            onLog(line)
            line.substringAfter("VNC_READY ", "").trim().takeIf { it.isNotEmpty() }?.let { endpoint = it }
        }
        return endpoint
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

    private fun execScript(
        name: String,
        insideContainer: Boolean,
        extraEnv: Map<String, String> = emptyMap(),
        onLog: (String) -> Unit,
    ): Int {
        val script = File(scriptsDir, name).absolutePath
        val command: List<String> = if (insideContainer) {
            // Pipe the desktop script through the proot wrapper into bash.
            listOf("/system/bin/sh", File(scriptsDir, "run-in-ubuntu.sh").absolutePath, "/bin/bash", "-c",
                "cat << 'WT_EOF' | bash\n${File(script).readText()}\nWT_EOF")
        } else {
            listOf("/system/bin/sh", script)
        }
        return exec(command, extraEnv, onLog)
    }

    private fun exec(command: List<String>, extraEnv: Map<String, String>, onLog: (String) -> Unit): Int {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        pb.environment().apply {
            put("WT_HOME", home.absolutePath)
            put("WT_ARCH", arch)
            put("PROOT", prootBinary.absolutePath)
            put("PROOT_TMP_DIR", File(home, "tmp").absolutePath)
            put("WT_NATIVE_LIB", nativeLibDir.absolutePath)
            put("WT_LIBDIR", libDir.absolutePath)
            put("HOME", home.absolutePath)
            put("PATH", "${scriptsDir.absolutePath}:/system/bin:/system/xbin")
            putAll(extraEnv)
        }
        val process = pb.start()
        process.inputStream.bufferedReader().forEachLine(onLog)
        return process.waitFor()
    }
}
