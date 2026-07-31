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

        desktopSection()
    }

    /**
     * Desktop environments are a separate axis from distributions: the distro is
     * the rootfs you download, the desktop is packages installed inside it. So
     * they get their own section rather than extra rows in the list above —
     * otherwise "Ubuntu XFCE" and "Ubuntu KDE" would each re-download the same
     * multi-gigabyte rootfs and you could never change your mind cheaply.
     */
    private fun desktopSection() {
        header(getString(R.string.de_title), 22f, bold = true, topDp = 32f)
        hint("Choose the desktop for “${env.activeDistro.name}”. Picking a new one lets the " +
            "main screen install it; anything already installed stays, so you can switch back " +
            "without reinstalling. Lighter desktops are noticeably faster on a phone.")

        val activeDe = env.activeDesktopEnv.id
        val installedDes = env.installedDesktops(env.activeDistro).map { it.id }.toSet()
        val rootfsReady = env.rootfsReady(env.activeDistro)

        for (de in env.allDesktopEnvs) {
            val isActive = de.id == activeDe
            val status = when {
                isActive && de.id in installedDes -> getString(R.string.distro_active)
                isActive -> getString(R.string.de_selected_not_installed)
                de.id in installedDes -> getString(R.string.distro_installed)
                else -> getString(R.string.distro_not_installed)
            }
            header("${de.name} · ${de.weight.label}  —  $status", 16f, bold = true, topDp = 18f)
            de.note?.let { hint(it) }

            list.addView(Button(this).apply {
                text = getString(R.string.distro_use)
                // Without a rootfs there is nothing to install a desktop into.
                isEnabled = !isActive && rootfsReady
                setOnClickListener {
                    env.activeDesktopEnv = de
                    finish()   // main screen now offers Install (or Launch, if present)
                }
            })
        }

        // Heavy options are labelled rather than removed: the device is the only
        // real judge, and a wrong guess about what "cannot work" costs the user
        // the choice entirely.
        hint("Heavier desktops are marked, not hidden. If one fails to start, the " +
            "launch log says so and you can switch back immediately — nothing you " +
            "already installed is lost.")

        gpuSection()
    }

    /**
     * virgl is a rendering accelerator, not a display method — orthogonal to both
     * the distro and the desktop, hence its own switch. Default off: it has not
     * shown a perceptible gain on real hardware, and a misbehaving virgl makes GL
     * clients abort instead of falling back to software, which a compositing
     * desktop does not survive.
     */
    private fun gpuSection() {
        header(getString(R.string.gpu_title), 22f, bold = true, topDp = 32f)
        val on = env.gpuEnabled
        hint("Currently: ${if (on) "ON — GL goes to the phone GPU" else "OFF — GL is rendered on the CPU"}.\n\n" +
            "This only changes who draws inside the container; the picture still reaches " +
            "the screen the same way. It can speed up 3D programs, but it has also been " +
            "seen to make GL applications crash outright rather than run slowly — which " +
            "can take a whole desktop session down with it. Leave it off unless you are " +
            "testing it.")
        list.addView(Button(this).apply {
            text = if (on) getString(R.string.gpu_disable) else getString(R.string.gpu_enable)
            setOnClickListener { env.gpuEnabled = !on; rebuild() }
        })
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
