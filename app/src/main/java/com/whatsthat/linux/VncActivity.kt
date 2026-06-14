package com.whatsthat.linux

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen host for the embedded VNC desktop. Connects to the loopback
 * display started by start-desktop.sh and renders it via [VncCanvasView].
 * A floating button toggles the soft keyboard for typing into the desktop.
 */
class VncActivity : AppCompatActivity() {

    private var client: VncClient? = null
    private lateinit var canvas: VncCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        val port = intent.getIntExtra(EXTRA_PORT, 5901)

        canvas = VncCanvasView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
            addView(keyboardButton(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                val m = (16 * resources.displayMetrics.density).toInt()
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
                finish()
            } },
        ).also {
            canvas.client = it
            it.start()
        }
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

    override fun onDestroy() {
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
