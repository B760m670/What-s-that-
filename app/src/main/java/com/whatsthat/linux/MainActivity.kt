package com.whatsthat.linux

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.whatsthat.linux.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one screen that answers "what have I got, and what happens if I press
 * this". It shows the active system, its condition, and the single action that
 * follows from that condition.
 *
 * The console is still here — it is genuinely useful, and the desktop install
 * sometimes needs a package added by hand — but collapsed, because it is a
 * power tool and it used to occupy half the display before anything was even
 * installed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var env: LinuxEnvironment

    /** Cached so onResume doesn't re-probe the port on the main thread. */
    @Volatile private var sessionLive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        env = LinuxEnvironment(this)
        env.prepareScripts()

        binding.toolbar.subtitle = getString(R.string.subtitle_build, env.arch, BuildConfig.GIT_SHA.take(7))
        binding.actionButton.setOnClickListener { onAction() }
        binding.resumeButton.setOnClickListener { openViewer(env.sessionEndpoint) }
        binding.distrosButton.setOnClickListener { startActivity(Intent(this, DistrosActivity::class.java)) }
        binding.resolutionButton.setOnClickListener { chooseResolution() }
        binding.runButton.setOnClickListener { runConsoleCommand() }
        binding.cmdInput.setOnEditorActionListener { _, _, _ -> runConsoleCommand(); true }
        binding.consoleHeader.setOnClickListener { toggleConsole() }

        refreshState()
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        refreshState()   // the active distro may have changed in DistrosActivity
    }

    // --- state ---------------------------------------------------------------

    private fun refreshState() {
        val d = env.activeDistro
        val accent = ContextCompat.getColor(this, d.accentRes)

        binding.heroIcon.setImageResource(d.iconRes)
        binding.heroIcon.imageTintList = ColorStateList.valueOf(accent)
        binding.heroName.text = d.name
        binding.heroSubtitle.text = d.tagline

        binding.actionButton.text = when {
            !env.isBootstrapped -> getString(R.string.action_install_ubuntu, d.name)
            !env.isDesktopInstalled -> getString(R.string.action_install_desktop)
            else -> getString(R.string.action_launch_desktop)
        }

        // Both of these touch the filesystem or a socket, so they are resolved
        // off the main thread and folded back in when they arrive.
        lifecycleScope.launch {
            val installed = env.isBootstrapped
            val running = withContext(Dispatchers.IO) { if (installed) env.isSessionRunning() else false }
            val size = withContext(Dispatchers.IO) { if (installed) env.diskUsage(d) else 0L }
            sessionLive = running

            binding.heroChips.removeAllViews()
            when {
                !installed -> addChip(getString(R.string.chip_not_installed), R.color.state_absent)
                env.isDesktopInstalled -> addChip(getString(R.string.chip_desktop_ready), R.color.state_ready)
                else -> addChip(getString(R.string.chip_no_desktop), R.color.state_partial)
            }
            if (running) addChip(getString(R.string.chip_session_running), R.color.state_ready)
            if (size > 0) addChip(LinuxEnvironment.formatBytes(size), R.color.state_absent)

            // A live session means the desktop is already up: offer to return to
            // it rather than silently starting a second one.
            binding.resumeButton.visibility = if (running) View.VISIBLE else View.GONE
        }
    }

    private fun addChip(label: String, colorRes: Int) {
        val tint = ContextCompat.getColor(this, colorRes)
        binding.heroChips.addView(Chip(this).apply {
            text = label
            isClickable = false
            isCheckable = false
            chipStrokeWidth = 1f
            chipStrokeColor = ColorStateList.valueOf(tint)
            setTextColor(tint)
            chipBackgroundColor = ColorStateList.valueOf(tint and 0x18FFFFFF)
            chipMinHeight = 30f * resources.displayMetrics.density
            textSize = 12f
        })
    }

    // --- actions -------------------------------------------------------------

    private fun onAction() {
        setBusy(true, getString(R.string.progress_working))
        lifecycleScope.launch {
            var endpoint: String? = null
            val result = withContext(Dispatchers.IO) {
                when {
                    !env.isBootstrapped -> Step.BOOTSTRAP to env.bootstrap(::appendLog, ::showProgress)
                    !env.isDesktopInstalled -> Step.DESKTOP to env.installDesktop(::appendLog, ::showProgress)
                    else -> {
                        endpoint = env.startDesktop(resolution, ::appendLog)
                        Step.LAUNCH to (if (endpoint != null) 0 else 1)
                    }
                }
            }
            val ok = result.second == 0
            appendLog(if (ok) "✓ ${result.first} ok" else "✗ ${result.first} failed (${result.second})")
            // A fresh install changes the footprint, so drop the cached figure.
            if (result.first != Step.LAUNCH) env.invalidateDiskUsage(env.activeDistro)
            setBusy(false)
            if (!ok) {
                // Failures used to be a log line the user might never see, since
                // the log itself was scrolled away. Say it where they are looking.
                showError(result.first)
            }
            refreshState()
            endpoint?.let { openViewer(it) }   // must start the activity from the main thread
        }
    }

    private fun showError(step: Step) {
        val what = when (step) {
            Step.BOOTSTRAP -> getString(R.string.err_bootstrap)
            Step.DESKTOP -> getString(R.string.err_desktop)
            Step.LAUNCH -> getString(R.string.err_launch)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.err_title)
            .setMessage(what)
            .setPositiveButton(R.string.err_show_log) { _, _ -> expandConsole() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** Open the desktop viewer, keeping the session alive in the background. */
    private fun openViewer(endpoint: String) {
        val parts = endpoint.split(":")
        val host = parts.getOrElse(0) { "127.0.0.1" }
        val port = parts.getOrNull(1)?.toIntOrNull() ?: LinuxEnvironment.VNC_PORT
        appendLog("Opening desktop viewer ($host:$port)…")
        // A foreground-service failure must not block the viewer from opening.
        runCatching { ContextCompat.startForegroundService(this, Intent(this, LinuxSessionService::class.java)) }
            .onFailure { appendLog("(session service not started: ${it.message})") }
        runCatching { VncActivity.start(this, host, port) }
            .onFailure { appendLog("Could not open viewer: ${it.message}") }
    }

    /**
     * Desktop resolution. A phone panel is not 1280x720, and the old fixed size
     * meant everything was scaled to fit whether or not that suited the screen.
     */
    private val resolutionOptions = listOf("1280x720", "1600x900", "1920x1080", "1024x600")

    private var resolution: String
        get() = getSharedPreferences("wt", MODE_PRIVATE).getString("geometry", "1280x720") ?: "1280x720"
        set(v) { getSharedPreferences("wt", MODE_PRIVATE).edit().putString("geometry", v).apply() }

    private fun chooseResolution() {
        val current = resolutionOptions.indexOf(resolution).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.resolution_title)
            .setSingleChoiceItems(resolutionOptions.toTypedArray(), current) { dialog, which ->
                resolution = resolutionOptions[which]
                dialog.dismiss()
                appendLog("Desktop resolution set to ${resolutionOptions[which]} (applies on next launch).")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- console -------------------------------------------------------------

    private fun toggleConsole() {
        if (binding.consoleBody.visibility == View.VISIBLE) {
            binding.consoleBody.visibility = View.GONE
            binding.consoleToggle.text = getString(R.string.console_show)
        } else expandConsole()
    }

    private fun expandConsole() {
        binding.consoleBody.visibility = View.VISIBLE
        binding.consoleToggle.text = getString(R.string.console_hide)
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun runConsoleCommand() {
        val cmd = binding.cmdInput.text.toString().trim()
        if (cmd.isEmpty()) return
        binding.cmdInput.text?.clear()
        appendLog("$ $cmd")
        binding.runButton.isEnabled = false
        lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) { env.runCommand(cmd, ::appendLog) }
            if (code != 0) appendLog("[exit $code]")
            binding.runButton.isEnabled = true
        }
    }

    // --- plumbing ------------------------------------------------------------

    /**
     * True while a byte-counted phase owns the caption, so log lines stop
     * writing over it — "231 MB of 487 MB · 2 min left" beats whatever the last
     * line of output happened to say.
     */
    private var labelOwned = false

    private fun setBusy(busy: Boolean, label: String? = null) {
        binding.actionButton.isEnabled = !busy
        binding.distrosButton.isEnabled = !busy
        if (busy) {
            labelOwned = false
            setIndeterminate(true)
            binding.progress.visibility = View.VISIBLE
            binding.progressLabel.visibility = View.VISIBLE
            binding.progressLabel.text = label
        } else {
            binding.progress.visibility = View.GONE
            binding.progressLabel.visibility = View.GONE
        }
    }

    /**
     * LinearProgressIndicator refuses to change mode while it is on screen, so
     * the switch has to happen with the view hidden.
     */
    private fun setIndeterminate(value: Boolean) {
        if (binding.progress.isIndeterminate == value) return
        val wasVisible = binding.progress.visibility == View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.progress.isIndeterminate = value
        if (wasVisible) binding.progress.visibility = View.VISIBLE
    }

    /** Drive the bar and its caption from a structured [Progress] report. */
    private fun showProgress(p: Progress) {
        runOnUiThread {
            if (binding.progress.visibility != View.VISIBLE) return@runOnUiThread
            val pct = p.percent
            setIndeterminate(pct < 0)
            if (pct >= 0) binding.progress.setProgressCompat(pct, true)
            // A byte-counted phase produces no other output, so it keeps the
            // caption. The desktop install streams apt's own output, which is a
            // far better sign of life than a step name sitting unchanged for
            // minutes — there the bar carries the step and the caption moves.
            labelOwned = pct >= 0 && p.countsBytes
            binding.progressLabel.text = describe(p)
        }
    }

    private fun describe(p: Progress): String {
        val verb = p.label ?: getString(
            when (p.phase) {
                Progress.Phase.RESOLVE -> R.string.phase_resolve
                Progress.Phase.DOWNLOAD -> R.string.phase_download
                Progress.Phase.VERIFY -> R.string.phase_verify
                Progress.Phase.EXTRACT -> R.string.phase_extract
                Progress.Phase.FINALISE -> R.string.phase_finalise
                Progress.Phase.DESKTOP -> R.string.phase_desktop
            }
        )
        if (p.percent < 0) return verb
        // Steps, not bytes: "Installing the XFCE desktop — step 4 of 7".
        if (!p.countsBytes) return getString(R.string.progress_step, verb, p.done + 1, p.total)
        val sb = StringBuilder(
            getString(
                R.string.progress_of,
                verb,
                LinuxEnvironment.formatBytes(p.done),
                LinuxEnvironment.formatBytes(p.total),
                p.percent,
            )
        )
        // The estimate is only shown for the download, where the rate reflects
        // the network and stays roughly steady. Hashing and unpacking are
        // CPU-bound and their early figures swing wildly.
        if (p.phase == Progress.Phase.DOWNLOAD) {
            if (p.bytesPerSecond > 0) sb.append(getString(R.string.progress_rate, LinuxEnvironment.formatBytes(p.bytesPerSecond)))
            val left = p.secondsLeft
            // Anything beyond a few hours is a figure from a stalling connection,
            // not information; showing it would only mislead.
            if (left in 1L..21_600L) sb.append(getString(R.string.progress_left, formatDuration(left)))
        }
        return sb.toString()
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> getString(R.string.duration_seconds, seconds)
        seconds < 3600 -> getString(R.string.duration_minutes, seconds / 60)
        else -> getString(R.string.duration_hours, seconds / 3600, (seconds % 3600) / 60)
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            binding.logView.append(line + "\n")
            // Surface what is happening on the card, so it is visible without
            // opening the console — unless a counted phase owns the label.
            if (!labelOwned && binding.progressLabel.visibility == View.VISIBLE && line.isNotBlank()) {
                binding.progressLabel.text = line.take(90)
            }
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** On launch, see if CI published a newer APK; if so, fetch + offer to install. */
    private fun checkForUpdates() {
        lifecycleScope.launch {
            val available = withContext(Dispatchers.IO) { Updater.isUpdateAvailable(::appendLog) }
            if (!available) return@launch
            appendLog("A new version is available.")
            val apk = withContext(Dispatchers.IO) { Updater.downloadApk(this@MainActivity, ::appendLog) }
            if (apk != null) {
                appendLog("Tap to install the update.")
                Updater.install(this@MainActivity, apk)
            }
        }
    }

    private enum class Step { BOOTSTRAP, DESKTOP, LAUNCH }
}
