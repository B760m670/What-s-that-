package com.whatsthat.linux

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen host for the shared-framebuffer backend — the faster alternative
 * to [VncActivity]. Instead of an RFB socket it maps the Xvfb framebuffer file
 * ([FramebufferReader]) and drives input straight into the X server
 * ([XTestInput]). The same [DesktopCanvasView] draws it and forwards touch/keys,
 * unaware of which backend is behind the [InputSink].
 *
 * Xvfb gives no "frame ready" signal, so a background thread polls the mapping
 * at a fixed cadence; the read is ~1 ms, so the cadence, not the read, sets the
 * cost. A cheap dirty-check skips the redraw when the screen has not changed.
 */
class FramebufferActivity : AppCompatActivity() {

    private lateinit var canvas: DesktopCanvasView
    private val env by lazy { LinuxEnvironment(this) }
    private val audio = AudioBridge()
    private var reader: FramebufferReader? = null
    private var xinput: XTestInput? = null
    @Volatile private var running = false
    @Volatile private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fbPath = intent.getStringExtra(EXTRA_FB) ?: run { finish(); return }
        val xSocket = intent.getStringExtra(EXTRA_XSOCK) ?: run { finish(); return }

        canvas = DesktopCanvasView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
            addView(controls(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                val m = (6 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, m)
            })
        }
        setContentView(root)

        Toast.makeText(this, "Opening the desktop…", Toast.LENGTH_SHORT).show()
        startBackend(fbPath, xSocket)
        audio.start()
    }

    private fun startBackend(fbPath: String, xSocket: String) {
        running = true
        Thread {
            val fb = FramebufferReader(java.io.File(fbPath))
            // The file may not exist for a beat after the script says READY.
            val deadline = System.currentTimeMillis() + 10_000
            while (running && System.currentTimeMillis() < deadline && !fb.open()) {
                Thread.sleep(100)
            }
            if (!running) return@Thread
            if (fb.bitmap == null) {
                runOnUiThread { if (!closing) Toast.makeText(this, "Framebuffer not ready", Toast.LENGTH_LONG).show() }
                return@Thread
            }
            reader = fb
            runOnUiThread { fb.bitmap?.let { canvas.setFrame(it) } }

            val xi = XTestInput(xSocket)
            if (xi.connect()) {
                xinput = xi
                runOnUiThread { canvas.input = xi }
            } else {
                runOnUiThread { if (!closing) Toast.makeText(this, "Input (XTEST) unavailable", Toast.LENGTH_LONG).show() }
            }

            // Poll loop. ~30 Hz is plenty for a desktop and keeps the read cost
            // (~1 ms) a small fraction of a frame. A one-line checksum avoids
            // re-uploading and re-drawing an unchanged screen.
            var lastSig = 0L
            while (running) {
                val sig = fb.frameSignature()
                if (sig != lastSig) {
                    lastSig = sig
                    if (fb.readFrame()) canvas.postInvalidateOnAnimation()
                }
                try { Thread.sleep(33) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "fb-poll"; start() }
    }

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

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
        val p = (8 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
        alpha = 0.8f
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun closeSession() {
        if (closing) return
        closing = true
        running = false
        Toast.makeText(this, "Closing session…", Toast.LENGTH_SHORT).show()
        audio.stop()
        Thread {
            runCatching { xinput?.close() }
            runCatching { reader?.close() }
            runCatching { env.prepareScripts() }
            runCatching { env.killSession {} }
            runOnUiThread {
                runCatching { stopService(Intent(this, LinuxSessionService::class.java)) }
                finish()
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        running = false
        runCatching { xinput?.close() }
        runCatching { reader?.close() }
        audio.stop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_FB = "fb"
        private const val EXTRA_XSOCK = "xsock"

        fun start(context: Context, fbPath: String, xSocketPath: String) {
            context.startActivity(Intent(context, FramebufferActivity::class.java).apply {
                putExtra(EXTRA_FB, fbPath)
                putExtra(EXTRA_XSOCK, xSocketPath)
            })
        }
    }
}
