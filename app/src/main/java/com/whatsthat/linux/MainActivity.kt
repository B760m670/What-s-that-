package com.whatsthat.linux

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.whatsthat.linux.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-screen driver: a big action button, a live log, and a progress bar.
 * The button's job changes with state — Install Ubuntu → Install Desktop →
 * Launch Desktop — so the user always has exactly one obvious next step.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var env: LinuxEnvironment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        env = LinuxEnvironment(this)
        env.prepareScripts()

        refreshState()
        binding.actionButton.setOnClickListener { onAction() }
        binding.distrosButton.setOnClickListener { startActivity(Intent(this, DistrosActivity::class.java)) }
        binding.runButton.setOnClickListener { runConsoleCommand() }
        binding.cmdInput.setOnEditorActionListener { _, _, _ -> runConsoleCommand(); true }

        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        refreshState()   // the active distro may have changed in DistrosActivity
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

    /** Run whatever the user typed in the console, inside the container. */
    private fun runConsoleCommand() {
        val cmd = binding.cmdInput.text.toString().trim()
        if (cmd.isEmpty()) return
        binding.cmdInput.text.clear()
        appendLog("$ $cmd")
        binding.runButton.isEnabled = false
        lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) { env.runCommand(cmd, ::appendLog) }
            if (code != 0) appendLog("[exit $code]")
            binding.runButton.isEnabled = true
        }
    }

    private fun refreshState() {
        binding.actionButton.text = when {
            !env.isBootstrapped -> getString(R.string.action_install_ubuntu, env.activeDistro.name)
            !env.isDesktopInstalled -> getString(R.string.action_install_desktop, env.activeDesktopEnv.name)
            else -> getString(R.string.action_launch_desktop)
        }
        binding.statusText.text =
            getString(R.string.status_arch, env.activeDistro.name, env.arch, BuildConfig.GIT_SHA.take(7)) +
                // State the settings that change what a launch does. They lived
                // only behind buttons on another screen, so a run could differ
                // from the one intended without anything saying so.
                "\n${env.activeDesktopEnv.name} · " +
                (if (env.gpuEnabled) "GPU GL on" else "GPU GL off") + " · " +
                (if (env.displayBackend == "fb") "framebuffer" else "VNC")
    }

    private fun onAction() {
        setBusy(true)
        lifecycleScope.launch {
            var launch: DesktopLaunch? = null
            val result = withContext(Dispatchers.IO) {
                when {
                    !env.isBootstrapped -> Step.BOOTSTRAP to env.bootstrap(::appendLog)
                    !env.isDesktopInstalled -> Step.DESKTOP to env.installDesktop(::appendLog)
                    else -> {
                        launch = env.startDesktop("1280x720", ::appendLog)
                        Step.LAUNCH to (if (launch != null) 0 else 1)
                    }
                }
            }
            appendLog(if (result.second == 0) "✓ ${result.first} ok" else "✗ ${result.first} failed (${result.second})")
            setBusy(false)
            refreshState()
            // Must launch the activity from the main thread (we're back on it here).
            launch?.let { openDesktop(it) }
        }
    }

    /** Route to the viewer for whichever backend the session came up on. */
    private fun openDesktop(launch: DesktopLaunch) {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, LinuxSessionService::class.java)) }
            .onFailure { appendLog("(session service not started: ${it.message})") }
        when (launch) {
            is DesktopLaunch.Vnc -> {
                appendLog("Opening desktop viewer (${launch.host}:${launch.port})…")
                runCatching { VncActivity.start(this, launch.host, launch.port) }
                    .onFailure { appendLog("Could not open viewer: ${it.message}") }
            }
            is DesktopLaunch.Framebuffer -> {
                appendLog("Opening desktop (framebuffer backend)…")
                runCatching { FramebufferActivity.start(this, launch.fbPath, launch.xSocketPath) }
                    .onFailure { appendLog("Could not open viewer: ${it.message}") }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.actionButton.isEnabled = !busy
        binding.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            binding.logView.append(line + "\n")
            binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private enum class Step { BOOTSTRAP, DESKTOP, LAUNCH }
}
