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

    /** proot is shipped as a native lib so Android marks it executable on install. */
    private val prootBinary: File
        get() = File(appContext.applicationInfo.nativeLibraryDir, "libproot.so")

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
    }

    /** Download + extract the Ubuntu rootfs. Streams log lines to [onLog]. */
    fun bootstrap(onLog: (String) -> Unit): Int =
        execScript(
            "bootstrap.sh", insideContainer = false,
            extraEnv = mapOf("WT_VARIANT" to BuildConfig.UBUNTU_VARIANT),
            onLog = onLog,
        )

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
            put("HOME", home.absolutePath)
            put("PATH", "${scriptsDir.absolutePath}:/system/bin:/system/xbin")
            putAll(extraEnv)
        }
        val process = pb.start()
        process.inputStream.bufferedReader().forEachLine(onLog)
        return process.waitFor()
    }
}
