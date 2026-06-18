package com.whatsthat.linux

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Lists the available distributions and lets the user pick which one is active,
 * install new ones, or remove them — all side-by-side, so installing/removing
 * one never touches another (each has its own rootfs dir). Shows each distro's
 * size (on disk if installed, otherwise the rough download size).
 *
 * Selecting a distro just makes it active and returns to the main screen, whose
 * single button then installs (if needed) or launches that distro.
 */
class DistrosActivity : AppCompatActivity() {

    private lateinit var env: LinuxEnvironment
    private lateinit var list: LinearLayout
    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = LinuxEnvironment(this)
        val pad = (16 * density).toInt()
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(list) })
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()
        text(getString(R.string.distros_title), 22f, bold = true)
        text("Pick a distribution and tap “Use”. The current one stays installed — " +
            "switching only changes which one the main screen installs and launches.",
            12f, color = Color.GRAY, topDp = 4f)

        val activeId = env.activeDistro.id
        for (d in env.allDistros) {
            val installed = env.rootfsReady(d)
            val isActive = d.id == activeId
            val status = when {
                isActive -> getString(R.string.distro_active)
                installed -> getString(R.string.distro_installed)
                else -> getString(R.string.distro_not_installed)
            }
            val title = d.name + "  —  " + status + if (d.experimental) "  ·  experimental" else ""
            text(title, 16f, bold = true, topDp = 22f)

            // Size line: on-disk (computed off-thread) if installed, else download estimate.
            val sizeView = text("…", 12f, color = Color.GRAY)
            if (installed) {
                sizeView.text = "measuring size…"
                Thread {
                    val bytes = env.installedSizeBytes(d)
                    runOnUiThread { sizeView.text = human(bytes) + " on disk" }
                }.apply { isDaemon = true; start() }
            } else {
                sizeView.text = "≈ ${d.approxDownloadMb} MB download (plus packages on install)"
            }

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                text = getString(R.string.distro_use)
                isEnabled = !isActive
                setOnClickListener { env.activeDistro = d; finish() }
            })
            if (isActive && installed) {
                row.addView(Button(this).apply {
                    text = getString(R.string.distro_close)
                    setOnClickListener {
                        isEnabled = false
                        Thread {
                            env.killSession { SessionLog.append(it) }
                            runOnUiThread {
                                // Also drop the foreground "session running" service
                                // so nothing keeps the session alive after closing.
                                runCatching {
                                    stopService(Intent(this@DistrosActivity, LinuxSessionService::class.java))
                                }
                                Toast.makeText(this@DistrosActivity, "Session closed", Toast.LENGTH_SHORT).show()
                                isEnabled = true
                            }
                        }.apply { isDaemon = true; start() }
                    }
                })
            }
            if (installed && !isActive) {
                row.addView(Button(this).apply {
                    text = getString(R.string.distro_remove)
                    setOnClickListener { env.removeDistro(d); rebuild() }
                })
            }
            list.addView(row)
        }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int? = null, topDp: Float = 0f): TextView {
        val tv = TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            color?.let { setTextColor(it) }
            setPadding(0, (topDp * density).toInt(), 0, 0)
        }
        list.addView(tv)
        return tv
    }

    private fun human(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }
}
