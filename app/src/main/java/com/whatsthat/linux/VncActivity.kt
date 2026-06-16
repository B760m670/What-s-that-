package com.whatsthat.linux

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen host for the embedded VNC desktop. Connects to the loopback
 * display started by start-desktop.sh and renders it via [VncCanvasView].
 * A floating button toggles the soft keyboard for typing into the desktop, and
 * a thin status line shows live connection/input diagnostics.
 */
class VncActivity : AppCompatActivity() {

    private var client: VncClient? = null
    private lateinit var canvas: VncCanvasView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 5901)

        canvas = VncCanvasView(this)
        status = TextView(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(12, 6, 12, 6)
            text = "connecting to $host:$port…"
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            val m = (16 * resources.displayMetrics.density).toInt()
            addView(keyboardButton(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(m, m, m, m)
            })
            addView(logButton(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(m, m, m, m)
            })
        }
        setContentView(root)

        client = VncClient(
            host = host,
            port = port,
            onConnected = { _, _, bmp -> runOnUiThread { canvas.setFrame(bmp) } },
            onFrame = { runOnUiThread { canvas.invalidate() } },
            onError = { msg -> runOnUiThread {
                Toast.makeText(this, "VNC: $msg", Toast.LENGTH_LONG).show()
                // don't auto-close: keep the status line visible for diagnosis
            } },
        ).also {
            canvas.client = it
            it.start()
        }
        startStatusUpdates()
    }

    /** Refresh the diagnostic status line ~1×/sec. */
    private fun startStatusUpdates() {
        ui.post(object : Runnable {
            override fun run() {
                client?.let { c ->
                    status.text = "conn:${if (c.isRunning) "Y" else "N"}  " +
                        "fb:${c.fbWidth}x${c.fbHeight}  frames:${c.framesReceived}  " +
                        "ptr:${c.pointerSent}  key:${c.keySent}" +
                        (c.lastError?.let { "  err:$it" } ?: "")
                }
                ui.postDelayed(this, 1000)
            }
        })
    }

    private fun keyboardButton() = Button(this).apply {
        text = getString(R.string.show_keyboard)
        alpha = 0.85f
        setOnClickListener {
            canvas.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(canvas, InputMethodManager.SHOW_FORCED)
        }
    }

    /** Share the full session log without leaving the running desktop. */
    private fun logButton() = Button(this).apply {
        text = getString(R.string.action_share_log)
        alpha = 0.85f
        setOnClickListener { SessionLog.share(this@VncActivity) }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        client?.stop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"

        fun start(context: Context, host: String, port: Int) {
            context.startActivity(Intent(context, VncActivity::class.java).apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            })
        }
    }
}
