package com.whatsthat.linux

import android.app.ActivityManager
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

    init {
        migrateLegacyUbuntu()
        forgetDialogChosenSessionTypes()
    }

    /**
     * Drop session types chosen in the old picker, once.
     *
     * That dialog asked the user which of X11 or nested Wayland to run, which
     * nobody can answer without trying, and stored the guess as if it were
     * knowledge. From now on this preference is only ever written by a launch
     * that actually produced a desktop, so the guesses have to go — otherwise a
     * device keeps starting with whichever one happened to be tapped.
     */
    private fun forgetDialogChosenSessionTypes() {
        if (prefs.getBoolean("session_pref_reset", false)) return
        val stale = prefs.all.keys.filter { it.startsWith("session:") }
        prefs.edit().apply {
            stale.forEach { remove(it) }
            putBoolean("session_pref_reset", true)
        }.apply()
    }

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

    /**
     * The session type to TRY FIRST — not a user setting.
     *
     * There is no way for a person to know whether their phone, their GNOME
     * version and proot will tolerate an X11 session or a nested Wayland one;
     * asking them made them responsible for an answer only an attempt can give.
     * So the launch script tries both and reports which one held, and this is
     * where that answer is kept so the discovery is paid for once.
     */
    fun sessionTypeFor(d: Distro): SessionType =
        SessionType.byId(prefs.getString("session:${d.id}", SessionType.X11.id))

    private fun rememberSessionType(d: Distro, t: SessionType) {
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
     * What TigerVNC's own log says, read straight off the rootfs.
     *
     * A session that disappears takes its explanation with it: the launch
     * script has long exited, and the viewer only learns that the port refuses
     * connections. The X server's log is still on disk, and it is the only
     * place that distinguishes a crash from being killed — a process the kernel
     * reaps for memory leaves no message at all, which is itself the answer.
     */
    fun vncServerLog(maxLines: Int = 40): List<String> {
        val dir = File(distroDir(activeDistro), "root/.vnc")
        val log = dir.listFiles { f -> f.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() } ?: return emptyList()
        return runCatching {
            // The tail of this file is not the end of the story unless the noise
            // comes out first. xkbcomp cannot resolve a few hundred XF86 keysyms
            // against TigerVNC's keymap and says so once per keysym, which is
            // more lines than the tail is long — so "the last 40 lines" was 40
            // warnings about sonar and autopilot keys, and the server's actual
            // last words were never shown. Filter, then take the tail.
            log.readLines().takeLast(2_000).filterNot(::isLogNoise).takeLast(maxLines)
        }.getOrDefault(emptyList())
    }

    // --- what the device had left ---------------------------------------------
    //
    // Three sessions have now ended with no message at all, and "the system
    // reclaimed the memory" has been the explanation every time — asserted,
    // never measured. These record what was actually free while the session
    // ran, so the next silence is either confirmed or refuted instead of
    // explained again.

    private val memoryLog: File get() = File(home, "tmp/wt-memory.log")

    /**
     * One reading, appended to a short rolling file. Cheap enough to call often.
     *
     * Stored as raw bytes rather than "1,9 GB": the app has to be able to say
     * whether memory was ever short, and a formatted, locale-punctuated string
     * cannot be compared with anything.
     */
    fun recordMemorySample() {
        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            // Free bytes, how many processes the container is running, and
            // whether Android called the device low — the three numbers that
            // between them name every way a session has died so far.
            val line = "${info.availMem},${guestProcessCount()}" + if (info.lowMemory) "|low" else ""
            memoryLog.parentFile?.mkdirs()
            // Rewritten rather than appended: it is a ring of a few dozen
            // readings, and it must never become another file that grows.
            val kept = (runCatching { memoryLog.readLines() }.getOrDefault(emptyList()) + line)
                .takeLast(60)
            memoryLog.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * How many processes are running inside the container right now.
     *
     * Counted the same way the sweeps identify the guest: under proot every
     * guest process is a ptrace tracee, and nothing else of ours is. The number
     * matters because Android caps how many unmanaged child processes an app
     * may have, and kills them when it is exceeded.
     */
    fun guestProcessCount(): Int = runCatching {
        (File("/proc").listFiles() ?: return 0).count { d ->
            d.isDirectory && d.name.all(Char::isDigit) && runCatching {
                File(d, "status").bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith("TracerPid:") }
                        ?.substringAfter(':')?.trim()?.toIntOrNull()?.let { it != 0 } ?: false
                }
            }.getOrDefault(false)
        }
    }.getOrDefault(0)

    /** Every reading still on file: free bytes, guest processes, low flag. */
    private fun memorySamples(): List<Triple<Long, Int, Boolean>> =
        runCatching {
            memoryLog.readLines().mapNotNull { line ->
                val low = line.endsWith("|low")
                val parts = line.removeSuffix("|low").trim().split(',')
                val bytes = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                Triple(bytes, parts.getOrNull(1)?.toIntOrNull() ?: 0, low)
            }
        }.getOrDefault(emptyList())

    /** The most processes the container was ever seen running. */
    fun guestProcessPeak(): Int = memorySamples().maxOfOrNull { it.second } ?: 0

    /** The last few readings, newest last, ready to show. */
    fun memoryTrace(samples: Int = 6): List<String> =
        memorySamples().takeLast(samples).map { (bytes, _, low) ->
            appContext.getString(
                R.string.mem_sample,
                formatBytes(bytes),
                if (low) appContext.getString(R.string.mem_low) else "",
            )
        }

    /**
     * The least free memory seen while the session ran, or null if nothing was
     * recorded. This is what turns the readings from a list into an answer: if
     * the floor is high, the session was not killed for memory, and three
     * sessions' worth of that assumption were wrong.
     */
    fun memoryFloor(): Long? = memorySamples().minOfOrNull { it.first }

    /** Did Android ever flag the device as low on memory during the session? */
    fun memoryEverLow(): Boolean = memorySamples().any { it.third }

    /**
     * How the container's own process ended, as the kernel reported it to us.
     * Null when nothing was recorded — a session that never started, or an app
     * that was itself killed before it could write the number down.
     */
    fun sessionExitStatus(): Int? = runCatching {
        File(home, "tmp/wt-session.exit").readText().trim().toIntOrNull()
    }.getOrNull()

    private fun recordSessionExit(code: Int) {
        runCatching {
            val f = File(home, "tmp/wt-session.exit")
            f.parentFile?.mkdirs()
            f.writeText("$code\n")
        }
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
        return runCatching {
            f.readLines().takeLast(2_000).filterNot(::isLogNoise).takeLast(maxLines)
        }.getOrDefault(emptyList())
    }

    /**
     * Lines that are always present and never mean anything.
     *
     * Every one of these was shipped by the distro, appears on a perfectly
     * healthy session, and cannot be fixed from here — the keysyms do not exist
     * in TigerVNC's keymap, VNC has no DPMS, and mutter's handler notice is
     * routine. They are filtered out of what the user is shown, not out of the
     * file: the log on disk stays complete for anyone who wants it.
     */
    private fun isLogNoise(line: String): Boolean = NOISE.any { line.contains(it) }
    /**
     * Rootfs directories on disk that no longer belong to any offered distro.
     *
     * Dropping a distro from the list must not strand its gigabytes: the
     * directory is keyed on the id, so a system installed when Debian was
     * offered is still there, still occupying space, and with nothing in the
     * interface able to reach it. Listing what is actually on disk — rather
     * than a remembered list of ids — is the version that cannot go stale.
     */
    fun orphanedRootfs(): List<File> =
        distrosDir.listFiles().orEmpty()
            .filter { it.isDirectory && Distros.all.none { d -> d.id == it.name } }
            .sortedBy { it.name }

    fun removeOrphan(dir: File) {
        dir.deleteRecursively()
        invalidateDiskUsage()
    }

    /** Bytes used by any directory, for the orphan entries that have no [Distro]. */
    fun diskUsageOf(dir: File): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

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
        runCatching { File(home, "tmp/wt-session.exit").delete() }   // last session's verdict
        val pb = ProcessBuilder(containerCommand("start-desktop.sh")).redirectErrorStream(true)
        configureEnv(pb, desktopEnvVars() + mapOf("WT_GEOMETRY" to geometry))
        val process = pb.start()
        desktopProcess = process
        val reader = process.inputStream.bufferedReader()

        var endpoint: String? = null

        // A total time limit was wrong. The launch can legitimately take a very
        // long time — it may install several hundred megabytes the first time,
        // try one session type, watch it die and try the other — and a 240
        // second cap reported LAUNCH FAILED in the middle of a Firefox download
        // that was going perfectly well, leaving the script running orphaned.
        //
        // What actually indicates a hang is silence, so that is what is
        // measured. readLine() blocks, so the watchdog runs beside it and ends
        // the process, which is what makes readLine() return.
        val lastOutput = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        Thread {
            while (desktopProcess === process && process.isAlive) {
                if (System.currentTimeMillis() - lastOutput.get() > SILENCE_LIMIT_MS) {
                    onLog("[launch] no output for ${SILENCE_LIMIT_MS / 1000}s — giving up.")
                    runCatching { process.destroy() }
                    return@Thread
                }
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "launch-watchdog"; start() }

        while (true) {
            val line = reader.readLine() ?: break          // EOF = process exited early
            lastOutput.set(System.currentTimeMillis())
            onLog(line)
            line.substringAfter("WT_SESSION_USED ", "").trim().takeIf { it.isNotEmpty() }?.let {
                rememberSessionType(activeDistro, SessionType.byId(it))
            }
            val ep = line.substringAfter("VNC_READY ", "").trim()
            if (ep.isNotEmpty()) { endpoint = ep; break }
        }

        if (endpoint != null) {
            // Keep the session alive; drain its remaining output off-thread —
            // and, when it finally ends, record HOW.
            //
            // This handle is proot itself: run-in-ubuntu.sh execs it, so the
            // process the app holds is the container's tracer, and its exit
            // status is the one number that separates the remaining
            // explanations. 137 is SIGKILL from outside; 159 is SIGSYS, the
            // sandbox refusing a system call proot made; 139 or 134 is proot
            // crashing on its own. Guessing between those has cost three
            // sessions already.
            Thread {
                runCatching { reader.forEachLine(onLog) }
                recordSessionExit(runCatching { process.waitFor() }.getOrDefault(-1))
            }.apply { isDaemon = true; name = "session-reaper"; start() }
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
    /**
     * Whether the desktop is up, answered by looking for its process rather
     * than by connecting to it.
     *
     * Connecting was the obvious way and it showed up in the server's own log
     * as a client that attached and left without asking for a single frame —
     * one accept() per refresh of the main screen, for a question we can answer
     * without touching the socket at all. That matters beyond tidiness: an
     * accept() is where the X server was killed with SIGSYS once already.
     *
     * Android mounts /proc with hidepid, so this sees only this app's own
     * processes — which is exactly the set the session belongs to.
     */
    fun isSessionRunning(): Boolean = runCatching {
        File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }
            .orEmpty()
            .any { dir ->
                runCatching {
                    val cmd = File(dir, "cmdline").readBytes().toString(Charsets.ISO_8859_1)
                    cmd.contains("Xtigervnc") || cmd.contains("Xvnc")
                }.getOrDefault(false)
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

    // --- the always-on service ------------------------------------------------
    //
    // A relay is not a desktop. It has no window, nobody watches it, and it is
    // supposed to still be there tomorrow — which makes almost every assumption
    // the desktop path is built on wrong for it. It gets its own command, its
    // own supervision and its own lifetime.

    var serviceEnabled: Boolean
        get() = prefs.getBoolean("relay_enabled", false)
        set(v) { prefs.edit().putBoolean("relay_enabled", v).apply() }

    var serviceCommand: String
        get() = prefs.getString("relay_command", "") ?: ""
        set(v) { prefs.edit().putString("relay_command", v.trim()).apply() }

    /**
     * Where the supervised command's output goes.
     *
     * This is the container's own `/tmp/wt-service/log` seen from the host side:
     * the relay writes to a file rather than to a pipe, so that nothing it
     * prints depends on this app being alive to read it. The supervisor's own
     * notes are appended to the same file, which keeps one chronological record
     * of a thing that is meant to run for weeks.
     */
    val serviceLogFile: File get() = File(home, "tmp/wt-service/log")

    fun serviceLog(maxLines: Int = 200): List<String> =
        runCatching { serviceLogFile.readLines().takeLast(maxLines) }.getOrDefault(emptyList())

    /**
     * Start the configured command inside the container and hand back the
     * process, still running. Unlike [runCommand] this does not wait: the whole
     * point is that it outlives the call.
     *
     * It goes through service-run.sh rather than straight to bash, because the
     * command has to be registered inside the container before it runs. Without
     * that registration the next desktop launch — or close — sweeps it away with
     * everything else in the guest; see the script for why that sweep cannot
     * simply be made gentler.
     */
    fun startServiceProcess(): Process? {
        val cmd = serviceCommand
        if (cmd.isBlank() || !isBootstrapped) return null
        val pb = ProcessBuilder(containerCommand("service-run.sh")).redirectErrorStream(true)
        configureEnv(pb, desktopEnvVars() + mapOf("WT_SERVICE_CMD" to cmd))
        return runCatching { pb.start() }.getOrNull()
    }

    /**
     * Is the registered service process still alive?
     *
     * Asked of the container's own registration, not of our [Process] handle:
     * after the app is restarted — by the user, or by Android reclaiming it —
     * the handle is gone while the relay itself may well still be running, and
     * answering "no" then would start a second copy.
     */
    fun isServiceProcessLive(): Boolean = runCatching {
        val dir = File(home, "tmp/wt-service")
        val pid = File(dir, "pid").readText().trim().toLongOrNull() ?: return false
        processAlive(pid, File(dir, "start").readText().trim())
    }.getOrDefault(false)

    /**
     * Is this exactly the process that was recorded, still running?
     *
     * Pids are recycled, so "the directory exists" is not an answer. Field 22 of
     * /proc/<pid>/stat is the process's start time, and pid plus start time is
     * an identity. The comm field (2) can contain spaces and parentheses, so it
     * is cut off at its closing ")" rather than split blindly.
     */
    private fun processAlive(pid: Long, startTime: String): Boolean {
        val stat = File("/proc/$pid/stat")
        if (!stat.canRead()) return false
        if (startTime.isEmpty()) return true
        val fields = runCatching { stat.readText() }.getOrNull() ?: return false
        return fields.substringAfter(") ").trim().split(" ").getOrNull(19) == startTime
    }

    // Deliberately absent: a check for whether the session's proot is still
    // alive. It is always gone after a session ends — it exits with its last
    // tracee — so finding it gone afterwards distinguishes nothing. What does
    // distinguish is in the log; see the ENOSYS signature in VncActivity.

    /**
     * Stop the relay, and nothing else.
     *
     * Not by destroying our handle: that handle is the proot tracing the relay,
     * and killing a tracer leaves its tracees orphaned mid-syscall. The script
     * signals the tracees instead, and leaves any desktop session alone.
     */
    fun stopServiceProcess(onLog: (String) -> Unit = {}): Int = runCatching {
        if (!rootfsReady(activeDistro)) 0
        else execScript("service-stop.sh", insideContainer = true, onLog = onLog)
    }.getOrDefault(1)

    /** What the relay was last started with, as recorded inside the container. */
    fun runningServiceCommand(): String? = runCatching {
        File(home, "tmp/wt-service/command").readText().trim().ifEmpty { null }
    }.getOrNull()

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
        /**
         * How long a launch may produce nothing at all before it is abandoned.
         * Generous: apt is quiet for a while between packages, and the device
         * is doing this on a phone.
         */
        private const val SILENCE_LIMIT_MS = 300_000L

        /** Harmless lines that drown the useful ones; see [isLogNoise]. */
        private val NOISE = listOf(
            "Could not resolve keysym",
            "Errors from xkbcomp are not fatal",
            "The XKEYBOARD keymap compiler (xkbcomp) reports:",
            "extension \"DPMS\" missing",
            "mieq: warning: overriding existing handler",
            "Impossible to set scaling on crtc",
            "Scalig CRTC",
        )

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
