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
            val available = withContext(Dispatchers.IO) { Updater.isUpdateAvailable() }
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
            !env.isDesktopInstalled -> getString(R.string.action_install_desktop)
            else -> getString(R.string.action_launch_desktop)
        }
        binding.statusText.text =
            getString(R.string.status_arch, env.activeDistro.name, env.arch, BuildConfig.GIT_SHA.take(7))
    }

    private fun onAction() {
        setBusy(true)
        lifecycleScope.launch {
            var endpoint: String? = null
            val result = withContext(Dispatchers.IO) {
                when {
                    !env.isBootstrapped -> Step.BOOTSTRAP to env.bootstrap(::appendLog)
                    !env.isDesktopInstalled -> Step.DESKTOP to env.installDesktop(::appendLog)
                    else -> {
                        endpoint = env.startDesktop("1280x720", ::appendLog)
                        Step.LAUNCH to (if (endpoint != null) 0 else 1)
                    }
                }
            }
            appendLog(if (result.second == 0) "✓ ${result.first} ok" else "✗ ${result.first} failed (${result.second})")
            setBusy(false)
            refreshState()
            // Must launch the activity from the main thread (we're back on it here).
            endpoint?.let { launchVncViewer(it) }
        }
    }

    /** Open the desktop in the app's own embedded VNC viewer, and keep the
     *  Linux session alive in the background while it's shown. */
    private fun launchVncViewer(endpoint: String) {
        val parts = endpoint.split(":")
        val host = parts.getOrElse(0) { "127.0.0.1" }
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 5901
        appendLog("Opening desktop viewer ($host:$port)…")
        // A foreground-service failure must not block the viewer from opening.
        runCatching { ContextCompat.startForegroundService(this, Intent(this, LinuxSessionService::class.java)) }
            .onFailure { appendLog("(session service not started: ${it.message})") }
        runCatching { VncActivity.start(this, host, port) }
            .onFailure { appendLog("Could not open viewer: ${it.message}") }
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
