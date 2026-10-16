package com.whatsthat.linux

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen host for the embedded VNC desktop. Connects to the loopback
 * display started by start-desktop.sh and renders it via [VncCanvasView].
 * Floating buttons let the user pop the soft keyboard and close the session;
 * there's no status overlay so nothing covers the desktop.
 */
class VncActivity : AppCompatActivity() {

    private var client: VncClient? = null
    private lateinit var canvas: VncCanvasView
    private val env by lazy { LinuxEnvironment(this) }
    private val audio = AudioBridge()
    @Volatile private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 5901)

        canvas = VncCanvasView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
            addView(controls(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                val m = (6 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, m)
            })
        }
        setContentView(root)

        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            val m = (12 * resources.displayMetrics.density).toInt()
            setMargins(m, m, m, m)
        })

        connect(host, port)
        audio.start()   // play the desktop's sound through the phone speaker
    }

    /**
     * A banner for what the connection is doing. The viewer used to say nothing
     * when the socket went away: the canvas kept the last frame it had, which
     * looks exactly like a desktop that has hung — and the desktop had not hung
     * at all, only the connection to it had.
     */
    private val status: TextView by lazy {
        TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(Color.WHITE)
            textSize = 13f
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
            visibility = View.GONE
        }
    }

    private var attempt = 0
    private val ui = Handler(Looper.getMainLooper())

    private fun connect(host: String, port: Int) {
        client?.stop()
        client = VncClient(
            host = host,
            port = port,
            onConnected = { _, _, bmp ->
                runOnUiThread {
                    attempt = 0
                    status.visibility = View.GONE
                    canvas.setFrame(bmp)
                }
            },
            onFrame = { runOnUiThread { canvas.invalidate() } },
            onError = { msg -> runOnUiThread { lost(host, port, msg) } },
        ).also {
            canvas.client = it
            it.start()
        }
    }

    /**
     * The session lives in the container and outlives any one connection to it,
     * so a dropped socket is worth retrying rather than reporting as the end.
     * Backs off, and eventually says so plainly instead of retrying forever.
     */
    private fun lost(host: String, port: Int, msg: String) {
        if (closing || isFinishing) return
        attempt++

        // ECONNREFUSED means nothing is listening: the X server itself is gone,
        // not merely unreachable. Retrying a port that refuses connections eight
        // times over tells the user nothing they cannot already see, so stop and
        // show what the server said on its way out.
        if (msg.contains("ECONNREFUSED") || msg.contains("Connection refused")) {
            showGone(host, port, msg)
            return
        }

        if (attempt > MAX_RECONNECTS) {
            status.text = getString(R.string.vnc_lost_for_good, msg)
            status.visibility = View.VISIBLE
            return
        }
        val delay = minOf(1000L shl (attempt - 1), 5000L)
        status.text = getString(R.string.vnc_reconnecting, attempt, MAX_RECONNECTS, msg)
        status.visibility = View.VISIBLE
        ui.postDelayed({ if (!closing && !isFinishing) connect(host, port) }, delay)
    }

    /** Bottom-right floating controls: close session, then keyboard. Kept small
     *  and tucked into the corner so they barely touch the desktop. */
    private fun controls() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val gap = (6 * resources.displayMetrics.density).toInt()
        addView(smallButton(getString(R.string.close_session)) { closeSession() })
        addView(smallButton(getString(R.string.show_keyboard)) {
            canvas.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(canvas, InputMethodManager.SHOW_FORCED)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = gap })
    }

    /** A compact, translucent floating button (no default min-size/insets). */
    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0; minHeight = 0
        minimumWidth = 0; minimumHeight = 0
        val p = (8 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
        alpha = 0.8f
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** End the session: kill the VNC server, drop the notification, and leave
     *  (back to the main screen, where another distro can be picked). */
    private fun closeSession() {
        if (closing) return
        closing = true
        Toast.makeText(this, "Closing session…", Toast.LENGTH_SHORT).show()
        client?.stop()
        audio.stop()
        Thread {
            runCatching { env.prepareScripts() }   // ensure kill-session.sh is present
            runCatching { env.killSession {} }
            runOnUiThread {
                // Tell the service the desktop is gone; do NOT stop it. It used
                // to be stopped outright here, which also ended the always-on
                // service — so closing a desktop window killed a relay that had
                // nothing to do with it. The service now decides for itself
                // whether anything is left to keep alive.
                runCatching { LinuxSessionService.send(this, LinuxSessionService.ACTION_DESKTOP_DOWN) }
                finish()
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * The desktop stopped. Say so once, with whatever the X server managed to
     * write before it went — including the silence that means it was killed
     * rather than crashed.
     */
    private fun showGone(host: String, port: Int, msg: String) {
        if (closing || isFinishing) return
        status.text = getString(R.string.vnc_desktop_gone)
        status.visibility = View.VISIBLE
        Thread {
            val tail = runCatching { env.vncServerLog() }.getOrDefault(emptyList())
            // A crash says so. The X server writes "Fatal server error" and a
            // backtrace on its way down, every time. Its absence is not missing
            // information — it is the information: a process that vanishes
            // without a word was killed, and on a phone that means the system
            // took the memory back.
            val crashed = tail.any {
                it.contains("Fatal server error") || it.contains("Caught signal")
            }
            val verdict = when {
                tail.isEmpty() -> getString(R.string.vnc_gone_no_log)
                crashed -> getString(R.string.vnc_gone_crashed)
                else -> getString(R.string.vnc_gone_killed)
            }
            // "Something killed it" is a hypothesis, and until now nothing here
            // tested it. The service samples free memory while a session is up,
            // so the last readings before the end either support it or rule it
            // out — and ruling it out matters just as much, because then the
            // cause is something else entirely and memory has been a red
            // herring for three sessions running.
            //
            // Shown for a crash too, which the first version of this got wrong:
            // a crash whose first symptoms are ENOSYS and SIGSYS is not the
            // guest's own fault, and then what happened to memory is the whole
            // question rather than an irrelevance.
            val trace = runCatching { env.memoryTrace() }.getOrDefault(emptyList())
            var evidence = if (trace.isEmpty()) ""
                           else "\n\n" + getString(R.string.vnc_gone_memory, trace.joinToString("  →  "))
            // Draw the conclusion here rather than leaving a list of numbers
            // for the user to interpret. The first time these were shown they
            // read 1.9-2.2 GB free throughout, which settles the question that
            // three sessions were argued over: it was not memory.
            val floor = runCatching { env.memoryFloor() }.getOrNull()
            if (floor != null && !env.memoryEverLow() && floor > MEMORY_COMFORTABLE) {
                evidence += "\n\n" + getString(
                    R.string.vnc_gone_memory_ruled_out, LinuxEnvironment.formatBytes(floor)
                )
            }
            // And the number that separates what is left: how the container's
            // own process ended.
            runCatching { env.sessionExitStatus() }.getOrNull()?.let { code ->
                evidence += "\n\n" + getString(R.string.vnc_gone_exit, describeExit(code))
                // A kill from outside, with memory ruled out, leaves one
                // measurable candidate: Android's cap on how many processes an
                // app may run outside its own management. State the count and
                // the cap; do not argue from either.
                if (code - 128 == 9) {
                    val last = runCatching { env.guestProcessLast() }.getOrDefault(0)
                    val peak = runCatching { env.guestProcessPeak() }.getOrDefault(0)
                    if (peak > 0) {
                        evidence += "\n\n" + getString(R.string.vnc_gone_processes, last, peak)
                    }
                    // Android 12 brought in two rules for an app's unmanaged
                    // processes, not one: a cap on how many, and a limit on how
                    // much processor they use. The count came back under the
                    // cap, so this is the other half of the same switch.
                    val guestCpu = runCatching { env.guestCpuPeakPercent() }.getOrNull()
                    val prootCpu = runCatching { env.prootCpuPeakPercent() }.getOrNull()
                    if (guestCpu != null || prootCpu != null) {
                        evidence += "\n\n" + getString(
                            R.string.vnc_gone_cpu, guestCpu ?: 0, prootCpu ?: 0
                        )
                    }
                }
                // Which phone this is, because manufacturers differ in what
                // they kill and this has never once been written down.
                evidence += "\n\n" + getString(
                    R.string.vnc_gone_device,
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    android.os.Build.VERSION.RELEASE,
                    android.os.Build.VERSION.SDK_INT,
                )
            }
            // One signature in the log is decisive, and it is not "proot is
            // gone" — proot exits whenever the session ends, so finding it gone
            // afterwards proves nothing either way. What cannot happen on a
            // healthy system is a plain read() of an established socket failing
            // with ENOSYS. That is the documented behaviour of a seccomp filter
            // returning SECCOMP_RET_TRACE with no tracer present: the call is
            // not executed at all. Seeing it means the tracer was gone while
            // the guest was still running — the floor removed from under it —
            // rather than the guest failing on its own.
            if (tail.any { it.contains("Function not implemented") }) {
                evidence = "\n\n" + getString(R.string.vnc_gone_tracer) + evidence
            }
            val detail = if (tail.isEmpty()) verdict + evidence
                         else verdict + evidence + "\n\n" + tail.joinToString("\n")
            runOnUiThread {
                if (closing || isFinishing) return@runOnUiThread
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.vnc_desktop_gone)
                    .setMessage("$msg\n\n$detail")
                    // The desktop died; the viewer is still the screen the user
                    // is looking at. Making them back out and find a button on
                    // another screen is friction for no reason.
                    .setPositiveButton(R.string.vnc_relaunch) { _, _ -> relaunch(host, port) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                    .setCancelable(true)
                    .show()
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * What an exit status means, in words.
     *
     * Java reports a child killed by a signal as 128 + the signal number, and
     * these particular signals each point somewhere different: SIGKILL comes
     * from outside the process (Android reclaiming, or a power manager),
     * SIGSYS is the sandbox refusing a system call the process made, and
     * SIGSEGV or SIGABRT mean proot failed on its own account.
     */
    private fun describeExit(code: Int): String = when {
        code == 0 -> getString(R.string.exit_clean)
        code > 128 -> when (val sig = code - 128) {
            9 -> getString(R.string.exit_sigkill, sig)
            31 -> getString(R.string.exit_sigsys, sig)
            11, 6, 7, 4 -> getString(R.string.exit_crash, sig)
            15 -> getString(R.string.exit_sigterm, sig)
            else -> getString(R.string.exit_signal, sig)
        }
        else -> getString(R.string.exit_status, code)
    }

    /** Start a fresh session and connect to it, without leaving the viewer. */
    private fun relaunch(host: String, port: Int) {
        status.text = getString(R.string.vnc_relaunching)
        status.visibility = View.VISIBLE
        val geometry = getSharedPreferences("wt", MODE_PRIVATE)
            .getString("geometry", "1280x720") ?: "1280x720"
        Thread {
            val endpoint = runCatching { env.startDesktop(geometry) {} }.getOrNull()
            runOnUiThread {
                if (closing || isFinishing) return@runOnUiThread
                if (endpoint != null) {
                    attempt = 0
                    connect(host, port)
                } else {
                    status.text = getString(R.string.vnc_relaunch_failed)
                }
            }
        }.apply { isDaemon = true; name = "vnc-relaunch"; start() }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        client?.stop()
        audio.stop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"

        /**
         * Above this much free memory, a session that ended was not short of
         * it. Deliberately generous: the point is to rule memory out, and a
         * device with half a gigabyte spare did not kill a 20 MB process for
         * space.
         */
        private const val MEMORY_COMFORTABLE = 512L * 1024 * 1024

        /** Give up after this many, rather than retrying a dead socket forever. */
        private const val MAX_RECONNECTS = 8

        fun start(context: Context, host: String, port: Int) {
            context.startActivity(Intent(context, VncActivity::class.java).apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            })
        }
    }
}
