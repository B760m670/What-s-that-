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

        Toast.makeText(this, "Connecting to the desktop…", Toast.LENGTH_SHORT).show()
        client = VncClient(
            host = host,
            port = port,
            onConnected = { _, _, bmp -> runOnUiThread { canvas.setFrame(bmp) } },
            onFrame = { runOnUiThread { canvas.invalidate() } },
            onError = { msg ->
                runOnUiThread { if (!closing) Toast.makeText(this, "VNC: $msg", Toast.LENGTH_LONG).show() }
            },
        ).also {
            canvas.client = it
            it.start()
        }
        audio.start()   // play the desktop's sound through the phone speaker
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
                runCatching { stopService(Intent(this, LinuxSessionService::class.java)) }
                finish()
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        client?.stop()
        audio.stop()
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
