package com.whatsthat.linux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/**
 * Keeps the container's work alive when nobody is looking at it.
 *
 * It has two jobs, and they have different lifetimes. One is the desktop
 * session: started when a desktop launches, ended when the user closes it. The
 * other is the always-on service — a relay, a server, anything meant to still
 * be running tomorrow — which must survive the desktop being closed, the app
 * being swept out of recents, and the screen being off all night.
 *
 * Treating those as the same thing is what used to end the second one with the
 * first. They are now separate: the notification stays while EITHER is wanted,
 * and the supervised command is restarted whenever it stops without being
 * asked to.
 */
class LinuxSessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var supervising = false
    @Volatile private var child: Process? = null
    private var supervisor: Thread? = null

    /** Set when a desktop session is up, so closing the relay does not kill it. */
    @Volatile private var desktopWanted = false

    private val env by lazy { LinuxEnvironment(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(statusText()))
        acquireWakeLock()

        when (intent?.action) {
            ACTION_BOOT -> append("[service] device started — bringing the service back up.")
            ACTION_SERVICE_START -> append("[service] switched on.")
            ACTION_DESKTOP_UP -> desktopWanted = true
            ACTION_DESKTOP_DOWN -> desktopWanted = false
            ACTION_STOP_SERVICE -> {
                env.serviceEnabled = false
                stopSupervising(alsoKill = true)
            }
        }

        if (env.serviceEnabled) startSupervising() else stopSupervising(alsoKill = false)

        // Nothing left to keep alive: let the notification go rather than
        // leaving a permanent entry in the shade for a container doing nothing.
        if (!env.serviceEnabled && !desktopWanted) {
            dropNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        update()
        // START_STICKY matters here: if Android reclaims the app, this is what
        // brings the relay back without anybody opening the app again.
        return START_STICKY
    }

    // --- supervision ---------------------------------------------------------

    private fun startSupervising() {
        if (supervising) return
        // Nothing to run and nothing already running: there is no supervision
        // to do, and the notification should not claim otherwise.
        if (env.serviceCommand.isBlank() && !env.isServiceProcessLive()) return
        supervising = true
        supervisor = Thread({ supervise() }, "wt-service").apply { isDaemon = true; start() }
    }

    /**
     * Stop supervising. [alsoKill] separates "we are going away" from "the user
     * turned it off": Android stopping this service — a configuration change, a
     * restart — must not take the relay down, because the relay is the thing
     * that is supposed to still be there afterwards. Only an explicit "off"
     * kills it.
     */
    private fun stopSupervising(alsoKill: Boolean) {
        supervising = false
        supervisor?.interrupt()
        child = null
        if (alsoKill) {
            Thread({ env.stopServiceProcess(::append) }, "wt-service-stop")
                .apply { isDaemon = true; start() }
        }
    }

    /**
     * Run it, watch it, start it again.
     *
     * Two cases, and the second is the one that matters. If no relay is running,
     * start one. If one is *already* running — because this service was
     * restarted while the container kept going, which is the normal outcome of
     * Android reclaiming the app — then adopt it and watch, rather than start a
     * second copy of a relay that is already connected.
     *
     * The backoff exists so a command that cannot work (a typo, a missing
     * binary) does not spin on the battery; it settles at a minute between
     * tries and keeps saying so in the log.
     */
    private fun supervise() {
        // The scripts live in app storage, copied out of the APK. After an
        // update they are the old ones until something copies them again, and
        // after a reboot nothing has opened the app to do it — so the
        // supervisor does it itself rather than running yesterday's script.
        runCatching { env.prepareScripts() }
            .onFailure { append("[service] could not prepare the scripts: ${it.message}") }

        var backoffMs = 1_000L
        while (supervising) {
            if (env.isServiceProcessLive()) {
                append("[service] already running in the container — watching it.")
                update()
                while (supervising && env.isServiceProcessLive()) sleepInterruptibly(10_000)
                if (supervising) append("[service] the running command has exited.")
                backoffMs = 1_000L
                continue
            }

            val started = System.currentTimeMillis()
            append("[service] starting: ${env.serviceCommand}")
            val p = env.startServiceProcess()
            if (p == null) {
                append("[service] could not start — is the system installed, and the command set?")
                sleepInterruptibly(30_000)
                continue
            }
            child = p
            update()

            // The command's own output goes to the log file inside the
            // container, not down this pipe (see service-run.sh), so this
            // normally yields only what proot itself says before the guest
            // takes over. It still has to be drained: that is where a failure
            // to enter the container at all shows up.
            runCatching { p.inputStream.bufferedReader().forEachLine { append(it) } }
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            child = null
            if (!supervising) break

            val ranFor = System.currentTimeMillis() - started
            if (code == 78) {
                // EX_CONFIG from service-run.sh: no command. Retrying cannot fix
                // that, and the user has to come back to the app anyway.
                append("[service] nothing configured to run — waiting.")
                update()
                break
            }
            // A command that ran for a while was working; start the backoff over
            // rather than punishing it for one late failure.
            backoffMs = if (ranFor > 60_000) 1_000L else minOf(backoffMs * 2, 60_000L)
            append("[service] exited with $code after ${ranFor / 1000}s — restarting in ${backoffMs / 1000}s")
            update()
            sleepInterruptibly(backoffMs)
        }
        supervising = false
        update()
    }

    private fun sleepInterruptibly(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (supervising && System.currentTimeMillis() < until) {
            try { Thread.sleep(500) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * Append a supervisor note to the same log the relay writes to, so the
     * record reads in order: what was started, what it printed, how it ended.
     *
     * Trimming happens only while no relay is running. The running command
     * holds this file open in append mode; rewriting it underneath would leave
     * that process writing past the end of a file it no longer matches.
     * service-run.sh rotates it properly at start-up for exactly that reason.
     */
    private fun append(line: String) {
        runCatching {
            val f: File = env.serviceLogFile
            f.parentFile?.mkdirs()
            if (f.length() > MAX_LOG_BYTES && child == null && !env.isServiceProcessLive()) {
                val keep = f.readLines().takeLast(500)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            f.appendText(line + "\n")
        }
    }

    // --- notification --------------------------------------------------------

    private fun statusText(): String = when {
        !env.serviceEnabled -> getString(R.string.notif_running)
        // "Running" means a process is actually there — including one adopted
        // from before this service existed, where there is no handle to check.
        child != null || env.isServiceProcessLive() -> getString(R.string.svc_running)
        else -> getString(R.string.svc_restarting)
    }

    private fun update() {
        runCatching {
            (getSystemService(NotificationManager::class.java))
                .notify(NOTIF_ID, buildNotification(statusText()))
        }
    }

    /** The boolean overload, because the int one needs API 24 and minSdk is 21. */
    @Suppress("DEPRECATION")
    private fun dropNotification() {
        stopForeground(true)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhatsThatLinux::session")
            .also { runCatching { it.acquire() } }
    }

    override fun onDestroy() {
        // Not alsoKill: this service going away is not the user saying stop.
        // The relay runs in the container, reparented to init if need be, and
        // the next start of this service adopts it again.
        stopSupervising(alsoKill = false)
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Linux session", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "linux_session"
        private const val NOTIF_ID = 42
        private const val MAX_LOG_BYTES = 256L * 1024

        const val ACTION_BOOT = "wt.boot"
        const val ACTION_SERVICE_START = "wt.service.start"
        const val ACTION_DESKTOP_UP = "wt.desktop.up"
        const val ACTION_DESKTOP_DOWN = "wt.desktop.down"
        const val ACTION_STOP_SERVICE = "wt.service.stop"

        /** Start or re-evaluate the service; safe to call repeatedly. */
        fun send(context: Context, action: String? = null) {
            val i = Intent(context, LinuxSessionService::class.java).apply { setAction(action) }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            }
        }
    }
}
