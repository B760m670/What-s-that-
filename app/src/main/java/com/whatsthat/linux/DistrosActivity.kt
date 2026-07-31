package com.whatsthat.linux

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Lists the available distributions and lets the user pick which one is active,
 * install new ones, or remove them — all side-by-side, so installing/removing
 * one never touches another (each has its own rootfs dir).
 *
 * Selecting a distro just makes it active and returns to the main screen, whose
 * single button then installs (if needed) or launches that distro.
 */
class DistrosActivity : AppCompatActivity() {

    private lateinit var env: LinuxEnvironment
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = LinuxEnvironment(this)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(list) })
        rebuild()
    }

    private fun rebuild() {
        list.removeAllViews()

        header(getString(R.string.distros_title), 22f, bold = true)
        hint("Pick a distribution and tap “Use”. The current one stays installed — " +
            "switching only changes which one the main screen installs and launches.")

        val activeId = env.activeDistro.id
        for (d in env.allDistros) {
            val installed = env.rootfsReady(d)
            val isActive = d.id == activeId
            val status = when {
                isActive -> getString(R.string.distro_active)
                installed -> getString(R.string.distro_installed)
                else -> getString(R.string.distro_not_installed)
            }
            header("${d.name}  —  $status", 16f, bold = true, topDp = 20f)

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                text = getString(R.string.distro_use)
                isEnabled = !isActive
                setOnClickListener {
                    env.activeDistro = d
                    finish()   // main screen will offer Install/Launch for it
                }
            })
            if (installed && !isActive) {
                row.addView(Button(this).apply {
                    text = getString(R.string.distro_remove)
                    setOnClickListener { env.removeDistro(d); rebuild() }
                })
            }
            list.addView(row)
        }
    }

    private fun header(text: String, size: Float, bold: Boolean = false, topDp: Float = 0f) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (topDp * resources.displayMetrics.density).toInt(), 0, 0)
        })
    }

    private fun hint(text: String) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
        })
    }
}
